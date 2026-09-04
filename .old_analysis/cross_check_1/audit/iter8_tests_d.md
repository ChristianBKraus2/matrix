# Iteration 8 — Integration Tests Audit (Group D)

Scope: MovementTest, MemoryManagementTest, FileOperationsTest, SlaveOperationsTest,
UploadDataAndScrambleTest, GridMock, HostMock. Each file read in full, line 1 → last line,
via sequential Read (no Grep-as-substitute). Roller semantics cross-checked against
`DiceRoller.kt` (face = raw `nextInt(1,7)` value; explode on 6):
winRoller face 0 (0 succ, wins on tie), failRoller face 3 (succ iff TN≤3),
hitRoller face 5 (succ iff TN≤5), winThenRoller = 0 for first N dice then value.
Implementation cross-checks: `DeckerOperationsExtensions.kt` (locateFile L210, locateSlave L237,
downloadData L343, editFile L369, uploadData L392, resolveScrambleDestructTest L650),
`DeckerNavigationExtensions.kt` (gracefulLogoff L231, jackOut L253).

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `integration/MovementTest.kt` | 157 | (open) L32 `scenario(diceRoller = winThenRoller(zeroCalls = 12, thenValue = 3))` — (mid) L97 `winThenRoller(zeroCalls = 24, thenValue = 3)` … `logonToRtg("AZT", succeed = false)` — (close) L140 `winThenRoller(zeroCalls = 25, thenValue = 3)` … L143 `gracefulLogoff(succeed = false)` | Location-transition assertions only (`assertOnHost/Ltg/Rtg`, `assertLoggedOff`, `assertNotJackedIn`). winThenRoller budgets are consistent with hackingPool=0 (decker rolls `computerSkill` dice only; e.g. zeroCalls=12 = 8 decker + host dice for one logon). No tally-persistence assertions (M-09..M-15) and no dump-shock verification. See D8TD-1, D8TD-2. |
| `integration/MemoryManagementTest.kt` | 142 | (open) L33 `val slowDeck = d.cyberdeck.copy(ioSpeedMpPerTurn = 1)` — L36 `Utility(UtilityType.ANALYZE, rating = 6)` (mpSize 6²×3=108) — (close) L139 `assertTrue(result.requiredMp > result.availableMp,` | loadUtility→pendingUploads (turns=ceil(108/1)>0), advanceCombatTurn promotion (rating 3 → 27 Mp, ceil(27/300)=1), unload, swap, InsufficientMemory. All Mp sizes and turn counts match Utility×mult and `turns=⌈Mp/io⌉`. No findings. |
| `integration/FileOperationsTest.kt` | 159 | (open) L34 `// BROWSE-4 + SLEAZE-6: DF = ceil((masking=6 + sleaze=6) / 2) = 6.` — (mid) L79 `val (opResult, handle) = icon.currentDecker().downloadData(file, host, winRoller())` — (close) L125 `assertTrue(d.runDownloadedFiles.any { it.name == file.name },` | locateFile threshold ≥5 (matches code L218) with hitRoller net 8 → Located; Ongoing/Failure path seeds `LOCATE_FILE@HOST`. download turns `⌈sizeMp/io⌉` (code L354, coerceAtLeast 1); completion routes to `runDownloadedFiles` (code L366), consistent with code (design DOC-10 storedUtilities is the stale side). editFile win/fail. Minor dead locals `state` (L41, L57) — code-quality only. No spec-contradicting assertion. |
| `integration/SlaveOperationsTest.kt` | 143 | (open) L35 `// Host: 6 dice vs TN=6, hitRoller face=5 → 0 successes. Decker: 8 dice vs TN=2 → 8 net → ≥ 3 → Located.` — (mid) L71 `val (opResult, handle) = icon.currentDecker().controlSlave(device, host, winRoller())` — (close) L138 `val (opResult, handle) = icon.currentDecker().monitorSlave(device, host, winRoller())` | locateSlave threshold ≥3 (matches code L245). controlSlave/monitorSlave return MonitoredOperationHandle (Success/Failure via win/failRoller), maintain keeps active, abort deactivates. Roller math sound. No findings. |
| `integration/UploadDataAndScrambleTest.kt` | 112 | (open) L29 `icon.currentDecker().uploadData(host, dataSizeMp = 100, winRoller())` — (mid) L59 `val tallyAfter = (result.decker.currentLocation as MatrixLocation.OnHost).host.securityTally` — (close) L92 `// failRoller: face=4, TN = max(2, computerSkill=8) = 8 → all dice fail` | uploadData dataSize=100 (spec default). Tally test correctly exercises M-05 (host successes always added via `withUpdatedTally`, code L396). Scramble destruct: STANDARD tier TN=max(2,5)=5, hitRoller face5 → destroyed (code L652); default HIGH_END TN=8; icRating recorded. Comment error at L92 → D8TD-3. |
| `integration/utility/GridMock.kt` | 34 | L10 `val matrix = GridInitializer.initialize()` … L15 `connectsToLtg = matrix.rtgs.first { it.name == rtg }.ltgs.first { it.name == ltg }` | Builds real grid via `GridInitializer`; jackpoint/host lookups by name. No fabricated ratings; tests navigate to real config hosts. No findings. |
| `integration/utility/HostMock.kt` | 21 | L13-16 `securityRating = ...SecurityRating(...GREEN, 3)` … `SubsystemRatings(3, 3, 3, 3, 3)` | Placeholder host only (used for `GameContext.host` in `buildDefaultContext`); assigned tests read the real host from `currentLocation`. Subsystem ratings all 3 (order-independent), GREEN/3, AVERAGE, TIERED — internally valid. No findings. |

Total: 7 files, 768 lines.

## Findings

**D8TD-1 — MovementTest has no tally-persistence assertions (M-09..M-15 coverage gap).**
`integration/MovementTest.kt` (whole file, e.g. L40-50 `integration - jack in to LTG, traverse RTGs, enter host, logoff via game layer`). The file traverses jackpoint→LTG→RTG→cross-RTG→LTG→PLTG→Host and back, but every assertion is a location/logoff check (`assertOnHost/Ltg/Rtg`, `assertLoggedOff`). Spec M-09 (RTG and its LTGs share one tally), M-10 (new RTG = fresh tally), M-11 (PLTG inherits parent RTG tally) — none of these persistence rules are asserted anywhere in this file despite it being the movement-scenario suite. Coverage gap for the movement tally spec. (Tally persistence may be covered in `AlertAndTallyTest.kt`, outside this group; flagged so it is not assumed covered here.)

**D8TD-2 — Dump-shock outcome asserted only as "logged off"; the dump-shock effect their names claim is never verified.**
`integration/MovementTest.kt:138-146` `graceful logoff failure falls back to jack-out with dump shock` and `:148-156` `forced jack-out causes dump shock`. Both assert only `assertLoggedOff()` (persona/location null). `gracefulLogoff` (code `DeckerNavigationExtensions.kt:246`) and `jackOut` (L258) return `LogoffResult.JackOut(..., dumpShock = shock)`, and dump-shock damage is applied only at the controller layer (`WebSocketDeckerController.kt:444`). The tests never assert `dumpShock == true` nor any resulting condition-monitor damage, so the "with dump shock" behavior in both test names is unverified. Coverage gap (assertion weaker than the claimed behavior).

**D8TD-3 — Incorrect roller face in Scramble test comment (`face=4`; actual failRoller face=3).**
`integration/UploadDataAndScrambleTest.kt:92` `// failRoller: face=4, TN = max(2, computerSkill=8) = 8 → all dice fail`. `failRoller()` (IntegrationTestBase L42-45) overrides `nextInt → 3`, and `DiceRoller.rollOne()` uses that value directly as the face, so the face is **3**, not 4 (confirmed by the roller table in memory `feedback_dice_roller_behavior.md`). The test still passes (3 < 8 = 0 successes), so the assertion is correct, but the comment misstates the stub's face value and would mislead any future tuning where TN sits at 3–4 (e.g. a STANDARD/LOW_END decker). Comment defect, not a live test failure.
