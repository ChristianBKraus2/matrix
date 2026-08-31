# Design vs. Implementation Discrepancies

**Generated:** 2026-08-30 | **Updated:** 2026-08-31  
**Scope:** Full codebase audit — design documents (excluding PRDs) vs. Kotlin implementation, with PRD annotations  
**Areas covered:** Combat, Cyberdeck/Programs, Movement/Creation, Operations, Game Loop, UI/Protocol  
**Note (2026-08-31):** CP-1, GL-5, GL-7, GL-9, UI-3 confirmed resolved and removed. GL-8 and UI-5 descriptions corrected. NEW-1 added. Second pass verification completed same day.

**Legend:**  
✅ **RESOLVED** — code and design now agree  
🟡 **DESIGN-ONLY** — code is correct; only the design document needs updating  
❌ **OPEN** — code still needs a fix

---

## 1. Combat

### C-1 — `resolveCrippler` / `resolveRipper` / `resolveSlow` signature mismatch

**Status:** 🟡 DESIGN-ONLY

**Design says:** All three methods accept `securityCode: SecurityCode` as their third parameter and convert it internally via `sv = securityValue(securityCode)`.

**Code does:** All three accept `securityValue: Int` directly. Callers pass `context.host.securityRating.value`.

**PRD verdict:** CC-15 requires the security value but does not mandate where the SecurityCode-to-Int conversion must occur. Behavior is equivalent; this is an API surface divergence only.

**Todo:** Change the design

---

### C-2 — Missing IC rating boost on persona crash (Lethal Black IC step 7)

**Status:** ✅ RESOLVED — `IcDamageResult` now has `personaOnlyCrashed`; `resolveLethalBlackIc` sets it when persona CM crashes but physical CM survives; `LethalBlackIC.action()` replaces the IC with a +2 rated copy via `withRatingBonus`.

**Design says:** If the icon crashes before the decker dies, the IC's effective rating is set to `ic.rating + 2` for all subsequent tests. `IcDamageResult` must carry enough information for the caller to track this state.

**Code does:** `IcDamageResult` has no field tracking a persona-only (non-lethal) crash. `resolveLethalBlackIc` never computes or reports the `+2` boost. No mechanism exists anywhere in the code to communicate this state to the caller.

**PRD verdict:** ICC-11 references the rule but the rating-boost detail after a persona-only crash is not reproduced in the PRD text. The design explicitly mandates it; the code silently omits it.

**Todo:** Update the design

---

### C-3 — Black IC pin guard in `applyIcDamage` adds undocumented null-check

**Status:** ✅ RESOLVED — guard removed; any Black IC hit now updates the pin regardless of prior pin state.

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

**Status:** 🟡 DESIGN-ONLY

**Design says:** `data class TrackState(val trackingIcRating: Int, val locationCycleTurnsRemaining: Int)`

**Code does:** The call site passes two additional fields: `opponentSensorRating = trackRating` and `trackerMcpRating = trackRating`.

**PRD verdict:** CC-33 specifies only the cycle-turn calculation, not the data class shape. The extra fields are undocumented.

**Todo:** Update the design

---

### C-5 — `resolveAttack` step text uses undefined field name `utilityRating`

**Status:** 🟡 DESIGN-ONLY — code uses `attackDicePool`/`weaponPower` (correct).

**Design says (steps 4 and 6):** References `attacker.utilityRating` and `attacker.utilityRating + attacker.hackingPool`. The `AttackParticipant` data class defined in the same design document has no such field; it has `weaponPower` and `attackDicePool`.

**Code does:** Uses `attacker.weaponPower` and `attacker.attackDicePool`, matching the data class definition.

**PRD verdict:** CC-23 and CC-27 confirm the code is correct. The design's step-text contains an internal inconsistency; the code is right.

**Todo:** Update the design

---

### C-6 — `applyIcDamage` calls `ConditionMonitor.applyDamage(Int)` instead of `applyDamage(DamageLevel)`

**Status:** ✅ RESOLVED — now calls `applyDamage(attack.stagedDamageLevel)` where `stagedDamageLevel: DamageLevel`.

**Design says:** `ConditionMonitor` defines exactly one signature: `fun applyDamage(damage: DamageLevel): ConditionMonitor`.

**Code does:** ~~Passes `stressBoxes: Int` (value 0 or 1) directly to `applyDamage`.~~

**PRD verdict:** CC-30 specifies the 10-box track but not the method signature. The design's single-signature spec is the authority.

---

### C-7 — `suppressIc` location check is too broad

**Status:** ✅ RESOLVED — `suppressIc` now takes a `host: Host` parameter and verifies `onHost?.host == host`; all test callers updated.

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

**Status:** 🟡 DESIGN-ONLY — code correctly uses `ic.rating * 2`.

**Design spec says:** Final MPCP attack uses `ic.rating * 2` (double rating).

**Design verification table says:** "Final MPCP attack at `ic.rating` before disconnect" (single rating).

**Code does:** Uses `ic.rating * 2`, matching the spec and contradicting the table.

**PRD verdict:** ICC-12 does not specify the multiplier. The spec (double) is authoritative; the table entry is erroneous. The code is correct; the internal design inconsistency is noted.

**Todo:** Update the design.

---

### C-9 — `resolveLethalBlackIc` / `resolveNonLethalBlackIc` inline MPCP test instead of delegating

**Status:** 🟡 DESIGN-ONLY — inline logic is functionally equivalent.

**Design says:** Both methods should delegate to `resolveBlasterMpcpTest(decker, ic, diceRoller, ratingOverride = ic.rating * 2)`.

**Code does:** Both methods copy the `reduceMcpRating` logic inline. Functionally equivalent.

**PRD verdict:** ICC-11 has no behavioral difference from this structural deviation. Deviation affects testability and single-source-of-truth only.

**Todo:** Update the design

---

## 2. Cyberdeck / Programs

### CP-2 — `invokeMedic()` and `MedicResult` file placement

**Status:** 🟡 DESIGN-ONLY

**Design says:** Both must live in `Decker.kt`.

**Code does:** `invokeMedic()` is in [DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt); `MedicResult` is in its own [MedicResult.kt](src/main/kotlin/com/shadowrun/matrix/decker/MedicResult.kt).

**PRD verdict:** No PRD rule mandates file location. Functionally equivalent.

**Todo:** Update the design

---

### CP-3 — `relocateIcon()` resolution mechanic bypasses `SystemTestResolver`

**Status:** ✅ RESOLVED — `relocateIcon` now calls `SystemTestResolver.resolve` with `RELOCATE_ICON` / `host.control` / `host.securityRating`; custom dual-roll removed; signature changed to `(host, diceRoller)`.

**Design says:** Route `RELOCATE_ICON` through `SystemTestResolver` as a Control Test (CD-16).

**Code does:** Bypasses `SystemTestResolver` entirely and implements a custom dual-roll opposed test: decker rolls `computerSkill` dice vs. `max(2, opponentSensor - relocateRating)`; tracker rolls `trackerMcpRating` dice vs. `max(2, relocateRating)`. Neither the PRD nor the design describes an opponent "tracker MCP Rating" roll.

**PRD verdict:** CD-16 names it a Control Test but does not define the opponent mechanic. The design generalizes it as a `SystemOperation`; the code uses a wholly different resolution path.

**Todo:** Update the code

---

### CP-4 — `invokeMedic()` auto-unload threshold uses `<= 0` instead of `== 0`

**Status:** 🟡 DESIGN-ONLY

**Design says (step 5):** Trigger auto-unload when `medic.currentRating == 0`.

**Code does:** `if (newMedicRating <= 0)` — triggers for any non-positive value.

**PRD verdict:** CD-22 uses "zero-rating," implying `== 0`. The code is more defensive but deviates from the literal spec.

**Todo:** Update the design

---

### CP-5 — `Cyberterminal()` factory `costNuyen` parameter has no default

**Status:** 🟡 DESIGN-ONLY

**Design says:** `costNuyen: Int = 0` (optional with default).

**Code does:** `costNuyen: Int` — required parameter, no default.

**PRD verdict:** CT-05 neither mandates nor forbids a default.

**Todo:** Update the design

---

### CP-6 — `Cyberdeck` carries an undocumented `detectionFactor()` instance method

**Status:** 🟡 DESIGN-ONLY

**Design says:** The Cyberdeck changes section specifies only: `pendingUploads`, MPCP utility-rating init checks, `usedActiveMemoryMp`, and `freeActiveMemoryMp`. No `detectionFactor()` method is mentioned.

**Code does:** Adds `fun detectionFactor(maskingRating: Int, sleazeRating: Int? = null): Int` to `Cyberdeck`, with `Decker.detectionFactor` delegating to it.

**PRD verdict:** CD-18 defines the formula but not where it must live. The formula is correct; this is an undocumented structural addition.

**Todo:** Update design

---

### CP-7 — `Cyberdeck.init` has two capacity checks not specified by the design

**Status:** 🟡 DESIGN-ONLY

**Design says:** The only additions to `Cyberdeck.init` are MPCP utility-rating checks. Active memory capacity validation is the responsibility of `DeckerLoader` (CD-05).

**Code does:** Adds two `require` checks in `Cyberdeck.init`: `activeMp <= activeMemoryMp` and `storageMp <= storageMemoryMp`.

**PRD verdict:** CD-05 does not mandate a constructor check. These are extra defensive guards; the design placed this responsibility in the loader, not the constructor.

**Todo:** Update design

---

### CP-8 — `loadUtility()` treats `ioSpeedMpPerTurn = 0` as instant load

**Status:** 🟡 DESIGN-ONLY

**Design says:** `turnsRequired = ceil(utility.mpSize / cyberdeck.ioSpeedMpPerTurn)`. No handling for zero I/O speed is specified.

**Code does:** Special-cases `ioSpeedMpPerTurn <= 0`: logs a warning and sets `turnsRequired = 0`, making the load instant.

**PRD verdict:** CD-10 does not address a zero I/O speed cyberdeck. The code prevents a divide-by-zero crash but exceeds the design spec.

**Todo:** Update the design

---

## 3. Movement / Creation

### MC-1 — `LogonResult.Failure.location` — semantic inversion and type mismatch

**Status:** 🟡 DESIGN-ONLY — code's approach is more useful; design spec should be updated to match.

**Design says:** `Failure.location: MatrixLocation` (non-nullable) is the decker's unchanged *previous* location.

**Code does:** `Failure.location: MatrixLocation?` (nullable) is the *attempted destination* with the host-success security tally already baked in.

**PRD verdict:** M-04/M-05 do not dictate what `Failure.location` should represent semantically. The code's approach is arguably more useful but directly contradicts the design spec.

**Todo:** Update design

---

### MC-2 — `logonToLtg` throws when current location is `OnLTG`

**Status:** ✅ RESOLVED — `logonToLtg` now handles `OnPLTG` location (PLTG supports all LTG operations, M-08).

**Design says:** `logonToLtg` must accept `currentLocation is OnLTG` when the target is a PLTG attached to that LTG.

**Code does:** ~~Throws `IllegalStateException` on `OnLTG` current location.~~

**PRD verdict:** M-06 requires PLTG access from an LTG. PRD supports the design.

---

### MC-3 — `logonToLtg` dispatch to `logonToPltg` is architecturally impossible

**Status:** 🟡 DESIGN-ONLY — `PLTG` has a `parentLtg` back-reference; architectural constraint holds.

**Design says:** When the target is a `PLTG`, `logonToLtg` should dispatch to `logonToPltg`, assuming `PLTG` is a subtype of `LTG`.

**Code does:** In [Grid.kt](src/main/kotlin/com/shadowrun/matrix/network/Grid.kt), `LTG` and `PLTG` are siblings under `Grid`, not in a supertype/subtype relationship. The dispatch is unreachable.

**PRD verdict:** M-11 requires tally carry-over for PLTG entry. **PRD supports the design intent; the type model prevents the design's unified dispatch path from existing.**

**Todo:** Update the design and comment the PRD

---

### MC-4 — `jackInToLtg` does not propagate tally to parent RTG; `mergeRtgTally` helper is absent

**Status:** ✅ RESOLVED — `jackInToLtg` and `logonToLtg` now propagate the tally delta to `ltg.parentRtg` inside their `buildLocation` lambda.

**Design says:** The RTG security tally (M-09) is tracked on the LTG's parent RTG. The helper `mergeRtgTally(ltg, outcome)` encapsulates this propagation.

**Code does:** `jackInToLtg` updates only `ltg.securityTally`. The helper `mergeRtgTally` does not exist; no mechanism updates the parent RTG's tally.

**PRD verdict:** M-09 states the RTG carries a shared tally across its LTGs. **PRD supports the design; the code leaves the RTG tally permanently stale.**

**Todo:** Update Code

### MC-5 — `logonToRtg` from `OnLTG` rejects connected RTGs; design allows them

**Status:** 🟡 DESIGN-ONLY — PRD supports the code; design over-specifies.

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

**Status:** 🟡 DESIGN-ONLY

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

**Status:** 🟡 DESIGN-ONLY — `creation.md` already uses `hosts:` throughout; `grid.yaml` and `GridLoader` both use `hosts`. The discrepancy was in this document's description only.

**Design says:** LTG entries in YAML use `host_files:`.

**Code does:** [GridLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt) reads key `hosts`. A grid.yaml following the design spec would produce zero hosts because `data["host_files"]` is never read.

**PRD verdict:** PRD supports per-host YAML files. The loading key mismatch means neither the design format nor the PRD file-per-host pattern is honored.

**Todo:** Update code and design according to the PRD. Also Change the yaml files, if needed.

---

### MC-8 — `security_sheaf` is a flat list in design YAML, a nested map in code

**Status:** 🟡 DESIGN-ONLY

**Design says:** `security_sheaf:` is a YAML sequence (list) at the top level.

**Code does:** Casts `security_sheaf` as `Map<String, Any>`, expecting a wrapper object with a `trigger_steps` key. A design-format YAML (a list) would silently load an empty `SecuritySheaf()`.

**PRD verdict:** PRD specifies hosts carry a security sheaf but does not define the YAML format. Discrepancy is design vs. code only.

**Todo:** Update the design

### MC-9 — `security_sheaf` step field names all differ between design and code

**Status:** 🟡 DESIGN-ONLY

**Design says:** Step fields are `tally`, `ic`, `alert`, `security_deckers`. The `ic` field is a list of strings in `"TypeName-Rating"` format (e.g., `["Probe-5"]`).

**Code does:** Reads `tally_threshold`, `activated_ic`, `alert_transition`, `security_decker_count`, plus a mandatory `description` field not in the design. The `activated_ic` field expects a list of maps (`{type: probe, rating: 5, ...}`), not strings.

**PRD verdict:** PRD does not specify field names. Mismatch is entirely between design YAML spec and code parser.

**Todo:** Update the design

---

### MC-10 — Topology YAML value format: hyphen vs. underscore

**Status:** ✅ RESOLVED — `HostLoader` now normalises with `.uppercase().replace('-', '_')`.

**Design says:** `topology: open-access` (hyphenated).

**Code does:** ~~`"open-access".uppercase()` → `"OPEN-ACCESS"` → `TopologyType.valueOf("OPEN-ACCESS")` throws.~~ Now applies `.replace('-', '_')` before `valueOf`.

**PRD verdict:** PRD does not specify YAML values. Discrepancy is design vs. code only.

---

### MC-11 — RTG-level PLTGs attached to first LTG only; code comment contradicts implementation

**Status:** ✅ RESOLVED — `GridLoader.buildRtg` now iterates all LTGs via `ltgs.map { ... }` to attach PLTGs.

**Design says:** PLTGs should be connected to all LTGs of that RTG.

**Code does:** ~~Attaches PLTGs only to `ltgs.first()`.~~ Now attaches to every LTG.

**PRD verdict:** M-11/M-15 imply PLTG reachability. PRD supports the design intent.

---

## 4. Operations

### NEW-1 — `analyzeIcon` subtracts persona sensor from Control TN before resolver

**Status:** ✅ RESOLVED — sensor pre-subtraction removed; `analyzeIcon` now passes `host.subsystemRatings.control` directly to `SystemTestResolver`.

**Design says:** `ANALYZE_ICON` is a System Test against the host's Control subsystem rating, consistent with all other analyze operations.

**Code does:**
```kotlin
val tn = host.subsystemRatings.control - (persona?.sensor ?: 0)
val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_ICON, tn, ...)
```
The TN is reduced by the persona's `sensor` attribute before being passed to the resolver. No other analyze operation does this.

**PRD verdict:** PRD intent is that Analyze utility reduces TN per CD-14; this pre-subtraction bypasses the standard path and is undocumented. **PRD neither supports the design as written nor the code's specific mechanism.**

**Todo:** Reconcile with CD-14 — either route through the standard utility-reduction path or document the sensor pre-reduction explicitly in the design and PRD.

---

### OP-1 — `locateDecker` sleaze rating source

**Status:** ✅ RESOLVED — `sleazeRating: Int = 0` added to `Persona`; `locateDecker` now reads `targetPersona.sleazeRating`; external `targetSleazeRating` parameter removed.

**Design says:** Read sleaze from the persona object: `val sensorTn = targetPersona.masking + (targetPersona.sleaze?.currentRating ?: 0)`.

**Code does:** Accepts sleaze as a separate `targetSleazeRating: Int = 0` parameter. If a caller omits it, the TN is underestimated even when the target has a loaded Sleaze utility.

**PRD verdict:** MP-10 does not specify this TN formula. The design document is the authoritative source; the code's default-to-zero path is a behavioral divergence.

**Todo:** Update the code

---

### OP-2 — `locateDecker` sensor TN has an undocumented floor of 2

**Status:** 🟡 DESIGN-ONLY

**Design says:** No floor is specified for the sensor TN.

**Code does:** Enforces `maxOf(2, masking + targetSleazeRating)`.

**PRD verdict:** CD-14 specifies floor 2 only for utility-reduced target numbers; this test is not utility-reduced. The floor is undocumented and inconsistent with the design's own convention.

**Todo:** Apply a minimal TN of 2 in the PRD and design.

---

### OP-3 — `tapComcall` scanner test is not opposed

**Status:** 🟡 DESIGN-ONLY — one-sided roll is the code's chosen approach.

**Design says:** Resolve an *opposed* Computer Skill vs. scanner Device Rating test — both sides roll.

**Code does:** Only the decker rolls; the outcome is determined by `successes == 0`. The scanner never rolls.

**PRD verdict:** PRD does not detail the scanner mechanics at this level. The design document explicitly labels the test "Opposed."

**Todo:** Update the design and add a comment to the PRD.

---

### OP-4 — `SWAP_MEMORY` has wrong testType and category

**Status:** ✅ RESOLVED — `SWAP_MEMORY` now has category `STANDARD` (was `ONGOING`). `testType` retains `CONTROL` but no test is actually run for this operation.

**Design says (via PRD table):** Swap Memory: None, None, Simple — no test type, no utility, Simple action.

**Code does:** ~~`SWAP_MEMORY(CONTROL, null, SIMPLE, ONGOING)`~~ Now `SWAP_MEMORY(CONTROL, null, SIMPLE, STANDARD)`.

**PRD verdict:** PRD directly contradicts `ONGOING`; `STANDARD` is now correct. `testType = CONTROL` remains a minor documentation gap.

**Todo:** Update the design to note `testType` is set but unused.

---

### OP-5 — `EDIT_SLAVE` has no PRD backing

**Status:** 🟡 DESIGN-ONLY — both design and code include it; PRD needs updating.

**Design says:** Includes `EDIT_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED)`.

**Code does:** Same — includes `EDIT_SLAVE` in [SystemOperation.kt](src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt) and [DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt).

**PRD verdict:** The PRD operations table lists only `Control Slave` and `Monitor Slave`. `Edit Slave` does not appear. Both design and code add an operation with **no PRD backing**.

**Todo:** Update the PRD

---

### OP-6 — `NullOperationModifier` API mismatch

**Status:** 🟡 DESIGN-ONLY

**Design says:** The companion object exposes `forDuration(seconds): NullOperationModifier`. The resolver calls `.forDuration(inactivitySeconds).bonus`.

**Code does:** `val bonus = NullOperationModifier.totalBonusForDuration(inactivitySeconds)` — method name not in design.

**PRD verdict:** SO-13/SO-14 govern null operations but do not specify the modifier API. The design document is authoritative.

**Todo:** Update design

---

### OP-7 — `resolveScrambleDestructTest` applies undocumented TN floor

**Status:** 🟡 DESIGN-ONLY

**Design says:** Roll `ic.rating` dice vs. TN = `decker.computerSkill`. No floor mentioned.

**Code does:** `diceRoller.roll(ic.rating, maxOf(2, computerSkill))` — applies a floor of 2.

**PRD verdict:** CD-14 specifies floor 2 only for utility-reduced target numbers; this test is not utility-reduced.

**Todo:** Update Design and PRD

---

### OP-8 — `resolveInterrogation` code comment contradicts behavior

**Status:** ✅ RESOLVED — wrong KDoc description corrected; ordering is now consistently documented: utility reduction first, then query-precision modifier.

**Design says:** Apply `queryPrecision.modifier` *after* utility reduction.

**Code does:** ~~Comment stated "Apply precision modifier before utility reduction."~~ Comment and docstring now correctly describe the order.

**PRD verdict:** SO-07 specifies the modifiers but not their ordering.

---

### OP-9 — `LocateResult.Located` uses `target: LocatedTarget` sealed class instead of `target: Any`

**Status:** 🟡 DESIGN-ONLY — code improvement over the design spec.

**Design says:** `data class Located(val target: Any, val accumulatedSuccesses: Int)`.

**Code does:** `data class Located(val target: LocatedTarget, val accumulatedSuccesses: Int)` with a sealed hierarchy.

**PRD verdict:** Not covered by PRD. The code is strictly safer than the design spec; this is an improvement, not a regression.

**Todo:** Update the design

---

### OP-10 — All resolver call sites use `host.securityRating.value` instead of `host.securityValue`

**Status:** 🟡 DESIGN-ONLY

**Design says:** Throughout the resolver algorithms, the host dice pool is `host.securityValue`.

**Code does:** Every call site uses `host.securityRating.value`.

**PRD verdict:** PRD distinguishes Security Value (dice pool) from Security Rating (threshold tier). If the numeric values happen to coincide, behavior is equivalent — but the naming discrepancy signals a potential semantic difference.

**Todo:** Update design

---

## 5. Game Loop

### GL-1 — `Crippler.action()` / `Ripper.action()` pass wrong argument type to `CombatResolver`

**Status:** 🟡 DESIGN-ONLY — code passes `securityRating.value: Int` correctly.

**Design says:** Pass `context.securityCode: SecurityCode`.

**Code does:** Passes `context.host.securityRating.value: Int` — matches the actual signature. The design document shows the wrong parameter type.

**PRD verdict:** PRD does not specify parameter types. The design doc is internally inconsistent with the `CombatResolver` spec it defines.

**Todo:** Update the design

---

### GL-2 — `icAttackParticipant` call has two arguments in design, three in code

**Status:** 🟡 DESIGN-ONLY — three-argument call is correct.

**Design says:** `CombatResolver.icAttackParticipant(this, context.securityCode)` — two arguments.

**Code does:** Three arguments: `this`, `context.securityCode`, `context.host.securityRating.value`. The confirmed `CombatResolver` signature requires the third; the code is correct.

**PRD verdict:** Not covered by PRD. The design is missing an argument in its call-site documentation.

**Todo:** Update Design

---

### GL-3 — `Ripper.action()` adds an MPCP test on attribute-zero not present in the design

**Status:** 🟡 DESIGN-ONLY — additional MPCP test is present and correct per ICC-07.

**Design says:** After `resolveRipper`, call `context.updateDecker(target, result.updatedDecker)` — done.

**Code does:** Additionally checks if the targeted attribute reached 0 and calls `CombatResolver.resolveRipperMpcpTest(finalDecker, this, diceRoller)` before updating the decker.

**PRD verdict:** ICC-07 requires this MPCP test when an attribute is reduced to 0. PRD supports the code; the design omits this step.

**Todo:** Update design

---

### GL-4 — `Probe.action()` guards `addToSecurityTally` with `if (tallyPoints > 0)`; design calls it unconditionally

**Status:** 🟡 DESIGN-ONLY — conditional guard is correct per PRD.

**Design says:** `context.addToSecurityTally(tallyPoints)` — always called.

**Code does:** `if (tallyPoints > 0) context.addToSecurityTally(tallyPoints)` — skips when zero.

**PRD verdict:** ICC-03 states "successes added to tally immediately," which implies only positive values. **PRD supports the code's conditional.**

**Todo:** Update Design

---

### GL-6 — `runCombatTurn` advances utility upload timers; design does not mention this

**Status:** 🟡 DESIGN-ONLY — timer advancement is correct per CD-11/CC-33.

**Design says:** Four steps: roll initiative, action loop, decrement scores by 10, end when all scores ≤ 0. Nothing beyond.

**Code does:** After the while loop, iterates all deckers and calls `context.updateDecker(decker, decker.advanceCombatTurn())` (annotated with CD-11, CC-33).

**PRD verdict:** CD-11 requires upload counters to decrement each Combat Turn. PRD supports the code; game loop design omits this step.

**Todo:** Update Design

---

### GL-8 — `checkTriggers` skips same-state alert re-applications

**Status:** 🟡 DESIGN-ONLY — no behavioral impact.

**Design says:** "Applies any alert-status transition via `updateHost` (AL-01/AL-02)."

**Code does:**
```kotlin
if (transition != host.alertStatus) updateHost(applyAlertTransition(host, transition))
```
Only re-applying the exact same state is skipped. Downward transitions are applied normally.

**PRD verdict:** AL-01/AL-02 support applying any transition; they do not address same-state re-applications. No behavioral impact.

**Todo:** Update design to clarify that same-state re-application is a no-op by design.

---

## 6. UI / Protocol

### UI-1 — `paramKind` string values contradict the TypeScript type union

**Status:** 🟡 DESIGN-ONLY — PRD supports the code's identifiers.

**Design says ([design_ui.md](design/design_ui/design_ui.md)):** TypeScript union is `'precision' | 'passcode' | 'scanner' | 'edit' | null`.

**Code does ([AvailableActionDto.kt](src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt)):** Emits `"hasValidPasscode"`, `"scannerDeviceRating"`, `"newContent"`.

**PRD verdict:** PRD uses the code's identifiers. **PRD supports the code; design_ui.md needs updating.**

**Todo:** Update the design

---

### UI-2 — LOCATE_FILE and LOCATE_SLAVE query input not signaled to the client

**Status:** 🟡 DESIGN-ONLY — PRD supports the code.

**Design says ([design_ui.md](design/design_ui/design_ui.md)):** `LOCATE_FILE` and `LOCATE_SLAVE` must show an additional optional text input `SEARCH: [________]` mapped to `params.query`.

**Code does:** All three operations receive `paramKind = "precision"` only. No signal for the query text input.

**PRD verdict:** PRD mentions only precision for all three operations and is silent on a query input. **PRD supports the code.**

**Todo:** Update the design

---

### UI-4 — Wrong reconnect token returns `BAD_REQUEST`, not `name_already_taken`

**Status:** 🟡 DESIGN-ONLY — PRD supports the code.

**[protocol.md](design/protocol.md) says:** When the token is wrong, respond with `name_already_taken`.

**Code does:** Returns `ErrorCode.BAD_REQUEST`.

**PRD verdict:** UI-03 specifies `BAD_REQUEST` for a mismatch. **PRD supports the code. Protocol.md needs updating.**

**Todo:** Update the design

---

### UI-5 — `staticResources` uses `default()` (current Ktor API) instead of design's `defaultResource()`

**Status:** 🟡 DESIGN-ONLY — functional intent fulfilled.

**Design says ([design_ui.md](design/design_ui/design_ui.md)):** `staticResources("/", "static") { defaultResource("index.html") }`

**Code does ([MatrixServer.kt](src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt)):** `staticResources("/", "static") { default("index.html") }` — uses current Ktor API name.

**PRD verdict:** PRD does not address static-file serving configuration.

**Todo:** Update the design to use the current Ktor API name `default()`.

---

### UI-6 — MAKE_COMCALL absent from `protocol.md` params table

**Status:** 🟡 DESIGN-ONLY — docs gap only; code is correct.

**[protocol.md](design/protocol.md) says:** ActionCommand params table omits MAKE_COMCALL.

**Code does:** Correctly handles `hasValidPasscode` for MAKE_COMCALL.

**PRD verdict:** PRD lists MAKE_COMCALL with `hasValidPasscode`. PRD supports the code; protocol.md has an incomplete params table.

**Todo:** Update protocol.md

---

## Remaining Issues

### ✅ Previously Open — All code changes applied

All 8 previously ❌ OPEN items (C-2, C-3, C-7, CP-3, MC-4, MC-7, NEW-1, OP-1) have been resolved. MC-7 was reclassified as 🟡 DESIGN-ONLY after verifying `creation.md` already uses `hosts:`.

### 🟡 Design-Only — Documentation updates still required (26 items)

| ID | What to update |
|----|---------------|
| C-1 | Design: update method signature to `securityValue: Int` |
| C-4 | Design: add `opponentSensorRating` and `trackerMcpRating` to `TrackState` |
| C-5 | Design: replace `utilityRating` references with `attackDicePool` / `weaponPower` |
| C-8 | Design: fix table entry — double rating, not single |
| C-9 | Design: note MPCP test is inlined (not delegated) |
| CP-2 | Design: update file placement |
| CP-4 | Design: use `<= 0` instead of `== 0` |
| CP-5 | Design: remove default value for `costNuyen` |
| CP-6 | Design: document `detectionFactor()` on `Cyberdeck` |
| CP-7 | Design: document constructor capacity checks |
| CP-8 | Design: document zero-I/O-speed guard |
| MC-1 | Design: update `Failure.location` semantics |
| MC-3 | Design: note `PLTG`/`LTG` are siblings; update dispatch description |
| MC-5 | Design: remove `connectedRtgs` path (PRD supports code) |
| MC-6 | Design: add comment referencing data-model reliance |
| MC-8 | Design: update `security_sheaf` format to nested map |
| MC-9 | Design: update step field names to match code |
| OP-2 | Design + PRD: document floor-2 on sensor TN |
| OP-3 | Design: update scanner test to one-sided; add PRD comment |
| OP-4 | Design: note `testType = CONTROL` but unused for SWAP_MEMORY |
| OP-5 | PRD: add `EDIT_SLAVE` to operations table |
| OP-6 | Design: rename `forDuration()` to `totalBonusForDuration()` |
| OP-7 | Design + PRD: document floor-2 on scramble destruct TN |
| OP-9 | Design: use `LocatedTarget` sealed class instead of `Any` |
| OP-10 | Design: use `securityRating.value` instead of `securityValue` |
| GL-1 | Design: update arg type to `Int` |
| GL-2 | Design: add third argument to `icAttackParticipant` call |
| GL-3 | Design: add MPCP test step on attribute-zero |
| GL-4 | Design: add `if (tallyPoints > 0)` guard |
| GL-6 | Design: add timer advancement step to combat turn |
| GL-8 | Design: clarify same-state skip is intentional |
| UI-1 | Design: update `paramKind` values to match PRD/code |
| UI-2 | Design: remove `SEARCH` query input requirement |
| UI-4 | Protocol.md: change `name_already_taken` to `BAD_REQUEST` |
| UI-5 | Design: use `default()` not `defaultResource()` |
| UI-6 | Protocol.md: add MAKE_COMCALL to params table |

---

## Appendix: Quick-Reference Index

| ID | Status | Area | Summary |
|----|--------|------|---------|
| C-1 | 🟡 | Combat | Method signatures take `Int` not `SecurityCode` |
| C-2 | ✅ | Combat | Rating `+2` boost after persona-only crash absent |
| C-3 | ✅ | Combat | `blackIcPin == null` guard prevents pin update from second Black IC |
| C-4 | 🟡 | Combat | Two undocumented `TrackState` fields |
| C-5 | 🟡 | Combat | Design step-text references non-existent field `utilityRating` |
| C-6 | ✅ | Combat | `applyDamage(DamageLevel)` now used correctly |
| C-7 | ✅ | Combat | `suppressIc` checks any host, not same host |
| C-8 | 🟡 | Combat | Design table says single rating; spec and code use double |
| C-9 | 🟡 | Combat | MPCP test inlined instead of delegated |
| CP-2 | 🟡 | Cyberdeck | Wrong files per design |
| CP-3 | ✅ | Cyberdeck | `relocateIcon` bypasses SystemTestResolver |
| CP-4 | 🟡 | Cyberdeck | Auto-unload uses `<= 0` |
| CP-5 | 🟡 | Cyberdeck | `costNuyen` has no default |
| CP-6 | 🟡 | Cyberdeck | Undocumented `detectionFactor()` method |
| CP-7 | 🟡 | Cyberdeck | Extra `init` capacity checks |
| CP-8 | 🟡 | Cyberdeck | Zero I/O speed treated as instant load |
| MC-1 | 🟡 | Movement | `Failure.location` semantics differ |
| MC-2 | ✅ | Movement | `logonToLtg` now handles PLTG location |
| MC-3 | 🟡 | Movement | `PLTG`/`LTG` sibling constraint |
| MC-4 | ✅ | Movement | No RTG tally propagation |
| MC-5 | 🟡 | Movement | Only parent RTG allowed from `OnLTG` (PRD supports) |
| MC-6 | 🟡 | Movement | No explicit tier guard; relies on data model |
| MC-7 | 🟡 | Creation | GridLoader reads `hosts`, design says `host_files` |
| MC-8 | 🟡 | Creation | `security_sheaf` format mismatch |
| MC-9 | 🟡 | Creation | Step field names differ |
| MC-10 | ✅ | Creation | Topology hyphen normalisation added |
| MC-11 | ✅ | Creation | PLTGs now attached to all LTGs |
| NEW-1 | ✅ | Operations | `analyzeIcon` pre-subtracts sensor from TN |
| OP-1 | ✅ | Operations | `locateDecker` sleaze from external param |
| OP-2 | 🟡 | Operations | Undocumented floor-2 on sensor TN |
| OP-3 | 🟡 | Operations | Scanner test is one-sided |
| OP-4 | ✅ | Operations | `SWAP_MEMORY` category now `STANDARD` |
| OP-5 | 🟡 | Operations | `EDIT_SLAVE` has no PRD backing |
| OP-6 | 🟡 | Operations | `totalBonusForDuration()` not in design |
| OP-7 | 🟡 | Operations | Undocumented floor-2 on scramble destruct TN |
| OP-8 | ✅ | Operations | Wrong comment fixed |
| OP-9 | 🟡 | Operations | `LocatedTarget` sealed class vs design's `Any` |
| OP-10 | 🟡 | Operations | `securityRating.value` vs design's `securityValue` |
| GL-1 | 🟡 | Game Loop | Design shows `SecurityCode` arg; code passes `Int` |
| GL-2 | 🟡 | Game Loop | Design shows 2-arg call; code has 3 |
| GL-3 | 🟡 | Game Loop | Extra MPCP test in `Ripper.action()` |
| GL-4 | 🟡 | Game Loop | `if (tallyPoints > 0)` guard (PRD supports) |
| GL-6 | 🟡 | Game Loop | Timer advancement not in design |
| GL-8 | 🟡 | Game Loop | Same-state alert skip (no behavioral impact) |
| UI-1 | 🟡 | UI/Protocol | `paramKind` values differ from design union |
| UI-2 | 🟡 | UI/Protocol | LOCATE query input not signaled (PRD supports code) |
| UI-4 | 🟡 | UI/Protocol | `BAD_REQUEST` vs `name_already_taken` in protocol.md |
| UI-5 | 🟡 | UI/Protocol | `default()` vs `defaultResource()` API name |
| UI-6 | 🟡 | UI/Protocol | MAKE_COMCALL absent from protocol.md table |
