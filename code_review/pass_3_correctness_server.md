# Correctness Review — server

## Summary

The server layer is structurally sound and handles the most dangerous concurrency scenarios well: the TOCTOU fix for `setPendingAction`/`promoteForTurn` ordering is correct, the `TurnCoordinator` mutex guards are consistent, and the `deregister`/`cancelIfActive` handoff is safe. Two issues stand out: a logic inversion in the reconnect-token guard that silently bypasses authentication when the stored token is absent, and a cluster of unsafe casts in `WebSocketDeckerController` that trade a meaningful error message for an opaque "Internal error — turn aborted" whenever an unexpected target type reaches the dispatch layer. Everything else is clean or only worth a low-priority note.

## Findings

### [HIGH] Reconnect token check inverted — token silently bypassed when stored value is null

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:59

**Issue:** The guard reads `if (stored != null && msg.reconnectToken != stored)`. The condition that blocks reconnection requires `stored != null`, so when `stored` is `null` the entire expression is `false` and the reconnect is unconditionally allowed — no token needed. The intent is the opposite: a missing stored token should be the strictest case (reject, not accept). In normal flow `reconnectTokens` always has an entry for every disconnected name, but the logic is incorrect regardless: any future code path that adds a name to `disconnectedDeckerNames` without a corresponding entry in `reconnectTokens` (e.g., a bulk-clear of tokens for a game reset) would let any client claim an abandoned decker identity without a token.

**Recommendation:** Invert the guard to require a valid match: `if (stored == null || msg.reconnectToken != stored)`. This rejects reconnection when no token is stored and when the supplied token does not match.

---

### [MEDIUM] Unsafe casts in dispatch helpers throw ClassCastException instead of returning clean errors

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:212

**Issue:** `dispatchAnalyzeOp` casts `action.target as MatrixObject.HostSubsystem` (hard cast) for `ANALYZE_SUBSYSTEM`, while the immediately preceding `ANALYZE_IC` and `ANALYZE_ICON` branches use the safe `as?` pattern with an explicit early-return `DispatchResult`. The same hard-cast pattern is repeated in `dispatchDataOp` (lines 243, 249, 259 — `as MatrixObject.File`) and `dispatchSlaveOp` (lines 270, 274, 279 — `as MatrixObject.Device`). If the game layer ever produces an `AvailableAction.Operation` with a null or mistyped target, the cast throws a `ClassCastException` that escapes the inner dispatch call, is caught by the outer `catch (e: Exception)` in `action()`, and results in the generic "Internal error — turn aborted" broadcast with no diagnostic details. The actual error is swallowed.

**Recommendation:** Replace every bare `as Type` in the dispatch helpers with `as? Type ?: return DispatchResult(decker, false, 0, 0, "…requires a Type target")`, matching the safe pattern already used for `ANALYZE_IC` and `ANALYZE_ICON`.

---

### [LOW] Double-claim window: two simultaneous action frames from the same session both pass `claimAction`

**File:** src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt:39

**Issue:** `claimAction` checks `f.isCompleted` inside the mutex and returns `f to null` if the deferred is not yet complete. The actual `future!!.complete(cmd)` call in `receiveAction` happens outside the mutex. If two WebSocket frames arrive and are dispatched concurrently, both can pass the `isCompleted` guard (before either has called `.complete()`), receive the same `CompletableDeferred`, and both attempt `complete(cmd)`. The second call silently returns `false` and the second command is dropped without any error feedback to the client.

**Recommendation:** Either complete the deferred inside the mutex (if the API allows it), or mark the pending action as "claimed" atomically (e.g., set `pendingAction = null` once claimed) so the second concurrent caller receives `NO_ACTION_PENDING`. The current behaviour is unlikely to cause a visible bug under normal single-user operation, but could produce silent drops under network retransmission or client bugs.

---

### [LOW] Capacity-refused WebSocket connection receives no error frame before close

**File:** src/main/kotlin/com/shadowrun\matrix\server\MatrixServer.kt:37

**Issue:** When `registry.register` returns `false` the `webSocket` block returns immediately. Ktor closes the underlying TCP connection cleanly, but the client receives no WebSocket-level error frame explaining why it was dropped. A newly connected client will see a silent close with no `error` message, making capacity rejection indistinguishable from a network interruption.

**Recommendation:** Before returning, send an `ErrorMessage(message = ErrorCode.BAD_REQUEST, details = "server at capacity")` (or a dedicated error code) so the client can display a meaningful message.

## No Issues Found In

- `TurnCoordinator.kt` — mutex usage is correct and consistent across all five methods; `cancelIfActive` atomically clears both `activeController` and `pendingAction` in one lock acquisition.
- `SessionRegistry.deregister` / `promoteForTurn` / `demoteAfterTurn` — the sequencing of `turns.setActive`, session map updates, and `cancelIfActive` is correct; no dangling active-controller state can result from normal or disconnect flows.
- `WebSocketDeckerController.action()` — the `setPendingAction`/`promoteForTurn` ordering fix (deferred created before promoting) correctly closes the TOCTOU window; `finally { registry.setPendingAction(null) }` always clears state regardless of which exit path is taken.
- `MatrixServer.kt` — two-level exception handling (inner try/catch per frame, outer finally for deregister) is correct; frame parse errors send a `BAD_REQUEST` without breaking the session loop.
- `dto/Messages.kt` — all DTO fields are accounted for; `MatrixJson { encodeDefaults = true }` ensures default-valued fields are included in serialisation, which is necessary for `ControlMessage.reconnect`.
- `dto/DeckerStateDto.kt` — `toDto()` mapping is straightforward and complete; nullable `currentLocation` is handled with a fallback string.
- `dto/AvailableActionDto.kt` — `toDto()` uses `mapIndexed` so indices always match positions in the filtered list sent to the client; the index alignment with `availableActions.getOrNull(cmd.actionIndex)` in the controller is correct.
- `dto/MatrixObjectDto.kt` — enum serialisation via `.name` is consistent across all eight variants; the comment about keeping frontend union types in sync is appropriate.
- `DeckerDisconnectedException.kt` — trivially correct.
