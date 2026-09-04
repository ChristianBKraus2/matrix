# Testing Review — server

## Summary

The server component has a solid structural foundation: the `SessionRegistry` unit tests cover the main happy and error paths for join, action, broadcast, and deregister; the `WebSocketServerTest` adds controller-lifecycle integration at the fake-session level; and the Ktor integration test exercises the real HTTP/WS stack end-to-end for a handful of scenarios. However, several meaningful code paths have zero test coverage — most notably the action-timeout branch, the name-too-long validation, the decker-reconnect feature, and every wire-level error path in `MatrixServer.kt`. The `FakeWebSocketSession.incoming` channel is structurally write-locked, which silently leaves all of `MatrixServer.kt`'s frame-parsing dispatch logic untestable at the unit level. DTO mapping tests have a few omissions. A handful of unit tests use raw `Thread` + time-bounded `join`, which can produce silent false-passes on slow CI hosts.

---

## Findings

### [CRITICAL] Action timeout path has no test coverage

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:74`

**Issue:** `withTimeoutOrNull(actionTimeoutSeconds * 1000L) { deferred.await() }` returns `null` when the client takes too long. This branch broadcasts "Action timed out", calls `demoteAfterTurn`, and returns `ActionResult.DeckerAction`. It is a realistic, user-visible failure mode (idle or disconnected browser) and is the only major turn-resolution branch with no test of any kind. `WebSocketServerTest` constructs controllers with `actionTimeoutSeconds = 5` but always sends an action before the window expires, so the `null` path is never reached.

**Recommendation:** Add a unit test that promotes a decker, sets `pendingAction`, but never calls `receiveAction`. Use a controller built with `actionTimeoutSeconds = 1` and assert that the broadcast contains "timed out" and that the session receives a demotion `ControlMessage(registered_decker)` afterwards.

---

### [HIGH] `NAME_TOO_LONG` error code is never exercised

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:37`

**Issue:** `if (name.length > 32)` → `ErrorCode.NAME_TOO_LONG`. Neither `SessionRegistryTest`, `WebSocketServerTest`, nor `WebSocketServerIntegrationTest` contains a test that sends a name longer than 32 characters. The boundary case (exactly 32 chars should succeed; 33 should fail) is also untested.

**Recommendation:** Add two tests in `SessionRegistryTest`: one with a 33-character name asserting `NAME_TOO_LONG`, and one with exactly a 32-character name asserting `registered_decker` success.

---

### [HIGH] Decker reconnect feature has no test coverage

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:48–49`

**Issue:** When `deregister` adds a name to `disconnectedDeckerNames` and the same name later calls `receiveJoin`, `isReconnect = true` and `ControlMessage.reconnect = true` is sent back. This is a distinct product feature (reconnect vs. fresh join) with no test anywhere — not in unit tests, not in the integration suite. The `reconnect` field on `ControlMessage` is never asserted in any test.

**Recommendation:** Add a unit test in `SessionRegistryTest`: register a session, join as "Kylie", deregister, register a new session, join as "Kylie" again, and assert the returned `ControlMessage` has `reconnect = true`. Also add a negative case: a fresh join for a name that was never disconnected must have `reconnect = false`.

---

### [HIGH] `MatrixServer.kt` frame-dispatch logic is entirely untested

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31–54`

**Issue:** The `for (frame in incoming)` loop, the `msgType` extraction, the `when(msgType)` dispatch (including the `else` → `UNKNOWN_MESSAGE_TYPE` branch), and the outer `catch` block that sends `BAD_REQUEST` are all inside the WebSocket handler. Because `FakeWebSocketSession.incoming` is a private RENDEZVOUS `Channel` with no external sender reference, unit tests cannot inject frames — they bypass this code entirely by calling `registry.receiveJoin()` / `registry.receiveAction()` directly. The integration test (`WebSocketServerIntegrationTest`) only sends valid `join` and `action` messages and never tests the error branches.

**Recommendation:** In `WebSocketServerIntegrationTest`, add tests that send through the real Ktor stack:
- A frame with an unknown `type` (e.g., `{"type":"ping"}`) and assert the response is `{"type":"error","message":"unknown_message_type"}`.
- Malformed JSON (e.g., `not-json`) and assert the response is `{"type":"error","message":"bad_request"}`.
- A valid JSON object that omits the `type` field entirely and assert `unknown_message_type`.

---

### [HIGH] `dispatch()` exception path not tested

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:115–120`

**Issue:** The `catch (e: Exception)` block around the `dispatch()` call broadcasts "Internal error — turn aborted" and calls `demoteAfterTurn`. Nothing in the test suite exercises this path. Since `dispatch()` does unchecked casts (e.g., `action.target as MatrixObject.IcProgram`) that can throw `ClassCastException` at runtime, this branch is not merely theoretical.

**Recommendation:** Add a unit test that promotes a decker, sets a pending action, and sends an `ActionCommand` with an `actionIndex` that resolves to an `AvailableAction` whose `dispatch` branch will throw (e.g., by mocking/overriding a target). Assert the broadcast contains "Internal error" and the session receives a demotion.

---

### [MEDIUM] `broadcastWithRoles` send-failure resilience not tested

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107,123`

**Issue:** Both `broadcast()` and `broadcastWithRoles()` wrap each `session.send()` in `runCatching` to absorb per-session failures. There is no test that verifies a broken session does not prevent healthy sessions from receiving the message. `FakeWebSocketSession` has no mechanism to simulate a send failure (its `send()` always succeeds).

**Recommendation:** Extend `FakeWebSocketSession` with a `simulateSendFailure: Boolean` flag that makes `send()` throw an `IOException`. Add a test with two sessions (one broken, one healthy) and assert the healthy session still receives the broadcast.

---

### [MEDIUM] `ActionParams` fields never exercised through the WebSocket layer

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:49–56`

**Issue:** `ActionParams` has five fields (`newContent`, `inactivitySeconds`, `precision`, `hasValidPasscode`, `scannerDeviceRating`). Every action command sent in unit and integration tests uses `ActionCommand(actionIndex = N)` with no params. The `dispatchHostOperation` code that reads these fields (`cmd.params?.newContent`, `p?.precision`, etc.) is therefore never reached from any test.

**Recommendation:** The integration test is the natural place for this. At minimum, add a test that sends an `action` frame with a non-null `params` block (e.g., `{"type":"action","actionIndex":0,"params":{"inactivitySeconds":30}}`) and verify it deserialises correctly through the Ktor route.

---

### [MEDIUM] `deregister` on a session that was never registered is untested

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:61`

**Issue:** Calling `deregister()` on an unregistered session is a defensive no-op (the `remove` calls return false/null silently), but no test exercises this path. In the real server this can occur if an error causes `deregister` to be called before `register` completes.

**Recommendation:** Add a one-line test: `registry.deregister(FakeWebSocketSession())` with no prior `register` call, asserting it completes without throwing.

---

### [MEDIUM] `MatrixLocation.OnPLTG` missing from `DtoMappingTest`

**File:** `src/test/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt:23`

**Issue:** `Decker.toDto()` has a `when` expression over `MatrixLocation` variants. `DtoMappingTest` covers the null, `OnRTG`, `OnLTG`, and `OnHost` branches but not `OnPLTG`. The PLTG location is reachable in gameplay (`decker navigating to UCAS RTG` uses `jackInToLtg`, not a PLTG, but the PLTG path exists in the grid model).

**Recommendation:** Add a test analogous to the `OnLTG` case but using `MatrixLocation.OnPLTG(pltg)` and asserting the `location` string is `"PLTG: ${pltg.name}"` (or whatever the mapping produces).

---

### [MEDIUM] `AvailableAction.LogonToHost` and `LogonToPltg` missing from `DtoMappingTest`

**File:** `src/test/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt:151`

**Issue:** `DtoMappingTest` exercises `LogonToRtg`, `JackOut`, `GracefulLogoff`, and `Operation` DTO mapping. `LogonToHost` and `LogonToPltg` are absent. `LogonToLtg` appears only in the integration test's `availableActions` assertion, not in `DtoMappingTest` as a named case.

**Recommendation:** Add one test per missing `AvailableAction` subtype in `DtoMappingTest`, asserting `dto` is the correct subclass and that name fields round-trip correctly.

---

### [LOW] Raw `Thread` + time-bounded `join` can silently pass on slow CI

**File:** `src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:131,155,178`

**Issue:** Several tests start a background `Thread { wsController.action(...) }` and later call `thread.join(3000)` or `thread.join(6000)`. If the host is slow, `join` returns when the timeout expires regardless of whether the thread actually finished. The test body continues and passes without verifying the thread completed. On slow CI this can mask hangs or late broadcasts.

**Recommendation:** Assert `!thread.isAlive` after `join()`, or switch to structured concurrency: use `coroutineScope { launch { ... } }` inside `runBlocking` and `await` via a `CompletableDeferred`, eliminating the timing dependency entirely.

---

### [LOW] `promoteForTurn` true-path return value not directly asserted

**File:** `src/test/kotlin/com/shadowrun/matrix/server/SessionRegistryTest.kt` (absent)

**Issue:** `SessionRegistryTest` has a test for `promoteForTurn` returning `false` (non-existent decker). The `true` case (decker registered, `activeController` set) is exercised only indirectly through `WebSocketServerTest` which does not assert the return value.

**Recommendation:** Add a test: register, join, call `promoteForTurn`, and `assertTrue(result)`.

---

### [LOW] Integration test helper only supports single-client scenarios

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt:132–138`

**Issue:** `webSocketTest` opens a single WebSocket and passes the registry. Multi-client scenarios (an observer watching a decker's turn via the real HTTP stack, concurrent duplicate-name join races) require a second client but the helper doesn't support this, making such tests awkward to add.

**Recommendation:** Overload `webSocketTest` to accept a two-client lambda or provide a named helper that opens a second `client.webSocket` connection inside the same `testApplication` block.

---

### [INFO] `FakeWebSocketSession.incoming` is structurally write-locked

**File:** `src/test/kotlin/com/shadowrun/matrix/server/FakeWebSocketSession.kt:23`

**Issue:** `incoming` is declared as `val incoming: ReceiveChannel<Frame> = Channel()` with no exposed `SendChannel` counterpart. There is no way for a test to inject a frame as though it arrived from the network. This is not a bug today (unit tests call registry methods directly) but it means the frame-parsing layer in `MatrixServer.kt` can only be tested through the full Ktor stack, so any future unit-level test of that path would require a redesign of the fake.

**Recommendation:** Expose the send side: `private val _incoming = Channel<Frame>(Channel.UNLIMITED); override val incoming: ReceiveChannel<Frame> = _incoming` and add `suspend fun simulateIncoming(frame: Frame) = _incoming.send(frame)`. This mirrors the pattern already used for `_outgoing`.

---

### [INFO] `DeckerDisconnectedException` only reachable via `completeExceptionally`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/DeckerDisconnectedException.kt`

**Issue:** The class is tested indirectly through the "active controller disconnect mid-turn" test. The `catch (_: DeckerDisconnectedException)` branch in `WebSocketDeckerController` is covered. No direct instantiation/message test exists, but the class is trivial enough that this is acceptable documentation rather than a coverage gap.

**Recommendation:** No action required.

---

## No Issues Found In

- `SessionRegistry` core join/action/deregister error codes — all four error codes reachable from those methods (`NOT_YOUR_TURN`, `NO_ACTION_PENDING`, `ALREADY_REGISTERED`, `NAME_ALREADY_TAKEN`) have explicit unit tests.
- `broadcastWithRoles` role assignment for `OBSERVER` and `REGISTERED_DECKER` — both branches asserted by name in `SessionRegistryTest`.
- `broadcastWithRoles` role assignment for `ACTIVE_CONTROLLER` — asserted in `WebSocketServerTest` (`assertEquals("active_controller", stateObj["role"]...)`).
- Disconnect-while-turn-pending path — `deregister` completing the `CompletableDeferred` exceptionally and the forfeit broadcast are well covered by `active controller disconnect mid-turn broadcasts forfeit ResultMessage`.
- `deregister` non-controller with live `pendingAction` — correctly asserts no signal is sent.
- `DtoMappingTest` core coverage — `MatrixObject` subtypes (GridNode, LocalGrid, HostNode, HostSubsystem, IcProgram with/without guardedNode, File, Device) all mapped and asserted.
- `MatrixJson` config — `encodeDefaults = true` is effective; `ControlMessage.reconnect` defaults are serialised (exercised by existing ControlMessage sends).
- Integration test Ktor wiring — `matrixModule` function, routing, and `WebSockets` plugin install are verified end-to-end by `WebSocketServerIntegrationTest`.
