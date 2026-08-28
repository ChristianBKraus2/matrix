---
# Correctness Review — server

## Summary

The server layer is broadly functional but contains several correctness defects that can silently corrupt game state or make failures invisible. The most dangerous pattern is a blanket `runCatching` in the WebSocket loop that discards every exception without notifying the client. Two hard-cast operations in `dispatchHostOperation` can throw `ClassCastException` that escapes `action()` entirely and may halt the game loop. A hardcoded `success = true` in `AnalyzeSecurityResult.toDispatch()` causes the host's wins to be invisible to all clients. Concurrency is mostly handled correctly (CompletableFuture is thread-safe, deregister holds the lock properly, broadcast snapshots before iterating), but the `pendingAction` read-check-complete sequence in `receiveAction` is not atomic, leading to a window where a submitted action can be silently dropped.

## Findings

### [CRITICAL] `runCatching` swallows all WebSocket handler exceptions silently
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29
**Issue:** The entire message-dispatch body is wrapped in `runCatching { }` with no `onFailure` handler. Any exception — JSON deserialization errors, mismatched type fields, unexpected nulls inside `receiveJoin`/`receiveAction`, serialization errors on the reply — is silently discarded. The offending client frame is dropped with no `ErrorMessage` sent back, making protocol violations and server-side bugs completely invisible.
**Recommendation:** Replace `runCatching` with an explicit try/catch that, on any `Exception`, sends `ErrorMessage(message = "internal_error: ${e.message}")` to the session before re-throwing (or continuing). Keep a catch for `SerializationException` separately to produce a `"malformed_message"` error without crashing the loop.

---

### [CRITICAL] Unchecked casts in `dispatchHostOperation` can crash the game loop
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:168,172,177,182,187,192,199,224
**Issue:** Multiple branches unconditionally cast `action.target` to a specific subtype (e.g. `action.target as MatrixObject.IcProgram`). If the domain layer ever produces an `AvailableAction.Operation` with a mismatched target — due to a domain bug, future refactor, or an unknown extension point — a `ClassCastException` is thrown. This exception is not caught anywhere in `action()` or its callers and will propagate out of the game loop, potentially halting the entire game session with no `ResultMessage` broadcast and no demotion of the active controller.
**Recommendation:** Replace each hard cast with a safe cast plus a guard:
```kotlin
val ic = (action.target as? MatrixObject.IcProgram)?.ic
    ?: return DispatchResult(decker, false, 0, 0, "Expected IcProgram target for ${action.operation}")
```
Alternatively, add a top-level `try/catch(e: Exception)` in `action()` that broadcasts a failure `ResultMessage` and calls `demoteAfterTurn` before re-throwing.

---

### [HIGH] `AnalyzeSecurityResult.toDispatch()` hardcodes `success = true`
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:293
**Issue:** The method always constructs `DispatchResult(decker, true, ...)` regardless of whether the decker or the host won the roll. All connected clients therefore always see `"success": true` for `ANALYZE_SECURITY` operations, even when the host out-rolled the decker.
**Recommendation:** Use the actual outcome flag:
```kotlin
return DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, details)
```

---

### [HIGH] `future.complete(cmd)` return value discarded — action silently dropped on race
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:117
**Issue:** `CompletableFuture.complete()` returns `false` if the future is already completed. This happens when a concurrent `deregister` has already called `completeExceptionally(DeckerDisconnectedException())` between the `isDone` guard (line 113) and the `complete` call (line 117). The action is silently lost: the client receives no `ErrorMessage`, believes its action was accepted, and the turn eventually times out or hangs.
**Recommendation:** Check the return value and send an error if the future was already resolved:
```kotlin
if (!future.complete(cmd)) {
    session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "action_not_accepted"))))
}
```

---

### [HIGH] `runBlocking` inside `action()` risks deadlock if game loop runs on a coroutine dispatcher
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53,55,73,78,85,109,116
**Issue:** `action()` calls `runBlocking { }` multiple times to bridge the coroutine-based registry API. If the calling thread is itself managed by a coroutine dispatcher (e.g. `Dispatchers.Default` or a single-threaded dispatcher), `runBlocking` will block that thread, potentially deadlocking when the inner coroutine needs the same thread to resume. This is a well-known Kotlin antipattern.
**Recommendation:** Either declare `action()` as a `suspend` function (propagating coroutine context to the caller) or ensure the game loop always runs on a plain non-coroutine thread (document this constraint in the class KDoc). If the game loop is guaranteed to run on a dedicated blocking thread, add an assertion: `check(!coroutineContext.isActive)` or a comment documenting the invariant.

---

### [MEDIUM] `AnalyzeSecurityResult.toDispatch()` — already covered under HIGH-1 above as the success flag is always `true`; additionally, `LogoffResult.JackOut` with dump shock reports `success = true`
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:272
**Issue:** `LogoffResult.JackOut` maps to `success = true` even when `dumpShock = true`. A decker taking dump shock has been forcibly ejected and taken biofeedback damage — this is a harmful failure state, not a success. Clients that key off `success` to display results will show a green outcome for what is actually a damaging event.
**Recommendation:** Map jack-out with dump shock to `success = false`:
```kotlin
is LogoffResult.JackOut -> DispatchResult(decker, !dumpShock, 0, 0,
    if (dumpShock) "Jacked out (dump shock!)" else "Jacked out")
```

---

### [MEDIUM] `pendingAction` read-check-complete is non-atomic — action can be silently lost
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:108-117
**Issue:** `receiveAction` reads `pendingAction` into a local variable (line 108), checks `future.isDone` (line 113), then calls `future.complete(cmd)` (line 117) — all without holding `lock`. A concurrent `deregister` can call `pendingAction?.completeExceptionally(...)` between lines 113 and 117. `@Volatile` ensures the reference is visible, but does not make the read-check-complete sequence atomic. The result is the same silent action drop described in the HIGH-2 finding above, via a different race path.
**Recommendation:** The `complete()` return-value check (see HIGH-2 recommendation) is sufficient mitigation. Alternatively, protect the entire read-check-complete block with `synchronized(lock)`, though that requires making the send call outside the lock.

---

### [MEDIUM] `locateWithState` has a dead `diceRoller` parameter
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:241-242
**Issue:** The `diceRoller` parameter is annotated `@Suppress("UNUSED_PARAMETER")` and never referenced inside `locateWithState`. All three callers' lambdas already capture `diceRoller` from `dispatchHostOperation`'s own parameter scope. The dead parameter misleads readers into thinking dice rolling happens inside `locateWithState`.
**Recommendation:** Remove the parameter from the `locateWithState` signature entirely. The callers already close over the correct `diceRoller` reference.

---

### [LOW] No validation of `deckerName` — empty string is accepted as a valid name
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:29-48
**Issue:** `receiveJoin` performs no validation on `msg.deckerName`. An empty string `""` is registered successfully and stored in `deckerSessions`, producing confusing broadcast messages ("Decker  — turn skipped") and a key collision risk if two clients both send an empty name (the second will receive `name_already_taken`, but the first is never warned).
**Recommendation:** Add a guard at the top of `receiveJoin`:
```kotlin
if (name.isBlank()) {
    session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "invalid_decker_name"))))
    return
}
```

---

### [LOW] `LOCATE_DECKER` and `SWAP_MEMORY` appear in `availableActions` but always fail at dispatch time
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:220,229-231
**Issue:** Both operations are reachable via `AvailableAction.Operation` and will be listed in the `availableActions` sent to clients, but `dispatch` immediately returns a failure `DispatchResult` explaining they are "not supported via WebSocket". A decker who chooses either operation wastes a turn and receives only a failure result message, with no indication at action-selection time that these choices are inoperative.
**Recommendation:** Either filter these operations out of `availableActions` before serialisation (in the domain layer or in `WebSocketDeckerController.action()` before building `stateBase`), or add a client-facing `"unsupported"` flag to `AvailableActionDto.Operation`.

---

### [INFO] Unknown `msgType` values are silently ignored — no error sent to client
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:35
**Issue:** The `when (msgType)` has no `else` branch, so any message type that is not `"join"` or `"action"` is dropped without feedback. During development this makes it hard to diagnose typos in the client's type field.
**Recommendation:** Add an `else` branch that sends `ErrorMessage(message = "unknown_type: $msgType")`.

---

## Clean Areas

- `deregister` atomically clears both `deckerSessions`, `sessionDecker`, and `activeController` inside a single `synchronized(lock)` block, then operates on `pendingAction` outside — correct ordering that prevents the maps from being observed in a torn state.
- `broadcastWithRoles` and `broadcast` both snapshot the session set inside the lock before iterating, preventing `ConcurrentModificationException` and ensuring no lock is held during the suspend call.
- `finally { registry.pendingAction = null }` in `WebSocketDeckerController.action()` runs regardless of which code path exits (timeout return, disconnect return, or normal completion), so the pending-action slot is always cleared.
- DTO sealed-class hierarchies (`MatrixObjectDto`, `AvailableActionDto`) cover all domain variants exhaustively via `when` expressions with no uncovered branches in `toDto()`.
- `promoteForTurn` / `demoteAfterTurn` correctly maintain `activeController` symmetry, and `broadcastWithRoles` reads this field to compute per-session roles without an additional data structure.
- `ActionCommand.actionIndex` is resolved against the `availableActions` snapshot that was used to build the `StateMessage`, and `getOrNull` is used (line 95) so an out-of-range index produces a graceful failure rather than an `IndexOutOfBoundsException`.
---
