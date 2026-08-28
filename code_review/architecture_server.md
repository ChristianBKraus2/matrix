---
# Architecture Review — server

## Summary

The server package has a clear layering intent — a thin Ktor entry point, a session/connection registry, a controller that bridges the domain game loop to WebSocket clients, and a clean DTO sub-package — but the implementation has drifted significantly from that intent. The most serious problem is a layer inversion: the domain game engine calls `ActiveIcon.action()`, which is implemented inside the server layer, making the domain depend upward on transport concerns. Compounding this, `WebSocketDeckerController` has accumulated at least five distinct responsibilities (turn orchestration, action dispatch, domain → DTO result conversion, per-decker interrogation state, and direct WebSocket broadcasting), and `SessionRegistry` crosses its own boundary by serialising and sending DTO messages rather than delegating that to a transport gateway. The DTO layer itself is largely clean, but a flat catch-all `ActionParams` bag and a redundant `kind` discriminator field are minor structural debts.

## Findings

### [CRITICAL] Domain layer calls into the server layer via `ActiveIcon`

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:42

**Issue:** `WebSocketDeckerController` implements `ActiveIcon`, which is a domain interface invoked by the game engine. This means the domain/game layer has a compile-time dependency on the server layer at runtime: `game.ActiveIcon → server.WebSocketDeckerController`. The entire point of the interface is to invert this dependency, but the concrete implementation lives in the server package and imports Ktor, kotlinx.serialization, `SessionRegistry`, and `CompletableFuture`. Any test of game turn-order logic is now forced to instantiate or mock server infrastructure. The domain is polluted by I/O concerns at its core call site.

**Recommendation:** Move `WebSocketDeckerController` to a dedicated `adapter` (or `infra`) package that sits outside the domain. Alternatively, introduce a `PlayerInputPort` interface in the domain with only the game-relevant contract (`awaitAction(availableActions): AvailableAction`), and let `WebSocketDeckerController` implement that port. The transport wiring (Ktor, `SessionRegistry`) then stays entirely in the adapter layer and the domain remains free of it.

---

### [HIGH] `SessionRegistry.pendingAction` is a public mutable field owned by the wrong class

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:22

**Issue:** `pendingAction: CompletableFuture<ActionCommand>?` is `@Volatile` and `var` — it is created, assigned, and nulled out by `WebSocketDeckerController.action()` (lines 70–93 of the controller), while `SessionRegistry.receiveAction()` reads and completes it (line 117 of the registry). This is a bidirectional coupling: the controller drives the registry's internal state and the registry reads state the controller wrote. There is no encapsulation of the future's lifecycle; any code that holds a `SessionRegistry` reference can overwrite or race on this field. A disconnect during the window between future creation and assignment (`registry.pendingAction = future` on line 72 of the controller) is a silent data race.

**Recommendation:** Encapsulate the future entirely inside `SessionRegistry`. Add a method `SessionRegistry.awaitAction(): CompletableFuture<ActionCommand>` that creates, stores, and returns the future. The controller calls `awaitAction()` and never writes to `pendingAction` directly. The field should be `private`.

---

### [HIGH] `WebSocketDeckerController.action()` has at least five distinct responsibilities

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:49

**Issue:** The single `action()` method and the private helpers below it perform: (1) turn lifecycle management (promote/demote decker via registry), (2) state broadcast to all clients, (3) blocking wait for player input via a `CompletableFuture`, (4) action dispatch across ~22 `SystemOperation` cases including inline business rules (e.g., pinned-by-black-IC guard on JackOut, hardcoded `opponentSensor = 0`), and (5) result conversion from domain types to `DispatchResult` and then to `ResultMessage` DTOs. This is a textbook Single Responsibility violation. The class is also 312 lines long, which is a symptom.

**Recommendation:** Extract at minimum: (a) a `DeckerTurnOrchestrator` responsible for promote/await/demote/broadcast, and (b) an `ActionDispatcher` containing the `dispatch`/`dispatchHostOperation`/`dispatchGridOperation` logic. The result-converter extension functions (`toDispatch()`) belong in the DTO layer or alongside the dispatcher, not on the controller. The `DispatchResult` private data class (line 257) is a duplicate of the domain's `OperationResult` concept and should be eliminated in favour of it.

---

### [HIGH] `runBlocking` is used throughout a class called synchronously from the game engine

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53, 56, 73, 78, 85, 97, 109

**Issue:** `action()` is a normal (non-suspend) function called by the game engine. Inside it, `runBlocking { ... }` is used seven times to bridge into coroutine-based Ktor send operations. `runBlocking` pins the calling thread for the entire duration of every I/O operation and of the `future.get(timeout)` wait. If the game engine runs on a shared thread pool, this starves it of threads during every player turn. It also makes the timeout semantics fragile: the game thread is blocked unconditionally for up to `actionTimeoutSeconds` (default 120 s) waiting for a client response.

**Recommendation:** Make `action()` (or its replacement in the orchestrator) a `suspend` function, or decouple game advancement from the blocking wait entirely using a callback/event model. If `ActiveIcon` cannot be suspend, consider running the full turn on a dedicated coroutine scope and returning a `Deferred<ActionResult>` that the game engine awaits.

---

### [MEDIUM] `SessionRegistry` serialises and sends DTO messages — it is both a session store and a messaging gateway

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:26, 43-48, 69-72, 80-82, 102-104, 110-112

**Issue:** Every public method in `SessionRegistry` directly calls `MatrixJson.encodeToString(...)` and `session.send(Frame.Text(...))`. The registry therefore imports `ControlMessage`, `ErrorMessage`, `StateMessage`, `MatrixJson`, and Ktor's `Frame`. It knows about serialisation format, DTO types, and WebSocket transport in addition to tracking which sessions exist. If the message format changes, the registry changes. If the transport changes, the registry changes. If session lookup logic changes, the registry changes — three independent axes of change in one class.

**Recommendation:** Extract a `DeckerMessageGateway` (or `WebSocketGateway`) class that owns `session.send(...)` and all serialisation. `SessionRegistry` should hold only session/role state and expose pure data (e.g., `fun getSession(deckerName: String): DefaultWebSocketServerSession?`). Callers compose the two.

---

### [MEDIUM] `interrogationStates` (multi-turn locate state) lives on the controller, not in the domain

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47

**Issue:** `interrogationStates: MutableMap<SystemOperation, InterrogationState>` tracks accumulated successes across multiple game turns for Locate operations. This is game state, not transport state. If the decker disconnects and reconnects (a new `WebSocketDeckerController` is created), this accumulated state is silently discarded. The domain `Decker` object is the natural owner of per-decker game state.

**Recommendation:** Move `interrogationStates` into `Decker` (or a `DeckerSession` domain concept that wraps it). The controller then queries `decker.interrogationState(op)` rather than maintaining its own map.

---

### [MEDIUM] Silent `runCatching` in `MatrixServer.kt` discards all parse and dispatch errors

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29

**Issue:** Every incoming WebSocket frame is wrapped in `runCatching { ... }` with no `.onFailure` handler. A malformed JSON payload, an unknown `type` field, a deserialization exception, or any exception thrown by `registry.receiveJoin/receiveAction` is silently swallowed. The client receives no error response, there is no logging, and the server continues as if nothing happened. This makes debugging protocol errors invisible in production.

**Recommendation:** Add an `.onFailure` branch that logs the exception and, for client-induced failures (parse errors), sends an `ErrorMessage` back to the offending session. Infrastructure failures should be rethrown or at minimum logged at ERROR level.

---

### [LOW] `ActionParams` is an untyped flat bag with implicit per-operation semantics

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:31

**Issue:** `ActionParams` has five nullable fields (`newContent`, `inactivitySeconds`, `precision`, `hasValidPasscode`, `scannerDeviceRating`), each relevant only to specific operations. There is no type-level contract specifying which fields are required for which operation. The controller reads these with `?: 0` / `?: false` defaults and never validates that the required field is actually present, so a client omitting a required param silently runs the operation with a default value.

**Recommendation:** Replace `ActionParams` with a sealed class hierarchy (e.g., `ActionParams.EditFile(newContent: String)`, `ActionParams.NullOperation(inactivitySeconds: Int)`, etc.) or add explicit validation in the controller dispatch with an error response when required fields are absent.

---

### [LOW] `kind` field in sealed DTO classes duplicates the `@SerialName` discriminator

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:10, src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:12

**Issue:** Each sealed subclass declares `override val kind: String = "GridNode"` (etc.) alongside `@SerialName("GridNode")`. The `kind` field is sent in the JSON payload redundantly alongside the serialisation discriminator. If a subclass's `@SerialName` is ever changed but its `kind` default is not (or vice versa), the wire format becomes inconsistent. The abstract `kind` also appears to serve no runtime polymorphism role — it is never switched on in the server code.

**Recommendation:** Remove the `kind` abstract field and its overrides. If the frontend needs a discriminator string in the JSON, configure kotlinx.serialization's `@JsonClassDiscriminator` so the `type`/`kind` key is controlled in one place on the sealed class.

---

### [LOW] `DispatchResult` private data class duplicates the domain's `OperationResult` concept

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:257

**Issue:** `DispatchResult(decker, success, deckerSuccesses, hostSuccesses, details)` is a private data class that normalises all domain result types into a common structure. The domain already has `OperationResult` (with `outcome.deckerSuccesses`, `outcome.hostSuccesses`, and a success discriminant). This is a translation shim that exists only because the controller is doing too many jobs at once (see HIGH finding above).

**Recommendation:** This class should disappear naturally when action dispatch and result conversion are extracted to their own layer. The extracted layer can map directly from domain result types to `ResultMessage` DTOs without an intermediate private struct.

---

### [INFO] Manual JSON type-discrimination in the route handler could use kotlinx.serialization polymorphism

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31

**Issue:** Incoming messages are decoded by first parsing to `JsonElement`, extracting `type`, then performing a second `decodeFromString` for the concrete type. This manual two-pass approach is only needed because the incoming message types are not wired as a sealed class hierarchy. The `type` field with a hardcoded default on each DTO (e.g., `val type: String = "join"`) suggests this was considered but not completed.

**Recommendation:** Define a sealed `ClientMessage` hierarchy annotated with `@Serializable` and `@JsonClassDiscriminator("type")`, and decode incoming frames with a single `MatrixJson.decodeFromString<ClientMessage>(json)`. This eliminates the manual element extraction and consolidates the protocol contract in one place.

## Clean Areas

- `DeckerDisconnectedException` is minimal, well-named, and serves a single purpose.
- `DeckerStateDto` / `toDto()` mapping in `DeckerStateDto.kt` is straightforward, non-leaky, and easy to extend.
- `MatrixObjectDto` and `AvailableActionDto` sealed hierarchies correctly mirror the domain sealed class shapes; the `toDto(index)` extension functions are a clean mapping pattern with no logic leakage.
- `MatrixServer.kt` is thin: it wires Ktor, registers the static resource route, and delegates entirely to the registry. The entry point does not accumulate logic.
- `SessionRegistry.broadcastWithRoles()` correctly snapshots the session list under the lock before iterating, avoiding a ConcurrentModificationException during broadcast — a subtle correctness detail done right.
- `SessionRegistry.deregister()` correctly nulls `activeController` and completes the pending future exceptionally on disconnect — the unhappy path is handled.
---
