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

Shared by Locate File, Locate Slave, and Locate Access Node (interrogation operations).

```kotlin
sealed class LocateResult {
    /** Accumulated successes < threshold; still searching. */
    data class Ongoing(val accumulatedSuccesses: Int) : LocateResult()
    /** Accumulated successes ≥ threshold; target located. */
    data class Located(val target: Any, val accumulatedSuccesses: Int) : LocateResult()
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

### `MonitoredOperationHandle` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt`

Tracks an active monitored operation. The caller holds this and must call `maintain()` every Initiative Pass.

```kotlin
data class MonitoredOperationHandle(
    val operation: SystemOperation,
    val target: Any,              // RemoteDevice, Commcode, etc.
    val active: Boolean = true
)
```

If `active` is `false`, the operation has been aborted and cannot be maintained further.

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
        fun forDuration(seconds: Int): NullOperationModifier = when {
            seconds < 10    -> UNDER_TEN_SECONDS
            seconds < 60    -> TEN_SECONDS_TO_ONE_MINUTE
            seconds < 3600  -> ONE_MINUTE_TO_ONE_HOUR
            else            -> ONE_HOUR_TO_TWELVE_HOURS
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
ANALYZE_SUBSYSTEM(CONTROL, ANALYZE, SIMPLE, STANDARD),     // test type = targeted subsystem (passed at call time)

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
1. Modifier = `NullOperationModifier.forDuration(inactivitySeconds).bonus`; add +1 per additional 12 hours beyond the first.
2. Base TN for decker = `host.controlRating - deception.currentRating` (floor 2) as normal.
3. Effective Security Value for host = `host.securityValue + modifier`.
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
3. New accumulated successes = `state.accumulatedSuccesses + outcome.deckerSuccesses`.
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

**PRD:** MP-01 through MP-05

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
   - If `icon` is a `Persona` (another decker): TN = `icon.masking + icon.sleaze?.currentRating ?: 0`
   - If `icon` is IC or another program: TN = `icon.rating`
2. Roll `persona.sensor` dice (no utility modifier; MP-01). Count successes ≥ TN → `successes`.
3. Return:
   - `successes == 0` → `Undetected`
   - `successes >= 1` → `Detected(icon, successes)` (1 = presence; 2 = type for IC; 3 = rating for IC)

The caller interprets `successes` against the thresholds in MP-03 to decide what information to reveal.

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
1. Resolve `SystemTestResolver.resolve(this, ANALYZE_HOST, host.controlRating, host.securityValue, diceRoller)`.
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
**Note:** TN = `host.controlRating - (persona.sensor + analyze.currentRating)`, but may **not** drop below 2 regardless of combined Sensor + Analyze ratings (not the standard floor; this is a special per-rule minimum).

```kotlin
fun analyzeIcon(icon: Icon, host: Host, diceRoller: DiceRoller): OperationResult
```

**Algorithm:**
1. Effective TN = `max(2, host.controlRating - persona.sensor - (analyze?.currentRating ?: 0))`.
2. Resolve System Test with this TN.
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

All three (`Locate Access Node`, `Locate File`, `Locate Slave`) share the interrogation mechanic.

```kotlin
fun locateFile(
    host: Host,
    state: InterrogationState,
    precision: QueryPrecision,
    diceRoller: DiceRoller
): Pair<OperationResult, InterrogationState>
```

**Algorithm:**
1. Call `SystemTestResolver.resolveInterrogation(...)` → `(outcome, newState)`.
2. If `newState.accumulatedSuccesses >= 5`: file is located → `OperationResult.Success`.
3. If `newState.accumulatedSuccesses >= 3` and file does not exist on this host: reveal "not found".
4. Otherwise: `OperationResult.Failure` (still searching; caller updates `state`).

`Locate Slave` requires only **3** accumulated successes (not 5) to locate a slave (PRD SO individual table).

---

### Download Data

**PRD:** SO individual table, SO-10–SO-12  
**Action:** Simple (starts the download); the transfer itself is ongoing.

```kotlin
fun downloadData(
    file: DataFile,
    host: Host,
    diceRoller: DiceRoller
): DownloadHandle
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

### Edit File

**PRD:** SO individual table  
**Action:** Simple

Two distinct use patterns:

**a) Small in-place edit** (≈ one line): single Files Test against the host. On success, change is applied directly.

**b) Larger replacement**: the new content must be prepared off-line and uploaded first (`Upload Data`), then a single Edit File operation inserts it regardless of size.

**Header authentication** (optional follow-up, same turn):
After any edit, the decker may make a Control Test (TN reduced by Read/Write `currentRating`) to authenticate the file headers. Record successes. If the authentication test is not attempted or fails, the system may later detect the tampered file: the GM makes a `Masking(Files)` Test; successes = hours before the host notices and reports the tampering.

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

Both are monitored operations. Their multi-step resolution (Index Test to find commcode, Control Test to trace, Files Test to tap) uses standard `SystemTestResolver.resolve()` calls sequenced by the caller. `Tap Comcall` dataline scanner detection uses an opposed `Computer Skill vs. Device Rating` test with the Commlink utility reducing the decker's TN.

The encrypt/decrypt sub-test in `Tap Comcall` is an opposed `Computer Skill vs. Device Rating` test with the Decrypt utility reducing the decker's TN. Each failed attempt adds +2 to the TN for subsequent tries. These sub-tests do **not** affect the decker's RTG security tally.

---

### Relocate Icon

Already registered in `cyberdeck_and_program_mechanics.md` as `RELOCATE_ICON`. The operation is a Success Contest: decker makes a Computer Test (TN = opponent's Sensor − Relocate utility rating); tracker makes an MPCP Test vs. Relocate utility rating. Relocating decker wins → track fails; the tracker must successfully attack again before relaunching the Track utility.

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
| `tapComcall` decrypt fails twice | TN +4 cumulative on third attempt |
| Pointer chain: `isPointer == true` | `resolvePointerChain` called; 1D6 intermediate hosts to traverse |
