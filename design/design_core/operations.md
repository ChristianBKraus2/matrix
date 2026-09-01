# System Operations Design Document

## Purpose

This document specifies the design for implementing all system operations defined in `prd.md` (requirements SO-01 through SO-14, MP-01 through MP-08, and the individual operation table). It covers:

- Non-combat action economy
- Matrix perception (noticing icons and reactive IC)
- Distributed database pointer chains
- The three operation categories (interrogation, ongoing, monitored)
- A concrete Kotlin design for every named system operation

Movement operations (`Logon to LTG/RTG/Host`, `Graceful Logoff`, `Jack Out`) are designed in `movement.md`. Active-memory management (`Swap Memory`, `Load Utility`, `Unload Utility`) is designed in `cyberdeck_and_program_mechanics.md`. This document covers everything else, and extends `SystemTestResolver` and `Decker` accordingly.

---

## New Types

### `OperationResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt`

The common return type for all system operations that involve a System Test.

```kotlin
sealed class OperationResult {
    /** Decker achieved ≥ hostSuccesses; action succeeded. */
    data class Success(
        val decker: Decker,
        val outcome: SystemTestOutcome
    ) : OperationResult()

    /** Host achieved > deckerSuccesses; action failed. */
    data class Failure(
        val decker: Decker,
        val outcome: SystemTestOutcome
    ) : OperationResult()
}
```

Operations that return richer payloads (e.g. Analyze Host, Locate File) use `OperationResult.Success` with the payload stored in a dedicated wrapper (see individual operations below).

---

### `AnalyzeHostResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt`

```kotlin
data class AnalyzeHostResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    val revealedSecurityRating: SecurityRating?,          // revealed if ≥ 1 net success
    val revealedSubsystemRatings: Map<SubsystemType, Int> // one entry per net success
)
```

7 or more net successes fills all six fields completely (Security Rating + all five subsystem ratings).

---

### `LocateResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt`

```kotlin
sealed class LocatedTarget {
    data class FileTarget(val file: DataFile) : LocatedTarget()
    data class SlaveTarget(val device: RemoteDevice) : LocatedTarget()
    data class AccessNodeTarget(val query: String) : LocatedTarget()
}
```

Shared by Locate File, Locate Slave, and Locate Access Node (interrogation operations).

```kotlin
sealed class LocateResult {
    /** Accumulated successes < threshold; still searching. */
    data class Ongoing(val accumulatedSuccesses: Int) : LocateResult()
    /** Accumulated successes ≥ threshold; target located. */
    data class Located(val target: LocatedTarget, val accumulatedSuccesses: Int) : LocateResult()
    /** Host confirmed it does not contain the queried data (≥ 3 successes). */
    object NotFound : LocateResult()
}
```

---

### `InterrogationState` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/InterrogationState.kt`

Tracks accumulated successes across repeated attempts at the same interrogation operation. Held by the caller between turns.

```kotlin
data class InterrogationState(
    val operation: SystemOperation,
    val query: String,           // the decker's stated search goal
    val accumulatedSuccesses: Int = 0
)
```

---

### `MonitoredTarget` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt`

Typed discriminated union for the resource a monitored operation is acting on.

```kotlin
sealed class MonitoredTarget {
    data class SlaveDevice(val device: RemoteDevice) : MonitoredTarget()
    data class ComcallHost(val host: Host) : MonitoredTarget()
}
```

---

### `MonitoredOperationHandle` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt`

Tracks an active monitored operation. The caller holds this and must call `Decker.maintainMonitoredOperation()` every Initiative Pass.

```kotlin
data class MonitoredOperationHandle(
    val operation: SystemOperation,
    val target: MonitoredTarget,
    val active: Boolean = true,
    val needsMaintenance: Boolean = false
)
```

- `active = false` — the operation has been aborted and cannot be maintained further.
- `needsMaintenance = true` — set at the start of each Initiative Pass; `maintainMonitoredOperation` must be called before the pass ends or the operation aborts (SO-13).

Maintenance is handled via `Decker.beginInitiativePass()` (arms the flag) and `Decker.maintainMonitoredOperation()` (clears it). Missing a maintenance call causes the operation to abort (SO-14).

---

### `NullOperationModifier` (enum)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/NullOperationModifier.kt`

```kotlin
enum class NullOperationModifier(val bonus: Int) {
    UNDER_TEN_SECONDS(0),
    TEN_SECONDS_TO_ONE_MINUTE(1),
    ONE_MINUTE_TO_ONE_HOUR(2),
    ONE_HOUR_TO_TWELVE_HOURS(4);

    companion object {
        fun totalBonusForDuration(seconds: Int): Int = when {
            seconds < 10    -> UNDER_TEN_SECONDS.bonus
            seconds < 60    -> TEN_SECONDS_TO_ONE_MINUTE.bonus
            seconds < 3600  -> ONE_MINUTE_TO_ONE_HOUR.bonus
            else            -> ONE_HOUR_TO_TWELVE_HOURS.bonus
        }
    }
}
```

For durations beyond 12 hours, add an additional +1 per additional 12-hour increment on top of `ONE_HOUR_TO_TWELVE_HOURS`. The modifier is applied to the host's Security Value (not to the target number).

---

## Changes to Existing Types

### `SystemOperation` (enum)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt`

Add all previously missing operations. The enum already carries `testType: SubsystemType`, `utility: UtilityType?`, `actionType: ActionType`, and `category: OperationCategory`. Extend `OperationCategory` with the new values:

```kotlin
enum class OperationCategory { STANDARD, INTERROGATION, ONGOING, MONITORED }
```

Complete enum entries (adding those not yet present):

```kotlin
// Analyze group — Control Test, Free or Simple Action
ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD),
ANALYZE_IC(CONTROL, ANALYZE, FREE, STANDARD),
ANALYZE_ICON(CONTROL, ANALYZE, FREE, STANDARD),
ANALYZE_SECURITY(CONTROL, ANALYZE, SIMPLE, STANDARD),
ANALYZE_SUBSYSTEM(null, ANALYZE, SIMPLE, STANDARD),         // testType is null — the targeted subsystem TN is passed dynamically at call time

// Slave group
CONTROL_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED),
EDIT_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED),
MONITOR_SLAVE(SLAVE, SPOOF, SIMPLE, MONITORED),

// Decrypt group
DECRYPT_ACCESS(ACCESS, DECRYPT, SIMPLE, STANDARD),
DECRYPT_FILE(FILES, DECRYPT, SIMPLE, STANDARD),
DECRYPT_SLAVE(SLAVE, DECRYPT, SIMPLE, STANDARD),

// File group
DOWNLOAD_DATA(FILES, READ_WRITE, SIMPLE, ONGOING),
EDIT_FILE(FILES, READ_WRITE, SIMPLE, STANDARD),
UPLOAD_DATA(FILES, READ_WRITE, SIMPLE, ONGOING),

// Locate group — Index Test, Complex Action, Interrogation
LOCATE_ACCESS_NODE(INDEX, BROWSE, COMPLEX, INTERROGATION),
LOCATE_DECKER(INDEX, SCANNER, COMPLEX, STANDARD),
LOCATE_FILE(INDEX, BROWSE, COMPLEX, INTERROGATION),
LOCATE_IC(INDEX, ANALYZE, COMPLEX, STANDARD),
LOCATE_SLAVE(INDEX, BROWSE, COMPLEX, INTERROGATION),

// Comms
MAKE_COMCALL(FILES, COMMLINK, COMPLEX, MONITORED),
TAP_COMCALL(FILES, COMMLINK, COMPLEX, MONITORED),           // test type varies per step — passed at call time

// Misc
NULL_OPERATION(CONTROL, DECEPTION, COMPLEX, STANDARD),
RELOCATE_ICON(CONTROL, RELOCATE, SIMPLE, STANDARD),        // already added in cyberdeck doc

// UI convenience — Medic is not a System Test; testType=CONTROL is nominal (see invokeMedic() design)
INVOKE_MEDIC(CONTROL, null, COMPLEX, STANDARD),
```

`ANALYZE_SUBSYSTEM` and `TAP_COMCALL` accept the relevant subsystem type as a runtime parameter rather than a fixed enum field, since the test type varies by context.

---

### `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt`

Extend with two additional overloads:

**Null Operation overload** — applies the inactivity TN bonus to `hostSecurityValue` rather than to the decker's TN:

```kotlin
fun resolveNullOperation(
    decker: Decker,
    host: Host,
    inactivitySeconds: Int,
    diceRoller: DiceRoller
): SystemTestOutcome
```

Algorithm:
1. Modifier = `NullOperationModifier.totalBonusForDuration(inactivitySeconds)`; add +1 per additional 12 hours beyond the first.
2. Base TN for decker = `host.controlRating - deception.currentRating` (floor 2) as normal.
3. Effective Security Value for host = `host.securityRating.value + modifier`.
4. Roll as standard System Test.

**Interrogation accumulation wrapper:**

```kotlin
fun resolveInterrogation(
    decker: Decker,
    operation: SystemOperation,
    host: Host,
    state: InterrogationState,
    queryPrecision: QueryPrecision,   // VAGUE, NORMAL, SPECIFIC, VERY_SPECIFIC
    diceRoller: DiceRoller
): Pair<SystemTestOutcome, InterrogationState>
```

`QueryPrecision` maps to TN modifiers:

```kotlin
enum class QueryPrecision(val modifier: Int) {
    VERY_VAGUE(+2), VAGUE(+1), NORMAL(0), SPECIFIC(-1), VERY_SPECIFIC(-2)
}
```

Algorithm:
1. Apply `queryPrecision.modifier` to the base target number (subsystem rating − utility rating, clamped ≥ 2).
2. Resolve System Test normally → `outcome`.
3. `netSuccesses = outcome.deckerSuccesses - outcome.hostSuccesses`. New accumulated successes = `state.accumulatedSuccesses + max(0, netSuccesses)` — a negative net contributes nothing but does not reduce the running total (SO-06).
4. Return the outcome and an updated `InterrogationState` with the new total.

The caller checks whether the accumulated total ≥ 5 (or host-assigned threshold) to determine if the target is located.

---

### `Decker`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

Add the following public methods. All are **pure** (return new `Decker` instances via `.copy()`); all subject to logging NFR.

Also add:

```kotlin
val actionsPerTurn: Int
    get() = ceil(persona!!.reaction / 10.0).toInt() + cyberdeck.responseIncrease
```

PRD: SO-01, SO-02. `persona!!.reaction` is already the augmented Persona Reaction (base + Response Increase × 2) from the `cyberdeck_and_program_mechanics.md` design. This property may only be called when `persona != null`.

---

## Matrix Perception

### `Decker.noticeIcon(icon: Icon, diceRoller: DiceRoller): SensorTestResult`

**PRD:** MP-01 through MP-05, MP-09

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

```kotlin
sealed class SensorTestResult {
    object Undetected : SensorTestResult()
    data class Detected(val icon: Icon, val successes: Int) : SensorTestResult()
}
```

**Preconditions:**
- `persona != null`
- `icon` has entered the decker's current area (triggered by the game engine/GM; this is a Free Action — no action economy cost)

**Algorithm** (PRD: MP-01–MP-03):
1. Determine TN:
   - If `icon` is a `Persona` (another decker): TN = `icon.masking + icon.sleazeRating`
   - If `icon` is IC or another program: TN = `icon.rating`
2. Roll `persona.sensor` dice (no utility modifier; MP-01). Count successes ≥ TN → `successes`.
3. Return:
   - `successes == 0` → `Undetected`
   - `successes >= 1` → `Detected(icon, successes)` (1 = presence; 2 = type for IC; 3 = rating for IC)

The caller interprets `successes` against the thresholds in MP-03 to decide what information to reveal.

**Friendly decker auto-reveal (MP-09):** If the incoming icon is a friendly decker who chooses to make their presence known, skip the Sensor Test entirely and return `Detected(icon, 1)` directly. The game engine passes a `friendlyReveal: Boolean` flag to `noticeIcon`; when true the test is bypassed.

---

### Persistent Icon Visibility

**PRD:** MP-04

`noticeIcon` is a one-shot test. Once it returns `Detected`, the game engine adds that icon to the decker's **visible-icons set** — a persistent collection on `Decker` (or `Persona`) that survives across turns without requiring a new Sensor Test.

```kotlin
// Addition to Decker or Persona:
val detectedIcons: Set<Icon> = emptySet()
```

The game engine checks this set before calling `noticeIcon`: if the icon is already in the set, skip the test and treat the icon as detected at the previously-established success level.

Icons are removed from the visible-icons set when:
- The icon successfully executes **Evade Detection** against this decker (the engine removes the icon from the set at the moment the countdown starts; the decker must re-run `LOCATE_IC` / `LOCATE_DECKER` to re-detect after the countdown expires).
- The icon leaves the current area or host.
- The decker logs off, jacks out, or is involuntarily disconnected.

The engine calls `decker.copy(detectedIcons = detectedIcons + icon)` on `Detected` and `decker.copy(detectedIcons = detectedIcons - icon)` on the removal conditions above.

---

### `Decker.noticeTriggeredIc(ic: IC, diceRoller: DiceRoller): IcDetectionResult`

**PRD:** MP-07, MP-08

Called secretly by the GM engine when a decker triggers reactive IC.

```kotlin
sealed class IcDetectionResult {
    object Undetected : IcDetectionResult()
    data class PresenceOnly(val successes: Int) : IcDetectionResult()
    data class TypeKnown(val ic: IC, val successes: Int) : IcDetectionResult()
    data class FullyLocated(val ic: IC, val successes: Int) : IcDetectionResult()
}
```

**Algorithm:**
1. Roll `persona.sensor` dice vs. TN = `ic.rating` → `successes`.
2. Map:
   - 0 successes → `Undetected`
   - 1 success → `PresenceOnly`
   - 2 successes → `TypeKnown`
   - 3+ successes → `FullyLocated`

This test is made **once only**, at the moment the IC becomes active (MP-08).

---

## System Operation Implementations

All operations follow the same pattern: precondition check → `SystemTestResolver.resolve()` → update security tally → return result. Only notable variants are spelled out in detail below.

### Analyze Host

**PRD:** SO individual table  
**Action:** Complex  
**Precondition:** `currentLocation is OnHost` (decker must be on the host, not merely on a grid)

```kotlin
fun analyzeHost(
    host: Host,
    requestedItems: List<HostInfoItem>,   // caller's priority-ordered wish list
    diceRoller: DiceRoller
): AnalyzeHostResult
```

**Algorithm:**
1. Resolve `SystemTestResolver.resolve(this, ANALYZE_HOST, host.controlRating, host.securityRating.value, diceRoller)`.
2. Net successes = `outcome.deckerSuccesses - outcome.hostSuccesses`.
3. If net ≤ 0: reveal nothing.
4. If net ≥ 7: ignore `requestedItems` — reveal Security Rating + all 5 subsystem ratings.
5. Otherwise: take the first `net` distinct items from `requestedItems` (extras silently ignored, duplicates collapsed). Reveal exactly those items.
6. Return `AnalyzeHostResult` with revealed data.

`HostInfoItem` (defined in `OperationResult.kt`):

```kotlin
sealed class HostInfoItem {
    object SecurityRating : HostInfoItem()
    data class Subsystem(val type: SubsystemType) : HostInfoItem()
}
```

---

### Analyze Icon

**PRD:** SO individual table  
**Action:** Free  
**Note:** Both `persona.sensor` and the active Analyze utility rating reduce the TN in sequence, each with the standard floor of 2. The sensor reduction is applied by the caller before invoking `SystemTestResolver.resolve`; the Analyze reduction is then applied inside `resolve` as with every other operation. The two-step application is mathematically equivalent to a single combined subtraction.

```kotlin
fun analyzeIcon(icon: Icon, host: Host, diceRoller: DiceRoller): OperationResult
```

**Algorithm:**
1. Sensor-reduced TN = `max(2, host.controlRating - persona.sensor)`.
2. Pass this TN to `SystemTestResolver.resolve(this, ANALYZE_ICON, sensorReducedTn, ...)`, which further reduces TN by the active Analyze utility rating (floor 2).
3. On success: return icon's general type (IC / Persona / Application / etc.).

---

### Analyze Security

**PRD:** SO individual table  
**Action:** Simple  
**Returns:** current Security Rating, the decker's current security tally (including any points accrued by this test), and the host's alert status.

```kotlin
fun analyzeSecurity(host: Host, diceRoller: DiceRoller): AnalyzeSecurityResult
```

```kotlin
data class AnalyzeSecurityResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    val securityRating: SecurityRating,
    val currentTally: Int,          // includes points from this test
    val alertStatus: AlertStatus
)
```

---

### Locate Operations (Interrogation)

All three (`Locate Access Node`, `Locate File`, `Locate Slave`) share the interrogation mechanic. Each takes a `query` string — the search goal the decker states on the first call. On subsequent calls for the same operation the query is already stored in the `InterrogationState` and the parameter is ignored.

```kotlin
fun locateFile(
    host: Host,
    query: String,
    precision: QueryPrecision,
    diceRoller: DiceRoller
): Pair<OperationResult, LocateResult>

fun locateSlave(
    host: Host,
    query: String,
    precision: QueryPrecision,
    diceRoller: DiceRoller
): Pair<OperationResult, LocateResult>

fun locateAccessNode(
    host: Host,
    query: String,
    precision: QueryPrecision,
    diceRoller: DiceRoller
): Pair<OperationResult, LocateResult>
```

**Location thresholds (PRD SO individual table):**

| Operation | Accumulated successes to locate |
|---|---|
| Locate File | ≥ 5 |
| Locate Access Node | ≥ 5 |
| Locate Slave | ≥ **3** |

**Algorithm:**
1. If no existing `InterrogationState` for this operation, create one with the provided `query`. If `query` is blank on a first call, the server rejects with `bad_request`.
2. Call `SystemTestResolver.resolveInterrogation(...)` → `(outcome, newState)`.
   - Net successes per turn = `deckerSuccesses − hostSuccesses`. A negative net contributes **0** — accumulated successes never decrease (SO-06).
3. If `newState.accumulatedSuccesses >= threshold` (see table above): target located → `OperationResult.Success`.
4. If `newState.accumulatedSuccesses >= 3` and target does not exist on this host: reveal "not found".
5. Otherwise: `OperationResult.Failure` (still searching; caller updates `state`).

---

### Locate Decker

**PRD:** MP-10, SO individual table
**Action:** Complex (two-step: Index Test then open-ended Sensor Test)

```kotlin
fun locateDecker(
    host: Host,
    targetPersona: Persona,
    diceRoller: DiceRoller
): LocateDeckerResult
```

```kotlin
data class LocateDeckerResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    val located: Boolean,
    val targetNotified: Boolean   // always true when located == true (MP-10)
)
```

**Algorithm:**
1. Resolve Index Test: `SystemTestResolver.resolve(this, LOCATE_DECKER, host.indexRating, host.securityRating.value, diceRoller)`.
2. If Index Test fails: return `LocateDeckerResult(decker, outcome, located = false, targetNotified = false)`.
3. On success, resolve open-ended Sensor Test: roll `persona!!.sensor` dice vs. TN = `max(2, targetPersona.masking + targetPersona.sleazeRating)` (`sensorTn = max(2, masking + sleazeRating)`). If Sensor Test achieves ≥ 1 success: decker is located.
4. If located: `targetNotified = true` — the game engine must fire a notification event to the target decker (MP-10). The target does not learn *who* performed the operation.
5. Return `LocateDeckerResult(updatedDecker, outcome, located, targetNotified)`.

---

### Download Data

**PRD:** SO individual table, SO-10–SO-12  
**Action:** Simple (starts the download); the transfer itself is ongoing.

```kotlin
fun downloadData(
    file: DataFile,
    host: Host,
    diceRoller: DiceRoller
): Pair<OperationResult, DownloadHandle?>
```

```kotlin
data class DownloadHandle(
    val file: DataFile,
    val totalMp: Int,
    val ioSpeedMpPerTurn: Int,
    val turnsRemaining: Int,
    val active: Boolean = true
)
```

Transfer rate = `cyberdeck.ioSpeedMpPerTurn`. Total turns = `ceil(file.sizeMp / ioSpeedMpPerTurn)`. A partial transfer (aborted before completion) produces a corrupted, worthless copy unless the GM rules otherwise (SO-12).

`advanceCombatTurn()` decrements `DownloadHandle.turnsRemaining`. At 0, the download is complete and the file copy moves to `cyberdeck.storedUtilities` (or off-line storage if specified).

---

### Upload Data

**PRD:** SO individual table, SO-10–SO-12  
**Action:** Simple (starts the upload); the transfer itself is ongoing.

```kotlin
fun uploadData(
    host: Host,
    dataSizeMp: Int,
    diceRoller: DiceRoller
): Pair<OperationResult, UploadHandle?>
```

```kotlin
data class UploadHandle(
    val description: String,
    val totalMp: Int,
    val ioSpeedMpPerTurn: Int,
    val turnsRemaining: Int,
    val active: Boolean = true
)
```

Transfer rate = `cyberdeck.ioSpeedMpPerTurn`. Total turns = `ceil(dataSizeMp / ioSpeedMpPerTurn)`. A partial transfer (aborted before completion) produces an incomplete file on the host unless the GM rules otherwise (SO-12).

`advanceCombatTurn()` decrements `UploadHandle.turnsRemaining`. At 0, the upload is complete.

---

### Edit File

**PRD:** SO individual table  
**Action:** Simple

Two distinct use patterns:

**a) Small in-place edit** (≈ one line): single Files Test against the host. On success, change is applied directly.

**b) Larger replacement**: the new content must be prepared off-line and uploaded first (`Upload Data`), then a single Edit File operation inserts it regardless of size.

**Header authentication** (optional follow-up, same turn):
After any edit, the decker may make a Control Test (TN reduced by Read/Write `currentRating`) to authenticate the file headers. Record successes. If the authentication test is not attempted or fails, the system may later detect the tampered file: the GM makes a `Masking(Files)` Test; successes = hours before the host notices and reports the tampering.

**Tamper detection (PRD: Edit File notes):** A subsequent party who performs a Files Test on the modified file can detect signs of tampering. If the decker authenticated the headers, the checker must exceed the original tamperer's authentication successes to detect any signs; otherwise the tampering is detectable on any success.

```kotlin
fun editFile(
    file: DataFile,
    host: Host,
    newContent: ByteArray?,  // null = deletion
    diceRoller: DiceRoller
): EditFileResult

data class EditFileResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    val authenticationSuccesses: Int?   // null if authentication was not attempted
)
```

---

### Control Slave / Edit Slave / Monitor Slave

All three are monitored operations.

```kotlin
fun controlSlave(
    device: RemoteDevice,
    host: Host,
    diceRoller: DiceRoller
): Pair<OperationResult, MonitoredOperationHandle?>
```

On `OperationResult.Success`, the returned `MonitoredOperationHandle` is non-null. The caller must call `maintainMonitoredOperation(handle)` — a Free Action — every Initiative Pass. Missing one Free Action causes the handle's `active` to flip to `false` and the operation to abort.

For `Control Slave` on a manufacturing or scientific process, pass the average of Computer Skill and the relevant B/R or Knowledge Skill as the effective skill value. The signature accepts an optional `effectiveSkill: Int?` override; if `null`, the decker's Computer Skill is used.

---

### Null Operation

**PRD:** SO-13 category (special case — not player-initiated; called by the GM engine)

```kotlin
fun nullOperation(
    host: Host,
    inactivitySeconds: Int,
    diceRoller: DiceRoller
): OperationResult
```

Uses `SystemTestResolver.resolveNullOperation(...)` which applies the inactivity bonus to the host Security Value (not the decker's TN). The GM may call this secretly on the decker's behalf. If the Security Test raises the tally past a trigger step, IC may activate at a point within the inactivity period (GM discretion).

---

### Make Comcall / Tap Comcall

Both are monitored operations. Their multi-step resolution (Index Test to find commcode, Control Test to trace, Files Test to tap) uses standard `SystemTestResolver.resolve()` calls sequenced by the caller. **Tap Comcall — dataline scanner mechanics (PRD: Tap Comcall):**
If the target phone has one or more dataline scanners, the decker makes a Computer Skill test (not opposed — the scanner does not roll). When multiple scanners are present, use only the **highest** Device Rating (not the sum). The Commlink utility reduces the decker's TN on this test (floor 2). If zero successes → tap fails (scanner detects the tap). If any successes → tap succeeds (scanner does not detect the tap). These scanner tests do **not** affect the decker's RTG security tally — they are resolved entirely outside the normal security-tally system.

The encrypt/decrypt sub-test in `Tap Comcall` is an opposed `Computer Skill vs. Device Rating` test with the Decrypt utility reducing the decker's TN. Each failed attempt adds +2 to the TN for subsequent tries. These sub-tests also do **not** affect the decker's RTG security tally.

**Make Comcall — additional rules (PRD: Make Comcall table):**

- **Licensed decker exception:** A corporate decker with a valid RTG passcode may skip all System Tests for Make Comcall. The caller must check `decker.hasValidPasscode(rtg)` before invoking any System Test steps; if true, proceed directly to the call without rolling.
- **Tap detection:** After placing a call, the decker may detect taps or tracers with an Opposed Sensor vs. Device Rating Test. Resolve as a standard opposed test: roll `persona.sensor` dice vs. TN = Device Rating; the tap/tracer rolls Device Rating dice vs. TN = `persona.sensor`. Decker wins on ≥ equal successes.
- **Tap neutralization:** Once a tap is detected, the decker may neutralize it with an Opposed Evasion vs. Device Rating Test (same opposed structure as detection).
- **Dump/join Files Tests:** Dumping a participant from the comcall or jumping into a tapped call each require a separate Files Test (standard `SystemTestResolver.resolve()` with `MAKE_COMCALL` operation).

**Tap Comcall — persistent monitoring (PRD: Tap Comcall table):**

Once a commcode has been successfully tapped, the decker does **not** need a new Index Test to detect future activity on that same commcode. The `MonitoredOperationHandle` for an active Tap Comcall carries the tapped commcode; the caller must not re-run the Index step for subsequent calls on the same commcode. New trace (Control Test) and tap (Files Test) steps are still required for each distinct call on that commcode.

---

### Relocate Icon

Already registered in `cyberdeck_and_program_mechanics.md` as `RELOCATE_ICON`. The operation is a Success Contest: decker makes a Computer Test (TN = opponent's Sensor − Relocate utility rating); tracker makes an MPCP Test vs. Relocate utility rating. Relocating decker wins → track fails; the tracker must successfully attack again before relaunching the Track utility.

Implementation note: `relocateIcon()` uses `trackState.opponentSensorRating` as the base TN when available (non-zero), falling back to `host.subsystemRatings.control` when the decker is not currently being tracked. `SystemTestResolver` subtracts the Relocate utility's `currentRating` internally.

---

### Decrypt Operations

**PRD:** SO individual table (`Decrypt Access`, `Decrypt File`, `Decrypt Slave`)
**Action:** Simple
**Test type:** varies by target subsystem (Access / Files / Slave respectively)

Each Decrypt operation resolves as a standard System Test. On success, the encrypted element is decrypted and the decker may proceed normally.

**Scramble IC destruct test on failed decrypt (rules p. 228):** If a decker attempts to decrypt an item that is protected by Scramble IC and **fails** the Decrypt System Test, the GM immediately makes a counter-test on behalf of the Scramble IC:

1. Roll `ic.rating` dice vs. TN = `max(2, decker.computerSkill)` → `scrambleSuccesses`.
2. If `scrambleSuccesses == 0`: the decker has suppressed the Scramble IC's destruct code — the data is safe, and the Scramble IC is effectively defused for this attempt.
3. If `scrambleSuccesses >= 1`: the destruct code fires and the data is destroyed. The GM removes the `DataFile` from the host permanently.

This counter-test is made secretly by the GM engine; the decker is not informed of the Scramble IC's presence until it fires (unless previously detected via `noticeIcon`). The test does not generate a security tally increment — it is handled entirely outside the normal System Test flow.

```kotlin
fun resolveScrambleDestructTest(ic: Scramble, decker: Decker, file: DataFile, diceRoller: DiceRoller): ScrambleDestructResult
```

```kotlin
data class ScrambleDestructResult(
    val dataDestroyed: Boolean,
    val icRating: Int
)
```

**Algorithm:**

1. Roll `ic.rating` dice vs. TN = `max(2, decker.computerSkill)` → `successes`.
2. Return `ScrambleDestructResult(dataDestroyed = successes >= 1, icRating = ic.rating)`.

The caller removes `file` from the host when `dataDestroyed == true`.

---

### Buffered Messages (Free Action)

**PRD:** rules p. 224

A decker may spend a Free Action to compose a message of up to 100 words and queue it for delivery to any character linked to them via hitcher electrodes, radiolink, datascreen, or equivalent device.

```kotlin
fun bufferMessage(text: String, recipient: LinkedObserver): BufferedMessage
```

```kotlin
data class BufferedMessage(
    val text: String,
    val recipient: LinkedObserver
)
```

The recipient receives the message at the **end of the Combat Turn** (not immediately). This is a Free Action — it does not count against the decker's Simple or Complex Action budget for the Initiative Pass.

**Second-character icon control:** A character receiving a buffered message via hitcher jack may also, at the GM's discretion, operate an icon that the decker can currently "see" (is aware of in the current area). This is a narrative mechanic; the GM resolves the hitcher's action as a directed action using the decker's active persona. No additional System Test is required beyond whatever the operated icon's action would normally require.

**Scope note:** `LinkedObserver` represents any entity connected by hitcher jack, radiolink, or datascreen. It is a lightweight reference type; the full `HitcherObserver` type defined in `cyberdeck_and_program_mechanics.md` is a subtype.

---

## Alert State Transitions

**PRD:** AL-01, AL-02

Called by the game engine whenever the security tally crosses a trigger step that carries an `AlertTransition`.

```kotlin
fun applyAlertTransition(host: Host, newAlertStatus: AlertStatus): Host
```

**AL-01 — Passive Alert:** When `newAlertStatus == PASSIVE_ALERT`, return a new `Host` with all five subsystem ratings incremented by 2. The increment is permanent for this session — it is not reversed if the tally later drops below the Passive Alert trigger step:

```kotlin
host.copy(
    accessRating  = host.accessRating  + 2,
    controlRating = host.controlRating + 2,
    indexRating   = host.indexRating   + 2,
    filesRating   = host.filesRating   + 2,
    slaveRating   = host.slaveRating   + 2,
    alertStatus   = AlertStatus.PASSIVE_ALERT
)
```

**AL-02 — Active Alert:** When `newAlertStatus == ACTIVE_ALERT`, set `alertStatus = ACTIVE_ALERT` and additionally spawn security decker NPCs if the triggering `TriggerStep` specifies them. `TriggerStep` gains an optional field:

```kotlin
data class TriggerStep(
    val tallyThreshold: Int,
    val description: String,
    val activatedIc: List<IC> = emptyList(),
    val alertTransition: AlertStatus? = null,
    val securityDeckerCount: Int = 0          // AL-02: number of NPC deckers to spawn
)
```

The game engine calls `spawnSecurityDeckers(host, count, diceRoller)` which creates `count` NPC `Decker` instances, assigns each a `Persona`, and places them on the host. These NPC personas act as hostile proactive combatants — they roll initiative and attack the intruding persona using standard cybercombat rules (CC-05/CC-07).

---

## Distributed Databases

**PRD:** SO-03, SO-04

When a `Locate File` (or `Locate Access Node`) result yields a `DataFile` whose `isPointer == true`, the decker has found only a reference. The actual data is on another connected host.

```kotlin
fun resolvePointerChain(file: DataFile, diceRoller: DiceRoller): PointerChain
```

```kotlin
data class PointerChain(
    val links: List<Host>,   // hosts to traverse in order; length = 1D6
    val finalFile: DataFile
)
```

The GM rolls `1D6` to determine chain length (SO-04). The decker must perform a `Logon to Host` and then a `Locate File` operation on each intermediate host before reaching the final file.

---

## Action Economy

**PRD:** SO-01, SO-02

```kotlin
val actionsPerTurn: Int
    get() = ceil(persona!!.reaction / 10.0).toInt() + cyberdeck.responseIncrease
```

`persona!!.reaction` already accounts for Response Increase (= base Reaction + Response Increase × 2, from `cyberdeck_and_program_mechanics.md`). The `+ cyberdeck.responseIncrease` term adds one action per additional Initiative die.

Each 3-second game turn the decker may perform `actionsPerTurn` actions. Free Actions do not count against this budget but each Initiative Pass allows at most one Free Action plus the normal action pair (two Simple or one Complex).

---

## Grid-Context Variants

Four operations accept a `Grid` (LTG, RTG, or PLTG) in place of a `Host`. The mechanics are
identical to the host-context versions; only the subsystem rating source changes.

### `analyzeSecurity(grid: Grid, diceRoller: DiceRoller): AnalyzeSecurityResult`

Uses `grid.subsystemRatings.control` as TN and `grid.securityRating.value` as Security Value.
Returns the grid's current `SecurityRating`, the accumulated tally after this test, and the grid's
`alertStatus`.

### `analyzeIc(ic: IC, grid: Grid, diceRoller: DiceRoller): OperationResult`

Uses `grid.subsystemRatings.control` as TN and `grid.securityRating.value` as Security Value.
Resolves `ANALYZE_IC` against the grid rather than a host subsystem.

### `locateAccessNode(grid: Grid, query: String, precision: QueryPrecision, diceRoller: DiceRoller): Pair<OperationResult, LocateResult>`

Interrogation operation. Uses `grid.subsystemRatings.index` implicitly via
`SystemTestResolver.resolveInterrogation`. The "accessible host" pool used to evaluate
`nodeExists` is:

| Grid type | Pool |
|---|---|
| `LTG`  | `ltg.hosts` |
| `RTG`  | all hosts across all child `LTG`s |
| `PLTG` | `pltg.hosts` |

Thresholds and accumulated-success rules are identical to the host-context variant (≥ 5 to locate,
≥ 3 with absent target → `NotFound`).

### `locateIc(grid: Grid, diceRoller: DiceRoller): OperationResult`

Uses `grid.subsystemRatings.index` as TN and `grid.securityRating.value` as Security Value.
Resolves `LOCATE_IC` against the grid.

---

## Verification

| Scenario | Expected Result |
| --- | --- |
| `actionsPerTurn` with Reaction 5, RI 0 | `ceil(5/10)` = 1 action per turn |
| `actionsPerTurn` with Reaction 9, RI 2 | `ceil(9/10)` = 1 + 2 = 3 actions per turn |
| `noticeIcon` (IC rating 6, Sensor 4): rolls 0 successes | `Undetected` |
| `noticeIcon` (IC rating 6, Sensor 4): rolls 2 successes | `Detected(ic, 2)` — type revealed |
| `noticeIcon` vs. decker Masking 6 + Sleaze 4 = TN 10, Sensor 3 | High TN; most rolls → `Undetected` |
| `noticeTriggeredIc`: 1 success | `PresenceOnly` — GM tells decker IC was triggered |
| `noticeTriggeredIc`: 3 successes | `FullyLocated` — type and rating revealed |
| `locateFile` accumulates 3 successes, file not on host | `NotFound` returned |
| `locateFile` accumulates 5 successes | `LocateResult.Located` returned |
| `locateSlave` accumulates 3 successes | `LocateResult.Located` (slave threshold = 3, not 5) |
| Very vague query on `locateFile` | TN +2 applied before subsystem rating reduction |
| Very specific query | TN −2 applied |
| `analyzeHost` with 7 net successes | All 6 ratings revealed |
| `analyzeIcon` with Sensor 4 + Analyze 6 = 10 | Effective TN = max(2, control − 10) = 2 (floor enforced) |
| `nullOperation` with 90-second inactivity | Host Security Value +2 applied |
| `nullOperation` with 7200-second (2 hr) inactivity | +4 + 0 (first 12-hr window) = +4 applied |
| `downloadData` aborted mid-transfer | Corrupted file copy (worthless unless GM rules otherwise) |
| Monitored operation: missing one Free Action | `MonitoredOperationHandle.active = false`; operation aborts |
| `controlSlave` on medical lab, Computer 5, Biotech 4 | Effective skill = (5+4)/2 = 4 (rounded down) |
| `locateDecker` succeeds (Sensor Test ≥ 1 success) | `LocateDeckerResult(located = true, targetNotified = true)`; notification event fired (MP-10) |
| `locateDecker` Index Test fails | `LocateDeckerResult(located = false, targetNotified = false)`; no notification |
| Tally crosses Passive Alert step | `applyAlertTransition` returns host with all five ratings +2; `alertStatus = PASSIVE_ALERT` (AL-01) |
| Tally crosses Active Alert step with `securityDeckerCount = 2` | `alertStatus = ACTIVE_ALERT`; 2 NPC decker personas spawned on host (AL-02) |
| Tally drops below Passive Alert step after transition | Ratings remain at +2; effect is permanent for the session (AL-01) |
| Tap Comcall with 3 scanners (ratings 4, 6, 7) | Only rating 7 used for the scanner test |
| Decker rolls 0 successes on scanner test | Tap fails (scanner detects the tap); tap aborted |
| `tapComcall` decrypt fails twice | TN +4 cumulative on third attempt |
| Tap Comcall: commcode already tapped | No new Index Test; trace/tap steps still required for new call on same commcode |
| Make Comcall: licensed decker with valid passcode | All System Tests skipped; proceed directly to call |
| Make Comcall: tap detected, Sensor 5 vs. Device 4 | Opposed test; decker wins on ≥ equal successes |
| Edit File: authenticated (3 successes), checker rolls 2 | Checker must exceed 3 successes to detect tampering; 2 is insufficient |
| Edit File: no authentication, checker rolls 1 | Tampering detected on any success |
| `noticeIcon` with `friendlyReveal = true` | Sensor Test bypassed; `Detected(icon, 1)` returned immediately (MP-09) |
| Pointer chain: `isPointer == true` | `resolvePointerChain` called; 1D6 intermediate hosts to traverse |
| Decrypt fails; Scramble IC present; `ic.rating` 0 successes vs. Computer Skill | Destruct suppressed; data safe; no tally increment |
| Decrypt fails; Scramble IC present; `ic.rating` ≥ 1 success vs. Computer Skill | `dataDestroyed = true`; file removed from host |
| Scramble destruct counter-test | Does not affect RTG/host security tally |
| Buffered message composed (Free Action) | Delivered to linked recipient at end of Combat Turn; no action budget consumed |
