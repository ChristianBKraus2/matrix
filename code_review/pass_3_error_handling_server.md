# Error Handling Review — server

## Summary

The server layer has solid structural discipline — `TurnCoordinator` uses atomics correctly, timeout and disconnect paths each return a `ResultMessage`, and the frame-dispatch loop sends a typed `ErrorMessage` on parse failures. However, several gaps remain: a broad `catch (e: Exception)` in the WebSocket loop will silently absorb Kotlin coroutine `CancellationException`, preventing clean shutdown; a capacity-refusal close sends no frame to the client; the inner `catch` block in `WebSocketDeckerController.action()` swallows dispatch exceptions with no log entry; multiple unsafe casts in the dispatch helpers throw `ClassCastException` caught only by that silent outer block; and two maps that track disconnected deckers grow without bound, creating both a memory leak and a potential denial-of-service vector.

## Findings

### [HIGH] Broad `catch (Exception)` swallows `CancellationException`
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:54
**Issue:** The inner `catch (e: Exception)` block catches every `Exception` subtype, including Kotlin's `CancellationException`. If the parent coroutine scope is cancelled (e.g., during server shutdown), the cancellation is silently absorbed, an error frame send is attempted, and the coroutine continues instead of terminating. This breaks structured concurrency and can leave WebSocket handler coroutines alive after the server stops.
**Recommendation:** Replace `catch (e: Exception)` with `catch (e: CancellationException) { throw e } catch (e: Exception) { ... }`, or use `catch (e: Exception) { if (e is CancellationException) throw e; ... }` so cancellation always propagates.

### [HIGH] No error frame sent when connection is refused at capacity
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:38-40
**Issue:** When `registry.register()` returns `false` (server at `MAX_CONNECTIONS`), the handler returns immediately with `return@webSocket`. The WebSocket upgrade has already completed, so the client receives a raw WebSocket close frame with no payload and no explanation. From the client's perspective the connection drops for an unknown reason.
**Recommendation:** Before `return@webSocket`, send a typed `ErrorMessage` (a new `ErrorCode.SERVER_FULL` or reuse `BAD_REQUEST`) so the client can display a meaningful message to the user.

### [HIGH] Dispatch exceptions in `action()` are caught but never logged
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:112-118
**Issue:** The `catch (e: Exception)` block that wraps the `dispatch()` call broadcasts "Internal error — turn aborted" but does not log the exception. There is no `logger` in this file at all. Any `ClassCastException`, `NullPointerException`, or other unexpected runtime error during action dispatch is entirely invisible in the logs, making production diagnosis impossible.
**Recommendation:** Add a `KotlinLogging` logger to the class and log the exception at `error` level inside this catch block before broadcasting the failure message.

### [MEDIUM] Multiple unsafe casts in dispatch helpers will throw `ClassCastException`
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:212, 243, 249, 259, 270, 274, 278
**Issue:** Several `when` branches perform hard casts such as `(action.target as MatrixObject.HostSubsystem)`, `(action.target as MatrixObject.File)`, and `(action.target as MatrixObject.Device)` with no null or type check. If the domain's `availableActions()` ever produces a mismatched target (e.g., due to a bug in game logic or a future refactor), these throw `ClassCastException`. The exception is caught by the outer `catch (e: Exception)` which does not log it (see finding above), producing a silent "Internal error" to the client.
**Recommendation:** Replace each hard cast with a safe cast followed by an explicit guard: `val node = (action.target as? MatrixObject.HostSubsystem) ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_SUBSYSTEM requires a HostSubsystem target")`. This mirrors the pattern already used correctly for `ANALYZE_IC` and `ANALYZE_ICON` on lines 201-207.

### [MEDIUM] `promoteForTurn()` send failure leaves turn state inconsistent
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:105-112
**Issue:** `turns.setActive(session)` is called on line 107 before the `session.send()` on line 108. If the send throws (e.g., the socket closed between the lookup and the send), the turn coordinator believes a controller is active but the client never received the `ACTIVE_CONTROLLER` role message and will never submit an action. The game then waits until the `actionTimeoutSeconds` expires, after which `demoteAfterTurn` is called but the session is already gone.
**Recommendation:** Either reverse the order (send first, then `setActive` only on success), or wrap the send in a try/catch that calls `turns.setActive(null)` to roll back promotion on failure.

### [MEDIUM] `disconnectedDeckerNames` and `reconnectTokens` grow without bound
**File:** src/main/kotlin/com/shadowrun\matrix\server\SessionRegistry.kt:24-25, 98
**Issue:** When a decker disconnects, `disconnectedDeckerNames` and `reconnectTokens` are populated but are only cleaned up on a successful reconnect using the same name. A decker that disconnects and never reconnects leaves their entry in both maps permanently. An attacker (or crash loop) creating and dropping connections with unique names will cause unbounded memory growth.
**Recommendation:** Introduce a TTL eviction mechanism — either a timestamped map with periodic cleanup, or remove entries after a fixed reconnect window (e.g., 5 minutes). At minimum, cap the size of `disconnectedDeckerNames`.

### [LOW] `register()` send failure leaves orphaned session in registry
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:32-41
**Issue:** `sessions.add(session)` is called inside the mutex (line 35), then `session.send()` is called outside it on line 39. If the send throws, the session remains in `sessions` but the client never received its welcome `ControlMessage` and may not know its role. The deregistration on WebSocket close will clean it up eventually, but until then `sessions.size` is inflated and the session could receive broadcasts it cannot correctly interpret.
**Recommendation:** Wrap the post-mutex send in a try/catch; on failure, call `deregister(session)` to remove it from `sessions` and close the connection cleanly.

### [LOW] Silent broadcast send failures produce no log output
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:125, 143
**Issue:** Both `broadcast()` and `broadcastWithRoles()` use `runCatching { session.send(...) }` to tolerate individual send failures, which is correct for resilience. However, the failure is silently discarded — there is no `onFailure { logger.warn(...) }` call. A persistently failing session (e.g., a stuck or half-open connection) will never be surfaced in logs.
**Recommendation:** Add `.onFailure { e -> logger.warn(e) { "Broadcast send failed for session" } }` to each `runCatching` call. Consider also proactively closing sessions whose sends fail repeatedly.

### [LOW] Empty decker name passes validation
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:45
**Issue:** `receiveJoin()` rejects names longer than 32 characters but does not reject blank or empty strings. A client can register with `deckerName = ""`, which is then used as a map key and broadcast to other clients in role messages. Downstream code that computes display strings or performs lookups by name may behave unexpectedly.
**Recommendation:** Add `if (name.isBlank()) { session.send(...ErrorCode.BAD_REQUEST...); return }` alongside the length check.

## No Issues Found In

- `TurnCoordinator.kt` — mutex usage is correct; `cancelIfActive` atomically reads and clears both `activeController` and `pendingAction`; `claimAction` returns typed error keys rather than throwing.
- `DeckerDisconnectedException.kt` — minimal and correct; used only for intentional disconnect signalling.
- `dto/Messages.kt` — `ErrorCode` enum covers all error paths; `MatrixJson` with `encodeDefaults = true` ensures all fields serialize even when defaulted; no deserialization risk since DTOs are only serialized server-to-client.
- `dto/DeckerStateDto.kt` — safe null handling with `?: "not jacked in"` for missing location; no unsafe casts.
- `dto/AvailableActionDto.kt` — mapping from domain to DTO uses safe pattern matching; no exception paths.
- `dto/MatrixObjectDto.kt` — exhaustive `when` over sealed class; all branches are accounted for.
