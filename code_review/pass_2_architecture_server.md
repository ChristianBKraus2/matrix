# Architecture Review — server

## Summary

The server package has a clean outer shell (`MatrixServer.kt` is admirably thin) but concentrates too much responsibility in the two classes behind it. `WebSocketDeckerController` is a God class: it implements the game engine's `ActiveIcon` interface, owns the entire operation dispatch table, translates every domain result to a DTO, manages async WebSocket turn flow with `runBlocking`, and holds mutable decker state that it must bidirectionally synchronise with `GameContext`. `SessionRegistry` suffers a parallel SRP violation by mixing low-level connection bookkeeping with game-turn state (`activeController`, `pendingAction`, `disconnectedDeckerNames`). The DTO layer is mostly solid but has two structural weaknesses: `ActionParams` is an untyped flat bag, and `AvailableActionDto.Operation` reduces a rich target type to two nullable strings. These issues compound each other — because turn state is spread across three objects, any change to turn flow requires touching `WebSocketDeckerController`, `SessionRegistry`, and their shared lock discipline simultaneously.

---

## Findings

### [HIGH] WebSocketDeckerController is a God class

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:36`

**Issue:** The class has five distinct responsibilities rolled into one:
1. Async WebSocket turn orchestration (`action()`, `runBlocking`, timeout, disconnect handling).
2. Routing every `AvailableAction` variant to the matching `Decker` call (`dispatch`, `dispatchGridOperation`, `dispatchHostOperation` — roughly 120 lines of `when`).
3. Domain-result-to-DTO translation (seven private `toDispatch()` extension functions).
4. Mutable decker ownership (`var decker`) and bidirectional sync with `GameContext` (lines 100–107).
5. Broadcast coordination (four separate `registry.broadcast(...)` call sites scattered through `action()`).

A single change — say, adding a new operation — requires touching the dispatch table, adding a converter, and potentially adjusting the broadcast flow, all in the same file.

**Recommendation:** Split along the seams that already exist:
- Extract a `DeckerActionDispatcher` (pure function or stateless object) that maps `(AvailableAction, ActionCommand, Decker, Host?, DiceRoller) -> DispatchResult`. This is already almost isolated in `dispatch`/`dispatchHostOperation`.
- Extract result mappers (`toDispatch` overloads) into a `DispatchResultMapper` or keep them with the dispatcher.
- Let `WebSocketDeckerController` focus solely on the async turn protocol: wait for command, validate, call dispatcher, broadcast outcome. It should not know the names of individual operations.

---

### [HIGH] SessionRegistry mixes connection state with game-turn state

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:16`

**Issue:** The class serves as a connection pool (`sessions`, `deckerSessions`, `sessionDecker`) but also owns three game-turn concepts: `activeController` (which session currently controls the game), `pendingAction` (the deferred waiting for a player command), and `disconnectedDeckerNames` (reconnection eligibility, a game-session concern). The `promoteForTurn`, `demoteAfterTurn`, `receiveAction`, and `setPendingAction` methods are turn-lifecycle operations, not connection-registry operations. Because `pendingAction` is stored here, `WebSocketDeckerController` must call back into the registry to set and clear it — a coupling that requires careful lock discipline and comment-documented ordering (see the "TOCTOU" comment in `WebSocketDeckerController.kt:51`).

**Recommendation:** Introduce a `TurnGate` or `TurnCoordinator` that owns `activeController`, `pendingAction`, and `disconnectedDeckerNames`. `SessionRegistry` retains only `register`/`deregister`/`broadcast`/`broadcastWithRoles` and a lookup of session → deckerName. `WebSocketDeckerController` and `TurnCoordinator` interact directly. The lock discipline becomes local to each class rather than spanning two.

---

### [HIGH] `runBlocking` in `action()` starves the game-loop thread

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45`

**Issue:** `action()` is called synchronously by the game engine (the `ActiveIcon` interface contract). To await the player's WebSocket response, the implementation wraps the entire coroutine block in `runBlocking`, which blocks the calling thread for up to `actionTimeoutSeconds` (default 120 s) per turn. With multiple deckers this chains into multi-minute thread blockages. Because Ktor's incoming-frame loop runs on a coroutine dispatcher and the game loop runs on this blocked thread, the threading model is: game thread blocked → coroutine resumes on IO dispatcher → game thread unblocks. The current design happens to work with one game and one decker at a time but will not scale and makes the threading contract implicit.

**Recommendation:** Change `ActiveIcon.action()` to a `suspend fun`. The game loop should itself be a coroutine (launched from a `CoroutineScope`). `WebSocketDeckerController.action()` can then be a straightforward suspend function with `withTimeoutOrNull` — no `runBlocking` needed. This is the bigger structural change but aligns with the coroutine-first Ktor model already used everywhere else.

---

### [MEDIUM] Entire operation dispatch table in one 80-line `when` expression

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:158`

**Issue:** `dispatchHostOperation` contains a `when` branch for every `SystemOperation` variant (~20 cases, ~80 lines). Every time an operation is added or changed, this method grows. There is no polymorphism; all parameter extraction, guard checks (e.g., file content size check on line 201), and `Decker` call selection live side by side in the same `when`. It also contains silent fallback logic (`else -> DispatchResult(..., "Unsupported: ${action.operation}")`) that can mask missing cases at compile time.

**Recommendation:** If the dispatcher is extracted as recommended above, consider a `Map<SystemOperation, OperationHandler>` or sealed-class–based command objects, each encapsulating its own parameter extraction and `Decker` invocation. At minimum, remove the `else` branch so the compiler enforces exhaustiveness on `SystemOperation`.

---

### [MEDIUM] `ActionParams` is an untyped flat bag with no per-operation validation

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:50`

**Issue:** `ActionParams` folds five unrelated fields into one nullable container. The client has no schema guidance about which fields are required for which operation. The server silently substitutes defaults when fields are absent (`p?.precision ?: QueryPrecision.NORMAL`, `p?.hasValidPasscode ?: false`, `p?.scannerDeviceRating ?: 0`). A client supplying the wrong combination gets no validation error, just a silently wrong result. The `newContent` length check (line 201 in the controller) is the only guard, and it lives in the dispatch body rather than at the DTO boundary.

**Recommendation:** Either (a) make `ActionCommand.params` a sealed class mirroring the operation variants (strongly typed, one subtype per param-carrying operation), or (b) keep the flat bag but validate required fields eagerly in the `dispatch` entry point and return a validation error before reaching the operation. Option (a) is preferable because it makes the wire contract explicit.

---

### [MEDIUM] Operation capability filtering is done in the wrong layer

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:48`

**Issue:** `SWAP_MEMORY` and `LOCATE_DECKER` are filtered out of available actions inside `WebSocketDeckerController.action()` before sending state to the client. This means the set of "WebSocket-supported operations" is a server-layer concern encoded inline alongside the game logic. When `dispatchHostOperation` encounters them anyway it returns stub error strings (lines 222, 232). The duplication means the suppression and the stub must be kept in sync manually.

**Recommendation:** Model WebSocket capability explicitly: either as a set constant (`UNSUPPORTED_VIA_WEBSOCKET`) checked in one place, or by routing the unsupported operations through a proper `UnsupportedOperationError` that the controller surfaces uniformly. Ideally the filtering would happen at a higher layer (the operation registry or `Decker.availableActions()` with context) rather than in a presentation-layer controller.

---

### [MEDIUM] Mutable decker with bidirectional GameContext sync is fragile

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:100`

**Issue:** `var decker` is overwritten three times in `action()`: once from `dispatch` result (line 103), once from `context.applyDeckerOperationResult` (line 104), and once re-read from `context.deckers` (line 107). The comment explains the last re-read is needed because `applyDeckerOperationResult` may replace the reference on alert transitions. This means the controller holds a decker reference that can silently become stale, and the correction depends on `context.deckers` containing the right instance. The pattern is fragile: adding another state-modifying call between lines 103 and 107 would silently use the wrong decker reference.

**Recommendation:** `action()` should not own the canonical decker reference. The decker should be looked up from `GameContext` by name at the start of each turn and not cached. Alternatively, `applyDeckerOperationResult` should return the updated decker so the controller can assign it in one place without a second context lookup.

---

### [MEDIUM] `setPendingAction` is public, exposing turn internals

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:26`

**Issue:** `setPendingAction` is `public` because `WebSocketDeckerController` must call it. This is an implementation detail of the turn flow leaking into the `SessionRegistry` API. Any code with a `SessionRegistry` reference can null out the pending action, causing a `receiveAction` call to return `NO_ACTION_PENDING` mid-turn.

**Recommendation:** If `SessionRegistry` retains turn state (see the SRP finding above), `setPendingAction` should be `internal` at minimum. If turn state is moved to a `TurnCoordinator`, this method disappears from `SessionRegistry` entirely.

---

### [LOW] `DeckerStateDto.location` loses type fidelity

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:11`

**Issue:** The decker's current network position is serialised as a plain string label (`"RTG: SAN Francisco"`, `"Host: Aztechnology HR"`). The frontend cannot distinguish between location types without parsing the prefix string, making location-dependent UI logic brittle.

**Recommendation:** Serialise location as a structured DTO (a sealed class or a `{ type, name }` object) matching the same `@JsonClassDiscriminator` pattern used in `MatrixObjectDto`.

---

### [LOW] Redundant type discriminators across the DTO layer

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt` and `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt`

**Issue:** Two different discriminator strategies coexist. Flat messages (`JoinMessage`, `ActionCommand`, `StateMessage`, etc.) carry a manual `val type: String = "..."` field that drives the dispatch `when` in `MatrixServer.kt`. Sealed-class DTOs (`AvailableActionDto`, `MatrixObjectDto`) use `@JsonClassDiscriminator("kind")`. Additionally, every `AvailableActionDto` subclass carries `val actionType: String` alongside the `kind` discriminator — these fields carry the same semantic information (both resolve to something like `"LogonToRtg"`), creating a third redundant channel.

**Recommendation:** Standardise on one strategy. `@JsonClassDiscriminator` with a sealed hierarchy works cleanly for all outbound messages. For inbound messages where the type field must drive a `when` dispatch, keep the manual field but at least make the dispatch in `MatrixServer.kt` use `@Serializable` with a sealed `ClientMessage` hierarchy and `Json.decodeFromString<ClientMessage>(json)`, eliminating the two-step "peek type then decode" pattern. Remove the redundant `actionType` field from `AvailableActionDto` subclasses or rename it to avoid confusion with the `kind` discriminator.

---

### [LOW] All exceptions caught as `BAD_REQUEST` in the frame handler

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:43`

**Issue:** The inner `catch (e: Exception)` in the WebSocket frame loop sends `ErrorCode.BAD_REQUEST` for any exception, including internal server errors (null dereferences, serialisation bugs). A client receiving `bad_request` will assume it sent malformed data when the fault may be server-side.

**Recommendation:** Distinguish `SerializationException` / `IllegalArgumentException` (genuine bad input → `BAD_REQUEST`) from unexpected `Exception` subtypes (internal error → a separate `INTERNAL_ERROR` error code or at least a different `details` message).

---

### [LOW] Stringly-typed target in `AvailableActionDto.Operation`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:50`

**Issue:** `Operation` DTO carries `targetKind: String?` and `targetName: String?` derived from `it::class.simpleName` and a `targetName()` helper. This compresses a typed `MatrixObject` variant down to two nullable strings. The client has to interpret `targetKind` as a discriminator and `targetName` as an opaque label with no further structure.

**Recommendation:** Embed the target as a `MatrixObjectDto` reference (or a lightweight `{ kind, name }` struct) using the same sealed-class serialisation already in place for `MatrixObjectDto`. This gives the client the same structural information it gets for visible objects.

---

## No Issues Found In

- **`DeckerDisconnectedException.kt`** — Minimal, correctly typed exception; nothing to improve.
- **`MatrixServer.kt` routing shape** — The outer WebSocket handler is admirably thin; it registers, loops on frames, and deregisters in `finally`. Frame-level concerns (parse type, decode, delegate) are properly kept out of the business layer.
- **`MatrixObjectDto.kt`** — Sealed-class hierarchy with `@JsonClassDiscriminator` is used correctly. The comment warning about enum serialisation and the TypeScript sync requirement is a useful maintenance note.
- **`DeckerStateDto.kt`** — Clean field selection and mapping; `toDto()` extension placement next to the DTO type is idiomatic.
- **`SessionRegistry` concurrency model** — The `synchronized(lock)` blocks are applied consistently, compound operations are kept atomic (e.g., the `futureToCancel` extraction in `deregister`), and the TOCTOU comment in the controller documents the ordering contract clearly. The approach is correct even if the scope of responsibility is too broad.
- **`broadcastWithRoles` snapshot pattern** — Taking a `toList()` snapshot under the lock and then iterating outside it is correct; it avoids holding the lock across suspend calls.
