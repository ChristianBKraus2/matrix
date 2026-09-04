# Correctness Review — server

## Summary

The server layer is structurally sound: message routing, session lifecycle, and turn promotion/demotion all follow a clear, defensible design. The most significant correctness gaps are concentrated in `WebSocketDeckerController.dispatch`: the `RELOCATE_ICON` host-context call silently passes hardcoded `0` for defensive parameters (nullifying host resistance), several pair-returning game operations discard their second element (potentially losing downloaded data, device state, or other results), and unsafe target-type casts for `ANALYZE_IC`/`ANALYZE_ICON` degrade gracefully but give no diagnostic information. The two known no-op stubs (`LOCATE_DECKER`, `SWAP_MEMORY`) are confirmed dead code under current filtering, posing no immediate runtime risk but remaining silent landmines if filtering is relaxed. Input validation has one gap: an empty decker name passes the only length guard.

---

## Findings

### [HIGH] RELOCATE_ICON ignores host defensive values in host context
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:230`
**Issue:** `dispatchHostOperation` calls `decker.relocateIcon(0, 0, diceRoller)` with both `opponentSensor` and `trackerMcpRating` hardcoded to `0`. The `host` variable is in scope but unused. The host's sensor rating is what lets security detect an icon relocation; zeroing it means the host exercises no opposition and the operation always succeeds unopposed. The identical zero-pass in `dispatchGridOperation` (line 153) is acceptable because grid-level operations have no local defender, but on a host it is a game-logic error.
**Recommendation:** Read the real values from the host object — e.g., `host.sensorRating` for `opponentSensor` and `decker.cyberdeck.mcpRating` for `trackerMcpRating` — matching how other host operations use the `host` parameter.

---

### [HIGH] Pair-returning operations silently discard their second element
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:187,196,208,225,227,233`
**Issue:** Six operations — `controlSlave`, `downloadData`, `editSlave`, `makeComcall`, `monitorSlave`, `tapComcall` — return `Pair<OperationResult, X>` and only `.first` (the `OperationResult`) is consumed. The second element (likely the data payload for `downloadData`, the updated device state for `controlSlave`/`editSlave`/`monitorSlave`, or communication metadata) is silently dropped. For `downloadData` in particular, discarding the second element means downloaded file contents are never applied or sent anywhere, making the operation functionally a no-op beyond the dice roll.
**Recommendation:** Destructure each pair and handle the second element explicitly. For `downloadData`, if the second element is the file content or an updated `Decker` state, it must be surfaced via the result or stored in the game context. At minimum, assert or log that the discarded value is intentionally unused with a comment explaining why.

---

### [MEDIUM] LOCATE_DECKER and SWAP_MEMORY are confirmed dead dispatch branches
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:222,232`
**Issue:** (Known issue, confirmed.) Both operations are filtered from `availableActions` before the state message is built (lines 48–49) and before `getOrNull(cmd.actionIndex)` resolves the client's choice (line 93). A client therefore can never legitimately select either operation. The dispatch branches at lines 222 and 232 are unreachable under current logic, making them dead code. Should the filtering ever be removed without adding real implementations, both silently return `success = false` with a string explanation — which is better than crashing, but gives the caller no actionable information.
**Recommendation:** Either implement these operations fully and remove the filter, or add a clear `// NOT_IMPLEMENTED — filtered above` comment to document the intentional gap and prevent accidental re-activation.

---

### [MEDIUM] Empty or whitespace-only decker name accepted
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:37`
**Issue:** `receiveJoin` validates only that `name.length > 32`. A name of `""` (empty string) or `"   "` (whitespace) passes the check and registers successfully. An empty name would produce confusing `ControlMessage` and `ResultMessage` broadcasts (e.g., "No controller registered for decker  — turn skipped"), and could conflict with any game-engine logic that treats an empty name as absent.
**Recommendation:** Add a `name.isBlank()` check and return `ErrorCode.NAME_TOO_LONG` (or a new `NAME_INVALID` code) before the length check.

---

### [MEDIUM] Unsafe target cast in ANALYZE_IC and ANALYZE_ICON produces generic error
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:172,177`
**Issue:** Both `ANALYZE_IC` and `ANALYZE_ICON` cast `action.target` to `MatrixObject.IcProgram` without null-guarding or type-checking:
```kotlin
val ic = (action.target as MatrixObject.IcProgram).ic
```
If `action.target` is null or a different `MatrixObject` subtype (which could happen if the game engine returns a malformed `AvailableAction`), a `NullPointerException` or `ClassCastException` is thrown. The outer `catch (e: Exception)` at line 115 catches it and broadcasts the generic `"Internal error — turn aborted"` message, swallowing all diagnostic detail.
**Recommendation:** Use a safe cast: `val ic = (action.target as? MatrixObject.IcProgram)?.ic ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_IC: target is not an IC program")`. This provides a useful error message and avoids exception-as-control-flow.

---

### [LOW] JSON serialization performed inside the registry lock in broadcastWithRoles
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:111-121`
**Issue:** The `synchronized(lock)` block in `broadcastWithRoles` builds the full list of `(session, serializedJson)` pairs by calling `MatrixJson.encodeToString(base.copy(role = role))` for every connected session inside the lock. Serialization is CPU-bound work that holds the lock far longer than a simple read, blocking `register`, `deregister`, `promoteForTurn`, and `receiveAction` for the entire duration.
**Recommendation:** Inside the lock, collect only `(session, role)` pairs (a trivial O(n) snapshot), then serialize outside the lock. The `base` object is immutable so this is safe.

---

### [LOW] disconnectedDeckerNames grows without bound
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:70`
**Issue:** `disconnectedDeckerNames.add(name)` is called on every disconnect; names are only removed on successful reconnect (`disconnectedDeckerNames.remove(name)` in `receiveJoin` line 48). In a long-running server where deckers join once and never reconnect, this set grows monotonically. There is also no TTL or cap.
**Recommendation:** Either enforce a max-size eviction policy (e.g., only track the last N disconnected names), or clear the set entry when the game engine declares the associated decker permanently out of the game.

---

### [LOW] Zero defaults for game-meaningful numeric parameters
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:225,229,233`
**Issue:** Three operations use `?: 0` as a default when the client omits a parameter:
- `NULL_OPERATION`: `p?.inactivitySeconds ?: 0` — zero inactivity seconds may be semantically different from "standard null operation."
- `TAP_COMCALL`: `p?.scannerDeviceRating ?: 0` — a rating of 0 likely means no scanner, which changes the dice pool differently from not specifying one.
- `MAKE_COMCALL`: `p?.hasValidPasscode ?: false` — reasonable default, but undocumented.

If the game engine distinguishes between "0" and "absent," callers that omit the field receive silently incorrect game behavior.
**Recommendation:** Document the expected defaults in `ActionParams` with KDoc, and verify that `0`/`false` are the correct sentinel values for each operation rather than assuming they are.

---

### [INFO] Inner exception handler conflates client errors with server errors
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:43-49`
**Issue:** The `catch (e: Exception)` block at line 43 wraps not only JSON deserialization but also the `registry.receiveJoin` and `registry.receiveAction` suspend-function calls. A server-side coroutine failure or infrastructure I/O error returns `ErrorCode.BAD_REQUEST` to the client, misleading it into thinking it sent a malformed message.
**Recommendation:** Narrow the `try` block to cover only JSON parsing/decoding. Let registry calls either handle their own exceptions or propagate to a separate error boundary that sends an appropriate server-error indication.

---

### [INFO] Dead WebSocket sessions stay in sessions set until frame loop exits
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107,123`
**Issue:** Both `broadcast` and `broadcastWithRoles` use `runCatching { session.send(...) }` which swallows send failures silently. A session whose underlying connection dropped (but whose WebSocket frame loop hasn't yet exited) remains in `sessions` and incurs a failed send on every broadcast with no log and no cleanup trigger. Deregistration happens only when the `for (frame in incoming)` loop in `MatrixServer` terminates.
**Recommendation:** This is largely an inherent property of Ktor WebSockets, but consider logging the failure count or issuing a `session.close()` on repeated failures to accelerate cleanup.

---

## No Issues Found In

- **Turn sequencing logic** (`WebSocketDeckerController.action`): The ordering of `setPendingAction → promoteForTurn → broadcast → await` correctly closes the TOCTOU window where a client message could arrive before the deferred is set.
- **DeckerDisconnectedException propagation**: `completeExceptionally` → `deferred.await()` throws → propagates through `withTimeoutOrNull` → caught by the correctly-ordered `catch (_: DeckerDisconnectedException)` before the generic `catch (e: Exception)`.
- **Timeout/null handling in action()**: All four exit paths (no controller, disconnect, timeout, invalid index) properly call `demoteAfterTurn` and return `ActionResult.DeckerAction` before falling through.
- **receiveAction TOCTOU**: Both the `session != activeController` check and `pendingAction` capture are done atomically inside the lock; the comment at line 128 correctly explains the intent.
- **Index consistency**: `availableActions.toDto()` and `availableActions.getOrNull(cmd.actionIndex)` both operate on the same filtered list, so client-supplied indices are correctly aligned.
- **DTO serialization** (`Messages.kt`, `DeckerStateDto.kt`, `AvailableActionDto.kt`, `MatrixObjectDto.kt`): All `@Serializable` annotations, `@SerialName` overrides, and `@JsonClassDiscriminator` usage look correct; the `MatrixJson { encodeDefaults = true }` instance is used consistently for outbound messages.
- **Reconnect handling**: The `disconnectedDeckerNames` / `isReconnect` flow correctly distinguishes a returning decker from a new one without re-registering a currently-connected name.
