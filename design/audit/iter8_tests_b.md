# Iteration 8 — Test Files Audit (Batch B)

Rule 6 mandates test files be audited. Every file below was read in full via `Read` from line 1 to
its last line in this session (no Grep substitution). Line counts and verbatim excerpts prove coverage
(align.md Rule 2). Spec values checked against `spec_baseline.md`, `iter2_operations.md`,
`iter2_move_game.md`. Namespace continues the B-series: `D8TB-n`.

## Coverage Table

| File | Lines | Verbatim excerpts (copied tokens) | Notes |
|---|---|---|---|
| `decker/CyberdeckAndProgramMechanicsTest.kt` | 975 | (open) `fun \`CD-01 active utility rating exceeding MPCP is rejected\`() {` / `val u = Utility(UtilityType.ANALYZE, rating = 9) // 9 > MPCP 8` — (mid) `// Masking=6, Sleaze.currentRating=5 → ceil((6+5)/2) = 6` `assertEquals(6, d.detectionFactor)` — (close) `fun \`invokeMedic TN is 6 for 7-9 filled boxes\`()` `ArrayDeque(listOf(6, 1, 6, 1, 6, 1, 6, 1))` | DF formulas correct (⌈(M+S)/2⌉; ⌈M/2⌉ no Sleaze). RI cap ⌊MPCP/4⌋ correct. Medic [6,1] exploding stub correct. Cyberterminal −1 utility / MPCP≤4 / immune dump shock correct. Finding D8TB-6 (weak CD-14). |
| `decker/DeckerOperationsTest.kt` | 650 | (open) `private val winRoller = fixedRoller(5)` `private val loseRoller = fixedRoller(3)` — (mid) `// System Test wins (files=2); scanner TN = max(2, 8-0) = 8; face=3 < 8 → tap detected` — (close) `fun \`withUpdatedTally triggers Passive Alert when tally crosses sheaf threshold\`()` | Rollers calibrated (face=5 wins TN≤5 & DF=3, ties→decker; face=3 host-only). locateAccessNode ≥5 locate / ≥3 NotFound correct. Comcall valid-passcode skips test (never-called roller). No findings. |
| `decker/DeckerTest.kt` | 307 | (open) `assertEquals(4, decker.hackingPool)` (Int 6 + MPCP 6)/3 — (mid) `deck(mcpRating = 6, responseIncrease = 2)  // max = 6/4 = 1` — (close) `fun \`withUpdatedTally with 0 successes returns same instance\`()` `assertTrue(updated === d)` | HackingPool ⌊(Int+MPCP)/3⌋=4 ✓. DF with/without sleaze ✓. RI cap ✓. Program-sum ≤MPCP×3 ✓. effectiveDF floor 2 ✓. No findings. |
| `decker/DeckerVisibilityTest.kt` | 256 | (open) `private val probe = Probe(rating = 4, guardedNode = null)` — (mid) `assertTrue(SystemOperation.LOCATE_ACCESS_NODE in ops)` — (close) `fun \`availableActions on Host never includes LOCATE_DECKER\`()` `assertFalse(SystemOperation.LOCATE_DECKER in ops` | Grid vs host action filtering matches game.md L427-432. LOCATE_ACCESS_NODE on RTG (M-07) ✓. LOCATE_DECKER excluded ✓. RELOCATE_ICON not on grid ✓. No findings. |
| `decker/MovementTest.kt` | 663 | (open) `private fun easyRatings() = SubsystemRatings(4, 4, 4, 4, 4)` — (mid) `// Old tally (5) must NOT carry over; only new logon successes count (M-10)` — (close) `assertTrue(result.decker.interrogationStates.isEmpty(),` `"interrogationStates should be cleared after jackOut")` | Tally persistence M-09/M-10/M-11 correct. M-05 (host successes counted on failure) ✓. jackOut Black-IC pin throws ✓. logonToLtg sibling-hop rejected ✓. line 585 exact tally=6 (6 host dice face=4≥DF3). No findings. |
| `game/GameContextTest.kt` | 275 | (open) `private fun ratings(v: Int = 5) = SubsystemRatings(v, v, v, v, v)` — (mid) `ctx.checkTriggers(0, 4)` `assertEquals(baseAccess + 2, ctx.host.subsystemRatings.access)` — (close) `fun \`addToSecurityTally rejects negative points\`()` | PASSIVE_ALERT +2 all subsystems (AL-01) ✓. checkTriggers fires (old,new] ✓; no-regress guard ✓. addToSecurityTally (Probe ICC-03 path) ✓. No findings. |
| `game/GameTest.kt` | 604 | (open) `private fun allFaces(face: Int, count: Int = 100) =` `DiceRoller(stubRandom(*IntArray(count) { face }))` — (mid) `fun \`Proactive IC moves when target is in different node\`()` `assertIs<ActionResult.IcMoved>(result)` — (close) `while (states.any { it.currentInitiative > 0 }) {` ... `assertEquals(listOf("A", "B", "A"), actionOrder)` | LethalBlackIC [6,1] stub avoids explode-loop ✓. **Findings D8TB-1..D8TB-5.** IC-move persistence (D4G-3) and crashed-IC-re-act (D4G-4) UNCOVERED; two runCombatTurn tests are weak (reimplemented loop / no assertion); Crippler/Ripper `<=` trivially true. |
| `network/AlertTransitionsTest.kt` | 82 | (only third) `fun \`PASSIVE_ALERT raises all five subsystem ratings by 2\`()` `assertEquals(6,  updated.subsystemRatings.access)` ... `assertEquals(10, updated.subsystemRatings.slave)` | AL-01 +2 all five ✓. ACTIVE_ALERT does not raise ratings ✓. NO_ALERT no-op ✓. Stacking on re-apply is a code choice not contradicting spec (GameContext guards re-entry). No findings. |
| `network/NetworkTest.kt` | 248 | (open) `private fun ratings() = SubsystemRatings(4, 5, 6, 7, 8)` — (mid) `fun \`Host rejects missing subsystem type\`()` — (close) `fun \`Probe IC is reactive and can guard a Files node\`()` `assertEquals(filesNode, probe.guardedNode)` | Grid hierarchy (RTG/LTG/PLTG), Jackpoint one-target invariant, Host 5-subsystem nodes, SecuritySheaf/TriggerStep, DataFile pointer all match domain spec. No findings. |
| `operations/SystemOperationTest.kt` | 34 | (whole) `fun \`ANALYZE_HOST uses Control subsystem and Analyze utility\`()` `assertEquals(SubsystemType.CONTROL, op.testType)` / `assertEquals(29, SystemOperation.entries.size)` | ANALYZE_HOST(CONTROL,ANALYZE,COMPLEX) ✓ (iter2 L184). DOWNLOAD_DATA ONGOING ✓. CONTROL_SLAVE MONITORED ✓. 29-entry count matches sibling test. No findings. |
| `operations/SystemOperationsTest.kt` | 540 | (open) `private val winRoller = fixedRoller(5)   // face=5: beats TN≤5 and DF=3` — (mid) `// computerSkill=9, hackingPool=0, face=5, TN=2 → 9 decker successes` `assertEquals(7, result.outcome.deckerSuccesses - result.outcome.hostSuccesses)` — (close) `fun \`InterrogationState accumulatedSuccesses defaults to 0\`()` | actionsPerTurn ⌈R/10⌉+RI ✓ (SO-01/02). analyzeHost net≥7 reveals all 6 ✓. locate ≥5 / slave ≥3 / NotFound ≥3 ✓. noticeTriggeredIc 0/1/3 tiers ✓. downloadData turns=⌈Mp/io⌉ ✓. No findings. |
| `operations/SystemTestResolverTest.kt` | 183 | (open) `fun \`resolveNullOperation under 10 seconds applies 0 bonus to SecurityValue\`()` `assertEquals(4, outcome.hostSuccesses)` — (close) `fun \`QueryPrecision modifiers match spec\`()` `assertEquals(+2, QueryPrecision.VERY_VAGUE.modifier)` ... `assertEquals(-2, QueryPrecision.VERY_SPECIFIC.modifier)` | NullOperation bonus <10→0/<60→1/<3600→2/else→4 ✓; +2 to SV not TN ✓. QueryPrecision modifiers ✓. Interrogation accumulates max(0,net); TN floor 2 ✓. No findings. |

**Files read: 12. Total lines: 4817.**

---

## Findings

### D8TB-1 — GameTest does not cover D4G-3 (IcMoved never persisted by the turn loop)
`GameTest.kt:249-257` (`Proactive IC moves when target is in different node`):
```
val result = ic.blockingAction(ctx, allFaces(1))
assertIs<ActionResult.IcMoved>(result)
```
The test asserts only the *return value* is `IcMoved`. It never asserts the move **persists** — i.e.
that the IC in `context.activeIc` is replaced with one whose `guardedNode` is the target's node.
D4G-3 (`iter4_game.md:59`, "REAL CODE BUG") documents that the turn loop `game/Game.kt:43-48` ignores
the returned `ActionResult`, so a proactive IC "moves" forever and never attacks. No test in this batch
exercises loop-level persistence, so the confirmed bug is untested. **Coverage gap** (finding criterion d).

### D8TB-2 — GameTest does not cover D4G-4 (crashed/removed IC re-acts in same turn)
No test in `GameTest.kt` verifies that an IC which calls `context.removeIc(this)` mid-turn is skipped by
the remainder of the initiative loop. D4G-4 (`iter4_game.md:80`) is a confirmed real correctness edge:
`states` is built once per turn and gated only on `currentInitiative > 0`, so a removed icon can be
selected and `action()`-ed again. The only initiative-loop test (`GameTest.kt:528`, see D8TB-3) uses two
inert deckers and never removes an icon. **Coverage gap** (finding criterion d).

### D8TB-3 — `runCombatTurn gives higher initiative icon more actions` never calls `runCombatTurn`
`GameTest.kt:528-565`. The test builds its own `states` list and **re-implements** the selection /
decrement loop inline:
```
while (states.any { it.currentInitiative > 0 }) {
    val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative }!!
    ...
    states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
}
assertEquals(listOf("A", "B", "A"), actionOrder)
```
`game.runCombatTurn()` is never invoked. The assertion validates the test's own copy of the loop, not
production code, so it would still pass if `runCombatTurn` were broken — which is precisely where D4G-3
and D4G-4 live. False-confidence test (criterion c — asserts nothing about the real code path).

### D8TB-4 — `runCombatTurn completes when no active IC` asserts nothing
`GameTest.kt:569-577`. The test calls `runBlocking { game.runCombatTurn() }` and has **no assertion** of
any kind — it only confirms no exception is thrown. Trivial/asserts-nothing test (criterion c).

### D8TB-5 — Crippler / Ripper attribute-reduction assertions are trivially true
`GameTest.kt:333` `assertTrue(ctx.deckers[0].persona!!.bod <= decker.persona!!.bod)` and `GameTest.kt:441`
`assertTrue(ctx.deckers[0].persona!!.evasion <= decker.persona!!.evasion)`. A persona attribute can only
stay equal or decrease under attribute damage, so the non-strict `<=` is **always true** regardless of
whether the attack landed — it cannot distinguish a working Crippler/Ripper from a no-op. Both tests
rig the dice to "guarantee IC wins" yet assert only `<=` (should be strict `<`, or assert the exact
staged reduction). Weak assertion (Common Failure Mode: "trivially true by construction").

### D8TB-6 — `CD-14 fully active utility reduces target number` does not verify TN reduction
`CyberdeckAndProgramMechanicsTest.kt:324-346`. The test name claims to verify Deception reduces the
System Test TN, but the `SystemTestResolver.resolve(d, SystemOperation.LOGON_TO_HOST, 10, 1, roller)`
result at L342 is **discarded**; the sole assertion (L345) is
`assertEquals(4, d.cyberdeck.activeUtilities.first { it.type == UtilityType.DECEPTION }.currentRating)`,
which only confirms the utility is loaded, not that the TN was reduced. The inline stub comment
`return 5 // face=6` also mislabels the face (constant 5 → face 5, not 6). Weak coverage — the CD-15
TN-reduction behaviour is genuinely exercised elsewhere (e.g. `DeckerOperationsTest` relocateIcon /
analyzeIcon), so this is a naming/coverage weakness, not a spec contradiction.

---

## Summary

- **No test encodes an expectation that CONTRADICTS the spec** (criterion a): every DF, HackingPool, RI,
  actionsPerTurn, TN-floor, interrogation-threshold, alert-transition, and NullOperation assertion checked
  against `spec_baseline.md` is correct.
- **No unsatisfiable DiceRoller stub** (criterion b): all positive-success stubs use face≥TN (typically 5)
  and all exploding-dice cases use the `[6,1]` sequence; no constant-6 loop, no all-zero "expects success".
- Findings are **coverage gaps and weak assertions** (criteria c/d): D8TB-1 and D8TB-2 are the material
  ones — GameTest does not cover the two confirmed real code bugs D4G-3 (IC move persistence) and D4G-4
  (crashed IC re-acts). D8TB-3/-4 are non-testing tests of `runCombatTurn`; D8TB-5/-6 are trivially-true /
  behaviour-not-verified assertions.
