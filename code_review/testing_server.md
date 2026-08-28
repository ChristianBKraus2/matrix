---
# Testing Review — server

## Summary

The server layer has reasonable unit coverage of `SessionRegistry` lifecycle operations and DTO mappings, and a useful end-to-end integration test that exercises the real Ktor WebSocket stack. However, `WebSocketDeckerController.dispatch` — the component that translates every player action into a game operation — has almost no test coverage beyond the "no controller registered" and "invalid action index" paths. Several error paths in dispatch can propagate uncaught exceptions that bypass the demotion flow, leaving the registry stuck with an active controller. One test in `WebSocketServerTest` is passing for the wrong reason (relying on an empty available-actions list rather than exercising a real dispatch), and the local `winRoller` helper deviates from the project convention documented in memory notes. The timeout path, the jack-out-while-pinned rule, multi-turn locate interrogation state, and all "not supported" stubs are completely unexercised.

## Findings

### HIGH: `winRoller` in WebSocketServerTest does not override `nextInt`, violating project convention
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:35
**Issue:** The local `winRoller()` only overrides `nextBits(bitCount): Int = 0`. `IntegrationTestBase.winRoller()` overrides both `nextBits` and `nextInt(from, until)`. Because Kotlin's abstract `Random.nextInt(from, until)` is derived from `nextBits`, the two are not equivalent when `from != 0`: `nextBits = 0` causes `nextInt(1, 7)` to return `from + 0 = 1` (face=1), whereas the base class override returns `0` (face=0, out of range). Per project memory notes, face=0 gives 0 successes always; face=1 does not count as a success either, so the difference rarely matters in practice, but the inconsistency makes the helper unreliable and confusing. Any future test that expects "win" semantics (decker beats host) will silently produce 0 successes for both sides, with the outcome determined by the tiebreaker of whichever operation is invoked.
**Recommendation:** Remove the local `winRoller()` from `WebSocketServerTest` and inherit `winRoller()` from `IntegrationTestBase` (or move the test class to extend it), so all server tests use the single agreed-upon roller.

### HIGH: "registered decker receives promotion" test verifies the wrong path — passes only if available actions are empty
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:139
**Issue:** The test sends `ActionCommand(actionIndex = 0)` and asserts `result.details.contains("Invalid action index")`. That assertion is only true when `availableActions.getOrNull(0)` returns null — i.e., when the decker's available-action list is empty. The test passes because `DeckerMock.build` with a not-jacked-in decker happens to produce an empty action list in that specific configuration. The test name implies it exercises the full promotion-and-dispatch flow, but it never reaches `dispatch()`: it short-circuits at the `chosen == null` guard. No test in the suite exercises a successful action dispatch through the WebSocket layer (logon, operation, jack-out). The entire `dispatch` / `dispatchHostOperation` / `dispatchGridOperation` tree is untested end-to-end via WebSocket.
**Recommendation:** Replace or augment the test so the decker is in a state with at least one available action (e.g., send the session through a logon turn first, as the integration test `decker navigating to UCAS RTG` already demonstrates). Assert on the `ResultMessage` content of that action. Separately, add a dedicated test that deliberately sends an out-of-range index (e.g., 99) to cover the invalid-index guard.

### HIGH: `QueryPrecision.valueOf` with an invalid precision string throws uncaught exception, leaving registry stuck
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245
**Issue:** `locateWithState` calls `QueryPrecision.valueOf(it)` on the string from `ActionParams.precision`. An invalid value (e.g. `"INVALID"`) throws `IllegalArgumentException`. This propagates through `dispatch()` → `action()`, bypassing the `finally` block on `future.get()` (that block sets `pendingAction = null`) but also bypassing the `demoteAfterTurn` call. The decker remains `activeController` in the registry indefinitely. Any subsequent `receiveAction` call from any session will be rejected with `not_your_turn` for all other sessions, and the stuck controller will receive `no_action_pending`. The server never recovers until the controller disconnects. There is no test for this path.
**Recommendation:** Wrap `QueryPrecision.valueOf(it)` in a `runCatching` or use `enumValueOrNull`; fall back to `QueryPrecision.NORMAL` and optionally send a warning message. Add a test that sends an action with an invalid `precision` string and verifies the decker is demoted and the registry is in a clean state afterward.

### MEDIUM: Timeout path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:77
**Issue:** The `TimeoutException` catch block broadcasts a "Action timed out" `ResultMessage` and calls `demoteAfterTurn`. No test verifies this path. The shortest timeout used in tests is 5 seconds — long enough that the path is never triggered by any test. It is unknown whether `demoteAfterTurn` is reached correctly or whether `pendingAction` is properly nulled in the `finally` block before the timeout message is sent.
**Recommendation:** Add a unit test that registers a decker, starts `wsController.action()` on a thread with `actionTimeoutSeconds = 1`, and does not send any action. Assert the observer receives a `ResultMessage` with `success = false` and details containing "timed out", and that `registry.pendingAction` is null after the thread completes.

### MEDIUM: Jack-out-while-pinned guard is untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:131
**Issue:** The `JackOut` dispatch branch has a special rule: if `decker.isPinnedByBlackIc`, it returns a failure `DispatchResult` with the message "Pinned by Black IC — cannot jack out" without calling `decker.jackOut()`. No test exercises this branch. The normal jack-out path (`decker.jackOut().toDispatch()`) and the dump-shock detail message in `LogoffResult.JackOut.toDispatch()` are also untested.
**Recommendation:** Add tests for (a) jack-out when pinned: assert `result.success == false` and details contain "Pinned"; (b) successful jack-out: assert `result.success == true`; (c) jack-out with dump shock: assert details contain "dump shock".

### MEDIUM: `locateWithState` interrogation state accumulation is entirely untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:237
**Issue:** `locateWithState` maintains `interrogationStates` across successive `action()` calls to accumulate successes for multi-turn locate operations (`LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE`). The map is updated on `LocateResult.Ongoing`, cleared on `Located` and `NotFound`. No test exercises more than one turn of a locate operation, so the accumulation logic, the state-clear on success, and the state-clear on not-found are all untested. There is also no test that verifies state is not shared between different operations (e.g., `LOCATE_FILE` and `LOCATE_SLAVE` should have independent entries).
**Recommendation:** Add integration-level tests for at least `LOCATE_FILE`: one test that resolves in a single turn, one that requires two turns (verifying `accumulatedSuccesses` increments), and one that ends with `NotFound` (verifying state is removed).

### MEDIUM: `LOCATE_DECKER` and `SWAP_MEMORY` "not supported" stubs are untested
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:220
**Issue:** Both operations return hardcoded failure `DispatchResult` with "not supported via WebSocket" messages. No test verifies these stubs. If the available-action list were ever to include these operations and a client sends the corresponding index, the fallback would silently deliver a failure result with no diagnostic. The `else ->` fallback at line 233 (`"Unsupported: ${action.operation}"`) is similarly untested.
**Recommendation:** Add unit tests for each stub: set up a decker with those operations available (or inject them directly), send the action index, and assert the failure result message.

### MEDIUM: `runCatching` in `matrixModule` silently discards all client errors
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29
**Issue:** The `runCatching` block swallows every exception from frame parsing and dispatch, including malformed JSON, unknown `msgType`, and serialization failures. No error is sent back to the client. No test verifies this behavior: there is no test for malformed JSON, for an unknown `type` field, or for a binary frame. A client that sends `{"type":"bogus"}` receives no feedback and cannot know whether the message was ignored.
**Recommendation:** On parse failure, send an `ErrorMessage` to the offending session. Add tests for (a) malformed JSON, (b) unknown `msgType`, (c) a binary `Frame.Binary` being silently ignored, verifying that in all cases the session receives either an error or nothing (and the server does not close the connection).

### MEDIUM: `broadcastWithRoles` active_controller role variant not tested in SessionRegistryTest
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistryTest.kt:109
**Issue:** `SessionRegistryTest` verifies `registered_decker` and `observer` roles in `broadcastWithRoles`, but never the `active_controller` branch (line 96 of `SessionRegistry`). The condition `s == activeController` is only set via `promoteForTurn`, which is not combined with `broadcastWithRoles` in any `SessionRegistryTest` case. This means the role-assignment logic for active controllers is only implicitly covered by the higher-level `WebSocketServerTest`.
**Recommendation:** Add a test: register a session, join as a decker, call `promoteForTurn`, then `broadcastWithRoles`, and assert the received `StateMessage` has `role = "active_controller"`.

### LOW: Several DTO mapping variants have no test case in DtoMappingTest
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt
**Issue:** The following mappings are missing test coverage:
- `MatrixObject.PrivateGrid.toDto()` — only `GridNode` and `LocalGrid` grid-level objects are tested.
- `AvailableAction.LogonToLtg`, `LogonToPltg`, `LogonToHost` `.toDto()` — only `LogonToRtg`, `JackOut`, and `GracefulLogoff` are tested.
- `MatrixLocation.OnPLTG` label in `Decker.toDto()` — only RTG, LTG, and Host labels are tested.
**Recommendation:** Add one test case per missing variant to `DtoMappingTest`. These are simple value-pass-through assertions and require minimal setup.

### LOW: `AnalyzeSecurityResult.toDispatch()` hardcodes `success = true` — unverified design decision
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:291
**Issue:** `AnalyzeSecurityResult.toDispatch()` always produces `success = true` regardless of the dice outcome. No test documents this as an intentional rule (e.g., "security analysis always tells you something"). If the intent is that the operation always succeeds from the decker's perspective, that should be captured in a test comment or assertion.
**Recommendation:** Add a test or in-code comment explaining why this is unconditional. If it is not intentional, use `outcome.deckerWins` (as `AnalyzeHostResult.toDispatch()` does) and add a test that verifies the correct outcome.

### INFO: No test for concurrent SessionRegistry access
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt
**Issue:** `SessionRegistry` uses `synchronized(lock)` blocks alongside `@Volatile` for `pendingAction`. The combination is not proven by any multithreaded test. In particular, `receiveAction` reads `pendingAction` outside the synchronized block (intentionally, to avoid deadlock on `session.send`), but there is no test that exercises concurrent `receiveAction` and `deregister` on different threads.
**Recommendation:** A single stress test that fires `receiveAction` and `deregister` concurrently (e.g., via `CompletableFuture.allOf`) would catch any missed memory visibility or ordering issue.

## Clean Areas
- `SessionRegistryTest` covers all the standard lifecycle paths thoroughly: register, join, duplicate join, re-join same session, deregister non-controller, deregister controller with null or completed future, promote/demote, and broadcast.
- `DtoMappingTest` is well-structured and tests both null and non-null `guardedNode` for `IcProgram`.
- `FakeWebSocketSession` is a minimal, correct test double — uses an `UNLIMITED` channel so sends never block, and `incoming` is an empty channel that callers can ignore.
- `WebSocketServerIntegrationTest` exercises the real Ktor routing stack end-to-end and includes a multi-turn navigation scenario that validates action-index-to-game-operation wiring against real grid data.
- `WebSocketServerTest` correctly covers the disconnect-mid-turn forfeit path, including the observer receiving the broadcast, which is the most complex concurrency path in the server layer.
---
