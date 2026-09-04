# Iteration 4 — Business Logic Audit: DeckerOperationsExtensions.kt

## Coverage Table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt` | 678 | (1) L91 `val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_HOST, host.subsystemRatings.control, host.securityRating.value, diceRoller)` — (2) L354 `val turns = ceil(file.sizeMp.toDouble() / ioSpeed).toInt().coerceAtLeast(1)` — (3) L652 `val successes = diceRoller.roll(ic.rating, maxOf(2, computerSkill)).successes` | Read in full L1→L678. Excerpts one per third, ≥50 source lines apart (91/354/652). See per-method verification below. |

Excerpts are ≥50 source lines apart, one per third of a 678-line file.

## Methods checked (Rule 8 — every function)

noticeIcon, noticeTriggeredIc, analyzeHost, analyzeIc, analyzeIcon, analyzeSecurity(host), analyzeSubsystem,
decryptAccess(host), decryptAccess(grid), decryptFile, withFileRemovedFromHost, decryptSlave,
locateFile, locateSlave, locateAccessNode(host), locateAccessNode(grid), analyzeSecurity(grid), locateIc(grid),
downloadData, recordCompletedDownload, editFile, uploadData, controlSlave, editSlave, monitorSlave,
maintainMonitoredOperation, beginInitiativePass, checkMaintenance, abortMonitoredOperation,
nullOperation(host), nullOperation(grid), invokeMedic, resolvePointerChain, locateDecker, locateIc(host),
makeComcall, tapComcall, relocateIcon, resolveScrambleDestructTest, bufferMessage, tallyFor(host), tallyFor(grid).

## Hacking Pool (GL-1) verification

All 28 `SystemTestResolver.resolve*` call sites were enumerated (resolve: L91, L116, L129, L140, L152, L163,
L171, L179, L203, L325, L336, L346, L379, L395, L426, L438, L447, L571, L587, L603, L612, L641;
resolveInterrogation: L216, L243, L268, L298; resolveNullOperation: L485, L493). **NONE** passes a
`hackingPoolDice` / `hackingPool` argument. No stray `hackingPoolDice = hackingPool` args remain. GL-1
Option-B revert is intact. Direct non-resolver `diceRoller.roll(...)` calls (noticeIcon, noticeTriggeredIc,
editFile auth, locateDecker sensor, tapComcall scanner, invokeMedic, resolveScrambleDestructTest,
resolvePointerChain) likewise pass no pool dice.

## Rule 11 — System Test math verification

Every resolve call passes the correct subsystem rating as `accessRating` and `host/grid.securityRating.value`
as the Security Value pool (analyzeHost/IC/Security→control; decryptAccess→access; decryptFile/download/
upload/editFile/comcall→files; decryptSlave/control/edit/monitorSlave→slave; locateIc/locateDecker→index;
locateAccessNode via resolveInterrogation→index). `outcome.deckerWins` (ties→decker) drives every branch, and
`withUpdatedTally(outcome.hostSuccesses)` is invoked on **every** path before returning — including the
ioSpeed≤0 early returns (download L347/L352, upload L396/L401), the tap-scanner-detected return (L613/L626),
and the locateDecker Index-fail return (L572/L575). Host successes are therefore ALWAYS added to the tally
(M-05). The only path with no tally update is makeComcall's valid-passcode short-circuit (L598-601), which runs
no System Test — correct.

## Findings

### D4O-1 — editFile parameter order differs from operations.md signature (doc-stale)
`DeckerOperationsExtensions.kt:369-375`:
```
fun Decker.editFile(
    file: DataFile,
    host: Host,
    newContent: ByteArray?,
    diceRoller: DiceRoller,
    attemptAuthentication: Boolean = false
): EditFileResult
```
operations.md L609-615 specifies `editFile(file, host, newContent, attemptAuthentication=false, diceRoller)` —
`diceRoller` last. Code places `diceRoller` before `attemptAuthentication`. Functionally equivalent (Kotlin
named/default args; all fields present per Rule 9), but the design doc signature is stale.
**Verdict:** Code correct, design doc stale → update operations.md L609-615. No code change.

### D4O-2 — Downloaded file stored in `runDownloadedFiles`, not `cyberdeck.storedUtilities` (resolves DOC-10)
`DeckerOperationsExtensions.kt:364-367`: `recordCompletedDownload` does `copy(runDownloadedFiles = runDownloadedFiles + file)`.
operations.md L559 / iter2 DOC-10 claims completed downloads move to `cyberdeck.storedUtilities`. Code uses a
distinct data-storage field (`runDownloadedFiles`), correctly separating downloaded DataFiles from the utility
store. Confirms DOC-10 as design-doc staleness, not a code bug.
**Verdict:** Code correct, design doc stale → operations.md L559 should reference `runDownloadedFiles`. No code change.

### D4O-3 — uploadData synthesizes a DataFile from `dataSizeMp` (resolves DOC-11)
`DeckerOperationsExtensions.kt:404`: `UploadHandle(file = DataFile(name = "upload to ${host.name}", sizeMp = dataSizeMp), totalMp = dataSizeMp, ioSpeedMpPerTurn = ioSpeed, turnsRemaining = turns)`.
iter2 DOC-11 flagged that `uploadData(host, dataSizeMp, diceRoller)` supplies no `DataFile` yet `UploadHandle`
requires one. Code resolves the gap by constructing a synthetic placeholder DataFile and mapping
`dataSizeMp → totalMp`. All UploadHandle fields supplied (Rule 9). Doc/handle-shape mismatch is a design-doc
issue, not a code bug.
**Verdict:** Code correct, design doc stale. No code change.

### D4O-4 — noticeIcon carries the `friendlyReveal` param the doc signature omits (resolves DOC-4)
`DeckerOperationsExtensions.kt:50`: `fun Decker.noticeIcon(icon: Icon, diceRoller: DiceRoller, friendlyReveal: Boolean = false)`.
operations.md L301 documents the signature without the flag while L329 says the engine passes it. Code
implements the flag (L53-56 bypass, returns `Detected(icon, 1)`), matching MP-09 behavior. Confirms DOC-4 as
doc staleness.
**Verdict:** Code correct, design doc stale → operations.md L301. No code change.

### D4O-5 — invokeMedic at Deadly returns without degrading Medic rating (examined, NOT a discrepancy)
`DeckerOperationsExtensions.kt:509-512`: at `filled >= 10` returns `MedicResult(this, 0, medic.currentRating)`
with no `currentRating` decrement. CD-20 (prd_core L192) / prd_core L114 / L330 state Medic degrades "regardless
of outcome." However, prd_core's TN table lists only Light→4, Moderate→5, Serious→6 — Deadly is not a
repairable state, and a full (10-box) Condition Monitor means the icon has crashed/dumped (CC-30), so no
invocation occurs. The early return is a pre-invocation guard, not a skipped-degradation path. TN mapping for the
repairable range is correct (filled≤3→4, ≤6→5, else→6; L513-517) and degradation (`newMedicRating =
currentRating - 1`, L521) plus CD-22 auto-unload/deplete (L523-533) are correctly applied on every real
invocation.
**Verdict:** Code correct. No change. Logged for completeness (Rule 5).

## Summary

- 28 `SystemTestResolver.resolve*` calls audited; **zero** stray `hackingPoolDice`/`hackingPool` args. GL-1 revert intact.
- Rule 11 System Test math verified at all call sites (correct subsystem/Security Value; ties→decker; host successes always tallied).
- Interrogation thresholds correct: Locate File/Access Node ≥5, Locate Slave ≥3, NotFound at ≥3-with-no-data for all four locate variants; grid/host use distinct state keys (no shared-key collision, Rule 10).
- File-ops (download/upload/editFile/decryptFile+scramble), comcall (make/tap, passcode skip), slave ops (control/edit/monitor + maintenance lifecycle), relocateIcon, resolvePointerChain, locateDecker two-step all conform to the operations.md distill.
- **No real code discrepancies.** All 4 substantive findings (D4O-1..4) are design-doc staleness (doc updates only); D4O-5 examined and cleared.
