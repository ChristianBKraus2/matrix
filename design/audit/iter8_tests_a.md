# Iteration 8 — Test-File Conformance Audit (batch A)

Rule 6 mandate: test files encode expected behaviour; a passing test with a wrong expectation,
an unsatisfiable stub, a trivial assertion, or a missing coverage of a known bug is a finding.
All files below were read in full from line 1 to last line via sequential `Read` (no Grep-substitute).

Spec sources consulted this session: `align.md` (§Methodology 1-2,5,6, Prohibited Patterns,
Per-File Checklist), `spec_baseline.md` (§System Test, §Combat, §Degradation, §IC),
`iter2_combat.md` (resolver formulas), and (for NullOperationModifier) the distilled
`iter2_operations.md` L25 which records design_core/operations.md L147-161.

## Coverage table

| File | Lines | Verbatim excerpts (prove full read) | Notes |
|---|---|---|---|
| `combat/CombatResolverTest.kt` | 1358 | **Opening (L56):** `private fun allFaces(face: Int, count: Int = 20) =` — DiceRoller(stubRandom(*IntArray(count){face})). **Middle (L708):** `assertEquals(3, result.reduction) // 6 successes/2 = 3 reduction`. **Closing (L1327):** `// tn = max(2, 0+1) = 2; IC 12 dice all show 5 → 12 successes → reduction=6 → max(0,1-6)=0`. | Every resolver test verified vs iter2_combat formulas. Stub math checked: `(6,1)` pairs produce exploding-7 successes; `allFaces(5)` produces successes only where TN≤5, no `allFaces(6)` (no infinite-loop stub). Two low-severity issues (D8T-1, D8T-2). |
| `combat/CombatTest.kt` | 24 | (L13) `val ds = DumpShock(SecurityRating(SecurityCode.ORANGE, 9))` · (L19-22) `DamageLevel.LIGHT, DumpShock(...BLUE,4).level` … `DEADLY … RED,10`. | DumpShock.power=SV and level map Blue=LIGHT/Green=MOD/Orange=SERIOUS/Red=DEADLY match spec (§Combat dump shock). No finding. |
| `common/SharedTypesTest.kt` | 34 | (L12) `val ratings = SubsystemRatings(access = 4, control = 5, index = 6, files = 7, slave = 8)` · (L30) `val after = cm.applyDamage(5)` … `assertEquals(10, after.damage)`. | SubsystemRatings.get per type; ConditionMonitor remaining/isDestroyed/cap-at-maxBoxes(10). Matches spec. No finding. |
| `programs/ProgramTest.kt` | 29 | (L12) `assertEquals(16, prog.mpSize)  // 4*4*1` · (L26-27) `assertEquals(4 * 4 * 2, lightAttack.mpSize)` … `4 * 4 * 5, deadlyAttack`. | mpSize=Rating²×mult; ANALYZE×3=27; Attack L×2/D×5 match §Utility-multipliers (Attack L/M/S/D ×2/3/4/5). No finding. |
| `utility/DiceRollerTest.kt` | 63 | (L34-35) `// die 1: rolls 6, then 3 → total 9; …` `DiceRoller(stubRandom(6, 3, 2))` · (L58) `assertFailsWith<IllegalArgumentException> { DiceRoller().roll(numberOfDice = 1, targetNumber = 1) }`. | Exploding-d6 (6→reroll, chains), success=face≥TN, invalid dice/TN throw. All stubs terminate 6-chains with a non-6. No finding. |
| `operations/NullOperationModifierTest.kt` | 60 | (L10-11) `assertEquals(NullOperationModifier.UNDER_TEN_SECONDS, NullOperationModifier.forDuration(0))` · (L56-58) `assertEquals(5, NullOperationModifier.totalBonusForDuration(86400))` … `assertEquals(6, …(129600))`. | Buckets <10→0/<60→1/<3600→2/else→4 and +1 per 12h beyond first (43200→still4 via L50 43199, 86400→5, 129600→6) match design_core/operations.md L147-161 (iter2_operations L25). The 2→4 jump is per spec, not a defect. No finding. |
| `ic/IcTest.kt` | 92 | (L18-23) `assertEquals(1, ic.initiativeDice(SecurityCode.BLUE))` … `4, ic.initiativeDice(SecurityCode.RED)` · (L84) `fun withConditionMonitor returns new IC with updated conditionMonitor preserving other fields`. | initiativeDice Blue1/Green2/Orange3/Red4 (§Combat CC-07); Probe REACTIVE WhiteIC; Crippler/Sparky/BlackIC PROACTIVE; Scramble is WhiteIC & can guard node; withConditionMonitor preserves rating. Matches spec. No finding. |
| `ic/IcBehaviorTest.kt` | 194 | (L40-41) `private fun allFaces(face: Int, count: Int = 40) = DiceRoller(stubRandom(*IntArray(count){face}))` · (L166-167) `assertFalse(result.dumpShockTriggered …)` `assertEquals(originalMcp, result.updatedDecker.cyberdeck.mcpRating, "MPCP must not be reduced …")`. | TarBaby/TarPit `action()` removeIc-on-win; decker-win keeps IC; Blaster/Sparky no-MPCP-reduction-without-crash. Stubs valid (win path: IC face5≥TN4; utility TN=icRating6 so face5 fails; decker-win path uses (6,1) pairs for utility=7). Tests only single `IC.action()`, never the `Game.runCombatTurn` loop — see D8T-3. No behavioural finding. |

Total files: **8**. Total lines read: **1854**.

## Findings

### D8T-1 — Attack-TN tests cite the stale PRD clause "CC-21" (low severity)
`combat/CombatResolverTest.kt:329` `fun resolveAttack Hit uses CC-21 table for Intruding in Blue TN=6`
and `:342` `fun resolveAttack Hit uses CC-21 table for Legitimate in Red TN=6`.
Verbatim (L329, L342): names/comments reference `CC-21 table`. `spec_baseline.md` §Combat states the
attack-TN table is **CC-24** (`Attack TN by security code × intruder/legit (CC-24 table)`), and
`iter2_combat.md` DOC-3 already flags that the design doc itself cites the table as both CC-21 and CC-24.
The **asserted values are correct** (BLUE/INTRUDING TN=6 asserting `attackerSuccesses=4`; RED/LEGITIMATE
TN=6). This is a stale-clause label in the test name only, mirroring doc finding DOC-3 — no wrong
runtime expectation.

### D8T-2 — `resolveSlow net 0` test comment is false; exercises 0-vs-0, not the IC-wins path it claims
`combat/CombatResolverTest.kt:1098-1110` `fun resolveSlow net 0 returns no effect`.
Verbatim (L1101-1103): `*IntArray(5) { 5 },  // IC dice succeed (sv=5 at TN=slowRating)` then
`*IntArray(6) { 1 },  // slow dice fail`. The comment "IC dice succeed" is false: DiceRoller success is
`face ≥ TN`, TN here is `slowRating = 6`, and a constant face **5 does not explode and does not reach 6**,
so the IC rolls **0** successes — not the positive tally the comment claims. The slow dice (face 1) also
score 0, so `net = slowSuccesses − icSuccesses = 0`. The assertion (`actionsLost=0`, `icInert=false`) passes,
but the test does **not** cover the intended "IC out-rolls Slow (net ≤ 0 with IC winning)" scenario — it is
a 0-vs-0 tie that passes trivially. Weak-coverage/misleading-stub defect (align.md "test assertion trivially
true by construction"). (Note: memory records GL-2 recalibrated the *positive* `resolveSlow` stub at L1085-1096,
which is correct; this net-0 case was not recalibrated.)

### D8T-3 — No coverage of D4G-3 / D4G-4 in the assigned test set (gap noted, lives elsewhere)
`ic/IcBehaviorTest.kt` is the only assigned file touching IC turn behaviour, but it invokes `IC.action(ctx, roller)`
**directly** (L110 `tarBaby.action(ctx, allFaces(5))`, L164/186 `CombatResolver.applyIcDamage(...)`) and never
drives `Game.runCombatTurn`. Therefore neither known real bug is covered here:
- **D4G-3** (displaced IC's `IcMoved` result discarded in `Game.runCombatTurn`, so a moved IC never persists its move / never attacks) — no test.
- **D4G-4** (crashed IC that already called `removeIc` can be re-selected and act again the same turn) — no test; the removeIc tests (L101-146) confirm removal but never re-run selection within the same turn.
Where such coverage *should* live: `game/GameTest.kt` and `integration/*CombatTest.kt` / `ICActivationTest.kt`
(they reference `runCombatTurn`/`removeIc`), none of which are in this batch. Recommend the D4G-3/D4G-4 gap be
recorded against the game-loop test files, not these unit tests.
