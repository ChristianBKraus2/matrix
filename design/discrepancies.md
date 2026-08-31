# Design vs. Implementation Discrepancies

**Generated:** 2026-08-30  
**Scope:** Full codebase audit — design documents (excluding PRDs) vs. Kotlin implementation, with PRD annotations  
**Areas covered:** Combat, Cyberdeck/Programs, Movement/Creation, Operations, Game Loop, UI/Protocol

---

## 1. Combat

### C-1 — `resolveCrippler` / `resolveRipper` / `resolveSlow` signature mismatch

**Design says:** All three methods accept `securityCode: SecurityCode` as their third parameter and convert it internally via `sv = securityValue(securityCode)`.

**Code does:** All three accept `securityValue: Int` directly. Callers pass `context.host.securityRating.value`.

**PRD verdict:** CC-15 requires the security value but does not mandate where the SecurityCode-to-Int conversion must occur. Behavior is equivalent; this is an API surface divergence only.

**Todo:** Change the design

---

### C-2 — Missing IC rating boost on persona crash (Lethal Black IC step 7)

**Design says:** If the icon crashes before the decker dies, the IC's effective rating is set to `ic.rating + 2` for all subsequent tests. `IcDamageResult` must carry enough information for the caller to track this state.

**Code does:** `IcDamageResult` has no field tracking a persona-only (non-lethal) crash. `resolveLethalBlackIc` never computes or reports the `+2` boost. No mechanism exists anywhere in the code to communicate this state to the caller.

**PRD verdict:** ICC-11 references the rule but the rating-boost detail after a persona-only crash is not reproduced in the PRD text. The design explicitly mandates it; the code silently omits it.

**Todo:** Update the design

---

### C-3 — Black IC pin guard in `applyIcDamage` adds undocumented null-check

**Design says:** If `ic is BlackIC && attack.attackerSuccesses > 0`, set `decker.blackIcPin = BlackIcPinState(ic as BlackIC)`. No further condition.

**Code does:**
```kotlin
if (attack.attackerSuccesses > 0 && updatedDecker.blackIcPin == null) {
    updatedDecker = updatedDecker.copy(blackIcPin = BlackIcPinState(ic))
}
```
The extra `&& updatedDecker.blackIcPin == null` guard means a second Black IC hit on an already-pinned decker does not update the pin to the new IC.

**PRD verdict:** ICC-10 lists the Black IC pin mechanic but the design is the authoritative source; the code diverges from it.

**Todo:** Update the design

---

### C-4 — `TrackState` has two undocumented fields

**Design says:** `data class TrackState(val trackingIcRating: Int, val locationCycleTurnsRemaining: Int)`

**Code does:** The call site passes two additional fields: `opponentSensorRating = trackRating` and `trackerMcpRating = trackRating`.

**PRD verdict:** CC-33 specifies only the cycle-turn calculation, not the data class shape. The extra fields are undocumented.

**Todo:** Update the design

---

### C-5 — `resolveAttack` step text uses undefined field name `utilityRating`

**Design says (steps 4 and 6):** References `attacker.utilityRating` and `attacker.utilityRating + attacker.hackingPool`. The `AttackParticipant` data class defined in the same design document has no such field; it has `weaponPower` and `attackDicePool`.

**Code does:** Uses `attacker.weaponPower` and `attacker.attackDicePool`, matching the data class definition.

**PRD verdict:** CC-23 and CC-27 confirm the code is correct. The design's step-text contains an internal inconsistency; the code is right.

**Todo:** Update the design

---

### C-6 — `applyIcDamage` calls `ConditionMonitor.applyDamage(Int)` instead of `applyDamage(DamageLevel)`

**Design says:** `ConditionMonitor` defines exactly one signature: `fun applyDamage(damage: DamageLevel): ConditionMonitor`.

**Code does:** Passes `stressBoxes: Int` (value 0 or 1) directly to `applyDamage`. This implies either an undocumented `applyDamage(Int)` overload or a latent type error.

**PRD verdict:** CC-30 specifies the 10-box track but not the method signature. The design's single-signature spec is the authority.

**Todo:** Update code

---

### C-7 — `suppressIc` location check is too broad

**Design says:** The decker must still be on the same host where the IC was active.

**Code does:**
```kotlin
require(decker.persona != null && decker.currentLocation is MatrixLocation.OnHost) { ... }
```
Verifies only that the decker is on *any* host, not the *same* host as the crashed IC.

**PRD verdict:** CC-22 establishes IC Suppression but not the boundary condition. The design's explicit same-host requirement is stronger than what the code enforces.

**Todo:** Update the code

---

### C-8 — Design verification table contradicts spec for Non-Lethal Black IC final MPCP attack rating

**Design spec says:** Final MPCP attack uses `ic.rating * 2` (double rating).

**Design verification table says:** "Final MPCP attack at `ic.rating` before disconnect" (single rating).

**Code does:** Uses `ic.rating * 2`, matching the spec and contradicting the table.

**PRD verdict:** ICC-12 does not specify the multiplier. The spec (double) is authoritative; the table entry is erroneous. The code is correct; the internal design inconsistency is noted.

**Todo:** Update the design.

---

### C-9 — `resolveLethalBlackIc` / `resolveNonLethalBlackIc` inline MPCP test instead of delegating

**Design says:** Both methods should delegate to `resolveBlasterMpcpTest(decker, ic, diceRoller, ratingOverride = ic.rating * 2)`.

**Code does:** Both methods copy the `reduceMcpRating` logic inline. Functionally equivalent.

**PRD verdict:** ICC-11 has no behavioral difference from this structural deviation. Deviation affects testability and single-source-of-truth only.

**Todo:** Update the design

---

## 2. Cyberdeck / Programs

### CP-1 — `invokeMedic()` deadly-condition behavior

**Design says:** When `filled >= 10`: return `MedicResult(this, 0, medic.currentRating)` — a graceful no-op.

**Code does:** `require(filled < 10) { "Cannot use Medic on a Deadly (10-box) condition monitor" }` — throws `IllegalStateException`.

**PRD verdict:** CD-20 does not address the deadly case. The design explicitly chose a graceful result; the code forces callers to catch an exception.

**Todo:** Update the code

---

### CP-2 — `invokeMedic()` and `MedicResult` file placement

**Design says:** Both must live in `Decker.kt`.

**Code does:** `invokeMedic()` is in [DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt); `MedicResult` is in its own [MedicResult.kt](src/main/kotlin/com/shadowrun/matrix/decker/MedicResult.kt).

**PRD verdict:** No PRD rule mandates file location. Functionally equivalent.

**Todo:** Update the design

---

### CP-3 — `relocateIcon()` resolution mechanic bypasses `SystemTestResolver`

**Design says:** Route `RELOCATE_ICON` through `SystemTestResolver` as a Control Test (CD-16).

**Code does:** Bypasses `SystemTestResolver` entirely and implements a custom dual-roll opposed test: decker rolls `computerSkill` dice vs. `max(2, opponentSensor - relocateRating)`; tracker rolls `trackerMcpRating` dice vs. `max(2, relocateRating)`. Neither the PRD nor the design describes an opponent "tracker MCP Rating" roll.

**PRD verdict:** CD-16 names it a Control Test but does not define the opponent mechanic. The design generalizes it as a `SystemOperation`; the code uses a wholly different resolution path.

**Todo:** Update the code

---

### CP-4 — `invokeMedic()` auto-unload threshold uses `<= 0` instead of `== 0`

**Design says (step 5):** Trigger auto-unload when `medic.currentRating == 0`.

**Code does:** `if (newMedicRating <= 0)` — triggers for any non-positive value.

**PRD verdict:** CD-22 uses "zero-rating," implying `== 0`. The code is more defensive but deviates from the literal spec.

**Todo:** Update the design

---

### CP-5 — `Cyberterminal()` factory `costNuyen` parameter has no default

**Design says:** `costNuyen: Int = 0` (optional with default).

**Code does:** `costNuyen: Int` — required parameter, no default.

**PRD verdict:** CT-05 neither mandates nor forbids a default.

**Todo:** Update the design

---

### CP-6 — `Cyberdeck` carries an undocumented `detectionFactor()` instance method

**Design says:** The Cyberdeck changes section specifies only: `pendingUploads`, MPCP utility-rating init checks, `usedActiveMemoryMp`, and `freeActiveMemoryMp`. No `detectionFactor()` method is mentioned.

**Code does:** Adds `fun detectionFactor(maskingRating: Int, sleazeRating: Int? = null): Int` to `Cyberdeck`, with `Decker.detectionFactor` delegating to it.

**PRD verdict:** CD-18 defines the formula but not where it must live. The formula is correct; this is an undocumented structural addition.

**Todo:** Update design

---

### CP-7 — `Cyberdeck.init` has two capacity checks not specified by the design

**Design says:** The only additions to `Cyberdeck.init` are MPCP utility-rating checks. Active memory capacity validation is the responsibility of `DeckerLoader` (CD-05).

**Code does:** Adds two `require` checks in `Cyberdeck.init`: `activeMp <= activeMemoryMp` and `storageMp <= storageMemoryMp`.

**PRD verdict:** CD-05 does not mandate a constructor check. These are extra defensive guards; the design placed this responsibility in the loader, not the constructor.

**Todo:** Update design

---

### CP-8 — `loadUtility()` treats `ioSpeedMpPerTurn = 0` as instant load

**Design says:** `turnsRequired = ceil(utility.mpSize / cyberdeck.ioSpeedMpPerTurn)`. No handling for zero I/O speed is specified.

**Code does:** Special-cases `ioSpeedMpPerTurn <= 0`: logs a warning and sets `turnsRequired = 0`, making the load instant.

**PRD verdict:** CD-10 does not address a zero I/O speed cyberdeck. The code prevents a divide-by-zero crash but exceeds the design spec.

**Todo:** Update the design

---

## 3. Movement / Creation

### MC-1 — `LogonResult.Failure.location` — semantic inversion and type mismatch

**Design says:** `Failure.location: MatrixLocation` (non-nullable) is the decker's unchanged *previous* location.

**Code does:** `Failure.location: MatrixLocation?` (nullable) is the *attempted destination* with the host-success security tally already baked in.

**PRD verdict:** M-04/M-05 do not dictate what `Failure.location` should represent semantically. The code's approach is arguably more useful but directly contradicts the design spec.

**Todo:** Update design

---

### MC-2 — `logonToLtg` throws when current location is `OnLTG`

**Design says:** `logonToLtg` must accept `currentLocation is OnLTG` when the target is a PLTG attached to that LTG.

**Code does:**
```kotlin
is MatrixLocation.OnLTG -> throw IllegalStateException("Cannot logon to LTG from $currentLocation")
```
There is no path to reach a PLTG from an LTG via `logonToLtg`.

**PRD verdict:** M-06 requires PLTG access from an LTG. **PRD supports the design; the code blocks it entirely.**

**Todo:** Update the code

---

### MC-3 — `logonToLtg` dispatch to `logonToPltg` is architecturally impossible

**Design says:** When the target is a `PLTG`, `logonToLtg` should dispatch to `logonToPltg`, assuming `PLTG` is a subtype of `LTG`.

**Code does:** In [Grid.kt](src/main/kotlin/com/shadowrun/matrix/network/Grid.kt), `LTG` and `PLTG` are siblings under `Grid`, not in a supertype/subtype relationship. The dispatch is unreachable.

**PRD verdict:** M-11 requires tally carry-over for PLTG entry. **PRD supports the design intent; the type model prevents the design's unified dispatch path from existing.**

**Todo:** Update the design and comment the PRD

---

### MC-4 — `jackInToLtg` does not propagate tally to parent RTG; `mergeRtgTally` helper is absent

**Design says:** The RTG security tally (M-09) is tracked on the LTG's parent RTG. The helper `mergeRtgTally(ltg, outcome)` encapsulates this propagation.

**Code does:** `jackInToLtg` updates only `ltg.securityTally`. The helper `mergeRtgTally` does not exist; no mechanism updates the parent RTG's tally.

**PRD verdict:** M-09 states the RTG carries a shared tally across its LTGs. **PRD supports the design; the code leaves the RTG tally permanently stale.**

**Todo:** Update Code

### MC-5 — `logonToRtg` from `OnLTG` rejects connected RTGs; design allows them

**Design says:** When `currentLocation is OnLTG`, valid targets are the parent RTG *or* any RTG connected via `connectedRtgs`.

**Code does:**
```kotlin
is MatrixLocation.OnLTG -> require(loc.ltg.parentRtg == rtg) { "Target RTG is not the parent of the current LTG" }
```
Only the parent RTG is allowed.

**PRD verdict:** M-06 states "From LTG: move to parent RTG." **PRD supports the code, not the design.** The design extends beyond what M-06 specifies.

**Todo:** Update design

---

### MC-6 — `logonToHost` tiered-topology guard relies entirely on data model

**Design says:** An explicit second-tier-to-sibling-second-tier guard (M-13) must be enforced as a structural check.

**Code does:**
```kotlin
is MatrixLocation.OnHost -> require(loc.host.connectedHosts.contains(host)) { ... }
```
No explicit tier detection; the guard is entirely dependent on `connectedHosts` being correctly populated.

**PRD verdict:** M-13 requires traversal through the first-tier host. PRD supports the design's intent; the code defers entirely to the data model.

**Todo:** Add a comment into the design that references the code.

---

### MC-7 — LTG YAML key is `host_files` in design, `hosts` in code

**Design says:** LTG entries in YAML use `host_files:`.

**Code does:** [GridLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt) reads key `hosts`. A grid.yaml following the design spec would produce zero hosts because `data["host_files"]` is never read.

**PRD verdict:** PRD supports per-host YAML files. The loading key mismatch means neither the design format nor the PRD file-per-host pattern is honored.

**Todo:** Update code and design according to the PRD. Also Change the yaml files, if needed.

---

### MC-8 — `security_sheaf` is a flat list in design YAML, a nested map in code

**Design says:** `security_sheaf:` is a YAML sequence (list) at the top level.

**Code does:** Casts `security_sheaf` as `Map<String, Any>`, expecting a wrapper object with a `trigger_steps` key. A design-format YAML (a list) would silently load an empty `SecuritySheaf()`.

**PRD verdict:** PRD specifies hosts carry a security sheaf but does not define the YAML format. Discrepancy is design vs. code only.

**Todo:** Update the design

### MC-9 — `security_sheaf` step field names all differ between design and code

**Design says:** Step fields are `tally`, `ic`, `alert`, `security_deckers`. The `ic` field is a list of strings in `"TypeName-Rating"` format (e.g., `["Probe-5"]`).

**Code does:** Reads `tally_threshold`, `activated_ic`, `alert_transition`, `security_decker_count`, plus a mandatory `description` field not in the design. The `activated_ic` field expects a list of maps (`{type: probe, rating: 5, ...}`), not strings.

**PRD verdict:** PRD does not specify field names. Mismatch is entirely between design YAML spec and code parser.

**Todo:** Update the design

---

### MC-10 — Topology YAML value format: hyphen vs. underscore

**Design says:** `topology: open-access` (hyphenated).

**Code does:**
```kotlin
val topology = TopologyType.valueOf((data["topology"] as? String ?: "OPEN_ACCESS").uppercase())
```
`"open-access".uppercase()` → `"OPEN-ACCESS"` → `TopologyType.valueOf("OPEN-ACCESS")` throws `IllegalArgumentException`. No hyphen-to-underscore normalisation is applied. A host YAML exactly following the design format crashes at load time.

**PRD verdict:** PRD does not specify YAML values. Discrepancy is design vs. code only.

**Todo:** Update the code

---

### MC-11 — RTG-level PLTGs attached to first LTG only; code comment contradicts implementation

**Design says:** PLTGs should be connected to all LTGs of that RTG.

**Code does:**
```kotlin
// We attach RTG-level PLTGs to each LTG so deckers on any of those LTGs can reach the PLTG.
val updatedFirst = ltgs.first().copy(pltgs = pltgsForFirstLtg)
listOf(updatedFirst) + ltgs.drop(1)   // only first LTG gets the PLTGs
```
The comment says "attach to each LTG" but the code attaches only to `ltgs.first()`.

**PRD verdict:** M-11/M-15 imply PLTG reachability. **PRD supports the design intent; the implementation fails to deliver it, and the code comment actively contradicts what the code does.**

**Todo:** Update the code and the comment.

---

## 4. Operations

### OP-1 — `locateDecker` sleaze rating source

**Design says:** Read sleaze from the persona object: `val sensorTn = targetPersona.masking + (targetPersona.sleaze?.currentRating ?: 0)`.

**Code does:** Accepts sleaze as a separate `targetSleazeRating: Int = 0` parameter. If a caller omits it, the TN is underestimated even when the target has a loaded Sleaze utility.

**PRD verdict:** MP-10 does not specify this TN formula. The design document is the authoritative source; the code's default-to-zero path is a behavioral divergence.

**Todo:** Update the code

---

### OP-2 — `locateDecker` sensor TN has an undocumented floor of 2

**Design says:** No floor is specified for the sensor TN.

**Code does:** Enforces `maxOf(2, masking + targetSleazeRating)`.

**PRD verdict:** CD-14 specifies floor 2 only for utility-reduced target numbers; this test is not utility-reduced. The floor is undocumented and inconsistent with the design's own convention.

**Todo:** Apply a minimal TN of 2 in the PRD and design.

---

### OP-3 — `tapComcall` scanner test is not opposed

**Design says:** Resolve an *opposed* Computer Skill vs. scanner Device Rating test — both sides roll.

**Code does:** Only the decker rolls; the outcome is determined by `successes == 0`. The scanner never rolls.

**PRD verdict:** PRD does not detail the scanner mechanics at this level. The design document explicitly labels the test "Opposed."

**Todo:** Update the design and add a comment to the PRD.

---

### OP-4 — `SWAP_MEMORY` has wrong testType and category

**Design says (via PRD table):** Swap Memory: None, None, Simple — no test type, no utility, Simple action.

**Code does:**
```kotlin
SWAP_MEMORY(CONTROL, null, SIMPLE, ONGOING)
```
`testType = CONTROL` and `category = ONGOING`.

**PRD verdict:** **PRD directly contradicts the code on both `testType` and `category`.**

**Todo:** Update the code

---

### OP-5 — `EDIT_SLAVE` has no PRD backing

**Design says:** Includes `EDIT_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED)`.

**Code does:** Same — includes `EDIT_SLAVE` in [SystemOperation.kt](src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt) and [DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt).

**PRD verdict:** The PRD operations table lists only `Control Slave` and `Monitor Slave`. `Edit Slave` does not appear. Both design and code add an operation with **no PRD backing**.

**Todo:** Update the PRD

---

### OP-6 — `NullOperationModifier` API mismatch

**Design says:** The companion object exposes `forDuration(seconds): NullOperationModifier`. The resolver calls `.forDuration(inactivitySeconds).bonus`.

**Code does:** `val bonus = NullOperationModifier.totalBonusForDuration(inactivitySeconds)` — method name not in design.

**PRD verdict:** SO-13/SO-14 govern null operations but do not specify the modifier API. The design document is authoritative.

**Todo:** Update dsign

---

### OP-7 — `resolveScrambleDestructTest` applies undocumented TN floor

**Design says:** Roll `ic.rating` dice vs. TN = `decker.computerSkill`. No floor mentioned.

**Code does:** `diceRoller.roll(ic.rating, maxOf(2, computerSkill))` — applies a floor of 2.

**PRD verdict:** CD-14 specifies floor 2 only for utility-reduced target numbers; this test is not utility-reduced.

**Todo:** Update Design and PRD

---

### OP-8 — `resolveInterrogation` code comment contradicts behavior

**Design says:** Apply `queryPrecision.modifier` *after* utility reduction: `clampedBase = max(2, base - utility)`, then `adjustedTn = max(2, clampedBase + precision.modifier)`.

**Code does:** Behavior matches the design. However, the code comment states "Apply precision modifier before utility reduction" — the opposite order. The `InterrogationState.kt` docstring also contradicts operations.md on ordering.

**PRD verdict:** SO-07 specifies the modifiers but not their ordering. The code behavior is correct; the comment and docstring are wrong.

**Todo:** Update comment and docstring

---

### OP-9 — `LocateResult.Located` uses `target: LocatedTarget` sealed class instead of `target: Any`

**Design says:** `data class Located(val target: Any, val accumulatedSuccesses: Int)`.

**Code does:** `data class Located(val target: LocatedTarget, val accumulatedSuccesses: Int)` with a sealed hierarchy.

**PRD verdict:** Not covered by PRD. The code is strictly safer than the design spec; this is an improvement, not a regression.

**Todo:** Update the design

---

### OP-10 — All resolver call sites use `host.securityRating.value` instead of `host.securityValue`

**Design says:** Throughout the resolver algorithms, the host dice pool is `host.securityValue`.

**Code does:** Every call site uses `host.securityRating.value`.

**PRD verdict:** PRD distinguishes Security Value (dice pool) from Security Rating (threshold tier). If the numeric values happen to coincide, behavior is equivalent — but the naming discrepancy signals a potential semantic difference.

**Todo:** Update design

---

## 5. Game Loop

### GL-1 — `Crippler.action()` / `Ripper.action()` pass wrong argument type to `CombatResolver`

**Design says:** Pass `context.securityCode: SecurityCode`.

**Code does:** Passes `context.host.securityRating.value: Int` — matches the actual signature. The design document shows the wrong parameter type.

**PRD verdict:** PRD does not specify parameter types. The design doc is internally inconsistent with the `CombatResolver` spec it defines.

**Todo:** Update the design

---

### GL-2 — `icAttackParticipant` call has two arguments in design, three in code

**Design says:** `CombatResolver.icAttackParticipant(this, context.securityCode)` — two arguments.

**Code does:** Three arguments. The confirmed `CombatResolver` signature requires the third argument; the code is correct and the design is missing it.

**PRD verdict:** Not covered by PRD. The design is missing an argument in its call-site documentation.

**Todo:** Update Design

---

### GL-3 — `Ripper.action()` adds an MPCP test on attribute-zero not present in the design

**Design says:** After `resolveRipper`, call `context.updateDecker(target, result.updatedDecker)` — done.

**Code does:** Additionally checks if the targeted attribute reached 0 and calls `CombatResolver.resolveRipperMpcpTest(finalDecker, this, diceRoller)` before updating the decker.

**PRD verdict:** Not covered in the supplied PRD excerpt. Neither supports nor contradicts this additional behavior.

**Todo:** Update design

---

### GL-4 — `Probe.action()` guards `addToSecurityTally` with `if (tallyPoints > 0)`; design calls it unconditionally

**Design says:** `context.addToSecurityTally(tallyPoints)` — always called.

**Code does:** `if (tallyPoints > 0) context.addToSecurityTally(tallyPoints)` — skips when zero.

**PRD verdict:** ICC-03 states "successes added to tally immediately," which implies only positive values. **PRD supports the code's conditional.**

**Todo:** Update Design

---

### GL-5 — `availableActions()` grid context missing three required operations

**Design says:** Grid context should offer: `RELOCATE_ICON`, `NULL_OPERATION`, `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`.

**Code does:** `addGridSystemActions()` adds only `NULL_OPERATION`, `RELOCATE_ICON`, `LOCATE_ACCESS_NODE`. `ANALYZE_SECURITY`, `LOCATE_IC`, and `ANALYZE_IC` appear only inside `addHostSystemActions`.

**PRD verdict:** PRD supports the broader grid list and does not list those three as deferred.

**Todo:** Upadte code

---

### GL-6 — `runCombatTurn` advances utility upload timers; design does not mention this

**Design says:** Four steps: roll initiative, action loop, decrement scores by 10, end when all scores ≤ 0. Nothing beyond.

**Code does:** After the while loop, iterates all deckers and calls `context.updateDecker(decker, decker.advanceCombatTurn())` (annotated with CD-11, CC-33).

**PRD verdict:** Game loop spec says nothing about utility timer advancement per combat turn. The behavior may be correct per the cyberdeck rules but is not documented in the game loop design.

**Todo:** Update Design

---

### GL-7 — `runOutOfCombatTurn` silently defaults to 1 action when decker has no persona

**Design says:** Calls `decker.action(context, diceRoller)` `decker.actionsPerTurn` times per decker. No fallback for a missing persona.

**Code does:** `val count = decker.persona?.let { decker.actionsPerTurn } ?: 1`. If persona is null, the action runs once anyway.

**PRD verdict:** SO-01/SO-02 define `actionsPerTurn` as requiring a persona. PRD does not sanction acting without one.

**Todo:** Update Code

---

### GL-8 — `checkTriggers` silently drops non-escalating alert transitions

**Design says:** "Applies any alert-status transition via `updateHost` (AL-01/AL-02)."

**Code does:** `if (transition.ordinal > host.alertStatus.ordinal) updateHost(...)` — transitions to the same or a lower alert level are silently dropped.

**PRD verdict:** AL-01/AL-02 support applying any transition; they do not mention downward transitions being invalid.

**Todo:** Update code

---

### GL-9 — Interrogation states exist in both `Decker` and `TurnCoordinator`; PRD specifies only `Decker`

**Design says:** `GameContext` spec lists no interrogation state. `TurnCoordinator` is not mentioned in the game design.

**Code does:** [Decker.kt](src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt) correctly holds `interrogationStates: Map<SystemOperation, InterrogationState>` per PRD. [TurnCoordinator.kt](src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt) additionally holds `interrogationStatesByDecker: MutableMap<String, Map<SystemOperation, InterrogationState>>` — a parallel server-side copy with no PRD or design backing. The two can silently diverge.

**PRD verdict:** **PRD explicitly assigns interrogation states to `Decker`. The duplicate store in `TurnCoordinator` has no authoritative source, creating a correctness risk.**

**Todo:** Update the code

---

## 6. UI / Protocol

### UI-1 — `paramKind` string values contradict the TypeScript type union

**Design says ([design_ui.md](design/design_ui/design_ui.md)):** TypeScript union is `'precision' | 'passcode' | 'scanner' | 'edit' | null`. MAKE_COMCALL → `'passcode'`, TAP_COMCALL → `'scanner'`, EDIT_FILE → `'edit'`.

**Code does ([AvailableActionDto.kt](src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt)):** Emits `"hasValidPasscode"`, `"scannerDeviceRating"`, `"newContent"` — none of which are members of the TypeScript union.

**PRD verdict:** PRD uses the code's identifiers (`hasValidPasscode`, `scannerDeviceRating`, `newContent`). **PRD supports the code; design_ui.md and the PRD are inconsistent with each other.**

**Todo:** Update the design

---

### UI-2 — LOCATE_FILE and LOCATE_SLAVE query input not signaled to the client

**Design says ([design_ui.md](design/design_ui/design_ui.md)):** `LOCATE_FILE` and `LOCATE_SLAVE` must show an additional optional text input `SEARCH: [________]` mapped to `params.query`, in addition to the precision selector.

**Code does:** All three operations receive `paramKind = "precision"` only. No signal for the query text input.

**PRD verdict:** PRD mentions only precision for all three operations and is silent on a query input. **PRD supports the code.**

**Todo:** Update the design

---

### UI-3 — Missing reconnect token silently allows reconnect; protocol requires rejection

**[protocol.md](design/protocol.md) says:** If the token is missing or wrong for a disconnected name, respond with `name_already_taken`.

**Code does ([SessionRegistry.kt](src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt)):**
```kotlin
if (msg.reconnectToken != null && storedToken != null && msg.reconnectToken != storedToken) {
    JoinOutcome(ErrorCode.BAD_REQUEST, false, null)
} else { /* reconnect succeeds */ }
```
When `msg.reconnectToken` is `null`, the `&&` short-circuits and reconnect succeeds. A client omitting the token for a disconnected decker name can hijack that slot.

**PRD verdict:** UI-03 specifies `BAD_REQUEST` on mismatch but is silent on the missing-token case. Neither PRD nor code matches the protocol.md requirement of `name_already_taken` on absence. **This is a security-relevant behavioral gap.**

**Todo:** Update the code


---

### UI-4 — Wrong reconnect token returns `BAD_REQUEST`, not `name_already_taken`

**[protocol.md](design/protocol.md) says:** When the token is wrong, respond with `name_already_taken`.

**Code does:** Returns `ErrorCode.BAD_REQUEST`.

**PRD verdict:** UI-03 specifies `BAD_REQUEST` for a mismatch. **PRD supports the code. Protocol.md and PRD are inconsistent; the code follows the PRD.**

**Todo:** Update the design

---

### UI-5 — `staticResources` missing `defaultResource("index.html")`

**Design says ([design_ui.md](design/design_ui/design_ui.md)):**
```kotlin
staticResources("/", "static") { defaultResource("index.html") }
```
Required for React SPA client-side routing.

**Code does ([MatrixServer.kt](src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt)):**
```kotlin
staticResources("/", "static")
```
No `defaultResource` configured. Any path other than an exact static file match returns a 404; deep-linking and page refresh in the SPA will break.

**PRD verdict:** PRD does not address static-file serving configuration.

**Todo:** Update the code

---

### UI-6 — MAKE_COMCALL absent from `protocol.md` params table

**[design_ui.md](design/design_ui/design_ui.md) says:** MAKE_COMCALL requires `hasValidPasscode` toggle.

**[protocol.md](design/protocol.md) says:** The ActionCommand params table lists LOCATE_FILE/SLAVE/ACCESS_NODE, EDIT_FILE, NULL_OPERATION, TAP_COMCALL — MAKE_COMCALL is absent.

**Code does:** Correctly handles `hasValidPasscode` for MAKE_COMCALL, consistent with design_ui.md and PRD.

**PRD verdict:** PRD lists MAKE_COMCALL with `hasValidPasscode`. PRD supports the code and design_ui.md. Protocol.md has an incomplete params table; **documentation gap only, not a code error.**

**Todo:** Update protocol.md

---

## Appendix: Quick-Reference Index

| ID | Area | File(s) | Summary | PRD Verdict |
|----|------|---------|---------|-------------|
| C-1 | Combat | CombatResolver.kt | Method signatures take `Int` not `SecurityCode` | API divergence; behavior equivalent |
| C-2 | Combat | CombatResolver.kt, IcDamageResult | Rating `+2` boost after persona-only crash absent | Design mandates it; PRD silent on detail |
| C-3 | Combat | CombatResolver.kt `applyIcDamage` | Extra `blackIcPin == null` guard prevents pin update from second Black IC | Design has no such guard |
| C-4 | Combat | TrackState | Two undocumented fields | PRD silent on shape |
| C-5 | Combat | CombatResolver.kt `resolveAttack` | Design step-text references non-existent field `utilityRating` | Code correct; design internally inconsistent |
| C-6 | Combat | CombatResolver.kt `applyIcDamage` | Calls `applyDamage(Int)` vs. designed `applyDamage(DamageLevel)` | Design spec is authoritative |
| C-7 | Combat | CombatResolver.kt `suppressIc` | Location check is any host, not same host as the IC | Design requires same-host check |
| C-8 | Combat | CombatResolver.kt, design table | Table says single rating; spec and code use double | Spec (double) authoritative; table erroneous |
| C-9 | Combat | CombatResolver.kt | MPCP test inlined instead of delegating to `resolveBlasterMpcpTest` | No behavioral difference; structural only |
| CP-1 | Cyberdeck | DeckerOperationsExtensions.kt | Throws on deadly condition monitor instead of graceful return | CD-20 silent on deadly case |
| CP-2 | Cyberdeck | DeckerOperationsExtensions.kt, MedicResult.kt | Wrong files per design assignment | No PRD file rule |
| CP-3 | Cyberdeck | DeckerOperationsExtensions.kt `relocateIcon` | Custom dual-roll opposed test; bypasses SystemTestResolver | CD-16 "Control Test"; tracker roll undocumented |
| CP-4 | Cyberdeck | DeckerOperationsExtensions.kt | Auto-unload uses `<= 0` not `== 0` | CD-22 implies `== 0` |
| CP-5 | Cyberdeck | Cyberterminal.kt | `costNuyen` required, not optional with default 0 | CT-05 silent |
| CP-6 | Cyberdeck | Cyberdeck.kt | Undocumented `detectionFactor()` instance method | CD-18 formula correct; structural addition |
| CP-7 | Cyberdeck | Cyberdeck.kt init | Two extra capacity `require` checks not in design | CD-05 assigns this to loader |
| CP-8 | Cyberdeck | DeckerMemoryExtensions.kt | Zero I/O speed treated as instant load | CD-10 silent on zero I/O speed |
| MC-1 | Movement | MovementResult.kt | `Failure.location` is attempted destination (nullable), not previous location (non-nullable) | M-04/M-05 do not specify semantics |
| MC-2 | Movement | DeckerNavigationExtensions.kt `logonToLtg` | Throws on `OnLTG` current location | **M-06 supports design; code blocks it** |
| MC-3 | Movement | DeckerNavigationExtensions.kt, Grid.kt | `PLTG` not a subtype of `LTG`; dispatch architecturally impossible | **M-11 supports design intent** |
| MC-4 | Movement | DeckerNavigationExtensions.kt `jackInToLtg` | No RTG tally propagation; helper absent | **M-09 supports design** |
| MC-5 | Movement | DeckerNavigationExtensions.kt `logonToRtg` | Only parent RTG allowed from `OnLTG` | **M-06 supports code** |
| MC-6 | Movement | DeckerNavigationExtensions.kt `logonToHost` | No explicit tier guard; relies on data model | M-13 supports design |
| MC-7 | Creation | GridLoader.kt | Reads key `hosts`, not design's `host_files` | PRD supports per-host files |
| MC-8 | Creation | HostLoader.kt | `security_sheaf` expected as map; design specifies flat list | PRD silent on format |
| MC-9 | Creation | HostLoader.kt | All step field names differ from design spec | PRD silent on field names |
| MC-10 | Creation | HostLoader.kt | Topology value `open-access` causes load crash | PRD silent on format |
| MC-11 | Creation | GridLoader.kt | RTG PLTGs attached only to first LTG; comment contradicts code | **M-11/M-15 support design** |
| OP-1 | Operations | DeckerOperationsExtensions.kt `locateDecker` | Sleaze taken as external parameter defaulting to 0 | Design is authoritative |
| OP-2 | Operations | DeckerOperationsExtensions.kt `locateDecker` | Undocumented floor-2 on sensor TN | Neither design nor PRD specifies floor here |
| OP-3 | Operations | DeckerOperationsExtensions.kt `tapComcall` | Scanner test is one-sided; design says opposed | Design says "Opposed"; PRD silent |
| OP-4 | Operations | SystemOperation.kt `SWAP_MEMORY` | `CONTROL`/`ONGOING` vs. PRD `None`/`Simple` | **PRD directly contradicts code** |
| OP-5 | Operations | SystemOperation.kt | `EDIT_SLAVE` has no PRD backing | **PRD does not list it** |
| OP-6 | Operations | SystemTestResolver.kt | `totalBonusForDuration()` not in design | Design is authoritative |
| OP-7 | Operations | SystemTestResolver.kt | Undocumented floor-2 on `computerSkill` TN | PRD specifies no floor |
| OP-8 | Operations | SystemTestResolver.kt | Code behavior correct; comment and docstring contradict operations.md ordering | Code correct; comments wrong |
| OP-9 | Operations | OperationResult.kt `LocateResult.Located` | `target: LocatedTarget` sealed class vs. design's `target: Any` | Code improves on design |
| OP-10 | Operations | All resolver call sites | `host.securityRating.value` vs. design's `host.securityValue` | PRD: SV and Security Rating are distinct concepts |
| GL-1 | Game Loop | IC action classes | Design shows `SecurityCode` argument; code passes `Int` | Design doc internally inconsistent |
| GL-2 | Game Loop | IC action classes | Design shows 2-arg call; code/signature require 3 | Design is missing an argument |
| GL-3 | Game Loop | Ripper.action() | Extra MPCP test on attribute-zero not in design | PRD silent |
| GL-4 | Game Loop | Probe.action() | Conditional `if (tallyPoints > 0)` vs. unconditional design | **ICC-03 supports code** |
| GL-5 | Game Loop | Game.kt `availableActions()` | Grid context missing `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC` | PRD supports broader grid list |
| GL-6 | Game Loop | Game.kt `runCombatTurn` | Utility upload timers advanced; not in design | Game loop spec silent |
| GL-7 | Game Loop | Game.kt `runOutOfCombatTurn` | Defaults to 1 action when persona is null | PRD requires persona |
| GL-8 | Game Loop | TurnCoordinator.kt `checkTriggers` | Non-escalating alert transitions silently dropped | PRD supports applying any transition |
| GL-9 | Game Loop | Decker.kt, TurnCoordinator.kt | Interrogation states duplicated in TurnCoordinator | **PRD assigns them to Decker only** |
| UI-1 | UI/Protocol | AvailableActionDto.kt | `paramKind` values don't match design_ui.md TypeScript union | **PRD supports code values** |
| UI-2 | UI/Protocol | AvailableActionDto.kt | LOCATE_FILE/SLAVE query input not signaled | **PRD supports code** |
| UI-3 | UI/Protocol | SessionRegistry.kt | Missing token silently allows reconnect; security gap | Protocol.md requires rejection; PRD silent |
| UI-4 | UI/Protocol | SessionRegistry.kt | Wrong token returns `BAD_REQUEST` not `name_already_taken` | **PRD supports code; protocol.md inconsistent** |
| UI-5 | UI/Protocol | MatrixServer.kt | `defaultResource("index.html")` absent; SPA deep-links will 404 | PRD silent |
| UI-6 | UI/Protocol | protocol.md | MAKE_COMCALL absent from protocol.md params table | **PRD supports code; documentation gap only** |
