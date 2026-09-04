# Error Handling Review — server

## Summary

The server component has a layered error handling strategy that is partially correct but has meaningful gaps at every layer. The WebSocket frame loop in `MatrixServer.kt` correctly catches parse/dispatch exceptions and sends an `ErrorMessage` back to the client — but wraps that send in a bare `runCatching` that swallows failures silently and never logs the original exception server-side. `WebSocketDeckerController` similarly catches unexpected game-logic exceptions and notifies clients, but again discards the exception without logging, making silent failures invisible to operators. Two resource-safety bugs compound this: `register()` is called outside the `try/finally` guard that invokes `deregister()`, so a connection that dies mid-handshake leaks its session forever; and `promoteForTurn`/`demoteAfterTurn` perform unguarded `send` calls whose exceptions can escape into turn-loop code that is not prepared to handle them. Several unchecked casts in the dispatch table and one silent input normalisation round out the picture.

---

## Findings

### [HIGH] Original exception never logged; error-send failure silently swallowed
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:43`
**Issue:** The outer `catch (e: Exception)` captures all parse and dispatch failures. It tries to send a `BAD_REQUEST` ErrorMessage to the client, but wraps that send in `runCatching {}` with no `onFailure` handler. If the client has already disconnected, the send throws and the failure is silently discarded. More importantly, `e` itself is never written to a logger — the only record of the original error is the truncated 120-character `e.message` that the client receives, which vanishes if the send fails. An operator watching logs sees nothing.
**Recommendation:** Log `e` at WARN or ERROR level before attempting the send. Replace the bare `runCatching` with a form that logs on failure, e.g. `runCatching { ... }.onFailure { logger.warn("Could not send BAD_REQUEST to client", it) }`.

---

### [HIGH] `register()` called outside `try/finally` — session leaks on handshake failure
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29`
**Issue:** `registry.register(this)` adds the session to `sessions` and then immediately calls `session.send()` to deliver the initial `OBSERVER` control message. If that `send` throws (connection closed before the handshake completes), the session has already been inserted into the `sessions` set but we never enter the `try/finally` block, so `registry.deregister(this)` is never called. The dead session stays in `sessions` permanently and will receive spurious broadcast attempts until the server restarts.
**Recommendation:** Move `registry.register(this)` inside the `try` block, or at minimum restructure so the `finally { registry.deregister(this) }` is guaranteed to run even when `register` itself throws.

---

### [HIGH] Unexpected game-logic exceptions logged nowhere
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:79` and `:115`
**Issue:** Two `catch (e: Exception)` blocks handle unexpected errors during turn processing. Both broadcast a generic failure message to clients ("Unexpected error — turn aborted" / "Internal error — turn aborted") but neither logs `e`. Any bug in the game engine — null pointer, arithmetic error, state machine invariant violation — produces only a vague client-visible string and leaves no trace in server logs.
**Recommendation:** Add `logger.error("Turn aborted for decker ${decker.name}", e)` (or equivalent) at the top of each catch block before the broadcast.

---

### [MEDIUM] Unguarded `send` in `promoteForTurn` and `demoteAfterTurn` propagates exceptions into turn loop
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:88` and `:99`
**Issue:** Both `promoteForTurn` and `demoteAfterTurn` call `session.send(...)` without a try/catch or `runCatching`. In `promoteForTurn`, the `activeController` field is set before the send; if the send throws, the controller slot is occupied but the client never received its promotion message — the turn will time out rather than fail fast. In `demoteAfterTurn`, all callers in `WebSocketDeckerController` are outside the inner try/catch, so an exception propagates to `runBlocking` and can corrupt the turn-loop invariants (`pendingAction` may not be cleared, `decker` may not be updated via `applyDeckerOperationResult`).
**Recommendation:** Wrap the `send` in both methods with `runCatching` and log failures. In `promoteForTurn`, treat a send failure as `return false` so the caller can skip the turn gracefully.

---

### [MEDIUM] Silent degradation of invalid `precision` parameter
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:243`
**Issue:** `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL` silently normalises an unrecognised precision string to `NORMAL`. The client receives a result with no indication that their parameter was invalid. There is no server-side log either, so typos in the precision field are invisible.
**Recommendation:** Validate the `precision` field explicitly. If the value is not a valid `QueryPrecision` name, return a `DispatchResult` with `success = false` and a descriptive message listing the valid values, the same way other invalid-input cases are handled.

---

### [MEDIUM] Broadcast send failures silently swallowed with no logging
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107` and `:124`
**Issue:** Both `broadcast` and `broadcastWithRoles` wrap each per-session `send` in `runCatching { ... }` with no failure handler. A session that is broken but not yet deregistered (e.g., half-closed TCP connection) will trigger a silent exception on every broadcast until the Ktor connection finaliser eventually fires `deregister`. There is no log entry to alert operators to a persistently failing session.
**Recommendation:** Add `.onFailure { logger.debug("Broadcast send failed for session, dropping: ${it.message}") }` so that repeated silent failures surface in debug logs without disrupting the broadcast loop.

---

### [MEDIUM] Unchecked `action.target` casts throw `ClassCastException` on type mismatch
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:172,176,182,192,197,200,209,226`
**Issue:** Multiple branches in `dispatchHostOperation` perform hard casts such as `(action.target as MatrixObject.IcProgram)` without a prior type check or null guard. If there is ever a mismatch between the type of `action.target` and the expected subtype (e.g., due to a logic error in action construction, or a future refactor), a `ClassCastException` is thrown. That exception is caught by the outer `catch (e: Exception)` in `action()`, broadcasts only "Internal error — turn aborted", and is never logged, making the root cause completely invisible.
**Recommendation:** Replace each hard cast with a safe cast and an explicit error path, e.g.:
```kotlin
val ic = (action.target as? MatrixObject.IcProgram)?.ic
    ?: return DispatchResult(decker, false, 0, 0, "Expected IcProgram target for ANALYZE_IC")
```

---

### [LOW] Empty decker name accepted without validation
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:37`
**Issue:** `receiveJoin` rejects names longer than 32 characters but does not reject blank or whitespace-only names. A decker registered with `""` or `"   "` would be accepted, appear in `deckerSessions` under that key, and produce confusing UI output.
**Recommendation:** Add `if (name.isBlank()) { session.send(ErrorMessage(NAME_TOO_LONG)); return }` — or introduce a dedicated `INVALID_NAME` error code — before the length check.

---

### [INFO] Non-null assertion `future!!` relies on lock-protected invariant invisible to type system
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:139`
**Issue:** `future!!.complete(cmd)` is reached only when `error == null`, which in the current logic means `pendingAction` was non-null and non-completed at the time of the synchronized block. The assertion is logically safe, but the reasoning is non-local and would break under refactoring.
**Recommendation:** Replace `future!!` with a `requireNotNull(future) { "pendingAction cleared between lock release and complete()" }` or restructure the destructured pair so the type is `CompletableDeferred<ActionCommand>` rather than nullable in the non-error branch.

---

## No Issues Found In

- `dto/Messages.kt` — serialisation models and `MatrixJson` config are straightforward; no error-prone logic.
- `dto/DeckerStateDto.kt` — the `toDto()` extension and `label()` helper are pure transforms with no exception surface.
- `dto/AvailableActionDto.kt` — mapping logic is pure; `targetName()` is exhaustive over a sealed class.
- `dto/MatrixObjectDto.kt` — pure DTO mapping; exhaustive `when` over the sealed `MatrixObject` hierarchy.
- `DeckerDisconnectedException.kt` — trivial marker exception, no issues.
- `SessionRegistry.receiveJoin` duplicate-name / already-registered guards — logic is correct and both error codes are returned to the client.
- `SessionRegistry.deregister` — correctly nulls `activeController`, cancels `pendingAction` via `completeExceptionally`, and removes both map entries inside a single `synchronized` block.
- `WebSocketDeckerController.dispatch` — `JackOut` pin-check guard is explicit and returns a clear message; `LOCATE_DECKER` and `SWAP_MEMORY` unsupported-path stubs return descriptive `DispatchResult` rather than throwing.
