# Maintainability Review — game_logic

## Summary

The game_logic component is well-structured at the macro level: small, focused value types dominate the domain model, enums are clean, and the Game/GameContext split is appropriately lean. The main maintainability pressure comes from two sources. First, `Decker.kt` has grown into a 1 285-line god class that owns navigation, every Matrix operation, memory management, combat perception, and utility lifecycle — far too many responsibilities for a single `data class`. Second, `CombatResolver.kt` repeats the same structural pattern four or five times across Black IC variants, Mpcp-test methods, and Tar-type IC, producing ~200 lines of near-identical code. Beyond those two hotspots the file is in good shape: naming is generally accurate to Shadowrun rules, PRD cross-references are consistently present, and the sealed-class hierarchy is well chosen throughout.

---

## Findings

---

### [HIGH] Decker is a 1 285-line god class

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`  
**Issue:** `Decker` is a `data class` that simultaneously holds persona/location state and implements ~25 distinct public operations spanning five unrelated concerns: Matrix navigation (`jackInToLtg`, `logonToRtg`, `logonToHost`, …), file/slave/comcall operations, memory management (`loadUtility`, `advanceCombatTurn`), Matrix perception (`noticeIcon`, `noticeTriggeredIc`), and an ad-hoc private helpers section. A reader trying to understand one concern must mentally skip over four others. Refactoring or testing any single operation requires importing the full 1 285-line class.  
**Recommendation:** Extract operations into extension-function files (one per concern group) similar to the existing `DeckerExtensions.kt` pattern. `Decker` itself should carry only state fields and the handful of derived properties (`hackingPool`, `detectionFactor`, `actionsPerTurn`). Navigation functions, operation functions, and memory-management functions each fit naturally in their own file under `decker/`.

---

### [HIGH] resolveLethalBlackIc / resolveNonLethalBlackIc duplicate ~80 lines

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:279–372`  
**Issue:** The two methods share identical code for: computing `rawLevel` (same BLUE/GREEN/ORANGE/RED branch), computing `power`/`effectivePower`, rolling `iconDefSuccesses`, staging `iconStaged`, applying `newCm`, detecting `dumpShockTriggered`, and performing the final MPCP attack at double rating. The only behavioural difference is the secondary damage target: lethal uses `decker.body` against `physicalConditionMonitor`; non-lethal uses `decker.willpower` against `mentalConditionMonitor`.  
**Recommendation:** Extract a private `resolveBlackIcAttack(decker, ic, securityCode, diceRoller, secondaryResistance: (Decker) -> Pair<Int, ConditionMonitor>): IcDamageResult` that accepts a lambda for the secondary-damage step, then call it from each public method with a one-line lambda.

---

### [HIGH] Sparky.action silently drops the body-damage step

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:151–157`  
**Issue:** `Sparky.action` calls `CombatResolver.resolveSparkyMpcpTest` and discards the returned `sparkySuccesses` with `_`. The follow-on function `CombatResolver.resolveSparkyBodyDamage` — which applies physical damage to the decker using those successes — is therefore never called from any IC action path, making it dead code and meaning Sparky never deals body damage in practice. This is the only Gray IC that silently omits a resolution step.  
**Recommendation:** Use the discarded value: `val (updated, sparkySuccesses) = CombatResolver.resolveSparkyMpcpTest(...)`, then call `CombatResolver.resolveSparkyBodyDamage(updated, this, sparkySuccesses, diceRoller)` and pass the result to `context.updateDecker`.

---

### [MEDIUM] resolveTarBaby / resolveTarPit are near-identical

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:176–263`  
**Issue:** Both methods share an identical structure: roll IC vs utility TN, roll utility vs IC TN, branch on `icSuccesses >= utilitySuccesses` — crash path filters `activeUtilities`, success path rolls `sensor`. The only structural difference is that `resolveTarPit` additionally has an `MpcpTest` variant. The 40-line shared body is duplicated in full.  
**Recommendation:** Extract the shared contest into a private `resolveTarContest(decker, icRating, utility, diceRoller): TarBabyResult` and call it from both public methods.

---

### [MEDIUM] resolveBlackHammer / resolveKilljoy are near-identical

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:376–406`  
**Issue:** Both methods follow the same pattern: compute `effectivePower`, apply icon damage to `newCm`, roll a resistance test, stage secondary damage, update the respective condition monitor, and set `dumpShockTriggered`. The only difference is `resolveBlackHammer` tests `body` against `physicalConditionMonitor` while `resolveKilljoy` tests `willpower` against `mentalConditionMonitor`.  
**Recommendation:** Same lambda-injection pattern as the Black IC recommendation above: extract a private helper and pass a secondary-damage lambda.

---

### [MEDIUM] resolveBlasterMpcpTest / resolveRipperMpcpTest are byte-for-byte identical

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:195–226`  
**Issue:** Both methods compute `tn = hardening + mcpRating`, roll IC vs TN, halve successes, and decrement `mcpRating`. The IC type parameter exists only to dispatch to a different public name; no actual behaviour differs.  
**Recommendation:** Collapse into one `resolveMpcpTest(decker, icRating, diceRoller): Decker` and call it from both `Blaster.action` and `Ripper.action` directly. The Sparky variant adds `+2` to TN so it stays separate.

---

### [MEDIUM] Interrogation operation pattern duplicated three times in Decker.kt

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:745–827`  
**Issue:** `locateFile`, `locateSlave`, and `locateAccessNode` all follow the same ~25-line pattern: retrieve `InterrogationState` from the map, call `SystemTestResolver.resolveInterrogation`, compare accumulated successes against a threshold, update the map, apply tally, and build the result pair. The only varying inputs are the `SystemOperation`, the threshold (`5` vs `3`), and the entity being located.  
**Recommendation:** Extract a private `performInterrogation(operation, host, threshold, precision, diceRoller, locate: (InterrogationState, Int) -> LocateResult): Pair<OperationResult, LocateResult>` and reduce each public method to a two-line call.

---

### [MEDIUM] controlSlave bypasses SystemTestResolver, re-implements the dice loop manually

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:905–916`  
**Issue:** Every other operation resolves via `SystemTestResolver.resolve`, which logs both rolls consistently and builds `SystemTestOutcome`. `controlSlave` manually calls `diceRoller.roll` twice and constructs `SystemTestOutcome` inline. This skips the resolver's structured logging and makes the inconsistency invisible to future callers who add observability to `SystemTestResolver`.  
**Recommendation:** Add a `SystemTestResolver.resolveWithCustomSkill(decker, operation, accessRating, securityValue, skillOverride, diceRoller)` overload, or pass the effective skill as an argument; then route `controlSlave` through the resolver.

---

### [MEDIUM] invokeMediac is a typo — should be invokeMedic

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:987`  
**Issue:** The method is named `invokeMediac` instead of `invokeMedic`. "Mediac" is not a Shadowrun term. The utility type it operates on is `UtilityType.MEDIC`. All call sites and tests must use the misspelled name, and an IDE search for `invokeMedic` returns nothing.  
**Recommendation:** Rename to `invokeMedic`. This is a straightforward rename-refactor.

---

### [MEDIUM] effectiveRating uses immuneToDumpShock as a cyberterminal proxy

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116–118`  
**Issue:** The comment correctly says CT-03 applies to cyberterminals, but the code checks `deck.immuneToDumpShock` to detect them. That flag is also set for hitcher observers (`Cyberdeck.immuneToDumpShock = true` in `Cyberterminal()` and in hitcher construction). If a future cyberdeck gains `immuneToDumpShock` for a different reason, its utility ratings will silently be penalised by 1.  
**Recommendation:** Add a dedicated `val isCyberterminal: Boolean` flag to `Cyberdeck` (set to `true` by the `Cyberterminal()` factory) and check that instead.

---

### [MEDIUM] Persona construction inline inside performLogon

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1235–1246`  
**Issue:** `performLogon` contains a 12-line `Persona(...)` construction that scans `cyberdeck.personaPrograms` four times with repeated `firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.X }?.rating ?: 0` expressions. This same lookup pattern is also used in the `detectionFactor` property. The construction logic is embedded inside a navigation helper, making it invisible to anyone looking for "how is a Persona built".  
**Recommendation:** Add `fun Cyberdeck.buildPersona(deckerReaction: Int): Persona` (or a method on `Cyberdeck`) that encapsulates the four attribute lookups. `performLogon` becomes a single call.

---

### [MEDIUM] GameContext uses System.err.println instead of a logger

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:32`  
**Issue:** The error path in `updateDecker` writes to `System.err` directly while every other class in the component uses `KotlinLogging`. This output bypasses log-level filtering, structured output, and any appender configuration set at runtime.  
**Recommendation:** Add `private val logger = KotlinLogging.logger {}` to `GameContext` and replace `System.err.println(...)` with `logger.error { ... }`.

---

### [LOW] resolveKiller / resolveBlaster / resolveSparky are one-line wrappers with no added behaviour

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:169, 192, 228`  
**Issue:** All three delegate unconditionally to `resolveAttack`. They add no logic, no preconditions, and no distinct logging. A maintainer adding new behaviour to, say, `resolveKiller` might miss that `resolveBlaster` calls the same underlying function and also needs updating.  
**Recommendation:** Either remove the wrappers and call `resolveAttack` directly from the IC action methods (the IC type is already apparent from the call site), or add a KDoc comment on each explaining the deliberate identity.

---

### [LOW] Magic numbers in analyzeHost success thresholds

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:633`  
**Issue:** The literal `7` (net successes required to reveal all host info) is a rule constant that appears with no named binding. Anyone changing or reading this code must know the rulebook to understand its meaning.  
**Recommendation:** Declare `private const val ANALYZE_HOST_FULL_REVEAL_THRESHOLD = 7` (and locate it near the method or in a rules-constants object).

---

### [LOW] Magic numbers in invokeMediac Medic TN thresholds

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:994–998`  
**Issue:** The literals `3`, `4`, `5`, `6` (damage-box boundaries and target numbers for the Medic utility) are unexplained inline integers.  
**Recommendation:** Named constants, e.g. `MEDIC_TN_LOW = 4`, `MEDIC_TN_MID = 5`, `MEDIC_TN_HIGH = 6` and boundary values `MEDIC_LOW_THRESHOLD = 3`, `MEDIC_MID_THRESHOLD = 6`.

---

### [LOW] Magic number 100 (word limit) in bufferMessage

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1190`  
**Issue:** `require(text.split("\\s+".toRegex()).size <= 100)` embeds a rule constant without a name.  
**Recommendation:** `private const val BUFFERED_MESSAGE_MAX_WORDS = 100`.

---

### [LOW] Magic number +2 in applyAlertTransition (Passive Alert subsystem boost)

**File:** `src/main/kotlin/com/shadowrun/matrix/network/AlertTransitions.kt:19–26`  
**Issue:** The `+ 2` applied to all five subsystem ratings on PASSIVE_ALERT is a rule-defined constant spelled out six times in the `copy` expression. If the rule ever changes, six literals must be updated.  
**Recommendation:** `private const val PASSIVE_ALERT_SUBSYSTEM_BOOST = 2` and use it in all five additions.

---

### [LOW] Convoluted 1D6 expression in resolvePointerChain

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1030`  
**Issue:** `diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 }` re-implements D6 semantics outside `DiceRoller` using modular arithmetic. `DiceRoller.roll` already counts successes, not face values, so the caller extracts the raw die face and wraps it manually. The `% 6 + 1` is both surprising and fragile (depends on the die-face range being 1–6, which is an internal `DiceRoller` detail).  
**Recommendation:** Add `DiceRoller.rollD6(): Int` (or `rollDie(faces: Int): Int`) so callers can request a plain face value without abusing the success-counting API.

---

### [LOW] Unimported qualified class names in Decker.kt

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:85, 1237–1246`  
**Issue:** `com.shadowrun.matrix.common.PersonaAttributeType` and `com.shadowrun.matrix.common.PersonaStatus` are referenced by their fully-qualified names in the class body rather than as imports at the top of the file. Every other `common` type in the same file is imported.  
**Recommendation:** Add `import com.shadowrun.matrix.common.PersonaAttributeType` and `import com.shadowrun.matrix.common.PersonaStatus` to the import block.

---

### [LOW] SecurityCode.securityValue private extension defined in the wrong file

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:509–514`  
**Issue:** `private val SecurityCode.securityValue` maps each code to its numeric value (3–6). This is a domain fact about `SecurityCode` that belongs on the enum in `Enums.kt` (or at least in a `SecurityCode`-adjacent file), not hidden as a private extension in `CombatResolver`. Future code that needs the same mapping cannot see it and will duplicate it.  
**Recommendation:** Move to `Enums.kt` or a dedicated `SecurityCode+Extensions.kt` file with `internal` visibility.

---

### [INFO] AlertStatus compared by ordinal rather than compareTo

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:55`  
**Issue:** `if (transition.ordinal > host.alertStatus.ordinal)` uses raw ordinal arithmetic. Ordinal semantics depend on declaration order in the enum, which is invisible at the call site.  
**Recommendation:** Use `if (transition > host.alertStatus)` — Kotlin enums implement `Comparable` by declaration order, so this is equivalent but expresses intent more clearly.

---

### [INFO] locateFile query field is always empty string

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:750, 756`  
**Issue:** `InterrogationState` is constructed with `query = ""` and no public API lets callers set a different query. The `host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }` match therefore always matches any file (empty string is a substring of everything). The `query` field in `InterrogationState` is currently dead.  
**Recommendation:** Either add a `query: String` parameter to `locateFile`/`locateSlave`/`locateAccessNode` and thread it into the state, or remove the `query` field from `InterrogationState` if name-based filtering is not yet intended.

---

## No Issues Found In

- `Game.kt` — clean, minimal; initiative loop is clear and well-contained.
- `GameContext.kt` — responsibilities are coherent (trigger stepping, host/decker sync); single `System.err` issue noted above but structure is sound.
- `ActionResult.kt`, `ActiveIcon.kt`, `ActiveIconState.kt` — appropriately minimal.
- `Matrix.kt`, `Node.kt`, `SAN.kt`, `RemoteDevice.kt`, `DataFile.kt`, `Accessory.kt` — tight, single-purpose value types.
- `SecuritySheaf.kt` / `AlertTransitions.kt` — logic is clearly commented with PRD references; the +2 literal noted above is the only nit.
- `Host.kt` — `init` constraint check is well-placed and clear.
- `SystemOperation.kt` — the enum table is dense but the four-property structure is self-explanatory and correctly mirrors the rules.
- `AvailableAction.kt`, `MatrixObject.kt`, `MatrixIcon.kt`, `PointerChain.kt` — sealed hierarchies are correctly scoped and named.
- `CombatInitiative.kt`, `CombatModifiers.kt`, `Combat.kt` — small and clear; `DumpShock` mapping from security code to damage level is explicit and readable.
- `Cyberdeck.kt` — `init` block validations are comprehensive and well-labelled.
- `Cyberterminal.kt` — factory function pattern is correct; constraint enforcement is clear.
- `Program.kt`, `PersonaProgram.kt`, `Utility.kt` — inheritance hierarchy is clean; the `ATTACK` multiplier special-case is commented.
- `DiceRoller.kt` — exploding-dice logic is correct and compact.
- `Enums.kt`, `SharedTypes.kt` — clean domain vocabulary with no surprises.
- `IC.kt` — sealed hierarchy maps well to rules taxonomy; `findTarget`/`moveIfNeeded` shared helpers eliminate duplication across all IC subclasses (except the Sparky body-damage gap noted above).
