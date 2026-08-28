---
# Architecture Review — Complete System (Cross-Cutting)

## Summary

The most serious cross-cutting problem is that `WebSocketDeckerController` implements the game domain interface `ActiveIcon` and therefore sits inside the game loop as a first-class game participant. As a consequence, the server layer owns substantial game logic: it dispatches every `SystemOperation`, enforces game rules (e.g. "can't jack out while pinned"), manages multi-turn interrogation state, and directly mutates `GameContext`. The DTO boundary does exist and the mapping code is properly separated into `server/dto/`, but the seam collapses completely inside `WebSocketDeckerController`, which is simultaneously a WebSocket session manager, a turn scheduler, a game-rule enforcer, and a domain-model mutator. The UI contract is well-typed in TypeScript but contains several silent mismatches with the server DTOs (optional vs. required fields, opaque location strings, stringly-typed error codes) that will surface only at runtime.

---

## Findings

### [CRITICAL] Server layer implements a domain interface and acts as a game participant

**Parts Affected:** game_logic / server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:42` (`class WebSocketDeckerController … : ActiveIcon`)
- `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:15` (`decker.action(context, diceRoller)`)

**Issue:** `WebSocketDeckerController` implements `ActiveIcon`, which is the interface the game loop calls via `Game.runCombatTurn()` / `runOutOfCombatTurn()`. This forces the server transport object directly into the domain model's actor list. The `action()` method that satisfies the interface is 70 lines of turn orchestration: it promotes/demotes the controller session, serialises state, blocks on a `CompletableFuture`, dispatches the chosen operation, and updates `GameContext`. The game does not know it is talking to a WebSocket; the server does not know it is acting as a game combatant — the reality is they are both true simultaneously, and this dual identity is the root cause of most other findings below.

**Recommendation:** Introduce a pure-domain `DeckerController` interface that the game loop calls (`fun chooseTurn(context: GameContext, availableActions: List<AvailableAction>): AvailableAction`). `WebSocketDeckerController` implements this thin interface and is injected at startup, but it is never put directly into `context.deckers`. The game loop calls the interface; the server layer does transport only. All game-state mutations stay in the domain.

---

### [HIGH] Game-rule enforcement embedded in the server dispatch layer

**Parts Affected:** game_logic / server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:131-135` (JackOut pin check)
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:154-235` (`dispatchHostOperation`)

**Issue:** Two separate problems share the same root:

1. The `JackOut` branch in `dispatch()` checks `decker.isPinnedByBlackIc` and returns an error rather than delegating to the domain. The rule "a decker pinned by Black IC cannot jack out" is a Shadowrun rulebook constraint; it belongs in `Decker.availableActions()` (don't offer JackOut) or `Decker.jackOut()` (return a failure result). Encoding it in the server means it can be bypassed by any non-WebSocket controller.

2. `dispatchHostOperation()` is a 70-line switch that selects and calls the correct `Decker` method for every `SystemOperation`. This is dispatch logic — the question "given this SystemOperation and these params, which Decker method do I call?" is a game-rule question, not a transport question. The server layer now has to know about every operation the game supports.

**Recommendation:** Move the Black IC pin check into `Decker.availableActions()` so JackOut is never offered when pinned, or into `Decker.jackOut()` as a `JackOutResult.BlockedByBlackIc` variant. Replace the server-side dispatch switch with a domain method: `Decker.execute(action: AvailableAction, params: OperationParams, diceRoller: DiceRoller): OperationOutcome`. The server translates `ActionCommand` → `OperationParams` (pure data conversion), then calls that method.

---

### [HIGH] Multi-turn interrogation state lives in the server layer

**Parts Affected:** game_logic / server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47` (`interrogationStates: mutableMapOf<SystemOperation, InterrogationState>`)
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:237-253` (`locateWithState`)

**Issue:** `InterrogationState` tracks accumulated successes for multi-turn LOCATE_FILE / LOCATE_SLAVE / LOCATE_ACCESS_NODE operations. This is game state — it represents in-progress work that spans multiple combat turns and belongs to the decker's persona in the domain model. By storing it in `WebSocketDeckerController`, the state is lost if the WebSocket reconnects between turns (the session object is replaced), and it is invisible to any other game-logic component that might need to inspect or reset it (e.g., if the decker gets dumped out of the host, the interrogation should be cancelled — but `GameContext` has no way to do that).

**Recommendation:** Move `InterrogationState` into `Decker` (or a `Persona` sub-object) as domain state. The domain's `locateFile(…)` etc. methods should read and update this state. The server simply calls the method and converts the result to a DTO.

---

### [HIGH] `SessionRegistry.pendingAction` is a public mutable field used as a cross-object synchronisation primitive

**Parts Affected:** server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:22` (`@Volatile var pendingAction: CompletableFuture<ActionCommand>? = null`)
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:70` (sets `registry.pendingAction = future`)
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:92` (clears `registry.pendingAction = null`)
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107-117` (reads it in `receiveAction`)

**Issue:** The `CompletableFuture` that synchronises the game loop thread with the incoming WebSocket message is written to the registry's public field by `WebSocketDeckerController` and read by `SessionRegistry.receiveAction`. This is a shared-mutable-state design that is not covered by the `lock` object; the `@Volatile` annotation prevents cache-visibility issues but not ABA or lost-update races if two turns somehow overlap. More structurally, the registry now has two responsibilities: session bookkeeping and turn synchronisation. Any future game mode that runs multiple concurrent turns would require a complete redesign of this field.

**Recommendation:** Encapsulate the `CompletableFuture` inside a `TurnSlot` object that the registry creates when `promoteForTurn` is called and destroys when `demoteAfterTurn` is called. Expose it only through `awaitAction(): ActionCommand` and `cancelAction(reason: String)` methods on the registry, removing the public field entirely.

---

### [HIGH] Server controller directly mutates `GameContext`

**Parts Affected:** game_logic / server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:107` (`context.applyDeckerOperationResult(oldDecker, decker)`)

**Issue:** `WebSocketDeckerController.action()` calls `context.applyDeckerOperationResult()` directly after every operation. This means the server object drives the game state transition: it decides when to call `updateDecker`, `updateHost`, and `checkTriggers`. If a non-WebSocket controller (e.g. an AI or a scripted test controller) were used, it would need identical boilerplate. The game loop in `Game.kt` does not do this — it calls `icon.action(context, diceRoller)` and trusts the icon to handle everything, but the contract is that `ActiveIcon.action()` is supposed to be a self-contained turn, not a partial operation that requires the caller to also drive context updates.

**Recommendation:** The domain `Decker.execute(…)` method (from the CRITICAL finding above) should return an `OperationOutcome` that the game loop applies via `context.apply(outcome)`. The server layer calls `execute(…)`, gets back a pure value, and does not touch `GameContext` at all.

---

### [MEDIUM] `ActionParams` is a flat stringly-typed bag that conflates parameters for unrelated operations

**Parts Affected:** server / ui
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:31-37` (`ActionParams`)
- `frontend/src/types/messages.ts:8-14` (`ActionParams`)

**Issue:** `ActionParams` has five optional fields covering at least six different operations: `newContent` (EDIT_FILE), `inactivitySeconds` (NULL_OPERATION), `precision` (LOCATE_*), `hasValidPasscode` (MAKE_COMCALL), `scannerDeviceRating` (TAP_COMCALL). There is no type-level guarantee that the correct field is populated for a given operation. The server silently uses defaults (`?: 0`, `?: false`) when a field is absent, so a UI bug that sends the wrong field (or omits the right one) produces a silent wrong result, not a validation error. The TypeScript type is identical to the Kotlin DTO so the mismatch cannot be caught at compile time either.

**Recommendation:** Model params as a discriminated union on both sides, keyed by operation. In Kotlin, use a sealed class `OperationParams`; in TypeScript, use a tagged union. Alternatively, use a server-side validator that checks the required field is present for each `SystemOperation` and returns an `ErrorMessage` if it is not.

---

### [MEDIUM] `DeckerStateDto.location` is an opaque human-readable string

**Parts Affected:** server / ui
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:9` (`val location: String`)
- `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:37-42` (formats location as `"RTG: foo"`, `"Host: foo"` etc.)
- `frontend/src/types/messages.ts:39` (`location: string`)

**Issue:** The location is serialised to a display string in the mapping layer. The UI receives `"Host: CorpSec Alpha"` and can only display it. It cannot determine: (a) whether the decker is currently on a host (relevant for enabling/disabling host-only actions), (b) what kind of node the decker is at, or (c) the name of the node for any programmatic comparison. Any future UI feature that branches on location type requires either parsing the human-readable string (fragile) or a second data source.

**Recommendation:** Replace the `location: String` field with a structured `location` DTO:
```kotlin
@Serializable
sealed class LocationDto {
    @Serializable @SerialName("Grid") data class Grid(val kind: String, val name: String) : LocationDto()
    @Serializable @SerialName("Host") data class Host(val name: String) : LocationDto()
    @Serializable @SerialName("None") object None : LocationDto()
}
```
Mirror the union in TypeScript. The display label becomes a UI concern, not a DTO concern.

---

### [MEDIUM] Error codes are string literals duplicated across all three layers

**Parts Affected:** server / ui
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:33` (`"already_registered"`)
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:34` (`"name_already_taken"`)
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:110` (`"not_your_turn"`)
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:114` (`"no_action_pending"`)
- `frontend/src/App.tsx:11-15` (`ERROR_LABELS` map with the same four strings)

**Issue:** The error code strings are the wire contract between server and UI. Any rename or addition on the server silently breaks the UI display (it falls back to showing the raw code). There is no shared schema or enum that can be checked at build time. New error conditions added to the server require a matching update to `ERROR_LABELS` in the UI, with no tooling to catch omissions.

**Recommendation:** Define error codes as an enum in the server and document them (or generate a schema) so the TypeScript side can be kept in sync. At minimum, add a server-side integration test that asserts the exact error string values to prevent accidental renames.

---

### [MEDIUM] `ResultMessage.deckerSuccesses` / `hostSuccesses` are required on the server but optional in TypeScript

**Parts Affected:** server / ui
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:43-46` (`deckerSuccesses: Int`, `hostSuccesses: Int` — non-nullable)
- `frontend/src/types/messages.ts:85-86` (`deckerSuccesses?: number`, `hostSuccesses?: number` — optional)

**Issue:** The TypeScript contract is weaker than the Kotlin contract. Any UI code that renders these fields must guard against `undefined` even though the server always sends an integer. Conversely, if the server were ever refactored to omit these fields for non-combat results (a reasonable future change), the TypeScript type would already allow it but the Kotlin type would not, producing a silent deserialization failure on the Kotlin side in the reverse direction.

**Recommendation:** Make both sides consistent: either make both required (the current server behaviour), or introduce a discriminated result type that separates combat outcomes (with success counts) from informational results (without). If you keep them required in Kotlin, remove the `?` in TypeScript.

---

### [LOW] Action selection is index-based with no replay guard

**Parts Affected:** server / ui
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:95` (`availableActions.getOrNull(cmd.actionIndex)`)
- `frontend/src/types/messages.ts:16-17` (`actionIndex: number`)

**Issue:** The UI receives a list of `AvailableActionDto` objects ordered by index, picks one, and sends back the index. The server resolves the index against the in-memory list computed at the start of the turn. If the state message and the action response are separated by a game-state change (possible in a multi-decker game where another player's action changes the available actions), the wrong action could be executed silently. The only protection is `getOrNull` which returns an error, but it does not detect action-list staleness — it only detects out-of-range indices.

**Recommendation:** Include a turn token (a per-turn UUID or monotonic counter) in both `StateMessage` and `ActionCommand`. The server rejects any `ActionCommand` whose turn token does not match the current turn, preventing stale-state execution.

---

### [LOW] `runBlocking` bridges the synchronous game loop into the coroutine-based server on every turn

**Parts Affected:** server
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53` (`runBlocking { registry.promoteForTurn(…) }`)
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:73` (`runBlocking { registry.broadcastWithRoles(…) }`)
- Multiple other `runBlocking` calls in the same file

**Issue:** The game loop runs on a plain JVM thread (it calls `Game.runCombatTurn()` synchronously), while `SessionRegistry` suspend functions use coroutines. `runBlocking` bridges these on the game thread, which blocks that thread during every suspend point. This is functional but creates an architectural impedance mismatch: the game engine thread is blocked waiting on I/O, which limits future options like running a game loop on a coroutine dispatcher or adding async triggers from the game engine.

**Recommendation:** Run the game loop inside a coroutine (e.g. on `Dispatchers.Default`) and make `ActiveIcon.action()` a suspend function. This eliminates all `runBlocking` calls and unifies the threading model. This is a larger refactor but follows naturally from the CRITICAL finding above.

---

## Clean Seams

- **DTO package boundary is well-defined.** All serialisable wire types live in `server/dto/`. The mapping functions (`Decker.toDto()`, `List<AvailableAction>.toDto()`) are co-located with the DTOs, not scattered across the domain. The domain types (`Decker`, `AvailableAction`, `SystemOperation`) are imported into the DTO package but the reverse is not true — the domain has no dependency on the server or DTO packages.

- **`MatrixServer.kt` is correctly thin.** The Ktor routing module does nothing but deserialise the message type field and delegate to `SessionRegistry`. Frame parsing, session lifecycle, and action routing are each a single line. This file is not the problem.

- **TypeScript message types mirror the Kotlin DTOs faithfully.** `messages.ts` defines the full discriminated union of server messages and matches the Kotlin serialisation structure (sealed class `@SerialName` tags, field names, and nesting). The structural alignment is good; the issues are in specific field optionality and the operation/params shapes, not in the overall schema design.

- **`SessionRegistry` session bookkeeping is correct and self-contained.** Name uniqueness, role tracking (`observer` / `registered_decker` / `active_controller`), the `broadcastWithRoles` per-session personalisation, and deregistration cleanup are all handled cleanly within the registry. This logic is appropriately in the server layer.

- **UI state machine in `useWebSocket.ts` is clean.** The reducer-based state machine correctly handles all message types, drives reconnection with exponential back-off, and does not contain any game logic. The `join` / `sendAction` callbacks are the only outbound operations, keeping the hook's surface area minimal.
---
