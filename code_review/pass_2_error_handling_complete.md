# Error Handling Review — complete (cross-cutting)

## Summary

Error propagation in this stack is asymmetric: the WebSocket boundary is reasonably well-defended (bad JSON returns a structured `ErrorMessage`, disconnects cancel pending futures cleanly, timeouts and invalid action indices produce user-visible `ResultMessage` failures), but the two ends of the chain — the game loop in `Game.kt` and the browser WebSocket hook — are almost entirely unguarded. The game loop has no try/catch around icon actions, so a single thrown exception kills the turn loop with no recovery or broadcast. Symmetric to that, the frontend silently discards malformed frames, WebSocket errors, and failed sends. Most critically, there is no server-side logging anywhere in the Kotlin stack: every `catch` block either swallows the exception entirely or sends a truncated string to the client, meaning production failures leave no trace on the server.

---

## Findings

### [CRITICAL] No server-side logging anywhere in the Kotlin stack
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:43-48`,
`src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:79-84`, `:115-120`
**Issue:** Every `catch` block in the Kotlin server either silently returns or sends a short string to the client. No logger call, `System.err`, `println`, or telemetry exists anywhere in the reviewed files. The catch at `MatrixServer.kt:43` forwards `e.message?.take(120)` to the client and discards the rest. The catches at `WebSocketDeckerController.kt:79` and `:115` broadcast generic strings ("Unexpected error — turn aborted", "Internal error — turn aborted") with the actual exception and stack trace fully discarded. When these paths are hit in production there is no way to reconstruct what happened.
**Recommendation:** Add a structured logger (e.g., `org.slf4j.LoggerFactory`) to `WebSocketDeckerController` and `MatrixServer`. At minimum log `e.toString()` or the full stack trace at `ERROR` level in each catch block before sending the user-facing message. The `details` sent to the client can remain sanitised; the server-side log should be complete.

---

### [CRITICAL] Game loop has no error handling — a thrown exception kills the entire run
**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:14-16`, `:22-25`
**Issue:** Both `runOutOfCombatTurn()` and `runCombatTurn()` call `icon.action(context, diceRoller)` with no surrounding try/catch. `WebSocketDeckerController.action()` has internal guards for the most common paths, but it also has a top-level `runBlocking` that can throw (e.g., if the coroutine scope is cancelled unexpectedly). Any uncaught exception from any icon action propagates out of the loop, aborting all remaining icon turns for that round with no broadcast to clients. Observer clients will see the game freeze with no explanation.
**Recommendation:** Wrap the per-icon action calls in both loop methods with a try/catch that logs the error and, at minimum, continues to the next icon. If the game has access to a broadcast mechanism, send a `ResultMessage(success=false, details="Internal error — turn skipped for ${icon}")` so observers are not left hanging.

---

### [HIGH] `broadcast()` and `broadcastWithRoles()` silently swallow send failures
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:104-125`
**Issue:** Both methods use `runCatching { session.send(...) }` with no `.onFailure` handler. If sending a `StateMessage` or `ResultMessage` to a client fails (e.g., due to a half-closed WebSocket), the failure is completely invisible. The affected client continues to think it is in sync, but it has missed a state update or action result. There is no way to detect or retry the missed delivery.
**Recommendation:** Add `.onFailure { logger.warn("Send failed for session $session: $it") }` to each `runCatching` block. For `StateMessage` broadcasts, consider tracking failed sessions and either evicting them or flagging them for a re-sync on their next message.

---

### [HIGH] Unchecked casts in `dispatchHostOperation` silently convert to "Internal error"
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:171-234`
**Issue:** Multiple branches cast `action.target` without any prior type check, for example:
```kotlin
val ic = (action.target as MatrixObject.IcProgram).ic   // lines 172, 177
val node = (action.target as MatrixObject.HostSubsystem).node  // line 183
val device = (action.target as MatrixObject.Device).device     // lines 186, 209, 226
val file = (action.target as MatrixObject.File).file           // lines 190, 196, 200
```
A `ClassCastException` here is caught by the outer `catch (e: Exception)` in `action()` (line 115), which broadcasts "Internal error — turn aborted" and discards the exception. Since `availableActions` is server-generated, this is unlikely in production, but it is an invisible cliff-edge during development and testing.
**Recommendation:** Replace unchecked casts with safe casts and explicit error results:
```kotlin
val ic = (action.target as? MatrixObject.IcProgram)?.ic
    ?: return DispatchResult(decker, false, 0, 0, "Missing IC target for ${action.operation}")
```
This converts a silent crash into a user-visible failure with a meaningful message, and removes the dependency on the outer catch for type safety.

---

### [HIGH] `ws.onerror` discards the error event entirely with no logging or user feedback
**File:** `frontend/src/hooks/useWebSocket.ts:122`
**Issue:** `ws.onerror = () => ws.close()` — the `ErrorEvent` argument is ignored. WebSocket error events carry a `.message` and `.type` that identify the failure. Discarding it means network errors, TLS failures, and connection refused scenarios are invisible both to the developer (no `console.error`) and to the user (no state change until `onclose` fires and the reconnect timer starts).
**Recommendation:** At minimum add `console.error('WebSocket error:', ev)` inside the handler. If the UX supports it, dispatch a transient UI notification distinguishing a connection error from a normal close.

---

### [HIGH] `sendAction()` silently drops the action when the socket is not OPEN
**File:** `frontend/src/hooks/useWebSocket.ts:142-150`
**Issue:** If `wsRef.current?.readyState !== WebSocket.OPEN`, `sendAction` returns without sending and without notifying the caller or user. The UI may have allowed the player to select and submit an action (e.g., `ActionsPanel` showing buttons while `connected` is briefly stale), and the action is silently lost. The server-side timeout will eventually fire ("Action timed out") but only after 120 seconds.
**Recommendation:** Return a `boolean` from `sendAction` indicating whether the send succeeded, or throw/reject. `ActionsPanel` should disable action buttons when `!connected`, and `sendAction` should surface a failure path for the caller.

---

### [MEDIUM] Generic `catch (e: Exception)` around `deferred.await()` discards exception identity
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:79-84`
**Issue:** The catch block after the `DeckerDisconnectedException` handler catches any other exception from `deferred.await()`, broadcasts "Unexpected error — turn aborted", and discards `e`. This means coroutine cancellation (`CancellationException`), `IllegalStateException`, and any future exception types from the deferred all produce the same generic message with no diagnostic trail.
**Recommendation:** Log `e` at ERROR level before broadcasting. Also consider explicitly re-throwing `CancellationException` since swallowing it interferes with structured concurrency cancellation.

---

### [MEDIUM] Exception message truncated to 120 chars in `BAD_REQUEST` detail sent to client
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:46`
**Issue:** `e.message?.take(120)` caps the exception detail forwarded to the client. While some truncation is reasonable for security, 120 characters is often not enough to include the relevant part of a JSON parse error (e.g., the field name and expected type). The remainder is lost both client-side and server-side (no logging).
**Recommendation:** Log the full `e.message` or `e.toString()` server-side, and consider raising the client-facing cap to 256 characters for a development/debug profile.

---

### [MEDIUM] Malformed WebSocket frames silently ignored with no logging
**File:** `frontend/src/hooks/useWebSocket.ts:109-111`
**Issue:** The `catch` block inside `ws.onmessage` is `// ignore malformed frames` with no `console.error` or other signal. A server-side serialization regression, an extra field on a message type, or an unrecognised `type` value would silently swallow the frame. The game state would fall out of sync with no indication of why.
**Recommendation:** Replace with `console.error('Malformed WS frame:', ev.data, e)`. Unrecognised `type` values are already silently dropped by the `switch` with no `default` case; adding `default: console.warn('Unknown message type:', msg.type)` would catch those too.

---

### [MEDIUM] `ActionResult` return values discarded in both `Game.kt` turn loops
**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:15`, `:24`
**Issue:** `ActionResult` is a sealed class with four variants: `IcAttack(message)`, `IcMoved(message)`, `NoTarget`, and `DeckerAction`. The `message` field on `IcAttack` and `IcMoved` is intended to carry narrative information about what happened, but both `runOutOfCombatTurn()` and `runCombatTurn()` call `.action()` and discard the return value entirely. IC attack and movement events are therefore never broadcast to observers.
**Recommendation:** Capture the return value and, for `IcAttack` and `IcMoved`, broadcast a `ResultMessage` (or a dedicated narrative message type) with the `message` string so observers can follow combat.

---

### [LOW] `register()` and `promoteForTurn()` sends are not wrapped in `runCatching`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:32`, `:88`
**Issue:** `broadcast()` and `broadcastWithRoles()` consistently use `runCatching` around each send to handle mid-iteration WebSocket failures. However, the single sends in `register()` and `promoteForTurn()` are bare `session.send(...)` calls. A failure there propagates as an uncaught exception to the WebSocket handler in `MatrixServer.kt`, which wraps it in a `BAD_REQUEST` error response — a misleading error code for what is actually a network-level send failure during the initial handshake or turn promotion.
**Recommendation:** Wrap both sends in `runCatching { ... }.onFailure { logger.warn(...) }` to match the pattern used elsewhere.

---

### [LOW] Error event ring-buffer (20 items) can push earlier errors off before user reads them
**File:** `frontend/src/hooks/useWebSocket.ts:51`, `:55`
**Issue:** Both `RESULT` and `ERROR` actions trim the events list to the last 20 items (`events.slice(-19)`). During rapid action sequences (combat turns with multiple IC activations), result messages can push earlier error messages off the list before the user has a chance to read them.
**Recommendation:** Either increase the cap, or implement separate ring-buffers for results and errors, since errors are typically more important to preserve than routine success results.

---

### [LOW] Duplicate, partially inconsistent `ERROR_LABELS` maps in frontend
**File:** `frontend/src/App.tsx:10-18`, `frontend/src/components/NarrativePanel.tsx:3-8`
**Issue:** Both files define an `ERROR_LABELS` map. `App.tsx` covers all 7 error codes. `NarrativePanel.tsx` covers only 4, omitting `name_too_long`, `unknown_message_type`, and `bad_request`. When one of those error codes arrives during a game session (after registration), `NarrativePanel` falls back to displaying the raw serialised enum key (e.g., `"bad_request"`) instead of a readable label. The duplication also means future error codes must be added in two places.
**Recommendation:** Extract `ERROR_LABELS` into a shared constant in `frontend/src/types/messages.ts` or a dedicated `frontend/src/utils/errorLabels.ts` and import it in both components.

---

### [INFO] `ErrorMessage.details` is available but unused for most server-side errors
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:38`, `:53`
**Issue:** `ErrorMessage` has an optional `details: String?` field, and the client renders it in `NarrativePanel`. However, `receiveJoin()` sends `ErrorMessage(message = error)` with `details = null` for `ALREADY_REGISTERED` and `NAME_ALREADY_TAKEN`. Adding a short contextual string (e.g., the decker name that is taken) would improve debuggability at negligible cost.
**Recommendation:** Pass contextual information in `details` where available (e.g., `details = name` for `NAME_ALREADY_TAKEN`).

---

## No Issues Found In

- **`SessionRegistry.receiveAction()`** — The TOCTOU race between checking `activeController` and consuming `pendingAction` is correctly handled with a single synchronized block. Error codes `NOT_YOUR_TURN` and `NO_ACTION_PENDING` are properly returned.
- **`SessionRegistry.deregister()`** — Captures and cancels `pendingAction` inside the same synchronized block as nulling `activeController`, avoiding the race between disconnection and action completion.
- **`WebSocketDeckerController.action()` timeout path** — `withTimeoutOrNull` is used correctly (not `withTimeout`), avoiding a spurious `TimeoutCancellationException`, and the null result is handled explicitly with a user-visible message.
- **`MatrixServer.kt` frame routing** — The outer try/finally guarantees `deregister()` is always called. The inner try/catch correctly scopes to the per-frame parse-and-dispatch, so a bad frame does not close the connection.
- **`useWebSocket.ts` reconnection logic** — Exponential back-off with a 30-second cap is correctly implemented. The `pendingNameRef` mechanism correctly re-sends the join message after reconnection.
- **`OperationResult` sealed class** — Clean success/failure discrimination with no thrown exceptions; `toDispatch()` handles both variants exhaustively.
- **`DeckerDisconnectedException`** — Defined, thrown, and caught in a tight scope; not leaked into unrelated code paths.
- **`ErrorCode` enum + `ERROR_LABELS` in `App.tsx`** — The full set of server error codes has corresponding human-readable labels at the join screen, and the TypeScript union type in `messages.ts` matches the Kotlin `@SerialName` values exactly.
