# Testing Review — server

## Summary

The server layer has a solid test foundation: unit tests in `SessionRegistryTest` and `WebSocketServerTest` cover the primary happy paths and several error paths (already-registered, name-taken, not-your-turn, forfeit on disconnect), and the integration test suite adds a real Ktor `testApplication` layer that exercises the wire protocol end-to-end. However, several non-trivial production branches are not exercised at all: the entire reconnect-with-token flow, the action-timeout path, the max-connections capacity guard, a subtle cast inside `dispatch` error handling, and a handful of DTO mapping gaps. The most structurally risky omission is the reconnect token logic, which contains a latent conditional bypass that no test would currently catch.

## Findings

### [HIGH] Reconnect flow has zero test coverage
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:56
**Issue:** The reconnect branch (lines 56–65) is never exercised by any test. A decker who disconnects and reconnects with the correct token should receive a `ControlMessage(reconnect=true)` without a new token; one reconnecting with the wrong token should receive `NAME_ALREADY_TAKEN`. Neither path is tested. A latent bypass also exists: if `reconnectTokens[name]` is null (possible if the map is cleared externally or in a future refactor) the condition `if (stored != null && msg.reconnectToken != stored)` silently allows any session to claim a disconnected decker's identity — a check that only a dedicated test would catch.
**Recommendation:** Add at minimum three tests to `SessionRegistryTest`: (1) disconnect then rejoin with the correct token — assert `reconnect=true` and no new `reconnectToken` in the response; (2) disconnect then rejoin with a wrong token — assert `NAME_ALREADY_TAKEN`; (3) a test that verifies the issued token is present in the original join response (currently `reconnectToken` in `ControlMessage` is never asserted).

### [HIGH] Action timeout path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:84
**Issue:** The `cmd == null` branch (timeout expiry) broadcasts a "Action timed out" `ResultMessage` and returns. Every test that sets `actionTimeoutSeconds = 5` immediately sends a valid action before the timer fires. There is no test that withholds the action and waits for the timeout result, so the broadcast message, the `demoteAfterTurn` call, and the `ActionResult.DeckerAction` return value on that path are all untested.
**Recommendation:** Add a unit test with `actionTimeoutSeconds = 1` that registers a decker, starts a turn thread, and does NOT call `receiveAction`. Assert that an observer session eventually receives a `ResultMessage` with `success=false` and `details` containing "timed out".

### [MEDIUM] Max-connections capacity guard is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:34
**Issue:** `register` returns `false` when `sessions.size >= maxConnections`, and `matrixModule` silently closes the WebSocket without sending an error to the rejected client (MatrixServer.kt:37–39). No test verifies either the return value or the silent-close behaviour. A regression in the capacity check would go undetected.
**Recommendation:** Add a `SessionRegistryTest` case that registers exactly `maxConnections` sessions, then attempts one more and asserts `register` returns `false`. Add a separate integration test that opens 33 connections and verifies the 33rd receives no message and the connection closes.

### [MEDIUM] `dispatch` exception handler is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:112
**Issue:** The outer `try/catch(e: Exception)` in `action` (lines 112–118) broadcasts "Internal error — turn aborted" and demotes the controller. No test triggers an exception from `dispatch`, so this branch — including the demotion call — is dead code from a test coverage perspective. A regression that removes the catch or the demotion call would be invisible.
**Recommendation:** Add a unit test that uses a `DiceRoller` or mocked `Decker` that throws inside `dispatch`, and assert that an observer receives `ResultMessage(success=false)` containing "Internal error" and that the session is subsequently demoted (no longer active controller).

### [MEDIUM] `locateWithState` invalid precision string falls back silently — untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:314
**Issue:** `locateWithState` uses `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL` to parse the precision parameter. An invalid string (e.g. `"ULTRA"`) silently defaults to `NORMAL`. No test sends an invalid precision value to verify the fallback, so if the fallback was accidentally removed or changed to throw, no test would catch it.
**Recommendation:** Add a unit or integration test that sends a `LOCATE_FILE` action with `params.precision = "INVALID_PRECISION"` and asserts the operation proceeds without error (using the NORMAL fallback).

### [MEDIUM] File content size guard is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:252
**Issue:** The `EDIT_FILE` handler rejects `content.length > 4096` with a dispatch failure. No test exercises this guard, meaning a regression that accidentally removed or miscalculated the boundary would not be caught.
**Recommendation:** Add a test that dispatches an `EDIT_FILE` action with content of exactly 4097 bytes and asserts a `ResultMessage(success=false)` with "exceeds maximum". Also add a boundary test with exactly 4096 bytes asserting it is accepted.

### [MEDIUM] `SWAP_MEMORY` / `LOCATE_DECKER` filter on availableActions is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45
**Issue:** The `filterNot` that removes `SWAP_MEMORY` and `LOCATE_DECKER` from the available actions list before broadcasting is never verified. No test checks that a `StateMessage` delivered to the client is missing these two operations even when the underlying `decker.availableActions()` would otherwise include them.
**Recommendation:** Add a test (possibly extending the existing integration navigation test) that puts the decker in a state where SWAP_MEMORY or LOCATE_DECKER would appear, and asserts neither operation appears in the `availableActions` array of the received `StateMessage`.

### [LOW] TurnCoordinator has no dedicated unit tests
**File:** src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt:1
**Issue:** `TurnCoordinator` is only exercised indirectly through `SessionRegistry`. The `cancelIfActive` path for a session that is NOT the active controller (returns null), the `claimAction` race between a completed future and a fresh one, and concurrent interleaving scenarios are all implicitly trusted to be covered by the registry tests but are not tested in isolation.
**Recommendation:** Add a `TurnCoordinatorTest` with direct calls to `setPendingAction`, `setActive`, `cancelIfActive` (both matching and non-matching sessions), and `claimAction` (not-your-turn, no-action-pending, already-completed future, and success cases).

### [LOW] Missing DTO mapping coverage — OnPLTG location and PrivateGrid
**File:** src/test/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt:1
**Issue:** `DtoMappingTest` tests `OnRTG`, `OnLTG`, and `OnHost` location labels, but `MatrixLocation.OnPLTG` (which produces the "PLTG: …" label) has no test. `MatrixObject.PrivateGrid.toDto()` is also untested despite the mapping existing in `MatrixObjectDto.kt`. `AvailableAction.LogonToPltg` and `LogonToHost` DTO mappings are similarly missing.
**Recommendation:** Add tests for `OnPLTG` location label, `MatrixObject.PrivateGrid.toDto()`, `AvailableAction.LogonToPltg.toDto()`, and `AvailableAction.LogonToHost.toDto()`.

### [LOW] Name length boundary not exercised
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:45
**Issue:** The name length guard (`name.length > 32`) is never tested. There is no test for a name of exactly 32 characters (should succeed) nor for a name of 33 characters (should return `NAME_TOO_LONG`). The guard itself is only indirectly trusted.
**Recommendation:** Add two tests: one with a 32-character name asserting successful registration, and one with a 33-character name asserting `ErrorCode.NAME_TOO_LONG` is returned.

### [LOW] Integration tests missing for wire-level error paths
**File:** src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt:1
**Issue:** The integration suite exercises the happy path and the `sendingLogonToLtgAction` flow, but none of the following wire-level error paths have integration coverage: sending an unknown `type` value (should return `UNKNOWN_MESSAGE_TYPE` error), sending malformed JSON (should return `BAD_REQUEST` error), sending a join with a duplicate name, and sending an action when not the active controller. These paths are covered at the unit level but the actual Ktor frame parsing in `matrixModule` is never exercised against them.
**Recommendation:** Add integration tests for: (1) `{"type":"bogus"}` — assert `error` message with `unknown_message_type`; (2) `{not:valid json` — assert `error` message with `bad_request`; (3) duplicate name join — assert `name_already_taken`; (4) action from observer — assert `not_your_turn`.

### [INFO] `broadcastWithRoles` active_controller role path untested in unit suite
**File:** src/test/kotlin/com/shadowrun/matrix/server/SessionRegistryTest.kt:111
**Issue:** `SessionRegistryTest` verifies that `broadcastWithRoles` sends `REGISTERED_DECKER` and `OBSERVER` roles, but never tests the `ACTIVE_CONTROLLER` path. The integration test covers this implicitly via the `StateMessage` assertion, but there is no focused unit test that promotes a decker and then checks the role field in the broadcast.
**Recommendation:** Add a `SessionRegistryTest` case that promotes a session with `promoteForTurn`, calls `broadcastWithRoles`, and asserts the promoted session receives `role = "active_controller"` in the state message.

## No Issues Found In

- `DeckerDisconnectedException` — trivial class; the disconnect mid-turn scenario is well covered by `WebSocketServerTest.active controller disconnect mid-turn broadcasts forfeit ResultMessage`
- Core `SessionRegistry` error codes (`ALREADY_REGISTERED`, `NAME_ALREADY_TAKEN`, `NOT_YOUR_TURN`, `NO_ACTION_PENDING`) — all have dedicated unit tests
- `TurnCoordinator.claimAction` completed-future guard — covered by `receiveAction when pendingAction is done sends no_action_pending error`
- `broadcast` delivery to multiple sessions — covered
- `promoteForTurn` false return for unknown decker — covered
- `demoteAfterTurn` with unknown name — covered
- `MatrixJson` / `encodeDefaults = true` configuration — not directly tested but implicitly exercised by every DTO round-trip in the integration suite
- DTO sealed class discriminator (`kind` field) — exercised by the integration test reading `actions[0].jsonObject["kind"]`
