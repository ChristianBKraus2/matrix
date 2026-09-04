---
# Error Handling Review — server

## Summary

The server has two distinct error-handling strata that behave very differently. The `SessionRegistry` / `MatrixServer` WebSocket layer uses `runCatching` in several places but discards the failure silently — no logging, no error response to the client, no cleanup of dead sessions. The `WebSocketDeckerController.dispatch` layer does send error messages for most bad inputs, but relies on unchecked casts against `action.target` throughout `dispatchHostOperation`; any mismatch between the server's action list and the client's submitted index produces a `ClassCastException` that escapes the game loop entirely. A handful of secondary paths (`QueryPrecision.valueOf`, re-thrown `ExecutionException`, unprotected `applyDeckerOperationResult`) can also crash or silently strand the game. Observer clients never learn when a decker disconnects or when an inbound frame is dropped.

## Findings

### [CRITICAL] Unchecked casts on `action.target` crash the game loop on stale action index

**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:168–225
**Issue:** `dispatchHostOperation` casts `action.target` to specific `MatrixObject` subtypes (e.g., `action.target as MatrixObject.IcProgram`) at lines 168, 172, 177, 186, 192, 196, 199, 224 with no `as?` or `is`-check. The cast is valid only if the `AvailableAction` the server computed is still in sync with the `actionIndex` the client submitted. If the game state advanced between the broadcast and the action arriving (or the client sends a fabricated index), the wrong branch is reached and `ClassCastException` escapes `dispatch`, then `action()`, then the game loop — crashing the turn for all players with no error message sent to anyone.
**Recommendation:** Replace every hard cast with a safe `as?` and short-circuit to an error `DispatchResult` on `null`:
```kotlin
val ic = (action.target as? MatrixObject.IcProgram)?.ic
    ?: return DispatchResult(decker, false, 0, 0, "Expected IcProgram target")
```
Alternatively, add a single outer `try-catch(ClassCastException)` in `dispatch` and return an error `DispatchResult`.

---

### [HIGH] `runCatching` in frame handler swallows all parse and dispatch errors silently

**File:** src/main/kotlin/…/server/MatrixServer.kt:29–36
**Issue:** Every parse and dispatch error inside the WebSocket frame loop is caught by `runCatching` and discarded. The `Result` is never inspected. If JSON is malformed, `decodeFromString` throws, `jsonObject` throws for a non-object payload, `receiveJoin`/`receiveAction` throws — none of these are logged and the client receives no error frame. The client is left hanging with no indication that its message was rejected or failed.
**Recommendation:** Replace `runCatching { … }` with explicit error handling:
```kotlin
val result = runCatching { … }
result.onFailure { e ->
    logger.warn("Frame handling failed: ${e.message}", e)
    session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "bad_request: ${e.message}"))))
}
```

**Resolution (Phase 1.3):**
`MatrixServer.kt` now sends an `ErrorMessage` back to the session on failure instead of silently discarding the exception. An `else` branch was also added for unknown `msgType` values, sending `ErrorMessage("unknown_message_type")` to the client.

---

### [HIGH] `QueryPrecision.valueOf()` throws `IllegalArgumentException` on bad client input — unhandled

**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:245
**Issue:** `params?.precision?.let { QueryPrecision.valueOf(it) }` throws `IllegalArgumentException` for any precision string the client sends that does not exactly match an enum constant. This exception is not caught anywhere between `locateWithState` and `action()`, so it propagates out of the game loop unhandled, crashing the current turn for all players.
**Recommendation:** Use `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL`, or add an explicit enum-safe lookup, and return an error `DispatchResult` immediately if the value is unrecognised rather than letting an exception escape.

---

### [HIGH] Non-disconnect `ExecutionException` is re-thrown without logging

**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:83–90
**Issue:** The `catch (e: ExecutionException)` block at line 83 only handles `DeckerDisconnectedException`; all other causes (domain bugs, serialization failures inside `CompletableFuture.complete`) fall through to `throw e` at line 90 with no log line. The exception then escapes `action()` into the game loop with no context, and no `ResultMessage` or `demoteAfterTurn` is sent, leaving the active-controller session permanently elevated.
**Recommendation:** Log `e` at `ERROR` level before re-throwing, and call `demoteAfterTurn` in a surrounding `finally` block (or restructure so that `demoteAfterTurn` always runs after the turn, not only on the happy path).

---

### [HIGH] Silent send failures in `broadcast` and `broadcastWithRoles` — dead sessions accumulate

**File:** src/main/kotlin/…/server/SessionRegistry.kt:88, 103
**Issue:** Both `broadcast` and `broadcastWithRoles` use `runCatching { session.send(…) }` and ignore the failure completely. When a session has been closed by the network layer but `deregister` has not yet been called (e.g., between connection drop and coroutine cancellation), every subsequent broadcast silently fails for that session. The failure is never logged, and the dead session is never pruned from `sessions` here, so it accumulates and continues to be attempted on every future broadcast.
**Recommendation:** On failure, log at `WARN` level at minimum. Consider removing the session from `sessions` on send failure:
```kotlin
runCatching { session.send(Frame.Text(text)) }.onFailure {
    logger.warn("Send failed, removing dead session", it)
    synchronized(lock) { sessions.remove(session) }
}
```

---

### [MEDIUM] Unknown WebSocket message type silently ignored — no error response, no log

**File:** src/main/kotlin/…/server/MatrixServer.kt:33–35
**Issue:** The `when (msgType)` expression has branches for `"join"` and `"action"` but no `else` branch. Any other message type (including a misspelled type, a future message type, or a debugging ping) is silently dropped. The client receives no feedback.
**Recommendation:** Add an `else` branch that sends an `ErrorMessage` back to the session and, at minimum, logs the unknown type at `DEBUG` level:
```kotlin
else -> session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "unknown_type:$msgType"))))
```

---

### [MEDIUM] `context.applyDeckerOperationResult` called without error protection

**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:107
**Issue:** After a successful `dispatch`, `context.applyDeckerOperationResult(oldDecker, decker)` is called bare. If the domain layer throws here (a bug in `GameContext`), the exception escapes `action()` with no error message sent to clients, no `demoteAfterTurn`, and the session remains in `active_controller` state permanently.
**Recommendation:** Wrap lines 104–119 in a `try-catch(Exception)` that sends a `ResultMessage(success=false, details="Internal error")`, calls `demoteAfterTurn`, and logs the exception, then returns `ActionResult.DeckerAction`.

---

### [LOW] Decker disconnect is not announced to remaining observers

**File:** src/main/kotlin/…/server/SessionRegistry.kt:51–62
**Issue:** When a registered decker disconnects, `deregister` removes the session from internal maps and cancels a pending `CompletableFuture`, but sends no broadcast to the remaining sessions. Observer clients and other deckers are never told that a named decker has left. Their UI shows the decker as still present until the next state broadcast (which only happens on the next turn).
**Recommendation:** After the `synchronized` block in `deregister`, if `name != null`, broadcast an `ErrorMessage` or a dedicated `ControlMessage(role = "decker_left", deckerName = name)` so all clients can update their UI immediately.

---

### [LOW] `pendingAction` read-check-complete sequence is not atomic

**File:** src/main/kotlin/…/server/SessionRegistry.kt:108–117
**Issue:** `receiveAction` reads `pendingAction`, checks `future.isDone`, and calls `future.complete(cmd)` as three separate steps. The `@Volatile` annotation only prevents stale reads; it does not prevent a second session from passing the `isDone` check and calling `complete` concurrently, causing a duplicate completion attempt. In the current single-active-controller design this is unlikely to matter, but it is a latent bug as concurrency increases.
**Recommendation:** Use `CompletableFuture`'s own atomic semantics: `future.complete(cmd)` already returns `false` if the future was already completed, so the `isDone` check is redundant. Simply check the return value of `complete` and send `no_action_pending` if it returns `false`.

## Clean Areas

- `DeckerDisconnectedException` is a well-scoped, properly typed exception used for exactly one purpose; its propagation path through `CompletableFuture.completeExceptionally` is correct.
- The timeout path (lines 77–82) correctly broadcasts a human-readable `ResultMessage`, calls `demoteAfterTurn`, and returns cleanly without crashing the game loop.
- Invalid `actionIndex` from the client (lines 96–101) is handled gracefully with an error broadcast and a clean demotion.
- `receiveJoin` correctly handles both "already registered" and "name taken" cases with distinct `ErrorMessage` strings sent back to the offending session.
- The `dispatch` `else ->` fallthrough (line 233) produces a graceful `DispatchResult` rather than throwing, which is the right pattern; the same applies to `LOCATE_DECKER` and `SWAP_MEMORY`.
- DTO serialization (`Messages.kt`, `DeckerStateDto.kt`, `MatrixObjectDto.kt`, `AvailableActionDto.kt`) is pure data mapping with no I/O or state; there are no error-handling concerns there.
---
