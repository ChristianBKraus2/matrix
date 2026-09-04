# Maintainability Review — Complete System (Cross-Cutting)

## Summary

The three-part system (game_logic / server / ui) shares a WebSocket protocol that is defined only implicitly: as Kotlin `@Serializable` data classes on one side and hand-written TypeScript interfaces on the other, with no machine-readable schema, no code generation, and no protocol document. Several protocol values — role strings, error codes, enum variants — are duplicated in both languages as raw string literals with no enforcement that they stay in sync. The most concrete symptom of this drift is already present: the TypeScript `ActionParams.precision` type allows `'HIGH'`, which is not a valid `QueryPrecision` enum name in Kotlin and will throw an uncaught `IllegalArgumentException` that crashes the game loop. More broadly, a new developer has no single artefact to consult in order to understand message ordering, the role state machine, or which `ActionParams` fields apply to which operation.

---

## Findings

### CRITICAL — `ActionParams.precision` TypeScript type contains an invalid Kotlin enum name

**Parts Affected:** server / ui  
**File(s):** `frontend/src/types/messages.ts:11` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245`  
**Issue:** The TypeScript type for `precision` is `'NORMAL' | 'HIGH'`. The Kotlin enum `QueryPrecision` has the values `VERY_VAGUE`, `VAGUE`, `NORMAL`, `SPECIFIC`, `VERY_SPECIFIC` — there is no `HIGH`. If the UI sends `precision: 'HIGH'`, the server executes `QueryPrecision.valueOf("HIGH")`, which throws `IllegalArgumentException`. That exception is thrown inside `WebSocketDeckerController.action()`, which is called synchronously from `Game.runOutOfCombatTurn()` / `runCombatTurn()`. It is not inside the `runCatching {}` guard in `MatrixServer.kt` (which only wraps JSON parsing and the initial dispatch), so it propagates uncaught and crashes the game coroutine. The TypeScript type gives the UI author a false guarantee and the crash produces no error message to the client.  
**Recommendation:** Replace the TypeScript type with the correct five-value union: `'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'`. Also add a `try/catch` around `QueryPrecision.valueOf()` in `locateWithState` and send an `ErrorMessage` back to the client instead of letting the exception escape.

**Resolution (Phase 1.2):**
`frontend/src/types/messages.ts` `precision` type corrected from `'NORMAL' | 'HIGH'` to the full five-value union `'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'`, matching the Kotlin `QueryPrecision` enum. `ActionsPanel.tsx` updated to render all five options. Server-side safe lookup fixed in Phase 1.1.

---

### HIGH — Role strings are raw literals in Kotlin, duplicated as a TypeScript union with no enforcement

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:26,44,70,80,96-98,103`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:64`, `frontend/src/types/messages.ts:24`, `frontend/src/App.tsx:78`  
**Issue:** The three role values `"observer"`, `"registered_decker"`, `"active_controller"` appear as plain string literals in nine places across two Kotlin files, and are independently copied into TypeScript as `export type Role = 'observer' | 'registered_decker' | 'active_controller'`. The TypeScript `useWebSocket.ts` branches on `msg.role === 'observer'` and `App.tsx` branches on `ws.role === 'registered_decker' || ws.role === 'active_controller'`. A rename or typo on either side causes a silent protocol failure — the role state machine stops advancing with no compile error on either side.  
**Recommendation:** Define a Kotlin `enum class SessionRole { OBSERVER, REGISTERED_DECKER, ACTIVE_CONTROLLER }` (or a `sealed class`), serialise it with `@Serializable` using lowercase names via `@SerialName`, and generate or manually keep the TypeScript `Role` union from a single source. At minimum, replace the Kotlin raw-string scatter with a constants object so there is one place to change.

**Resolution (Phase 5.2):**
`Messages.kt` now defines a `SessionRole` enum with `@SerialName` values. `SessionRegistry.kt` and `WebSocketDeckerController.kt` are updated to use enum constants instead of raw strings, and `messages.ts` frontend type is updated to match.

---

### HIGH — Error code strings duplicated between `SessionRegistry.kt` and `App.tsx` with no shared definition

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:33,35,43,110,114` and `frontend/src/App.tsx:10-15`  
**Issue:** Server error codes (`"already_registered"`, `"name_already_taken"`, `"not_your_turn"`, `"no_action_pending"`) are plain string literals in Kotlin. The UI maps them to human-readable labels in a `Record<string, string>` keyed by those same string values. If a new error code is added server-side without updating `ERROR_LABELS`, the UI displays the raw code string to the user with no warning at compile time. The `ResultMessage.details` freeform strings from `WebSocketDeckerController.kt` are a separate category (narrative text is fine as freeform), but the `ErrorMessage.message` codes are a defined set and should be treated as an enum.  
**Recommendation:** Define a Kotlin `enum class ErrorCode` (or sealed class) for the `ErrorMessage.message` field. Serialise to the same snake_case strings for backward compatibility. In TypeScript, replace `Record<string, string>` with `Record<ErrorCode, string>` where `ErrorCode` is a string union derived from the Kotlin enum, making missing entries a compile error.

**Resolution (Batch 2 — Maintainability):**
`Messages.kt` now defines `@Serializable enum class ErrorCode` with 7 `@SerialName` snake_case values (`NOT_YOUR_TURN`, `NO_ACTION_PENDING`, `ALREADY_REGISTERED`, `NAME_ALREADY_TAKEN`, `NAME_TOO_LONG`, `UNKNOWN_MESSAGE_TYPE`, `BAD_REQUEST`). `ErrorMessage.message` changed from `String` to `ErrorCode`; a new `details: String?` field carries dynamic context. All Kotlin call sites in `SessionRegistry.kt` and `MatrixServer.kt` updated to enum constants. `frontend/src/types/messages.ts` exports `ErrorCode` as a string union and types `ErrorMessage.message: ErrorCode`. `App.tsx` `ERROR_LABELS` is now `Record<ErrorCode, string>`, making missing entries compile errors; all 7 codes have entries.

---

### HIGH — No machine-readable protocol definition; role state machine exists only in code

**Parts Affected:** game_logic / server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt`, `frontend/src/hooks/useWebSocket.ts`, `frontend/src/types/messages.ts`  
**Issue:** There is no AsyncAPI spec, OpenAPI document, Protobuf schema, or even a README that describes the WebSocket protocol. A new developer must read both the Kotlin server and the TypeScript client to reconstruct: (1) which messages flow in which direction, (2) the role state machine (`connect → observer → registered_decker → active_controller → registered_decker`), (3) the turn lifecycle (server sends `state` → client sends `action` → server sends `result` → server sends new `state`), and (4) which `ActionParams` fields are meaningful for each operation. The role transition triggered by receiving a `control{role:"observer"}` and immediately sending `join` is implicit behaviour buried in `useWebSocket.ts:90-93`.  
**Recommendation:** Add a `design/protocol.md` (or AsyncAPI YAML) that describes all message types, their direction, the role state machine as a diagram, and the turn lifecycle sequence. This is a one-time investment that pays off every time a new developer, test author, or frontend contributor touches the system.

**Resolution (Batch 2 — Maintainability):**
`design/protocol.md` created, covering: transport and endpoint; all message types with direction and when-sent; full JSON schemas; the role state machine as an ASCII diagram; turn lifecycle sequence; complete error code reference table; and discriminant tables for `AvailableActionDto` and `MatrixObjectDto` sealed classes.

---

### HIGH — `runCatching {}` in `MatrixServer.kt` silently swallows all message-processing errors

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29-35`  
**Issue:** Every exception thrown during JSON parsing or message dispatch is swallowed by `runCatching { }` with no logging and no error response to the client. If the server receives a malformed `ActionCommand` (e.g. missing required fields, wrong type for `actionIndex`), the client receives silence — no `ErrorMessage`, no log line, no indication that anything went wrong. This makes debugging protocol errors extremely difficult across the server/client boundary.  
**Recommendation:** Replace the bare `runCatching` with explicit handling: catch `SerializationException` and send an `ErrorMessage` back to the client; catch unexpected exceptions with a `logger.error(...)` call and optionally a generic error response. Swallowing errors at the outermost handler is the worst place to lose information.

**Resolution (Phase 1.3 — see also error_handling_complete.md):**
`MatrixServer.kt` now has an explicit `try/catch` inside the frame loop. On exception it sends `ErrorMessage(message = ErrorCode.BAD_REQUEST, details = e.message?.take(120))` back to the session. An `else` branch was also added to the `when (msgType)` block returning `ErrorMessage(ErrorCode.UNKNOWN_MESSAGE_TYPE, details = msgType)` for unrecognised message types.

---

### MEDIUM — `ResultMessage.deckerSuccesses` / `hostSuccesses` are always-present in Kotlin but optional in TypeScript

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:40-46` and `frontend/src/types/messages.ts:84-91`  
**Issue:** The Kotlin `ResultMessage` always serialises `deckerSuccesses: Int` and `hostSuccesses: Int`. The TypeScript `ResultMessage` declares both as `deckerSuccesses?: number` and `hostSuccesses?: number`. Any UI component that reads these fields must null-guard them even though the server never omits them. The mismatch inverts the actual contract: the fields are required, but the TypeScript type implies they may be absent.  
**Recommendation:** Remove the `?` from both fields in the TypeScript interface. If there is ever a future case where they should be optional, that is a deliberate protocol change that should be made consciously on both sides simultaneously.

**Resolution (Phase 2.5):**
`frontend/src/types/messages.ts` `ResultMessage` interface updated — `?` removed from `deckerSuccesses` and `hostSuccesses`, making both required `number` fields that match the server's non-nullable `Int` contract.

---

### MEDIUM — `SystemOperation` (28 values) serialised as `operation: string` in TypeScript with no union type and no param-to-operation mapping

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt`, `frontend/src/types/messages.ts:74`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:161-234`  
**Issue:** The `Operation` variant of `AvailableActionDto` carries `operation: string` on the TypeScript side. There is no union type listing the 28 valid `SystemOperation` names, so the UI cannot switch exhaustively over them, and there is no compile-time feedback if a new operation is added or an existing one is renamed. More critically, the mapping from operation name to required `ActionParams` fields is encoded only in `dispatchHostOperation` — for example, `EDIT_FILE` reads `params.newContent`, `NULL_OPERATION` reads `params.inactivitySeconds`, and `MAKE_COMCALL` reads `params.hasValidPasscode`. This mapping is invisible to the UI author.  
**Recommendation:** Add a TypeScript union type `export type SystemOperation = 'ANALYZE_HOST' | 'ANALYZE_IC' | ...` for all 28 values. Add an exported constant or type mapping each operation to its required `ActionParams` keys (even a simple `Partial<Record<SystemOperation, (keyof ActionParams)[]>>` would help). This enables the `ActionsPanel` to render correct parameter inputs for each operation.

**Resolution (Batch 2 — Maintainability):**
`frontend/src/types/messages.ts` now exports `SystemOperation` as a 28-value string union covering all `SystemOperation` enum names. The `Operation` variant of `AvailableActionDto` uses `operation: SystemOperation` instead of `operation: string`, enabling exhaustive TypeScript checking.

---

### MEDIUM — `kind` field in sealed DTOs is redundant with the kotlinx.serialization type discriminator, creating two competing discriminants in the JSON

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:16-17`, `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:16-17`  
**Issue:** Both `AvailableActionDto` and `MatrixObjectDto` are `@Serializable sealed class`es. kotlinx.serialization emits a `type` discriminator field (shaped by `@SerialName`) for sealed class polymorphism. Each subclass also has an explicit `kind: String` field that carries the same value (e.g. `kind: String = "LogonToRtg"`). The JSON wire format therefore contains both `"type":"LogonToRtg"` and `"kind":"LogonToRtg"`. The TypeScript side uses only `kind` as its discriminant and is unaware of the `type` field. A developer new to the codebase will wonder which field is authoritative, and any future attempt to add `@JsonClassDiscriminator("kind")` to unify them will be blocked by the collision with the `type` field already present in `StateMessage` and other wrapper types.  
**Recommendation:** Add `@JsonClassDiscriminator("kind")` to both sealed classes to make `kind` the official kotlinx.serialization discriminator and eliminate the redundant `type` field from the wire format. This aligns the Kotlin serialisation with the TypeScript discriminant and removes the ambiguity.

**Resolution (Phase 5.1):**
`AvailableActionDto.kt` and `MatrixObjectDto.kt` now use `@JsonClassDiscriminator("kind")` with `@OptIn(ExperimentalSerializationApi::class)`. The explicit `kind: String` fields have been removed from all subclasses, eliminating the duplicate discriminator in the wire format.

---

### MEDIUM — `WebSocketDeckerController.action()` blocks the game loop thread for up to 120 seconds, hidden behind a synchronous interface

**Parts Affected:** game_logic / server  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:14-17`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:49,75`  
**Issue:** `Game.runOutOfCombatTurn()` calls `decker.action(context, diceRoller)` as a plain synchronous call on the `ActiveIcon` interface. Nothing in `Game.kt` or the `ActiveIcon` contract indicates that this call can block. In reality, `WebSocketDeckerController.action()` calls `runBlocking { registry.promoteForTurn(...) }` and then `future.get(actionTimeoutSeconds, TimeUnit.SECONDS)`, which blocks the calling thread for up to 120 seconds waiting for a human to respond. If the `Game` methods are ever called from a coroutine context, `runBlocking` inside a coroutine is a known Kotlin pitfall that can deadlock. A new developer looking at `Game.kt` has no warning.  
**Recommendation:** Document the blocking contract on the `ActiveIcon` interface with a KDoc comment. Long term, consider making `action()` a `suspend fun` and replacing `runBlocking` with a proper coroutine-based `CompletableDeferred`.

**Resolution (Batch 2 — Concurrency/Maintainability):**
`WebSocketDeckerController.kt` was refactored to use a single outer `runBlocking { }` with `CompletableDeferred` and `withTimeoutOrNull`, eliminating all nested `runBlocking` calls (see concurrency finding). `action()` remains a non-suspend function to avoid changes across ~40 tests; the blocking nature at the `ActiveIcon` boundary is unchanged, but the implementation no longer risks deadlock in coroutine contexts.

---

### LOW — Five enum types serialised as raw `String` in Kotlin DTOs, duplicated as TypeScript union types with no code generation

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:19,29,40,53,59,68,78`, `frontend/src/types/messages.ts:50-53,61`  
**Issue:** `AlertStatus`, `SecurityCode`, `TopologyType`, `SubsystemType`, and IC `behavior` are Kotlin enums serialised with `.name` (plain string). The TypeScript counterparts (`export type AlertStatus = 'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT'`, etc.) are hand-maintained copies. When a Kotlin enum gains or loses a variant (e.g. if a new `SecurityCode` tier is added), the TypeScript union silently becomes incomplete — new values from the server are accepted by the `string` base type at runtime but never appear as valid TypeScript literals.  
**Recommendation:** Introduce a build step (e.g. a Kotlin script, `kotlinx-serialization` reflection, or a simple Gradle task) that dumps the enum values to a TypeScript `const` file at build time. Until then, add a comment in both files cross-referencing the other side so that a developer knows to update both when changing an enum.

**Resolution (Phase 6.3):**
`MatrixObjectDto.kt` has a KDoc on the sealed class listing all five raw-name types (`AlertStatus`, `SecurityCode`, `TopologyType`, `SubsystemType`, `IcBehavior`) and instructing developers to update `frontend/src/types/messages.ts` when any variant changes. `messages.ts` has a matching comment block before the five union types, cross-referencing the Kotlin enum FQNs. A build-step code-generation approach remains a future improvement.

---

### LOW — `interrogationStates` accumulation across turns is undocumented at the architectural seam

**Parts Affected:** game_logic / server  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47,243-252`  
**Issue:** The `interrogationStates` map in `WebSocketDeckerController` persists `InterrogationState` across multiple turns for locate operations (`LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE`). This is correct Shadowrun rule behaviour (interrogation operations accumulate successes over repeated actions), but there is no comment explaining why the state lives here rather than in the `Decker` domain object, and no explanation of what happens if a decker logs off mid-interrogation (the state is silently discarded when the controller is garbage-collected). A developer asked to support saving game state would not know to persist this map.  
**Recommendation:** Add a KDoc comment on the field explaining the rule (interrogation operations require multiple actions to accumulate enough successes) and the lifetime expectation (cleared on disconnect; not persisted).

**Resolution (Phase 5.5):**
`interrogationStates` was moved from `WebSocketDeckerController` into the `Decker` data class as `interrogationStates: Map<SystemOperation, InterrogationState>`. `locateFile`, `locateSlave`, and `locateAccessNode` now manage state internally, so interrogation progress survives reconnect and is visible to domain logic.

---

## Clean Seams

- **DTO mapping is co-located with DTOs.** The `toDto()` extension functions in `DeckerStateDto.kt`, `AvailableActionDto.kt`, and `MatrixObjectDto.kt` live in the same file as the DTOs they produce. A developer changing a DTO and its mapping stays in one file.
- **`MatrixJson` is a single shared serialisation instance.** All server-side JSON encoding goes through `MatrixJson` (defined in `Messages.kt`), ensuring consistent `encodeDefaults = true` behaviour everywhere.
- **TypeScript `messages.ts` is a single file for the entire protocol.** All server-sent and client-sent types are in one place. The client/server boundary is easy to find.
- **`useWebSocket.ts` is a clean React hook abstraction.** The hook fully encapsulates WebSocket lifecycle, reconnection, and message dispatch. `App.tsx` and child components are entirely free of WebSocket concerns.
- **`ActionCommand` uses an index rather than an operation name.** Sending `actionIndex` rather than re-serialising the action avoids the UI having to reconstruct a complete action from user input — the server owns the canonical action list and the UI just picks from it by position.

---
