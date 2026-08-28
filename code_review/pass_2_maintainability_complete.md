# Maintainability Review — complete (cross-cutting)

## Summary

The codebase maintains a sound overall structure with clear Kotlin/TypeScript separation, and several good practices stand out: the bidirectional documentation comment in `MatrixObjectDto.kt`, consistent use of kotlinx.serialization throughout, and the `sealed class` + `@JsonClassDiscriminator` pattern for polymorphic DTOs. However, meaningful maintainability gaps exist where cross-cutting consistency breaks down. The most significant is that the canonical "where to update the Kotlin enum" comment block in `messages.ts` lists three incorrect package paths — the primary navigational aid for this class of change is wrong for three out of five entries. Secondary issues include the same session-role concept named `SessionRole` on one side of the wire and `Role` on the other, the `SystemOperation` TypeScript union being over-broad in a way that obscures what values are actually valid in context, and UI-layer concerns embedded in the protocol type file. Together these create avoidable friction for future maintainers working across the full stack.

---

## Findings

### [HIGH] Cross-reference comments in messages.ts point to wrong Kotlin packages

**File:** `frontend/src/types/messages.ts:53-57`
**Issue:** The comment block that guides developers to "update the matching Kotlin enum" lists three incorrect package paths. `AlertStatus` is attributed to `com.shadowrun.matrix.network.AlertStatus`, but the enum is defined in `com.shadowrun.matrix.common` (`Enums.kt`). `TopologyType` is similarly attributed to `com.shadowrun.matrix.network`, but lives in `com.shadowrun.matrix.common`. `IcProgram.behavior` is attributed to `com.shadowrun.matrix.ic.IcBehavior`, but `IcBehavior` is in `com.shadowrun.matrix.common`. Only `SecurityCode` and `SubsystemType` correctly map to `com.shadowrun.matrix.common`. A developer following this comment to "find and update the enum" will navigate to the wrong package on three of five cases, potentially missing the real definition and concluding no change is needed.
**Recommendation:** Verify actual package locations and correct all five paths. Since all five enums appear to live in `src/main/kotlin/com/shadowrun/matrix/common/Enums.kt`, the references should read `com.shadowrun.matrix.common.Enums` for AlertStatus, TopologyType, and IcBehavior. If any of these enums have been intentionally duplicated in other packages, consolidate them into `common`.

---

### [MEDIUM] `SessionRole` (Kotlin) vs `Role` (TypeScript) — same concept, different names

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:10` and `frontend/src/types/messages.ts:24`
**Issue:** The role of a WebSocket session participant is called `SessionRole` in Kotlin and `Role` in TypeScript. The wire values are consistent (`observer`, `registered_decker`, `active_controller`), but the type names diverge. Every other major cross-boundary type (`DeckerStateDto`, `MatrixObjectDto`, `AvailableActionDto`, `ResultMessage`, etc.) shares its name across both sides. A developer reading the Kotlin controller and then the TypeScript state management must mentally translate the name to find the matching type.
**Recommendation:** Align the names. Since `Role` alone is ambiguous, prefer `SessionRole` on both sides. Alternatively, add a terse comment in `messages.ts`: `// Kotlin: SessionRole`.

---

### [MEDIUM] `UtilityDto` (Kotlin) vs `ActiveUtility` (TypeScript) — same DTO, different names

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:22` and `frontend/src/types/messages.ts:33`
**Issue:** The DTO carrying a loaded cyberdeck utility is named `UtilityDto` in Kotlin and `ActiveUtility` in TypeScript. Unlike every other major DTO in the system (`DeckerStateDto`, `MatrixObjectDto`, `AvailableActionDto`), which share names across both sides, this type diverges. The TypeScript name does not follow the `*Dto` convention used by its sibling types in the same file.
**Recommendation:** Rename the TypeScript interface to `UtilityDto` to match the Kotlin name and the `*Dto` naming convention already established on both sides.

---

### [MEDIUM] `actionType` field is `String` in Kotlin DTO but strongly typed `ActionType` in TypeScript

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:15` and `frontend/src/types/messages.ts:73`
**Issue:** Every variant of `AvailableActionDto` declares `abstract val actionType: String`. The actual values are always an `ActionType` enum name (`FREE`, `SIMPLE`, or `COMPLEX`). The TypeScript side correctly narrows this to `ActionType = 'FREE' | 'SIMPLE' | 'COMPLEX'`. The Kotlin DTO uses `String`, so if the `ActionType` enum is renamed or a new value is added, no compile-time error fires at the DTO layer. The type information is discarded at precisely the boundary where it would be most useful for catching contract drift.
**Recommendation:** Change `abstract val actionType: String` to `abstract val actionType: ActionType` in the sealed class and all subclasses. Ensure the enum is `@Serializable` and that its values serialize to their `.name` strings (matching the existing wire format).

---

### [MEDIUM] `SystemOperation` TypeScript union includes logon/logoff values that have their own `kind` variants

**File:** `frontend/src/types/messages.ts:75-82`
**Issue:** `SystemOperation` is used as the type of the `operation` field inside `{ kind: 'Operation' }` in `AvailableActionDto`. However, the union includes `'GRACEFUL_LOGOFF'`, `'LOGON_TO_HOST'`, `'LOGON_TO_LTG'`, `'LOGON_TO_RTG'`, and `'LOGON_TO_PLTG'` — all of which are modelled as dedicated `kind` values (`GracefulLogoff`, `LogonToHost`, etc.) and can never appear in the `operation` field of an `Operation` action. The type is over-broad: code switching on `operation` could write cases for `GRACEFUL_LOGOFF` that are unreachable dead code, and TypeScript will not warn about it.
**Recommendation:** Introduce a narrower `HostOperation` (or `OperationKind`) union type that excludes the logon/logoff values and use it as the type of the `operation` field in `AvailableActionDto`. Retain `SystemOperation` as a full mirror of the Kotlin enum for documentation purposes, but annotate it so the distinction is clear.

---

### [MEDIUM] Top-level messages use ad-hoc `type: String` discriminators in Kotlin; inconsistent with the sealed-class pattern used for DTOs in the same package

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:28-80`
**Issue:** `JoinMessage`, `StateMessage`, `ActionCommand`, `ResultMessage`, `ControlMessage`, and `ErrorMessage` are independent data classes, each carrying `val type: String = "..."` as a discriminator. In contrast, `MatrixObjectDto` and `AvailableActionDto` in adjacent DTO files correctly use `sealed class` with `@JsonClassDiscriminator`. The inconsistency means the Kotlin compiler provides no exhaustiveness guarantee when processing top-level messages, while for DTOs it does. The TypeScript side already defines the equivalent of `sealed class` via `ServerMessage = ControlMessage | StateMessage | ResultMessage | ErrorMessage`, giving TypeScript better structural safety than Kotlin has here.
**Recommendation:** Introduce a `sealed class ServerMessage` and `sealed class ClientMessage` in Kotlin, using `@JsonClassDiscriminator("type")` to preserve the existing wire format. This brings the top-level message hierarchy in line with the DTO hierarchy and gives Kotlin callers the same exhaustiveness checks that the TypeScript union already provides.

---

### [MEDIUM] `GameEvent` (a UI concern) is defined in the protocol-contract file messages.ts

**File:** `frontend/src/types/messages.ts:125-128`
**Issue:** `GameEvent` is a frontend display abstraction — a tagged union wrapping `ResultMessage` and `ErrorMessage` for the events log in `NarrativePanel`. It has no Kotlin counterpart and is not part of the wire protocol. Its presence in `messages.ts` — the file that otherwise mirrors the wire protocol contract — blurs the boundary between protocol types and application-layer types. Future developers will be uncertain whether new application-level state types belong here.
**Recommendation:** Move `GameEvent` (and the `ServerMessage` union if desired) to a separate file, e.g., `frontend/src/types/appState.ts`, leaving `messages.ts` as a pure, protocol-only mirror.

---

### [MEDIUM] `AvailableActionDto.Operation.targetKind` is derived from Kotlin reflection — fragile on rename

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:66`
**Issue:** `targetKind = target?.let { it::class.simpleName }` derives the wire string from the Kotlin class's `simpleName`. Unlike the `@SerialName`-controlled discriminators used by all other DTO variants, this value will silently change if any `MatrixObject` subclass is renamed. Since the TypeScript side receives this as an untyped `string | null`, a rename will be invisible to both the compiler and tests until a user sees wrong data in the UI.
**Recommendation:** Replace with an explicit `when`-expression mapping each `MatrixObject` subtype to a stable string constant, identical in style to the `targetName()` function immediately below it in the same file:
```kotlin
targetKind = target?.let { obj ->
    when (obj) {
        is MatrixObject.GridNode      -> "GridNode"
        is MatrixObject.LocalGrid     -> "LocalGrid"
        // ...
    }
}
```

---

### [LOW] `MatrixJson` JSON configuration instance is defined in Messages.kt rather than a dedicated serialization file

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:7`
**Issue:** `val MatrixJson = Json { encodeDefaults = true }` is a module-level singleton used by `WebSocketDeckerController` and potentially others. Placing it in `Messages.kt` — a file of message DTOs — means any new DTO file needing this configuration must import from `messages`, creating a non-obvious dependency. New developers will not know to look there.
**Recommendation:** Move `MatrixJson` to a dedicated file, e.g., `src/main/kotlin/com/shadowrun/matrix/server/dto/Serialization.kt`, so its location matches its purpose.

---

### [LOW] `locateWithState` is a poorly descriptive method name

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:239`
**Issue:** The private method `locateWithState` does not "provide state" — it parses a `QueryPrecision` from `ActionParams` (defaulting to `NORMAL`) and delegates to a caller-supplied locate function. The name gives no indication of this purpose at call sites.
**Recommendation:** Rename to `invokeLocateOperation` or `locateWithPrecision` to make the method's role legible without reading its body.

---

### [LOW] Field order in `ActionParams` differs between Kotlin and TypeScript

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:50-56` and `frontend/src/types/messages.ts:8-14`
**Issue:** Kotlin `ActionParams` orders: `newContent, inactivitySeconds, precision, hasValidPasscode, scannerDeviceRating`. TypeScript `ActionParams` orders: `newContent, precision, hasValidPasscode, scannerDeviceRating, inactivitySeconds`. The ordering difference is cosmetic but signals that the two were edited independently. Side-by-side comparison during future changes (e.g., adding a new param) is made harder than it needs to be.
**Recommendation:** Align field order in both files. A logical grouping: locate params (`precision`), comcall params (`hasValidPasscode`, `scannerDeviceRating`), then operation-specific params (`newContent`, `inactivitySeconds`).

---

### [LOW] `GameContext.securityCode` may become stale when host security rating changes

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:13`
**Issue:** `GameContext` stores a constructor-injected `val securityCode: SecurityCode` that is never updated. `updateHost()` replaces the live `host` reference, but does not refresh `securityCode`. If the host's security rating changes during play, `context.securityCode` — used in `Game.kt` for IC initiative rolls — will be stale relative to `host.securityRating.code`. At minimum this is a latent inconsistency; in a host that escalates security, it is a correctness bug.
**Recommendation:** Remove `securityCode` from `GameContext` and derive it at the call site as `context.host.securityRating.code`, or update it inside `updateHost()` when the host's security rating changes.

---

### [INFO] `UtilityDto.type` uses `String` — loses utility type identity at the wire boundary

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:22` and `frontend/src/types/messages.ts:33`
**Issue:** `UtilityDto(val type: String, val rating: Int)` serializes the utility type as a raw string (`it.type.name`). If a fixed enum of utility types exists in the model, the type information is discarded at the DTO boundary and the TypeScript side receives an untyped `string`. This makes it impossible for the frontend to exhaustively handle all known utility types or detect when a new type is added.
**Recommendation:** If a `UtilityType` enum (or equivalent) exists in the Kotlin model, add a corresponding TypeScript union type and document it in the cross-reference comment block in `messages.ts`, following the same pattern used for `AlertStatus`, `SubsystemType`, etc.

---

## No Issues Found In

- `MatrixObjectDto` and `AvailableActionDto` wire encoding: both sides consistently use the `kind` discriminator with matching values.
- `ErrorCode` naming and wire values: `@SerialName` values in Kotlin match the TypeScript `ErrorCode` union exactly.
- `DeckerStateDto` field names: exact match between Kotlin and TypeScript across all ten fields, including camelCase consistency (`isPinnedByBlackIc`, `hackingPool`, `mcpRating`).
- `ResultMessage`, `ControlMessage`, `StateMessage`: field names consistent across the boundary.
- Reconnect flag: `reconnect: Boolean = false` (Kotlin) / `reconnect?: boolean` (TypeScript) — semantically consistent and handled correctly in `useWebSocket.ts`.
- Serialization discipline: `@SerialName` applied consistently to all enum values with stable wire representations (`SessionRole`, `ErrorCode`); `@JsonClassDiscriminator` used on sealed hierarchies.
- The cross-reference documentation block in `MatrixObjectDto.kt` is a valuable pattern and should be maintained and expanded to cover `AvailableActionDto` variants.
- `ActionsPanel.tsx` helper functions (`needsPrecision`, `needsPasscode`, `needsScanner`, `needsEdit`, `buildParams`): cleanly separate detection logic from render logic; easy to extend.
- `useWebSocket.ts` reducer: clean separation of WebSocket event handling from state transitions; actions are well-named and exhaustive.
- `DeckerStateDto` to `Decker.toDto()` mapping is co-located in the same file — good discoverability.
