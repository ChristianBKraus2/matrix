---
# Maintainability Review — game_logic

## Summary

The game logic is generally well-structured: sealed classes are used idiomatically throughout, every public method has KDoc with PRD cross-references, and the separation between `CombatResolver` (pure dice math), `Decker` (state + operations), and `GameContext` (world state) is clear. The most significant maintainability problem is concentrated in two places: `CombatResolver.kt` contains several near-identical method pairs that have been copy-pasted rather than factored out, and `Decker.kt` has grown to ~1270 lines as an operations god class. Secondary concerns are a handful of magic numbers that encode game-rule constants inline, a few misleading names, and two parsing utility functions duplicated verbatim across config loaders.

---

## Findings

### [HIGH] `resolveLethalBlackIc` and `resolveNonLethalBlackIc` are ~90% duplicated
**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:279`
**Issue:** Both methods share identical code for computing `rawLevel`, `power`, `effectivePower`, rolling icon-defense successes, applying `iconStaged` to the persona condition monitor, setting `blackIcPin`, and the entire MPCP-on-kill block (the inner `if (dumpShockTriggered)` branch, lines 309-319 and 357-367, are character-for-character the same). The only real difference is that Lethal uses `body` + `physicalConditionMonitor` for the secondary resistance roll, while Non-Lethal uses `willpower` + `mentalConditionMonitor`. That difference can be expressed as two parameters.
**Recommendation:** Extract a private helper, e.g. `resolveBlackIcCore(decker, ic, securityCode, diceRoller, secondaryResistancePool: Int, applySecondaryDamage: (Decker, DamageLevel) -> Decker): IcDamageResult`, and call it from both public methods with a lambda that handles the physical vs. mental split. The MPCP kill-shot block should live only once inside that helper.

---

### [HIGH] `Decker.kt` is a 1270-line operations god class
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:51`
**Issue:** `Decker` is a single data class that directly implements every decker capability: grid navigation (5 logon methods), 6 analyze operations, 3 decrypt operations, 3 interrogation operations, 4 file/slave operations, 3 comcall operations, utility memory management (load/unload/swap/advance), medic, relocate icon, pointer chain resolution, icon perception, and buffered messaging. Adding or modifying any one capability requires navigating the entire file. The class is not large because its state is rich — the state is modest — it is large because all operation logic lives inline rather than in focused extension-function files.
**Recommendation:** Move operation families into separate files as extension functions: `DeckerMovement.kt` (logon/logoff), `DeckerAnalyzeOps.kt`, `DeckerFileOps.kt`, `DeckerSlaveOps.kt`, `DeckerCombatSupport.kt`. The data class itself and its computed properties (`hackingPool`, `detectionFactor`, `actionsPerTurn`) can stay in `Decker.kt`, keeping it under ~150 lines.

---

### [HIGH] `SystemTestResolver.effectiveRating` uses `immuneToDumpShock` as proxy for "is cyberterminal"
**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116`
**Issue:** The comment states "Cyberterminal users have all utility ratings reduced by 1 (CT-03)", but the implementation checks `deck.immuneToDumpShock`. That flag is also set for hitcher observers (ACC-03 comment in `Cyberdeck.kt`). Hitchers cannot operate the persona at all, so the distinction is moot in practice today — but if hitcher decks are ever tested independently, the flag would incorrectly apply the CT-03 penalty to them. More importantly, the code communicates the wrong invariant to readers: `immuneToDumpShock` does not mean "is a cyberterminal".
**Recommendation:** Add an explicit `val isCyberterminal: Boolean` property to `Cyberdeck` (defaulting to false) and check that instead. Document in the property KDoc that it triggers CT-03 utility degradation.

---

### [MEDIUM] `resolveCrippler` and `resolveRipper` are near-identical
**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:154`
**Issue:** Both methods roll `securityCode.securityValue` dice vs `decker.effectiveDetectionFactor`, roll the persona attribute vs `ic.rating`, compute `net / 2`, and build a `CripplerResult`. The only difference is the floor in `max(1, ...)` vs `max(0, ...)` for the new attribute value (Crippler floors at 1 to avoid zeroing an attribute; Ripper can reach 0).
**Recommendation:** Extract a private `resolveAttributeReduction(decker, icRating, securityCode, targetAttribute, minimumValue, diceRoller)` helper that both delegate to. The floor value is the single parameter that varies.

---

### [MEDIUM] `resolveTarBaby` and `resolveTarPit` are near-identical
**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:176`
**Issue:** Both methods perform the same opposed roll, the same branch on `icSuccesses >= utilitySuccesses`, the same utility-removal logic on the IC winning side, and the same sensor notice roll on the IC losing side. The `TarPit` variant also has `resolveTarPitMpcpTest` for a secondary effect, but the primary combat block is a copy-paste of `TarBaby`.
**Recommendation:** Extract `resolveTarEffect(decker, icRating, utility, diceRoller): TarBabyResult` and call it from both methods. The MPCP test remains specific to `TarPit`.

---

### [MEDIUM] MPCP-reduction boilerplate repeated in three methods
**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:195`
**Issue:** `resolveBlasterMpcpTest` (line 195), `resolveRipperMpcpTest` (line 219), and `resolveSparkyMpcpTest` (line 231) all compute `val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating` and `val reduction = successes / 2`, then update `mcpRating` via a copy. Sparky adds `+ 2` to `tn`; everything else is identical.
**Recommendation:** Extract a private `applyMpcpReduction(decker, ic, tnOffset: Int = 0, diceRoller): Decker` helper. The three public methods become one-liners.

---

### [MEDIUM] `parseSecurityRating` and `parseSubsystemRatings` duplicated in `GridLoader` and `HostLoader`
**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:135` and `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:182`
**Issue:** Both private methods are character-for-character identical in both loader objects. Any change to the YAML format for security ratings or subsystem ratings must be made in two places.
**Recommendation:** Move both parsing functions into a shared `ConfigParsers.kt` internal object (or top-level internal functions in the `config` package) and call them from both loaders.

---

### [MEDIUM] `controlSlave` manually reconstructs the System Test instead of calling `SystemTestResolver`
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:883`
**Issue:** The method manually looks up the Spoof utility, computes the effective TN, rolls decker and host dice, and assembles a `SystemTestOutcome` — duplicating the logic that already lives in `SystemTestResolver.resolve()`. This happened because `controlSlave` sometimes uses `effectiveSkill` instead of `computerSkill`, and `SystemTestResolver.resolve()` hardcodes `decker.computerSkill`. The result is that security-logging, the `deckerWins` tie-break rule, and utility-rating adjustment can drift between the two code paths.
**Recommendation:** Add an overload `SystemTestResolver.resolve(..., overrideSkillDice: Int? = null)` that uses `overrideSkillDice ?: decker.computerSkill`. Then `controlSlave` delegates to it like every other operation.

---

### [MEDIUM] `relocateIcon` and `makeComcall` use a variable named `fakeOutcome`
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1149` and `:1084`
**Issue:** `val fakeOutcome = SystemTestOutcome(...)` appears in two places. The name signals that `OperationResult` is the wrong return type for these operations — Relocate Icon is a peer-vs-peer contest with no actual system test, and `makeComcall`'s passcode fast-path bypasses all dice. The smell will grow as callers inspect `outcome.hostSuccesses` or `outcome.deckerSuccesses` and get meaningless zeros.
**Recommendation:** For `relocateIcon`, introduce a dedicated `RelocateResult` sealed class. For the `makeComcall` passcode shortcut, either return a `MonitoredOperationHandle` directly (with no `OperationResult` wrapper) or document clearly that the returned `outcome` is synthetic. Rename the variable to `syntheticOutcome` at minimum.

---

### [MEDIUM] `resolvePointerChain` simulates 1D6 via modulo of an exploding-die result
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1017`
**Issue:**
```kotlin
val chainLength = diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 } // 1D6
```
`DiceRoller.roll` uses exploding d6 dice: a die result can be 7, 8, 13, etc. because 6s cause an extra roll. `result % 6 + 1` on an exploded value maps 7→2, 8→3, 13→3, 14→4 — the distribution is not uniform over 1–6. Values 2–6 are reachable from both unexploded and exploded dice, so they appear more often than 1. The inline comment `// 1D6` acknowledges the intent but not the error.
**Recommendation:** Add a `DiceRoller.rollPlainD6(): Int` method that calls `random.nextInt(1, 7)` directly (no exploding), and use it here.

---

### [MEDIUM] Magic numbers for initiative reduction and interrogation success thresholds
**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:25`, `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:755`
**Issue:** `state.currentInitiative - 10` in `Game.runCombatTurn` and the thresholds `>= 5` (locate file, locate access node) and `>= 3` (locate slave) in the interrogation methods are bare rule-table constants. They will be mystifying to anyone unfamiliar with SR2 without a comment or constant name.
**Recommendation:** Define named constants: `INITIATIVE_REDUCTION_PER_PASS = 10`, `LOCATE_SUCCESS_THRESHOLD = 5`, `LOCATE_SLAVE_SUCCESS_THRESHOLD = 3` (in a `MatrixRules` or `GameConstants` object). Same applies to the Medic TN breakpoints (3→TN4, 6→TN5) in `invokeMediac`.

---

### [LOW] `invokeMediac` is a misspelling of `invokeMedic`
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:974`
**Issue:** The method is named `invokeMediac` but the utility it invokes is `UtilityType.MEDIC`. "Mediac" does not appear elsewhere in the codebase or the Shadowrun ruleset.
**Recommendation:** Rename to `invokeMedic`.

---

### [LOW] `BufferedMessage.deliverAtEndOfTurn` is an always-true dead property
**File:** `src/main/kotlin/com/shadowrun/matrix/operations/BufferedMessage.kt:17`
**Issue:** The property's own KDoc says "Always true". It serves no discriminating purpose and will mislead future readers into assuming a `false` path exists.
**Recommendation:** Remove the property. The delivery timing is an invariant of the type, not configurable data — capture it only in the class-level KDoc.

---

### [LOW] `ConditionMonitor` exposes both `isDestroyed` and `isCrashed` for the same condition
**File:** `src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt:24`
**Issue:** `isCrashed` is literally `get() = isDestroyed`. The codebase uses `isCrashed` almost exclusively in combat code, making `isDestroyed` effectively dead. Having two names for the same Boolean adds unnecessary cognitive load when auditing damage-tracking logic.
**Recommendation:** Remove `isDestroyed` and use `isCrashed` everywhere, or vice versa. Add a KDoc to the surviving property explaining that "crashed" and "destroyed" are equivalent in this model.

---

### [LOW] `logonToPltg` silently reuses `LOGON_TO_LTG` with no explanation
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:241`
**Issue:** `performLogon(operation = SystemOperation.LOGON_TO_LTG, ...)` is passed for a PLTG logon, while `SystemOperation` has no `LOGON_TO_PLTG` entry. The choice is silent: a reader will wonder whether this is intentional or a copy-paste oversight.
**Recommendation:** Add a comment: `// PLTG logons use the same Access Test as LTG per PRD M-06` and consider adding a `LOGON_TO_PLTG` enum entry aliased to the same parameters as `LOGON_TO_LTG` for clarity.

---

### [LOW] `DeckerExtensions.asDefenderParticipant` performs unsafe casts with no precondition
**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:7`
**Issue:** `persona!!` will throw `NullPointerException` and `currentLocation as MatrixLocation.OnHost` will throw `ClassCastException` if called for a decker not currently on a host. Other methods in `Decker.kt` pair `!!` with an explicit `check(persona != null)` guard that produces a meaningful message; this extension does not.
**Recommendation:** Add `require(persona != null) { "asDefenderParticipant requires a jacked-in decker" }` and `require(currentLocation is MatrixLocation.OnHost) { "asDefenderParticipant requires the decker to be on a host" }` before the property accesses.

---

### [LOW] `performLogon` persona initialization repeats `firstOrNull` lookup four times
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1222`
**Issue:** The block that constructs a new `Persona` on first logon contains four identical `cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.XXX }?.rating ?: 0` calls, one per attribute. The pattern also uses fully-qualified type names (`com.shadowrun.matrix.common.PersonaAttributeType`) instead of an import, suggesting the imports were forgotten.
**Recommendation:** Add a `Cyberdeck.buildInitialPersona(baseReaction: Int): Persona` factory method that encapsulates the attribute lookup. Fix the missing imports.

---

### [INFO] `@Suppress("UNCHECKED_CAST")` appears nine times across config loaders
**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt`, `HostLoader.kt`, `DeckerLoader.kt`
**Issue:** The SnakeYAML `load<Map<String,Any>>` approach requires broad unchecked casts throughout both loaders. This is suppressed per-call rather than systematically. If the YAML structure is ever wrong, the cast will succeed but produce a `ClassCastException` at the field access site, far from the actual parse point.
**Recommendation:** Consider adding an inline validation helper `Map<String,Any>.requireString(key)`, `requireInt(key)`, etc. that fails with a useful message at parse time. This reduces the cast surface and eliminates most `@Suppress` annotations without requiring a new YAML library.

---

## Clean Areas
- `Game.kt` — short and focused; initiative loop is readable
- `GameContext.kt` — clear single responsibility; `checkTriggers` and `applyDeckerOperationResult` are well-named
- `ActionResult`, `ActiveIcon`, `ActiveIconState` — minimal and clean contracts
- `SystemOperation` enum — parameters are self-documenting; enum entries follow a clear naming convention
- `DiceRoller` — well-encapsulated; exploding-die mechanic is clearly implemented
- `Host`, `Node`, `SAN`, `DataFile`, `MatrixLocation` — simple immutable value types, no logic leaking in
- `AlertTransitions.kt` — one pure function, clearly documented per rule reference
- `DamageLevel.boxes` extension — avoids magic numbers for damage box counts
- `CombatModifiers` — the `init` constraint guard is a good use of require
- `Persona.attribute` / `withAttribute` — clean bidirectional dispatch on `PersonaAttributeType`
- `Cyberdeck.init` — thorough validation of MPCP constraints, memory limits, and rating caps
- `SecuritySheaf` / `TriggerStep` — clean data model with no hidden logic
- `IC` sealed hierarchy — `findTarget` / `moveIfNeeded` are correctly factored into the base class
- `LocateResult`, `SensorTestResult`, `IcDetectionResult` — sealed return types are expressive and complete
---
