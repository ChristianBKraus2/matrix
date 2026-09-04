# Architecture Review — complete (cross-cutting)

## Summary

Matrix of Shadowrun follows a clean three-layer design: a pure-Kotlin game engine (`game/`, `decker/`, `operations/`, `ic/`, `network/`, `common/`), a Ktor WebSocket server (`server/`), and a React/TypeScript frontend. The bridge between server and engine is `WebSocketDeckerController`, which implements the game engine's `ActiveIcon` interface and uses `SessionRegistry` for all WebSocket I/O — meaning the engine itself is fully transport-agnostic. The DTO layer (`server/dto/` on the Kotlin side, `frontend/src/types/messages.ts` on the TypeScript side) covers the full message contract and is largely well-structured. The main architectural concerns are: a mixed enum serialisation strategy that makes some wire values fragile, a stringly-typed parameter bag with silent fallback, non-exhaustive dispatch for new operations, and the inherent tension of bridging a synchronous game-engine interface with an async WebSocket turn.

---

## Findings

### [HIGH] `runBlocking` inside game-engine callback can starve Ktor's coroutine dispatcher

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45`

**Issue:** `ActiveIcon.action()` is a synchronous interface method called directly by the game loop. `WebSocketDeckerController` bridges it to async WebSocket I/O by wrapping the entire body in `runBlocking { … }`. The coroutine inside blocks for up to `actionTimeoutSeconds` (120 s) waiting on a `CompletableDeferred`. If the game loop is ever invoked from within a coroutine scope — for example if it is launched via `launch` or `async` in the future — `runBlocking` inside a coroutine will deadlock. Even today, the blocking call pins a thread for the full timeout duration; with multiple simultaneous decker turns the thread pool could exhaust under load.

**Recommendation:** Promote `ActiveIcon.action()` to `suspend fun action(…)` in the game engine interface. This eliminates `runBlocking` and lets the controller `await` the deferred naturally. `Game.runOutOfCombatTurn()` and `runCombatTurn()` would become suspend functions too, and the game-loop entry point would be launched in a dedicated coroutine scope. The change is mechanical and confines blocking only to CPU-bound dice logic.

---

### [MEDIUM] Mixed enum serialisation strategy — some enums use `@SerialName`, others use raw `.name`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:10–25` and `frontend/src/types/messages.ts:51–61`

**Issue:** `SessionRole` and `ErrorCode` are annotated with `@SerialName` (producing lowercase/snake_case wire values, e.g. `"active_controller"`). All other enums that appear inside DTOs — `AlertStatus`, `SecurityCode`, `TopologyType`, `SubsystemType`, `IcBehavior` — are serialised using the Kotlin default, which is the enum constant's `.name` (ALL_CAPS, e.g. `"PASSIVE_ALERT"`). The TypeScript file even carries an explicit warning comment about this fragility. The consequence is that renaming a Kotlin enum constant in any of the unguarded enums silently breaks the frontend with no compile-time signal on either side.

**Recommendation:** Annotate every enum that crosses the wire with `@SerialName` on each variant, regardless of whether the default name happens to be acceptable. This makes the wire contract explicit and immune to Kotlin-side renames. Alternatively, apply `@Serializable` with a custom serialiser that enforces a canonical string, but `@SerialName` is the lowest-friction fix.

---

### [MEDIUM] `ActionParams.precision` is `String?` on the wire — invalid values silently fall back to `NORMAL`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:54` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:243`

**Issue:** `ActionParams.precision` is declared as `String?` and is converted to `QueryPrecision` via `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL`. A misspelled or unknown precision string produces no error — it silently runs the locate operation at `NORMAL` precision. The TypeScript side correctly constrains this to a union of the five valid string literals, but that safety only applies to TypeScript callers; any other client (or a future refactor that renames a `QueryPrecision` variant) will fail silently.

**Recommendation:** Declare `precision` as a serialisable enum in the DTO package and reference it directly in `ActionParams`, consistent with how `SessionRole` and `ErrorCode` are handled. The `locateWithState` helper then receives a typed value and the silent fallback is eliminated.

---

### [MEDIUM] No protocol version or capability handshake

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt` (all messages)

**Issue:** No WebSocket message carries a protocol version field. If the server changes a message shape — adds a required field, renames a discriminator value, splits a DTO — the frontend will silently receive unexpected data and render incorrectly or crash. There is currently no way for a connected client to know it is out of date.

**Recommendation:** Add a `version: Int` (or semver string) to the initial `ControlMessage` sent on connection. The client should disconnect and show an "incompatible server version" notice if the version does not match the build it was compiled against. This is low overhead and saves debugging time during deployment.

---

### [MEDIUM] Two-pass JSON parsing in `MatrixServer` — manual discriminator extraction instead of polymorphic deserialisation

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:35–38`

**Issue:** Every incoming WebSocket frame is parsed twice: once with `Json.parseToJsonElement` to extract the `"type"` discriminator, and a second time with `Json.decodeFromString<T>` to produce the typed object. This is fragile (if the two parses see inconsistent JSON, behaviour is undefined), wastes allocations on every message, and cannot be extended to new message types without touching `MatrixServer`. The `type` field itself exists as a hardcoded default in each DTO (`val type: String = "join"` etc.), and there is no compile-time check that these strings are unique or match the `when` branches.

**Recommendation:** Model inbound messages as a sealed class hierarchy annotated with `@Serializable` and `@SerialName` on each subclass, then use `kotlinx.serialization` polymorphism with `classDiscriminator = "type"`. A single `Json.decodeFromString<ClientMessage>(json)` replaces the two-pass approach, the `type` values are declared once in one place, and adding a new message type is a matter of adding a subclass.

---

### [LOW] `dispatchHostOperation` `when` block is not exhaustive — new `SystemOperation` values fail silently at runtime

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:235`

**Issue:** `SystemOperation` is an enum. The `when` block in `dispatchHostOperation` covers all current variants but has a catch-all `else` branch that returns a failure `DispatchResult` with the message `"Unsupported: ${action.operation}"`. Adding a new `SystemOperation` to the engine produces no compile warning; the new operation will silently fail in production until someone notices the log.

**Recommendation:** Remove the `else` branch. With it absent, adding a new `SystemOperation` variant will produce a compile-time "when expression must be exhaustive" error, forcing the implementor to handle it. The stub `gridOperation` handler already uses this pattern correctly for its small `when`.

---

### [LOW] `DeckerStateDto.location` is a free-form human-readable string

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:12`

**Issue:** The decker's current location is serialised as a display string (`"Host: Corp HQ"`, `"RTG: Seattle"`, `"not jacked in"`). The frontend cannot make structural decisions (e.g. which panels to show, whether the decker is on a host) without string-parsing the prefix. Any future i18n effort, or a UI change that needs to know the location *type* rather than the *name*, requires parsing an undocumented ad-hoc format.

**Recommendation:** Replace the `location: String` field with a small discriminated-union DTO (`LocationDto` with subclasses `NotJackedIn`, `OnRtg(name)`, `OnLtg(name)`, `OnPltg(name)`, `OnHost(name)`). This mirrors the existing Kotlin `MatrixLocation` sealed class and keeps the contract explicit on both sides.

---

### [LOW] `GameContext` exposes mutable collections with only a comment for thread-safety

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:17–20`

**Issue:** `deckers: MutableList<Decker>` and `activeIc: MutableList<IC>` are `val` but fully mutable, with only a doc comment reading "Game-loop thread only — no concurrent access." Any code that obtains a `GameContext` reference can mutate these lists directly, bypassing `updateDecker` / `removeIc`, which defeats the intent of the managed-update methods.

**Recommendation:** Expose `deckers` and `activeIc` as `List<Decker>` / `List<IC>` (read-only views via `Collections.unmodifiableList` or by storing as a private `MutableList` and exposing a read-only property). All mutations go through the existing named methods. The compile error when external code tries to call `.add()` or `.remove()` on the read-only view is the enforcement mechanism.

---

### [LOW] Dual ownership of `decker` reference between `WebSocketDeckerController` and `GameContext`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:100–107`

**Issue:** `WebSocketDeckerController` holds a `var decker: Decker` that is also registered inside `GameContext.deckers`. After each dispatched operation, the controller updates its local reference and calls `context.applyDeckerOperationResult`, which updates the context copy. It then re-reads the decker back from the context (`context.deckers.firstOrNull { it.name == decker.name } ?: decker`) to pick up any additional changes (e.g. alert transitions). This round-trip works but means the same logical decker lives in two places and must be kept in sync manually. If `applyDeckerOperationResult` ever replaces the decker without the controller re-reading, the controller's local copy would silently diverge.

**Recommendation:** Make `GameContext` the single source of truth. `WebSocketDeckerController` should look up its decker from the context by name before and after each operation rather than caching a local copy. The post-operation re-read on line 107 already acknowledges this need — removing the local `var decker` and always querying the context eliminates the divergence risk entirely.

---

## No Issues Found In

- **Game engine independence from transport.** `Game`, `GameContext`, `Decker`, `IC`, and all `operations/` code contain zero imports from `server/` or any WebSocket/HTTP package. The `ActiveIcon` interface is the only coupling point, and it is defined in the game layer with no transport dependencies.
- **`ActiveIcon` abstraction.** Human-controlled deckers (`WebSocketDeckerController`) and AI-controlled IC both implement the same `ActiveIcon` interface. `Game` drives turns through the interface without knowing which kind it is talking to — this is the correct adapter pattern.
- **`SessionRegistry` concurrency discipline.** All mutations to shared session maps are guarded by `synchronized(lock)`, the lock is never held across suspension points, and the `TOCTOU`-prone `promoteForTurn` / `setPendingAction` ordering is correctly defended with the deferred-set-before-promote pattern.
- **DTO layer coverage.** `Messages.kt` and `messages.ts` cover the same message types, discriminators match, and the TypeScript union type `ServerMessage` correctly enumerates all server-to-client message variants. The TS `ActionParams.precision` field uses a precise union literal type even though the Kotlin side is a raw string.
- **`DeckerStateDto` / `UtilityDto` mapping.** The `Decker.toDto()` extension function in `DeckerStateDto.kt` is the only place where engine state is converted to transport representation. No game-engine class contains serialisation annotations or DTO knowledge.
- **`broadcastWithRoles` personalisation.** The per-recipient role stamping in `SessionRegistry.broadcastWithRoles` is clean and correctly performs all serialisation outside the lock.
- **Reconnection handling.** The `disconnectedDeckerNames` set and the `reconnect` flag in `ControlMessage` form a coherent reconnection contract that the frontend consumes correctly in `useWebSocket`.
- **`useWebSocket` hook design.** The hook is a clean, self-contained reducer-based state machine. All WebSocket lifecycle concerns (connect, reconnect with exponential backoff, join, send) are encapsulated; `App.tsx` consumes only the derived state and action functions.
