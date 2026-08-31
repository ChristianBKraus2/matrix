# Design vs. Implementation Discrepancies

Audit date: 2026-08-31  
Design files checked (non-PRD): `design_core/combat.md`, `design_core/movement.md`, `design_core/operations.md`, `design_core/cyberdeck_and_program_mechanics.md`, `design_game/game.md`, `design_ui/design_ui.md`  
PRDs consulted: `prd_core.md`, `prd_game.md`, `prd_ui.md`

---

## C-1 — Armor degradation is unconditional; design requires it only when damage bleeds through

**Design** (`combat.md` / CD-19): Armor degrades "each time an Armor utility **fails to fully absorb** incoming damage — meaning damage bleeds through to the persona's condition monitor."

**Code** (`CombatResolver.kt`): `degradeArmor()` is called unconditionally after every IC hit in `applyIcDamage` (line 94), `resolveLethalBlackIc` (line 279), `resolveNonLethalBlackIc` (line 339), `resolveBlackHammer` (line 380), and `resolveKilljoy` (line 399). Armor degrades even if the persona took zero damage.

**PRD verdict:** PRD (CD-19) says "Each time an Armor utility fails to fully absorb incoming damage — meaning damage bleeds through to the persona's condition monitor, the Armor utility's `currentRating` decreases by 1." → **PRD supports the design.** The code is wrong.

**Status:** ✅ Fixed — `degradeArmor` now takes `damageBledThrough: Boolean`; all five call sites pass the correct condition.

---

## C-2 — Medic invocation degrades stored copy's `currentRating`; stored rating must be immutable

**Design** (`cyberdeck_and_program_mechanics.md` / CD-21): `storedRating` is immutable at runtime. Only the active in-memory instance's `currentRating` decrements on degradation (CD-20). CD-23 specifies that "the fresh instance starts with `currentRating = storedRating`", so the stored copy must remain at its original rating to allow reload.

**Code** (`DeckerOperationsExtensions.kt`, `invokeMediac`, lines 498–503): When the Medic's `currentRating` decrements, both `activeUtilities` and `storedUtilities` are updated with the new lower `currentRating`. A subsequent reload via Swap Memory would give a degraded copy, not a fresh one.

**PRD verdict:** PRD (CD-21) "storedRating (immutable at runtime, from the YAML)" and CD-23 "fresh instance starts with `currentRating = storedRating`" → **PRD supports the design.** The stored copy must not be modified at runtime.

**Status:** ✅ Fixed — `invokeMedic` now leaves `storedUtilities` unchanged when `newMedicRating > 0`; only removes the stored entry when depleted.

---

## C-3 — Depleted Medic is removed from storage; design says mark as depleted

**Design** (`cyberdeck_and_program_mechanics.md` / CD-22): "auto-unloads it from active memory, **marks it depleted** in the storage inventory". The stored entry is retained but flagged.

**Code** (`DeckerOperationsExtensions.kt`, `invokeMediac`, lines 496–502): When `newMedicRating <= 0`, both the active and stored entries are removed entirely (`filterNot { it.type == MEDIC }`). There is no "depleted" flag; the utility simply disappears.

**PRD verdict:** PRD (CD-22) says "A depleted utility cannot be re-loaded" — this end-goal is achieved by both approaches. However, the PRD does not specify whether the entry should be removed or retained with a flag. → **PRD supports neither approach precisely.** The design says "mark depleted"; the code removes. The observable behavior (can't reload) is the same.

**Status:** ✅ Design updated — `cyberdeck_and_program_mechanics.md` now says "Auto-unloaded and removed from storage; event logged (CD-22)", matching the code's removal approach.

---

## C-4 — `detectedIcons` set is absent from `Decker`; persistent icon visibility is unimplemented

**Design** (`operations.md` / MP-04): Adds `val detectedIcons: Set<Icon> = emptySet()` to `Decker` or `Persona`. Once a Sensor Test succeeds, the icon enters this set and remains visible across subsequent turns without re-rolling. Icons are removed on successful Evade Detection, on the icon leaving the area, or on logoff/jackout/dump.

**Code** (`Decker.kt`): No `detectedIcons` field exists. Every call to `noticeIcon` is a fresh, independent test. If no test is called, a previously detected icon is forgotten.

**PRD verdict:** PRD (MP-04): "Once located, an icon remains 'visible' unless it performs a combat maneuver to escape detection." → **PRD supports the design.** The code is incomplete.

**Status:** ✅ Fixed — `val detectedIcons: Set<MatrixIcon> = emptySet()` added to `Decker`; cleared on `gracefulLogoff` and `jackOut`.

---

## C-5 — `RELOCATE_ICON` appears in grid `availableActions`; design restricts it to host context

**Design** (`game.md`, available-actions section): Grid context (`OnLTG / OnRTG / OnPLTG`) exposes only `NULL_OPERATION`, `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`.

**Code** (`Decker.kt`, `addGridSystemActions()`, lines 157–164): Also adds `RELOCATE_ICON` to all grid contexts.

**PRD verdict:** PRD (CD-16): RELOCATE_ICON is a "Control Test" — Control is a host subsystem, not a grid property. Grid nodes have no Control subsystem to test against. → **PRD supports the design.** `RELOCATE_ICON` should be host-only.

**Status:** ✅ Fixed — `RELOCATE_ICON` removed from `addGridSystemActions()`; remains in `addHostSystemActions()` only.

---

## M-1 — `logonToPltg` from `OnHost` location is silently rejected with `IllegalStateException` rather than a clean precondition error

**Design** (`movement.md`, `logonToPltg` preconditions): Allowed from `OnLTG` or `OnPLTG` only. The document does not explicitly state what happens if called from `OnHost`.

**Code** (`DeckerNavigationExtensions.kt`, `logonToPltg`, lines 163–169): `else -> throw IllegalStateException(...)` for any location other than `OnLTG` or `OnPLTG`. This includes `OnHost`, which is not discussed in the design.

**PRD verdict:** PRD (M-06, M-11) connects PLTGs to LTGs; there is no path from a host to a PLTG directly. → **PRD supports the design restriction.** The code behavior (throw) is acceptable but the design does not address the host-to-PLTG case explicitly.

**Status:** ✅ Design updated — `movement.md` `logonToPltg` preconditions now explicitly state: "Any other location (including `OnHost`) is not a valid origin — callers must first logoff to the grid. The implementation throws `IllegalStateException` for these cases."

---

## M-2 — `LogonResult.Failure.decker` returns the original decker without the tally update embedded

**Design** (`movement.md`, `jackInToLtg`): "Increment `ltg.securityTally` by `outcome.hostSuccesses`... If not: return `LogonResult.Failure` with updated tally."

**Code** (`DeckerNavigationExtensions.kt`, `performLogon`, lines 288–290): In the failure case, `LogonResult.Failure(this, newLocation, ...)` is returned where `this` is the original decker (no tally update on its `currentLocation`). The tally update IS embedded in `newLocation` (the attempted destination). However, since the decker is not jacked in (or was not moved), their `currentLocation` does not point to the updated grid node. Callers that use `result.decker` exclusively will not see the tally increment on the target grid.

**PRD verdict:** PRD (M-04, M-05): "Each System Test result is added to the decker's security tally on that system, **regardless of who won the contest**." → **PRD supports the design.** The tally should be persisted even on failure. The code embeds it in `newLocation` but that field may be unused by callers.

**Status:** ✅ Fixed — `performLogon` failure path now returns `withDestinationTallyEmbedded(newLocation)`, propagating the tally increment back through the decker's current-location network graph.

---

## OP-1 — `analyzeIcon` does not apply the `max(2, ...)` floor before the sensor subtraction

**Design** (`operations.md`, Analyze Icon): "TN = `host.controlRating - (persona.sensor + analyze.currentRating)`, but may **not** drop below 2 regardless of combined Sensor + Analyze ratings."

**Code** (`DeckerOperationsExtensions.kt`, `analyzeIcon`, lines 127–130):
```kotlin
val tn = host.subsystemRatings.control - sensorRating   // sensor subtracted first
SystemTestResolver.resolve(this, ANALYZE_ICON, tn, ...)  // resolve() subtracts analyze rating, then applies max(2, ...)
```
The floor of 2 is applied inside `resolve()` after both reductions, which is mathematically equivalent to the design formula `max(2, control - sensor - analyze)`. The computation is correct; only the code structure differs from the design's stated formula.

**PRD verdict:** PRD (SO individual table, Analyze Icon): "Decker may subtract Sensor Rating + Analyze rating from TN, but TN may not drop below 2." → **PRD supports the design.** The code produces the same result but via a different decomposition. No functional discrepancy.

**Status:** ⏭ Skipped — no functional discrepancy; result is mathematically identical. No change needed.

---

## OP-2 — `locateAccessNode` is implemented for both `Host` and `Grid` targets; design only specifies `Host`

**Design** (`operations.md`): `locateAccessNode` signature takes a `Host` parameter.

**Code** (`DeckerOperationsExtensions.kt`, lines 236–292): Two overloads exist — one for `Host` and one for `Grid`. The grid overload searches attached hosts for a name match.

**PRD verdict:** PRD (M-07): "From an RTG, a decker may perform a **Locate Access Node** operation to discover LTG codes and host addresses." This explicitly places Locate Access Node on grids. → **PRD supports the code.** The design document is incomplete; the grid overload is correct per PRD.

**Status:** ✅ No discrepancy — code is correct per PRD; design was incomplete, not wrong.

---

## CD-1 — `Cyberdeck.init` validates `u.rating` against MPCP but does not validate `currentRating`

**Design** (`cyberdeck_and_program_mechanics.md` / CD-01): "Every utility's rating must not exceed the deck's MPCP Rating."

**Code** (`Cyberdeck.kt`, `init`, lines 61–70): Validates `u.rating <= mcpRating` for active and stored utilities. Does not validate `u.currentRating`. Since `currentRating` starts at `rating` and only decreases at runtime, this is not an initial-load concern.

**PRD verdict:** PRD (CD-01): "The application rejects any configuration where a utility rating > MPCP." Refers to `rating`, not `currentRating`. → **PRD supports the code.** No functional discrepancy.

**Status:** ✅ Design updated — step 8 of decker creation in `cyberdeck_and_program_mechanics.md` now explicitly notes: "`currentRating` is not validated at load time — it starts equal to `rating` and can only decrease during play, so it is always within bounds at construction."

---

## CD-2 — `invokeMediac` typo in method name (extra 'a')

**Design**: Not explicitly named in any design doc (the operation is described but not given a method name).

**Code** (`DeckerOperationsExtensions.kt`, line 474): Method is named `invokeMediac` (should be `invokeMedic`).

**PRD verdict:** PRD (CD-20) describes Medic mechanics but does not name the method. → **PRD supports neither** (not applicable). Naming-only issue; no functional impact.

**Status:** ✅ Fixed — renamed to `invokeMedic` in `DeckerOperationsExtensions.kt` and all call sites in tests.

---

## UI-1 — `reconnectToken` in `ControlMessage` not verified as implemented

**Design** (`design_ui/design_ui.md`): The UI should store and send a `reconnectToken` in subsequent `JoinMessage` requests (UI-01 through UI-04).

**Code** (`WebSocketDeckerController.kt`, `SessionRegistry`): Not audited in full. The `ControlMessage` DTO and `JoinMessage` handling in `SessionRegistry` / `MatrixServer` were not fully reviewed in this audit pass.

**PRD verdict:** PRD (UI-01 through UI-04): Specifies reconnect token issuance and validation. → **PRD supports the design.** Implementation status unverified; requires further audit.

**Status:** ⚠️ Open — not yet implemented; requires full audit of `WebSocketDeckerController` and `SessionRegistry`.

---

## Summary Table

| ID   | Area        | Status | Notes |
|------|-------------|--------|-------|
| C-1  | Combat      | ✅ Fixed | `degradeArmor` conditional on bleed-through |
| C-2  | Combat/CD   | ✅ Fixed | Stored utility `currentRating` now immutable at runtime |
| C-3  | Combat/CD   | ✅ Design updated | Design now matches code: depleted utility removed from storage |
| C-4  | Operations  | ✅ Fixed | `detectedIcons: Set<MatrixIcon>` added to `Decker`; cleared on logoff/jackout |
| C-5  | Game        | ✅ Fixed | `RELOCATE_ICON` removed from `addGridSystemActions()` |
| M-1  | Movement    | ✅ Design updated | `movement.md` now documents `OnHost` as invalid origin for `logonToPltg` |
| M-2  | Movement    | ✅ Fixed | `performLogon` failure embeds tally via `withDestinationTallyEmbedded` |
| OP-1 | Operations  | ⏭ Skipped | No functional discrepancy; result mathematically identical |
| OP-2 | Operations  | ✅ No discrepancy | Code correct per PRD; design was incomplete |
| CD-1 | Cyberdeck   | ✅ Design updated | Note added: `currentRating` not validated at construction |
| CD-2 | Cyberdeck   | ✅ Fixed | Renamed `invokeMediac` → `invokeMedic` everywhere |
| UI-1 | UI/Server   | ⚠️ Open | Reconnect token not yet audited or implemented |
