# Discrepancies Without a PRD Verdict

All five items identified during the design-vs-implementation audit have been resolved.

---

## CD-2 — `Accessory.kt` is in the wrong package ✓ resolved

**Decision:** File stays in `accessories/` package (cleaner separation).  
**Fix:** Updated `cyberdeck_and_program_mechanics.md` to reference `accessories/Accessory.kt`.

---

## CD-3 — Cyberterminal detection flag name mismatch ✓ resolved

**Decision:** Unified into one field: kept `isCyberterminal`, removed `immuneToDumpShock`.  
**Fix:** Removed `immuneToDumpShock` from `Cyberdeck`, updated `Cyberterminal` factory,
`CombatResolver`, `DeckerNavigationExtensions`, and all test files. Updated design doc to reference
`isCyberterminal` throughout.

---

## MC-2 — Grid-context operation overloads undocumented in `operations.md` ✓ resolved

**Decision:** Document the grid-context variants in `operations.md`.  
**Fix:** Added "Grid-Context Variants" section to `operations.md` documenting `analyzeSecurity`,
`analyzeIc`, `locateAccessNode`, and `locateIc` grid overloads.

---

## OP-2 — `SWAP_MEMORY` absent from the `SystemOperation` enum ✓ resolved

**Decision:** Add as a deferred placeholder following the `LOCATE_DECKER` precedent.  
**Fix:** Added `SWAP_MEMORY(null, null, SIMPLE, STANDARD)` to `SystemOperation.kt`, excluded from
`availableActions()`. Updated `protocol.md` deferred operations table and operation count in tests.

---

## UI-1 — `protocol.md` `IcProgram` DTO is incomplete vs `design_ui.md` ✓ resolved

**Decision:** Update `protocol.md` to match the code and `design_ui.md`.  
**Fix:** Updated `IcProgram` row in `protocol.md` `MatrixObjectDto` table to document `analyzed`,
`rating` (nullable), `behavior` (nullable), and `guardedNodeType` (nullable).

---

---

## IM-1 — `INVOKE_MEDIC` in `SystemOperation` enum

**Design:** `operations.md` does not list `INVOKE_MEDIC` in the `SystemOperation` enum. `cyberdeck_and_program_mechanics.md` defines `invokeMedic()` as a standalone method on `Decker`, not as a System Test.

**Code:** `SystemOperation.kt` includes `INVOKE_MEDIC(CONTROL, null, COMPLEX, STANDARD)`.

**PRD verdict:** The PRD (CD-20, Utilities/Medic section) describes Medic invocation as "spend a Complex Action and roll Medic Rating dice" — a direct dice roll with no System Test. The PRD neither requires nor prohibits it being listed as a `SystemOperation`. The enum entry was added as a UI-layer convenience so the action appears in `availableActions()`, but `testType = CONTROL` is semantically nominal — `invokeMedic()` never calls `SystemTestResolver`.

**Status:** Design updated to document `INVOKE_MEDIC` in `operations.md` (with a note). No code change warranted; the implementation is correct per PRD.

---

## NM-1 — `Icon` type in design vs `MatrixIcon` in code ✓ resolved

**Design inconsistency:** `operations.md` uses `Icon` throughout (e.g. `detectedIcons: Set<Icon>`, `SensorTestResult.Detected(val icon: Icon)`, `noticeIcon(icon: Icon)`). The same name appears in `combat.md` and `game.md`.

**Code:** The runtime type was named `MatrixIcon` — `Decker.kt` declared `detectedIcons: Set<MatrixIcon>` and all related methods used `MatrixIcon`.

**PRD verdict:** The PRD never names this type; the name is a design-layer choice. There is no PRD guidance to resolve it.

**Fix:** Renamed `sealed class MatrixIcon` to `sealed class Icon` in `MatrixIcon.kt` and updated all imports and usages in `Decker.kt`, `DeckerOperationsExtensions.kt`, `WebSocketDeckerController.kt`, and three test files. All tests pass.

**Design inconsistency:** `cyberdeck_and_program_mechanics.md` (ACC-01) originally stated that `DownloadHandle` accepts an optional `destination: DownloadDestination`. `operations.md` defines `DownloadHandle` without any such field. The two design docs were inconsistent.

**Code:** `DownloadDestination.kt` exists in code (matching the sealed class definition in `cyberdeck_and_program_mechanics.md`), but `DownloadHandle` has no `destination` field and always routes downloads to deck storage.

**PRD verdict:** PRD ACC-01 describes offline storage as expanding deck capacity but does not specify download routing mechanics. The PRD does not mandate a `destination` field on `DownloadHandle`.

**Status:** `cyberdeck_and_program_mechanics.md` updated to correctly reflect the current state — `DownloadDestination` is a stub for future routing support; `DownloadHandle` does not yet use it. No code change warranted until offline storage routing is implemented.

---

## RL-1 — `relocateIcon()` TN used Control subsystem instead of opponent's Sensor ✓ resolved

**Design / PRD:** TN = opponent's Sensor − Relocate utility rating.

**Code (before fix):** `relocateIcon()` used `host.subsystemRatings.control` unconditionally — ignoring both the PRD rule and the existing `TrackState.opponentSensorRating` field.

**Code (correct):** `resolveTrackLock()` in `CombatResolver` already populates `TrackState(opponentSensorRating = trackRating, ...)` — the Track IC's rating serves as its Sensor rating.

**Fix applied:** `relocateIcon()` now uses `trackState?.opponentSensorRating?.takeIf { it > 0 } ?: host.subsystemRatings.control` as the base TN. When being tracked, the IC's sensor rating is used correctly. When not tracked, the Control subsystem rating is used as a fallback (decker is relocating defensively without an active tracker).

**PRD verdict:** PRD supports the fix. Implementation is now fully correct.

---

## AS-1 — `ANALYZE_SUBSYSTEM` testType: design doc said `CONTROL`, code uses `null` ✓ resolved

**Design (before fix):** `operations.md` listed `ANALYZE_SUBSYSTEM(CONTROL, ANALYZE, SIMPLE, STANDARD)`.

**Code:** `SystemOperation.kt` had `ANALYZE_SUBSYSTEM(null, UtilityType.ANALYZE, SIMPLE, STANDARD)` — `null` because the TN (targeted subsystem rating) is passed dynamically at call time.

**PRD verdict:** PRD describes the test type as "Targeted Subsystem" — a dynamic value, not a fixed subsystem. Code is correct.

**Fix applied:** `operations.md` updated to `ANALYZE_SUBSYSTEM(null, ANALYZE, SIMPLE, STANDARD)` with a comment explaining the dynamic TN. No code change needed.

---

## NFR-1 — `invokeMedic()` missing start log ✓ resolved

**Design / PRD NFR:** Every public `Decker` method must log at start (intention) and at end (outcome).

**Code (before fix):** `invokeMedic()` had only an end log; no start log.

**Fix applied:** Added `logger.info { "[$name] invokeMedic: invoking Medic utility" }` as the first line of `invokeMedic()`.

---

## GM-1 — `game.md` hardcoded `meatworldComm = false` in `Decker.initiative()` ✓ resolved

**Design (`game.md` before fix):**
```kotlin
override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
    CombatResolver.rollDeckerInitiative(this, meatworldComm = false, diceRoller)
```

**Code (`Decker.kt`):**
```kotlin
override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
    CombatResolver.rollDeckerInitiative(this, meatworldComm = meatworldComm, diceRoller)
```

**PRD verdict:** CC-06 states "Direct meatworld communication reduces a decker's Initiative by –1D6 until the link is dropped." The code correctly reads the decker's `meatworldComm` field. The design doc had a placeholder `false` that was never updated when `meatworldComm: Boolean` was added as a field to `Decker`. PRD supports the code.

**Fix applied:** Updated `game.md` to use `meatworldComm = meatworldComm`.

---

## AI-1 — `ANALYZE_ICON` offered for `File` and `Device` targets but dispatch only handles `IcProgram` ✓ resolved

**Design (`Decker.availableActions()`):** `addHostSystemActions()` added `ANALYZE_ICON` for every `icProgram`, every `dataFile`, and every `remoteDevice` in the host.

**Code (`WebSocketDeckerController.dispatchAnalyzeOp`):**
```kotlin
SystemOperation.ANALYZE_ICON -> {
    val ic = (action.target as? MatrixObject.IcProgram)?.ic
        ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_ICON requires an IcProgram target")
    decker.analyzeIcon(Icon.IcIcon(ic), host, diceRoller).toDispatch()
}
```
The dispatch only accepts `IcProgram` targets. If a player selected `ANALYZE_ICON` on a file or device it would always fail with an error, even though the action was listed as available.

**Root cause:** The `Icon` sealed class only defines `PersonaIcon` and `IcIcon`. `analyzeIcon()` accepts `Icon`, so files and devices cannot be passed to it. Additionally, `analyzeIcon()` does not actually use the `icon` parameter in its current implementation (TN is derived from `persona.sensor` and `host.controlRating`).

**PRD verdict:** PRD says "Analyze Icon: Scans any icon; identifies general type." The PRD supports analyzing files and devices (which are Matrix icons). However, the design's `Icon` sealed class does not yet model those types. Neither the design's type system nor the dispatch are fully PRD-aligned; offering the action for non-IC targets while failing it silently is the worst outcome.

**Fix applied:** Removed `ANALYZE_ICON` from the `dataFiles` and `remoteDevices` loops in `addHostSystemActions()` (`Decker.kt`). `ANALYZE_ICON` is now only offered for IC programs, which the dispatch can handle correctly. This makes the system consistent with the `Icon` type definition. Full PRD compliance (any icon) requires extending `Icon` to include `FileIcon` and `DeviceIcon` variants and updating `analyzeIcon()` to surface the icon's type in the result — deferred to a future milestone.
