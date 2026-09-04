# Iteration 8 — Integration Test Audit (batch C)

Scope: `src/test/kotlin/com/shadowrun/matrix/integration/` — AlertAndTallyTest, CombatTest,
DeckerCombatTest, GrayCombatTest, ManeuverTest. Read in full (line 1 → last line) in this session.
Shared harness (IntegrationTestBase / ScenarioBuilder / DeckerMock / HostMock) read as reference only
(audited in S1 — not re-logged). Spec source: `spec_baseline.md`, `iter2_combat.md`,
`discrepancies_without_prd.md` (GL-1/GL-2).

## Coverage table

| File | Lines | Verbatim excerpts (prove full read) | Notes |
|---|---|---|---|
| `AlertAndTallyTest.kt` | 137 | **Open (L27):** `val updatedHost = applyAlertTransition(host, AlertStatus.PASSIVE_ALERT)` · **Mid (L60):** `val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)` · **Close (L119):** `assertTrue(aztTally != ucasTally || (aztTally == 0 && ucasTally == 0),` | Passive alert +2 all 5 subsystems (AL-01), Active alert flips status only (AL-02), tally accrual/reset — all match spec. GL-1: two `zeroCalls=26` rollers (L60, L126), correct for hackingPool=0. Finding D8TC-1 (weak assertion L119). |
| `CombatTest.kt` | 231 | **Open (L32):** `val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)` · **Mid (L142):** `// Blaster(rating=2): main attack with 2 dice (face 5) vs TN 4 (ORANGE/INTRUDING) → 2 successes → hits.` · **Close (L205):** `assertFailsWith<IllegalStateException>("Pinned decker should not be able to jack out")` | Attack TN ORANGE-intruding=4 (CC-24) correct; Killer(GREEN) MODERATE, degradation floors, Black IC pin, Medic repair — all match spec. GL-1: `zeroCalls=26` (L32) correct. D8TC-5 (minor comment inaccuracy L142). D4G-3/D4G-4 coverage gaps (D8TC-3/4). |
| `DeckerCombatTest.kt` | 245 | **Open (L39):** `val result = CombatResolver.resolveBlackHammer(decker, attack, hitRoller())` · **Mid (L126):** `assertEquals(dfBefore - 1, suppressed.effectiveDetectionFactor, "Each suppressed IC reduces DF by 1")` · **Close (L229):** `... diceRoller = winThenRoller(zeroCalls = 5, thenValue = 5))` | Black Hammer / Killjoy damage split, suppression DF −1 each (CD-18a), track lock, reactive-IC Slow no-op — all match spec. **GL-2 stub at L229 = `winThenRoller(zeroCalls=5, thenValue=5)` exactly as resolved.** Finding D8TC-2 (stale comment L179). |
| `GrayCombatTest.kt` | 202 | **Open (L30):** `icon.injectIc(Ripper(rating = 6, targetAttribute = PersonaAttributeType.BOD))` · **Mid (L76):** `icon.injectIc(Sparky(rating = 6))` · **Close (L193):** `val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {` | Ripper floor 0 (vs Crippler floor 1), Sparky MPCP test TN=hardening+mcp+2, TarPit trap, NonLethal Black IC mental damage — all match spec. GL-1: `zeroCalls=26` (L193) correct. No stale pool-inflated comment. |
| `ManeuverTest.kt` | 129 | **Open (L28):** `private fun weakOpponent() = ManeuverParticipant(evasion = 1, sensor = 1)` · **Close (L124):** `val result = CombatResolver.resolveManeuver(CombatManeuverType.POSITION_ATTACK, deckerWithCloak, moderateOpponent, hitRoller())` | Mover TN=max(2,sensor−cloak), opponent TN=max(2,evasion−lockOn); **ties→fail (opponent)** for maneuvers per combat.md L736 — correct (distinct from System Test ties→decker). Cloak lowers mover TN. hackingPool added to maneuver pool = within PRD "may", untouched by GL-1. No findings. |

Total files read: **5**. Total lines: **944**.

## Findings

**D8TC-1 — AlertAndTallyTest L119: permissive assertion gives false confidence on RTG-tally independence.**
Verbatim: `assertTrue(aztTally != ucasTally || (aztTally == 0 && ucasTally == 0), "AZT tally ($aztTally) should not inherit from UCAS tally ($ucasTally)")`. The disjunct `(aztTally == 0 && ucasTally == 0)` makes the test pass in the exact both-zero state the scenario can legitimately reach, so it does not strongly prove tally independence (M-10: new RTG starts fresh). Finding type (c) weak/near-trivial assertion. No spec contradiction — assertion is sound but under-powered.

**D8TC-2 — DeckerCombatTest L179: stale comment names the wrong roller and wrong outcome.**
Comment: `// hitRoller makes decker roll face=5 (evade succeeds) but attack already has high successes`, but the call at L187 is `diceRoller = failRoller()`. Evasion TN = max(2, trackRating=6) = 6, so neither hitRoller (5<6) nor failRoller (3<6) yields evade successes — the "evade succeeds" claim is doubly wrong. Test assertion (notNull + cycleTurns≥1) is still correct per resolveTrackLock (net=10 → cycleTurns=ceil(10/10)=1). Comment-only defect.

**D8TC-3 — Coverage gap: D4G-3 (IC move never persists — Game.runCombatTurn discards IcMoved) untested.**
None of the 5 files assert that an IC relocation performed inside `Game.runCombatTurn` persists to `context.activeIc`. All combat tests drive attacking/reactive IC that hit or miss in place; the known bug that `IcMoved` is discarded has no covering test here. (Note per task: Game loop not wired to production; these tests drive IC via Game directly.)

**D8TC-4 — Coverage gap: D4G-4 (crashed IC re-acts same turn) untested.**
No test verifies that an IC crashed mid-turn does not take a further action in the same combat turn. `GrayCombatTest.analyzeIc failure leaves IC active` (L189) only checks that a *failed analyze* leaves IC active — it does not exercise the crashed-then-re-acts path.

**D8TC-5 — CombatTest L142: comment overstates IC attack dice (low severity).**
Comment: `// Blaster(rating=2): main attack with 2 dice (face 5) vs TN 4 (ORANGE/INTRUDING) → 2 successes → hits.` Per CC-23 an IC main attack rolls the **host Security Value** pool (HostMock securityRating value = 3), not `ic.rating=2`; only the Blaster MPCP test (L143) legitimately rolls `ic.rating` dice. The assertion (`mcpRating < mcpBefore`) holds regardless, so this is a comment inaccuracy, not a behavioural defect. Not a pool-inflated ("+Hacking Pool") comment.

## GL-1 / GL-2 consistency verdict

**Consistent.** Every setup roller in batch C is `winThenRoller(zeroCalls = 26, thenValue = 3)`
(AlertAndTallyTest L60/L126, CombatTest L32, GrayCombatTest L193) — the calibration for
hackingPool = 0 (jack-in 8 + SV + logon 8 + SV ≈ 26 decker+host dice, ties→decker during the zero
window). No stale pool-inflated dice comment (e.g. "+6 Hacking Pool" / inflated decker-success count)
appears in these five files. The GL-2 `resolveSlow` stub (DeckerCombatTest L229) is exactly
`winThenRoller(zeroCalls = 5, thenValue = 5)` — IC's securityValue=5 dice zeroed, then slowRating=4
dice at face 5 ≥ TN 5 → net 4 → actionsLost 2 > 0, satisfying the tightened `> 0` assertion.
