---
# Testing Review — Complete System (Cross-Cutting)

## Summary

The Kotlin game-logic and server layers have solid unit and integration test coverage, including a real Ktor `testApplication` WebSocket integration test. However, the React/TypeScript frontend has zero automated tests, leaving the client-side half of every WebSocket contract completely unverified. Several cross-cutting contract mismatches exist between the server DTOs and the TypeScript type definitions — notably the optionality of `ResultMessage` success fields and unvalidated free-text enum strings — that no current test exercises. The reconnect-and-rejoin flow, multi-client state fan-out, multi-turn interrogation state persistence through the WebSocket layer, and the silent swallowing of server-side dispatch errors are all cross-cutting seams that have no test coverage. Several tests that do exist use bare `Thread.join(N)` with hardcoded timeouts, creating a latency-sensitive flakiness risk in CI.

## Findings

### CRITICAL No frontend tests at any layer
**Parts Affected:** ui
**File(s):** `frontend/src/hooks/useWebSocket.ts`, `frontend/src/App.tsx`, `frontend/src/types/messages.ts`
**Issue:** There are no test files anywhere under `frontend/` (glob for `*.test.ts*` returns nothing). The WebSocket hook (`useWebSocket`) implements non-trivial state logic — a reducer, auto-join on reconnect, exponential backoff, role-gated action dispatch — and the `App` component has branching logic over `role`, `connected`, and `gameState`. None of it is exercised by any test. Contract assumptions about `ServerMessage` shape, error code handling (`ERROR_LABELS` in `App.tsx`), and action submission gating are entirely unverified at the UI layer.
**Recommendation:** Add Vitest + React Testing Library. At minimum: unit-test the `reducer` function with every `WsAction` variant; write hook tests using a mock WebSocket (e.g., `jest-websocket-mock` or a hand-rolled fake) covering initial connect, join flow, state update, reconnect with pending name, and error display.

---

### HIGH `runCatching` in message dispatch silently eats all server-side errors
**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29-36`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245`
**Issue:** The entire inbound message dispatch in `matrixModule` is wrapped in a bare `runCatching { }` with no `onFailure` handler. Any exception thrown during parsing or dispatch — including `IllegalArgumentException` from `QueryPrecision.valueOf(it)` when the client sends a precision string that does not exactly match an enum member — is silently discarded. The client receives no error response and has no way to know the action was dropped. The TypeScript type for `precision` (`'NORMAL' | 'HIGH'`) is correct today, but the server never validates this boundary and has no test that sends an invalid precision string and asserts an error response.
**Recommendation:** Replace the bare `runCatching` with one that sends an `ErrorMessage` to the session on failure. Add a test in `WebSocketServerIntegrationTest` that sends `{"type":"action","actionIndex":0,"params":{"precision":"BOGUS"}}` and asserts an error frame is received rather than silence.

---

### HIGH Reconnect + auto-rejoin flow has no end-to-end test
**Parts Affected:** server / ui
**File(s):** `frontend/src/hooks/useWebSocket.ts:110-118` (reconnect), `frontend/src/hooks/useWebSocket.ts:90-93` (auto-join on observer), `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:51-63` (deregister)
**Issue:** On disconnect, `useWebSocket` schedules a reconnect via `setTimeout`, preserves `pendingNameRef`, and on the next `observer` control message automatically re-sends a `join`. The server's `deregister` removes the decker name when the session closes. This sequence forms a multi-step cross-cutting flow — WebSocket close event, exponential backoff timer, new connect, observer message, auto-join — but it has no test anywhere. The key risk is a race: if reconnect completes while the server's `finally { registry.deregister(this) }` block in `matrixModule` has not yet executed for the old session, the auto-join returns `name_already_taken`. The UI's `ERROR_LABELS` map in `App.tsx:13` handles this string but the `JoinScreen` only shows it after the error is in `events`, which requires the user to be in the join screen, and a reconnecting registered decker is not in the join screen.
**Recommendation:** Add an integration test using `testApplication` with two sequential WebSocket connections that share the same decker name, verifying the second connect receives `registered_decker` (not an error) after the first session's `deregister` has completed. Add a frontend hook test simulating disconnect → reconnect → observer → auto-join → registered_decker.

---

### HIGH `ResultMessage` field optionality contract mismatch
**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:41-46`, `frontend/src/types/messages.ts:84-90`
**Issue:** The server declares `deckerSuccesses: Int` and `hostSuccesses: Int` as non-nullable required fields on `ResultMessage`. The TypeScript definition marks them `deckerSuccesses?: number` and `hostSuccesses?: number` (optional). The UI therefore treats them as possibly absent in any code that reads them. Today the server always sends `0` for error scenarios (timeout, disconnect, invalid index), so the values are never actually absent — but the TypeScript type creates a contract that is looser than what the server guarantees, and no test exercises the missing-field path. If the server ever omits these fields in a future code path, the mismatch becomes a runtime divergence that tests would not catch.
**Recommendation:** Either tighten the TypeScript type to `deckerSuccesses: number` (matching the server guarantee), or add a server code path that intentionally omits them and update both sides consistently. Add a serialization round-trip test that deserializes a `ResultMessage` JSON on the TypeScript side and asserts both fields are always present numbers.

---

### MEDIUM Multi-client state fan-out never tested end-to-end
**Parts Affected:** server / ui
**File(s):** `src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt`, `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:92-105`
**Issue:** Every test in `WebSocketServerIntegrationTest` opens a single WebSocket client. The `broadcastWithRoles` path — which is the primary state delivery mechanism — assigns different roles to different sessions in one atomic pass, but this is only tested with a single session in `SessionRegistryTest` (unit level). There is no integration test with two simultaneous Ktor test clients (one decker, one observer) verifying that the decker receives `role=active_controller` and the observer receives `role=observer` in the same broadcast round, or that the `WebSocketDeckerController.action` flow delivers the correct state to both.
**Recommendation:** Add a test to `WebSocketServerIntegrationTest` that opens two client connections concurrently (`createClient` twice, run both in coroutines), has one join as decker, drives a full turn, and asserts that both clients receive a `StateMessage` with the correct respective roles.

---

### MEDIUM Multi-turn interrogation state not tested through WebSocket layer
**Parts Affected:** game_logic / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:237-253`
**Issue:** `WebSocketDeckerController` holds a `interrogationStates` map that accumulates partial successes across turns for `LOCATE_FILE`, `LOCATE_SLAVE`, and `LOCATE_ACCESS_NODE`. This is a stateful, multi-turn server-side concern. The game-logic layer has unit tests for interrogation, but there is no test that drives two consecutive `controller.action(...)` calls via the WebSocket layer and verifies that the accumulated success count from turn 1 is visible in the `details` string of turn 2's result, and that the state is removed from the map on completion. A bug that resets or double-counts `interrogationStates` across turns would not be caught.
**Recommendation:** Add a `WebSocketServerTest` (or integration test) that runs two consecutive turns of `LOCATE_FILE` with a controlled dice roller, checks that the `details` in the second turn's `ResultMessage` reflects accumulated successes, and verifies the map is cleared after `LocateResult.Located`.

---

### MEDIUM Unsupported operations (`SWAP_MEMORY`, `LOCATE_DECKER`) silently return failure with no contract test
**Parts Affected:** game_logic / server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:220`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:230`
**Issue:** `SWAP_MEMORY` and `LOCATE_DECKER` are valid entries in the `SystemOperation` enum and can be listed as `availableActions` on the server. The dispatch function returns `success=false` with a stub message for both. There is no test verifying this behavior, and no test verifying that the frontend's `ActionsPanel` can correctly render an `Operation` DTO with `operation="SWAP_MEMORY"` without crashing. If a host configuration exposes these operations, the player sees a button that silently fails with no actionable feedback.
**Recommendation:** Add a `WebSocketServerTest` that drives a turn where `availableActions` contains a `SWAP_MEMORY` operation, the client submits that action index, and the result message is asserted to have `success=false` with the stub reason. Consider filtering these operations out of `availableActions` before broadcasting rather than failing silently at dispatch.

---

### MEDIUM No JSON round-trip serialization tests spanning server → wire → client types
**Parts Affected:** server / ui
**File(s):** `src/test/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt`, `frontend/src/types/messages.ts`
**Issue:** `DtoMappingTest` tests domain-to-DTO object mapping in Kotlin, but never serializes DTOs to JSON and checks the resulting string. The TypeScript types in `messages.ts` are manually maintained and have no automated verification against the actual JSON the server emits. Fields like `alertStatus` (serialized as the enum name string) and `securityCode` (also a string) are typed as specific union literals on the TypeScript side (`'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT'`, `'BLUE' | 'GREEN' | 'ORANGE' | 'RED'`), but no test serializes a `MatrixObjectDto.HostNode` and asserts the JSON contains `"alertStatus":"NO_ALERT"` rather than some other representation.
**Recommendation:** Add snapshot or assertion tests in `DtoMappingTest` that call `MatrixJson.encodeToString(dto)` and assert specific JSON field values. On the frontend, add Zod schemas or equivalent runtime validators matched to the TypeScript types, and test them against the JSON strings produced by the Kotlin serializer.

---

### LOW Test thread join timeouts are hardcoded and CI-fragile
**Parts Affected:** server
**File(s):** `src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:136`, `src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:172`, `src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:203`
**Issue:** Tests that exercise the `WebSocketDeckerController.action` blocking loop use raw `Thread { ... }` with `thread.join(3000)` or `thread.join(6000)` timeouts. If the CI environment is slow, these tests will either produce false passes (the thread has not actually finished cleanly) or false failures. The `actionTimeoutSeconds = 5` on the controller itself means the disconnect-forfeit test has a thread that could run up to 5 seconds, but `thread.join(6000)` gives only 1 second of margin.
**Recommendation:** Replace raw `Thread` with coroutines (`launch` + `job.join()` with a coroutine timeout), or at minimum use a `CountDownLatch` / `CompletableFuture` to signal completion rather than sleeping. Increase the `join` timeout to at least `actionTimeoutSeconds * 1000 + 3000` ms.

---

### LOW Observer client never tested for graceful handling of unknown `ServerMessage` type
**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:32`, `frontend/src/hooks/useWebSocket.ts:105-107`
**Issue:** Both sides silently drop unrecognized message types (server: no `else` in the `when`, client: no `else` in the `switch`, with a `catch` block that ignores parse errors). While this is defensive, there is no test that sends an unrecognized `type` from the client and verifies the session remains alive and functional afterward, nor a test that sends a malformed JSON frame and verifies the connection is not dropped.
**Recommendation:** Add a test that sends `{"type":"unknown_future_type"}` from a client, then sends a valid `join` message, and asserts that `join` is processed correctly — confirming the session was not poisoned by the ignored frame.

---

## Clean Seams

- **DTO field naming is consistent.** Every camelCase field in the Kotlin `@Serializable` DTOs has a direct TypeScript counterpart with the same name. kotlinx.serialization's default camelCase output matches TypeScript conventions without any renaming layer.
- **`kind` discriminator is explicit.** Both `AvailableActionDto` and `MatrixObjectDto` carry a `kind` field as a default value on each subclass, and the TypeScript union type discriminates on the same field. The sealed-class `@SerialName` and the `kind` default values are in sync.
- **`DtoMappingTest` is thorough for domain→DTO mapping.** All eight `MatrixObject` variants and all six `AvailableAction` variants are covered, including edge cases like null `guardedNode` and null `target`.
- **`WebSocketServerIntegrationTest` exercises the full Ktor stack.** Using `testApplication` rather than mocking Ktor means the routing, WebSocket upgrade, and frame parsing are all real. The navigation-to-RTG test is especially valuable as a multi-turn end-to-end flow.
- **Session cleanup on disconnect is well-tested.** The deregister scenarios (non-controller, controller with null future, controller with completed future) are all covered in `SessionRegistryTest`, closing the most dangerous race conditions.
- **Exponential reconnect backoff is implemented correctly in the hook.** The `reconnectDelay` doubles on each `onclose` up to a 30-second cap and resets on successful open — a correct pattern that mirrors what server-side session cleanup provides.
---
