# Architecture Review — server

## Summary

The server layer has a solid foundation in its smaller components (TurnCoordinator, DTOs, MatrixServer), but two central classes — `SessionRegistry` and `WebSocketDeckerController` — carry significantly more responsibilities than their names imply. The most serious structural problem is that the game-domain interface `ActiveIcon` is implemented by a server-layer class that directly drives WebSocket I/O, serializes JSON, and manages connection lifecycle, creating an inverted dependency where the game engine unknowingly triggers transport operations. `SessionRegistry` compounds the issue by merging connection bookkeeping, identity management, reconnection-token logic, and protocol message serialization into one class. These two violations make the server layer difficult to test in isolation and would be the highest-leverage targets for refactoring.

## Findings

### [HIGH] Game-domain interface implemented by a server-layer class (layer inversion)

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:37`

**Issue:** `WebSocketDeckerController` implements `ActiveIcon`, a game-domain interface. When the game engine calls `ActiveIcon.action()`, it transparently triggers WebSocket I/O: JSON serialization, `registry.broadcast(...)`, `registry.promoteForTurn(...)`, `CompletableDeferred` coordination, and connection-lifecycle handling. The game layer is thus coupled to the transport layer through what appears to be a pure domain callback. Any test of the game engine that involves a `WebSocketDeckerController` must instantiate or mock the full server infrastructure.

**Recommendation:** Introduce a transport-agnostic `DeckerInputPort` (or similar) interface in the game layer that receives a pre-resolved `ActionCommand` value. `WebSocketDeckerController` handles all WebSocket concerns (timeout, deferred, broadcast) and then calls the port with the resolved command. The game engine depends only on the port, not on any server class. This cleanly separates the turn-input protocol from game execution.

---

### [HIGH] SessionRegistry conflates session store, identity management, and protocol messaging

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:81`

**Issue:** `SessionRegistry` holds three distinct responsibilities. (1) Raw connection tracking: `sessions`, `deckerSessions`, `sessionDecker`. (2) Decker identity and reconnection logic: name validation, UUID token generation, `disconnectedDeckerNames`, token verification in `receiveJoin`. (3) Protocol message serialization and send: every method calls `MatrixJson.encodeToString(ControlMessage(...))`, `ErrorMessage(...)`, or `StateMessage(...)` and pushes the result into the WebSocket frame. The class therefore knows about `SessionRole`, message shapes, and wire encoding in addition to its storage duties. A bug in reconnection logic, a protocol change, and a connection-limit change all touch the same file.

**Recommendation:** Extract a `DeckerIdentityService` (or `RegistrationService`) that encapsulates name validation, token issuance, and the `disconnectedDeckerNames` / `reconnectTokens` maps. Extract a `SessionMessenger` (or fold into the controller) that owns `broadcast`, `broadcastWithRoles`, and the individual `session.send(...)` calls. `SessionRegistry` then becomes a thin, thread-safe map of sessions to names, delegating to those two collaborators.

---

### [MEDIUM] WebSocketDeckerController is a dispatch god-object (SRP)

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:123`

**Issue:** Beyond the layer-inversion issue above, the class itself has at least five distinct responsibilities: (1) async turn coordination (deferred, timeout, promote/demote); (2) WebSocket I/O and serialization; (3) action dispatch via `dispatch` → `dispatchHostOperation` → `dispatchAnalyzeOp` / `dispatchLocateOp` / `dispatchDataOp` / `dispatchSlaveOp` / `dispatchCommsOp` / `dispatchMiscOp` — eight methods totalling ~200 lines; (4) domain-result-to-`DispatchResult` conversion (seven `toDispatch()` extension functions); (5) maintaining a mutable `decker` reference that gets reassigned after each action and re-read from the game context. The file is 374 lines and growing.

**Recommendation:** Extract a `DeckerActionDispatcher` class that receives a `Decker`, an `AvailableAction`, an `ActionCommand`, and a `DiceRoller` and returns a `DispatchResult`. The `toDispatch()` converters belong there as well. `WebSocketDeckerController` then reduces to turn orchestration only: acquire deferred, promote, await, hand off to dispatcher, broadcast result, demote.

---

### [MEDIUM] TurnCoordinator exposes string error keys across a layer boundary

**File:** `src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt:42`

**Issue:** `claimAction()` returns `Pair(null, "NOT_YOUR_TURN")` and `Pair(null, "NO_ACTION_PENDING")` as raw `String` error discriminators. The caller in `SessionRegistry.receiveAction()` maps these back to `ErrorCode` enum values using a `when` with a catch-all `else -> ErrorCode.BAD_REQUEST`. Any new error key added to `TurnCoordinator` that is not listed in the `when` will silently become `BAD_REQUEST`, making the error invisible.

**Recommendation:** Replace the string return with a sealed class or an enum defined alongside `TurnCoordinator` (e.g., `ClaimError.NotYourTurn`, `ClaimError.NoActionPending`). The `when` in `receiveAction` then becomes exhaustive and the compiler enforces coverage.

---

### [MEDIUM] `SessionRegistry.turns` is a public field, leaking internal collaboration

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:27`

**Issue:** `val turns = TurnCoordinator()` is `public`. This allows any caller to interact with `TurnCoordinator` directly, bypassing `SessionRegistry`'s mutex-protected state. Currently `WebSocketDeckerController` calls `registry.setPendingAction(deferred)` — a one-liner that just delegates to `turns` — making `SessionRegistry` an inconsistent gatekeeper: it guards some `turns` operations and exposes others.

**Recommendation:** Make `turns` private. The single delegation method `setPendingAction` can stay or be inlined. Callers should only ever see `SessionRegistry`'s interface; `TurnCoordinator` is an implementation detail.

---

### [LOW] `MatrixJson` serialization configuration defined in a DTO file

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:7`

**Issue:** `val MatrixJson = Json { encodeDefaults = true }` is a package-level global inside a DTO file. DTO files should be pure data shapes. The serialization configuration (`encodeDefaults = true`) is a cross-cutting concern used by every server message encode/decode, but its definition is buried in one arbitrarily chosen DTO file with no visibility into why those settings were chosen.

**Recommendation:** Move `MatrixJson` to a dedicated `MatrixSerialization.kt` file (or to the server bootstrap in `MatrixServer.kt`), with a comment explaining the `encodeDefaults = true` requirement (needed so fields with default values, e.g. `reconnect = false`, are included in control messages consumed by the frontend).

---

### [INFO] Manual enum-name contract between Kotlin DTOs and TypeScript frontend

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:6`

**Issue:** Five Kotlin enums (`AlertStatus`, `SecurityCode`, `TopologyType`, `SubsystemType`, `IcBehavior`) are serialized using raw `.name` strings (not `@SerialName`), and the comment explicitly warns that the matching TypeScript union types must be kept in sync by hand. There is no compile-time or test-time enforcement of this contract.

**Recommendation:** Add an integration or schema test that enumerates the Kotlin enum members for these five types and asserts they match the TypeScript union string literals (or a shared schema file). Alternatively, adopt a code-generation step (e.g., kotlin-js, ts-poet, or a JSON Schema round-trip) to make the contract machine-verified.

---

## No Issues Found In

- `src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt` — focused, mutex-guarded turn state with a clear, minimal API
- `src/main/kotlin/com/shadowrun/matrix/server/DeckerDisconnectedException.kt` — single-purpose exception, no responsibilities beyond its name
- `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt` — tight Ktor wiring; routing, frame dispatch, and error handling are all proportionate for an entry-point module
- `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt` — clean mapping DTO; the `toDto()` extension on `Decker` is appropriately co-located with the DTO it produces
- `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt` — clean sealed DTO hierarchy with a well-scoped mapper; `targetName()` correctly kept private to the file
