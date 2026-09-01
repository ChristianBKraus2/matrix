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

## GR-1 — `ANALYZE_IC` offered on grid without a target; dispatch always fails

**Design (`game.md`, grid context):** `ANALYZE_IC` is listed in the grid-context operation set (`addGridSystemActions()`). The design implies it is a valid grid action.

**Code (`Decker.addGridSystemActions()`):**
```kotlin
add(AvailableAction.Operation(SystemOperation.ANALYZE_IC))  // no target argument
```
The action is added with no `MatrixObject` target. `visibleObjects()` for `OnRTG`, `OnLTG`, and `OnPLTG` never includes `MatrixObject.IcProgram` entries — IC programs only appear in host context.

**Controller (`WebSocketDeckerController.dispatchGridOperation()`):**
```kotlin
SystemOperation.ANALYZE_IC -> {
    val ic = (action.target as? MatrixObject.IcProgram)?.ic
        ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_IC requires an IcProgram target")
    decker.analyzeIc(ic, grid, diceRoller).toDispatch()
}
```
With no target on the action, dispatch always returns failure.

**PRD verdict:** The PRD supports grid-context `ANALYZE_IC` (scanning IC visible on the grid) but does not specify how the IC reference is obtained on a grid — IC programs are host-resident objects and are not naturally surfaced by grid navigation. There is no PRD guidance for resolving the mismatch.

**Status:** No fix applied. The issue is that the grid `visibleObjects()` model does not expose IC programs, so there is nothing to target. Full resolution requires either (a) modelling grid-visible IC as a distinct concept, or (b) removing `ANALYZE_IC` from the grid-context action set. Deferred to a future milestone.

**Todo** remove ANALYZE_IC from grid

---

## PR-1 — `protocol.md` `MatrixObjectDto` table was missing many fields ✓ resolved

**Design (design_ui.md / code):** Full DTO fields for all object kinds, including `securityCode`, `securityTally`, `connectedRtgCount` on `GridNode`; `alertStatus`, `securityTally`, `pltgCount` on `LocalGrid`; `alertStatus`, `securityCode`, `hostCount` on `PrivateGrid`; `offline`, `alertStatus` on `HostNode`; `isPointer` on `File`. `Operation` AvailableActionDto includes `paramKind`.

**Code:** `MatrixObjectDto.kt` and `AvailableActionDto.kt` serialise all fields correctly.

**PRD verdict:** PRD (prd_ui.md) describes these fields. Design_ui.md and code are correct; protocol.md was out of date.

**Fix applied:** Updated `protocol.md` `MatrixObjectDto Discriminant` table to include all fields for every kind. Added `paramKind` to the `Operation` entry in `AvailableActionDto`.

---

## PR-2 — `NULL_OPERATION` sent `paramKind = "inactivitySeconds"` but TypeScript type omits it ✓ resolved

**Design (design_ui.md):** `paramKind` union type is `"precision" | "hasValidPasscode" | "scannerDeviceRating" | "newContent" | null` — no `"inactivitySeconds"` variant.

**Code (before fix):** `AvailableActionDto.kt` mapped `NULL_OPERATION` to `"inactivitySeconds"`, which is not a valid member of the TypeScript union.

**PRD verdict:** prd_ui.md states "NULL_OPERATION: uses `inactivitySeconds` (default `0`) — the default is sufficient; no extra input required." PRD supports `paramKind = null`; no inline control should be rendered.

**Fix applied:** Removed `SystemOperation.NULL_OPERATION -> "inactivitySeconds"` branch from the `when` in `AvailableActionDto.kt`; it now falls through to `else -> null`.

---

## CM-1 — `combat.md` `ConditionMonitor` section has stale file path and field name

**Design (`combat.md`):** States file is `src/main/kotlin/com/shadowrun/matrix/decker/ConditionMonitor.kt` and uses field name `filledBoxes`.

**Code:** `ConditionMonitor` is a `data class` inside `src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt`. The field holding damage is named `damage`, not `filledBoxes`. The `isCrashed` computed property delegates to `isDestroyed` (`damage >= maxBoxes`), not `filledBoxes >= 10` directly.

**PRD verdict:** PRD CC-27 describes a 10-box condition monitor without specifying file location or field names. No PRD guidance resolves the naming.

**Status:** No code change needed — the code is correct. Design doc has stale references from an earlier draft. Updating the doc is deferred (no behavioural impact).

---

## EP-1 — `EntitiesPanel.tsx` renders IcProgram fields unconditionally (ignores `analyzed`)

**Design (`design_ui.md`):** IcProgram cards must show an `[ANALYZED]` / `[UNKNOWN]` badge, and only render `rating`, `behavior`, and `guardedNodeType` when `analyzed === true`. When not yet analyzed those fields are null and must not be rendered.

**Code (`EntitiesPanel.tsx`):** `rating` and `behavior` are rendered unconditionally — when `analyzed === false` the server sends them as `null` and the rendered card shows `RATING: null` / `BEHAVIOR: null`. The `analyzed` field itself is never rendered.

**PRD verdict:** `prd_ui.md` lists `IcProgram` fields as `name, rating, behavior, guardedNodeType` without a conditional on `analyzed`. The PRD does not address what to do when those fields are null. PRD neither supports the design's conditional guard nor the code's unconditional rendering.

**Status:** No fix applied. Rendering null values is poor UX and leaks internal state to the player. The design's approach (badge + conditional) is the correct UX. Deferred.

---

## RT-1 — `useWebSocket.ts` cleared `reconnectToken` on every disconnect ✓ resolved

**Design / PRD (UI-02):** The reconnect token issued by the server must be stored and included in every subsequent `JoinMessage` for the same decker name so the server can re-associate the returning session.

**Code (before fix):** `ws.onclose` unconditionally set `reconnectTokenRef.current = null`. This cleared the token before the auto-reconnect JoinMessage was sent, so the server always received a token-free join and could not re-associate the session.

**PRD verdict:** PRD UI-02 supports the design. Code was wrong.

**Fix applied:** Removed `reconnectTokenRef.current = null` from `ws.onclose`. The token now survives disconnect/reconnect cycles and is included in the reconnect JoinMessage. The token is cleared only on intentional logout (to be added when a logout button is implemented).

---

## TS-4 — `design_ui.md` TypeScript type spec has minor precision gaps vs `messages.ts`

**Design (`design_ui.md`):**
- `AvailableActionDto.Operation.operation` typed as `string`
- `AvailableActionDto.Operation.paramKind` typed as `"precision" | "hasValidPasscode" | "scannerDeviceRating" | "newContent" | null`

**Code (`messages.ts`):**
- `operation: SystemOperation` (a typed union — stricter than `string`)
- `paramKind: string | null` (looser than the design's union)

**PRD verdict:** PRD does not specify TypeScript type signatures. No PRD verdict.

**Status:** No fix applied. The code is more strict than the design in one direction and less strict in another. Neither is a runtime bug. The design doc could be updated to use `SystemOperation` and the code could tighten `paramKind` to the known union — both deferred.

---

## TRK-1 — `combat.md` `resolveTrackLock` algorithm is incomplete vs code

**Design (`combat.md`):**
- Step 1 rolls evasion vs `trackRating` (no TN floor documented)
- Return value documented as `TrackState(trackRating, cycleTurns)` (2 fields)

**Code (`CombatResolver.kt`):**
- Evasion roll uses `max(2, trackRating)` as TN (standard SR3 TN floor — not in design doc)
- `TrackState` is constructed with all 4 fields: `trackingIcRating`, `locationCycleTurnsRemaining`, `opponentSensorRating`, `trackerMcpRating`

**PRD verdict:** PRD CC-30 describes track lock without specifying TN floors or `TrackState` field names. No PRD verdict.

**Status:** No fix applied. The code is correct. The design doc is an incomplete description of the algorithm. Deferred.

---

## PB-1 — `combat.md` describes wrong trigger for `resolveProbe`

**Design (`combat.md`):** States that `resolveProbe` is "Called by the game engine each time the decker performs a System Test while Probe is active."

**Code (`IC.kt`):** `Probe` is declared as `REACTIVE` IC (`IcBehavior.REACTIVE`). It calls `CombatResolver.resolveProbe` inside its own `action()` method, which fires at end-of-turn in the reactive IC phase — not when the decker performs a System Test.

**PRD verdict:** PRD ICC-03 describes Probe IC's effect (adding security tally) without specifying the trigger mechanism (system-test-triggered vs. per-turn initiative). No PRD guidance resolves the discrepancy.

**Status:** The code matches `game.md` (Probe acts on its own initiative). The `combat.md` resolver description is misleading. Deferred — updating the combat.md comment to reflect the actual trigger mechanism.

---

## DU-1 — `DownloadHandle`/`UploadHandle` computed but never tracked; multi-turn transfers never complete

**Design (`operations.md`):** `advanceCombatTurn()` decrements `DownloadHandle.turnsRemaining` each Combat Turn. When it reaches 0 the download completes and the file is added to `cyberdeck.runDownloadedFiles` via `recordCompletedDownload()`. Same mechanic for `UploadHandle`.

**Code:** `downloadData()` and `uploadData()` (in `DeckerOperationsExtensions.kt`) correctly compute `turnsRemaining = ceil(sizeMp / ioSpeed)` and return a `DownloadHandle`/`UploadHandle`. The controller (`WebSocketDeckerController.dispatchDataOp`) receives the handle but immediately discards it (`val (opResult, _) = ...`). `Decker` has no `activeDownloads` or `activeUploads` fields. `advanceCombatTurn()` never touches download/upload handles. `recordCompletedDownload()` is never called. Net result: the System Test resolves correctly, the client receives a success message with "N turn(s) at X Mp/turn", but the file is never actually added to `runDownloadedFiles` — transfers never complete.

**PRD verdict:** PRD SO-10/SO-11 explicitly states "Download Data: Begins the transfer of a file; actual transfer takes multiple Combat Turns based on I/O Speed." PRD supports the design. The code is missing the turn-advancement mechanism.

**Status:** Deferred. Full fix requires: (1) add `activeDownloads: List<DownloadHandle>` and `activeUploads: List<UploadHandle>` fields to `Decker`; (2) update `advanceCombatTurn()` to decrement them and call `recordCompletedDownload()`/`recordCompletedUpload()` at zero; (3) update the controller to store handles on the decker after a successful dispatch.

---

## MS-1 — `missing.md` items 5–14 are stale (addressed in design docs)

**Design (`missing.md`):** Lists items 5–14 as "not reflected in any design document":
- 5: Physical enhancements don't affect Matrix initiative
- 6: Meatworld comms action-timing displacement
- 7: Delayed action resolution with physical world
- 8: Evade Detection — IC re-detection countdown
- 9: Black IC — data deletion on MPCP destruction
- 10: Non-lethal Black IC — final MPCP shot on unconsciousness
- 11: Scramble IC — data destruction on failed decrypt
- 12: Buffered messages (Free Action)
- 13: Deckers cannot suppress IC after leaving a system
- 14: Legitimate passcode devalidation on jack-out

**Code / other design docs:** Items 5–14 have since been addressed in the design documents. `combat.md` explicitly covers items 5, 6, 7, 8, 9, 10, 13. `operations.md` covers items 11 and 12 (including `resolveScrambleDestructTest` and `bufferMessage`). `movement.md` covers item 14. The code implements most of these (Game.kt physical segment for item 6, CombatResolver for items 9/10/13, etc.). Item 8 (evade detection countdown state machine) remains unimplemented in `Game.kt` despite being designed in `combat.md`.

**PRD verdict:** N/A — `missing.md` is a design-layer tracking document; the PRD does not adjudicate its contents.

**Status:** `missing.md` should be updated to remove or mark items 5–7 and 9–14 as resolved. Item 8 (evade detection countdown in `Game.kt`) remains a code-vs-design gap: `combat.md` defines the countdown and tally-shortening mechanic but `Game.kt` has no countdown state to track it. Deferred until the game engine adds evade-detection state to `GameContext`.

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

---

## INT-1 — `locateAccessNode` grid and host overloads share the same `InterrogationState` key

**Code (`DeckerOperationsExtensions.kt`):** Both `locateAccessNode(host, …)` and `locateAccessNode(grid, …)` use `SystemOperation.LOCATE_ACCESS_NODE` as the key in `decker.interrogationStates: Map<SystemOperation, InterrogationState>`.

**Impact:** If a decker begins a `LOCATE_ACCESS_NODE` interrogation on a grid (accumulating partial successes), then moves to a host and initiates a new `LOCATE_ACCESS_NODE`, they inherit the grid's partial accumulated successes. The security value, subsystem TN, and success count from the grid context all bleed into the host context — giving a spurious head-start or causing an early Located/NotFound based on wrong state.

**PRD verdict:** PRD SO-05 through SO-09 describe multi-turn accumulation without specifying key isolation by context. No PRD guidance resolves the mismatch.

**Fix required:** Change `interrogationStates` from `Map<SystemOperation, InterrogationState>` to a composite-keyed structure (e.g. `Map<String, InterrogationState>` with keys `"LOCATE_ACCESS_NODE@HOST"` vs `"LOCATE_ACCESS_NODE@GRID"`) so each execution context maintains independent state. Moderate refactor — deferred.

---

## DF-1 — `effectiveDetectionFactor` has no floor and can go negative under heavy IC suppression

**Code (`Decker.kt`):**
```kotlin
val effectiveDetectionFactor: Int = detectionFactor - suppressionDfPenalty
```
`suppressionDfPenalty` equals `suppressedIc.size`. No floor is applied. With more suppressed ICs than the base `detectionFactor`, this returns a negative value.

**Impact:** `SystemTestResolver.resolve()` passes `decker.effectiveDetectionFactor` directly as the host's roll TN. SR3 rules specify TN floor = 2 for all tests. If `DiceRoller.roll()` does not enforce this floor, a negative DF causes all host detection tests to resolve at an undefined TN — most likely extremely easy for the host or producing nonsensical results.

**PRD verdict:** PRD CC-22 describes DF reduction by 1 per suppressed IC without specifying a minimum floor. No PRD guidance resolves the edge case.

**Fix required:** Apply `maxOf(2, detectionFactor - suppressionDfPenalty)` or `maxOf(0, …)` as appropriate — pending a PRD clarification on whether suppression can fully blind the host (floor 0) or merely reduce it to the standard TN minimum (floor 2).

---

## DS-1 — `deckingSpecialization: Boolean` field on `Decker` is declared but never read

**Code (`Decker.kt`):** `val deckingSpecialization: Boolean = false` is a constructor parameter. No code in any audited file reads or uses it.

**Design (`cyberdeck_and_program_mechanics.md`):** The field is not mentioned or defined. No designed behaviour exists for it.

**Status:** Ghost field with no design backing. Either a placeholder for a future mechanic (e.g. specialisation bonus to dice pools) or a leftover from an earlier design that was dropped. Deferred until design specifies an effect.

---

## DUP-1 — `resolveInterrogation(host)` duplicates `resolveInterrogationCore` body verbatim

**Code (`SystemTestResolver.kt`):** The grid overload `resolveInterrogation(decker, operation, grid, …)` correctly delegates to the private `resolveInterrogationCore(…)`. The host overload `resolveInterrogation(decker, operation, host, …)` copies the entire body of `resolveInterrogationCore` verbatim instead of calling it.

**Impact:** No functional bug today. Any future logic change to the interrogation algorithm must be applied to two separate copies; the private core function and the host overload can silently diverge.

**Fix required:** Refactor the host overload to delegate to `resolveInterrogationCore(…)` the same way the grid overload does. Straightforward — deferred.

---

## UP-1 — `UPLOAD_DATA` advertises `paramKind = null`; server silently defaults to 100 Mp ✓ resolved

**Code (`AvailableActionDto.kt`):** `UPLOAD_DATA` falls through to `else -> null` in the `paramKind` `when` expression — no param is advertised to the client.

**Code (`WebSocketDeckerController.kt`):** `p?.query?.toIntOrNull() ?: 100` — the controller reads the upload size from `params.query` (a field shared with locate operations) and silently defaults to 100 Mp when absent. The `query` field is semantically overloaded (designed for locate query strings).

**Impact:** The client has no signal that it should send a data size. Every UPLOAD_DATA action silently uses 100 Mp regardless of what the user intends.

**PRD verdict:** PRD SO-11 states "Upload Data: Begins the transfer of a file; the decker must specify the data size at invocation." PRD explicitly requires user-supplied size.

**Fix applied:** Added `dataSize: Int?` to `ActionParams` in `Messages.kt` and `messages.ts`. Set `UPLOAD_DATA -> "dataSize"` in `AvailableActionDto.kt`. Added a DATA SIZE (Mp) stepper to `ActionsPanel.tsx`. Updated `WebSocketDeckerController.kt` to read `p?.dataSize ?: 100`. Updated `protocol.md` params table.

---

## DF-EQ — `DataFile.equals` excludes `pointerToHost` and `pointerTargetFile` fields

**Code (`network/DataFile.kt`):** Custom `equals`/`hashCode` is keyed on `name`, `isScrambleProtected`, and `sizeMp` only. `pointerToHost` and `pointerTargetFile` are excluded (apparently to avoid cyclic-reference issues: `Host` embeds `DataFile` which would embed `Host`).

**Impact:** Two pointer files with the same name and size but pointing to different hosts compare as identical. `DeckerOperationsExtensions.withFileRemovedFromHost` uses `dataFiles.minus(file)` (set-minus), which relies on `equals`. If a host ever has two pointer files with the same name/size but different targets, removing one would silently remove both.

**PRD verdict:** No PRD guidance on `DataFile` equality semantics. The exclusion appears to be a workaround for circular references, not intentional domain logic. Deferred.

---

## UH-1 — `UploadHandle` uses `description: String` instead of a typed `DataFile` reference

**Code (`operations/UploadHandle.kt`):** `val description: String` — a free-text label with no reference to the file being uploaded.

**Comparison (`operations/DownloadHandle.kt`):** `val file: DataFile` — a typed reference enabling `recordCompletedDownload` to append the correct file to `runDownloadedFiles`.

**Impact:** When DU-1 is eventually fixed and upload tracking is implemented, the uploaded file's identity will be unrecoverable from the handle. The asymmetry should be fixed (add a `file: DataFile` field to `UploadHandle`) before DU-1 work begins.

**PRD verdict:** No PRD field-level specification. Structural fix should precede DU-1 implementation.

---

## DC-1 — `LocateDeckerResult.toDispatch()` is dead code

**Code (`WebSocketDeckerController.kt`):** A private extension function `LocateDeckerResult.toDispatch(): DispatchResult` is defined but never reachable. `LOCATE_DECKER` is a deferred operation, is never added to `availableActions()`, and no dispatch branch calls `decker.locateDecker()`.

**Impact:** Dead code that will silently drift from the implementation. When `LOCATE_DECKER` is eventually implemented, the function may be stale. Additionally, the current `locateDecker()` always sets `targetNotified = located` — a silent locate (found but target unaware) is not yet representable.

**Status:** Remove when implementing `LOCATE_DECKER`, or annotate explicitly as a forward stub. Deferred.

---

## TS-7 — `GameEvent` wrapper type in `useWebSocket.ts` diverges from design

**Design (`design_ui.md`):** `WsState.events` described as `(ResultMessage | ErrorMessage)[]` — a plain union array of raw server messages.

**Code (`frontend/src/hooks/useWebSocket.ts`):**
```typescript
type GameEvent = { kind: 'result'; msg: ResultMessage } | { kind: 'error'; msg: ErrorMessage }
// WsState.events: GameEvent[]
```

**Impact:** Components consuming events must unwrap `.msg` to access the raw message. The extra `kind` discriminator is redundant (`msg.type` already discriminates). No functional bug, but the design doc is stale.

**Fix required:** Update `design_ui.md` `WsState` type spec to reflect the `GameEvent` wrapper. No code change needed. Deferred.

---

## SD-1 — `HostInfoItem.SecurityRating` object shadows `common.SecurityRating` data class

**Code (`operations/OperationResult.kt`):** Inside `sealed class HostInfoItem` there is an `object SecurityRating`. The file also imports `com.shadowrun.matrix.common.SecurityRating` (a data class). Within this file, `SecurityRating` unqualified resolves to the `HostInfoItem` variant (the object), not the common data class.

**Impact:** Confusing for code readers; a subtle refactoring trap. If `HostInfoItem.SecurityRating` is ever moved or renamed, call sites relying on implicit resolution could silently change semantics.

**Fix required:** Rename `HostInfoItem.SecurityRating` to `HostInfoItem.SecurityRatingItem` (or similar). Low risk. Deferred.
