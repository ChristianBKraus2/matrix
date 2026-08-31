# Movement Design Document

## Purpose

This document specifies the design for implementing all movement use cases defined in `prd.md` (requirements M-01 through M-18). Movement is exposed as public methods on the `Decker` class. The design is purely functional: every method returns new immutable objects; no shared mutable state.

---

## New Types

### `MatrixLocation` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/network/MatrixLocation.kt`

Represents where a decker's persona is currently active in the Matrix. `null` means the decker is jacked out.

```kotlin
sealed class MatrixLocation {
    data class OnLTG(val ltg: LTG) : MatrixLocation()
    data class OnRTG(val rtg: RTG) : MatrixLocation()
    data class OnPLTG(val pltg: PLTG) : MatrixLocation()
    data class OnHost(val host: Host) : MatrixLocation()
}
```

The active security tally for the current system is read from the contained grid/host object's `securityTally` field.

---

### `LogonResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/MovementResult.kt`

```kotlin
sealed class LogonResult {
    /** Decker succeeded the System Test and is now at the new location. */
    data class Success(
        val decker: Decker,
        val location: MatrixLocation,
        val deckerSuccesses: Int,
        val hostSuccesses: Int
    ) : LogonResult()

    /** Decker failed the System Test; still at previous location. */
    data class Failure(
        val decker: Decker,
        val location: MatrixLocation?,  // attempted destination (null if not applicable)
        val deckerSuccesses: Int,
        val hostSuccesses: Int
    ) : LogonResult()
}
```

---

### `LogoffResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/MovementResult.kt`

```kotlin
sealed class LogoffResult {
    /** Graceful logoff succeeded; traces cleared; no dump shock. */
    data class GracefulSuccess(val decker: Decker) : LogoffResult()

    /** Graceful logoff failed or was not attempted; dump shock may apply. */
    data class JackOut(val decker: Decker, val dumpShock: Boolean) : LogoffResult()
}
```

---

### `SystemTestOutcome` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestOutcome.kt`

```kotlin
data class SystemTestOutcome(
    val deckerSuccesses: Int,
    val hostSuccesses: Int,       // added to security tally
    val deckerWins: Boolean       // deckerSuccesses >= hostSuccesses
)
```

---

## Changes to Existing Types

### `Decker`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

Add one field:

```kotlin
val currentLocation: MatrixLocation? = null
```

The eight public movement methods are added to this class (see below).

### `Persona`

`Persona.currentNode: Node?` is already present. It remains null while on a grid; it is set when entering a specific node within a host (out of scope for this document — covered by System Operations design).

---

## Helper: `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt`

A stateless object that encapsulates the Success Contest logic reused by every logon method.

```kotlin
object SystemTestResolver {
    fun resolve(
        decker: Decker,
        operation: SystemOperation,  // determines which utility (if any) reduces the TN (CD-14/CD-15)
        targetNumber: Int,           // subsystem rating (Access Rating)
        hostSecurityValue: Int,      // host/grid Security Value dice
        diceRoller: DiceRoller
    ): SystemTestOutcome
}
```

Full algorithm is specified in `cyberdeck_and_program_mechanics.md`. Summary: TN is reduced by the `currentRating` of the utility mapped to `operation` (CD-15), floored at 2; host rolls against `decker.effectiveDetectionFactor`.

---

## Public Methods on `Decker`

All eight methods are **pure**: they take immutable inputs and return new `Decker` instances (via `.copy()`) plus a `MatrixLocation` carrying updated security tallies. The caller is responsible for persisting the returned objects.

### 1. `jackInToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-01, M-04, M-05

**Preconditions:**
- `persona == null` (not already jacked in)
- `jackpoint != null`
- `jackpoint.type` ∈ `{LEGAL_ACCESS, ILLEGAL_ACCESS, TELECOM, ILLEGAL_JUNCTION_BOX}`

**Logic:**
1. Run `SystemTestResolver.resolve(decker, LOGON_TO_LTG, ltg.subsystemRatings.access, ltg.securityRating.value, diceRoller)`.
2. Increment `ltg.securityTally` by `outcome.hostSuccesses`.
3. If `outcome.deckerWins`: create persona, set `currentLocation = OnLTG(updatedLtg)` → return `LogonResult.Success`.
4. If not: return `LogonResult.Failure` with updated tally (persona remains null).

**Note:** The RTG security tally (M-09) is tracked on the LTG's parent RTG object; the caller must propagate the tally to `ltg.parentRtg.securityTally` as well. Helper `mergeRtgTally(ltg, outcome)` encapsulates this.

---

### 2. `jackInToHost(host: Host, diceRoller: DiceRoller): LogonResult`

**PRD:** M-02, M-04, M-05

**Preconditions:**
- `persona == null`
- `jackpoint != null`
- `jackpoint.type` ∈ `{WORKSTATION, CONSOLE, REMOTE_DEVICE, ILLEGAL_JUNCTION_BOX}` (M-02, M-03)
- `jackpoint.connectsToHost == host` (can only log onto the host the trunk is connected to)

**Logic:**
1. Run `SystemTestResolver.resolve(decker, LOGON_TO_HOST, host.subsystemRatings.access, host.securityRating.value, diceRoller)`.
2. Increment `host.securityTally` by `outcome.hostSuccesses`.
3. If `outcome.deckerWins`: create persona; set `persona.currentNode` based on jackpoint type (M-02, M-03):
   - `WORKSTATION` → the host's Access node (I/O port)
   - `REMOTE_DEVICE` → the host's Slave node (slave controller)
   - `CONSOLE` → the host's Control node (CPU node)
   - `ILLEGAL_JUNCTION_BOX` → the host's Access node (default entry point; trunk connects to the host's SAN)

   Set `currentLocation = OnHost(updatedHost)` → `LogonResult.Success`.
4. Otherwise: `LogonResult.Failure`.

---

### 3. `logonToRtg(rtg: RTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06 (from LTG), M-07 (from another RTG), M-10 (tally reset)

**Preconditions:**
- `currentLocation is MatrixLocation.OnLTG` — only the parent RTG of that LTG is reachable
- **or** `currentLocation is MatrixLocation.OnRTG` — another RTG (long-distance hop)

**Logic:**
1. Determine whether the decker is hopping from an LTG (to its parent RTG) or from an RTG (to a peer RTG).
2. Run `SystemTestResolver.resolve(decker, LOGON_TO_RTG, rtg.subsystemRatings.access, rtg.securityRating.value, diceRoller)`.
3. Increment `rtg.securityTally` by `outcome.hostSuccesses`.
4. If different RTG (M-10): carry **no** prior RTG tally; start fresh on target RTG.
5. If `outcome.deckerWins`: `currentLocation = OnRTG(updatedRtg)` → `LogonResult.Success`.
6. Otherwise: `LogonResult.Failure`.

---

### 4. `logonToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06, M-07, M-09, M-12

Navigates to a public LTG. `LTG` and `PLTG` are sibling types — `PLTG` is **not** a subtype of `LTG`. To navigate to a PLTG, callers must invoke `logonToPltg` directly; this method does not accept or dispatch PLTG targets.

**Preconditions:**
- `currentLocation is OnRTG` and `ltg` is attached to that RTG, **or**
- `currentLocation is OnPLTG` (PLTG supports all LTG operations, M-08)

**Logic:**
1. Run `SystemTestResolver.resolve(decker, LOGON_TO_LTG, ltg.subsystemRatings.access, ltg.securityRating.value, diceRoller)`.
2. Increment `ltg.securityTally` by `outcome.hostSuccesses`.
3. **Tally persistence (M-09):** if target `ltg` shares the same parent RTG as current LTG, the RTG tally is unchanged.
4. If `outcome.deckerWins`: `currentLocation = OnLTG(updatedLtg)` → `LogonResult.Success`.
5. Otherwise: `LogonResult.Failure` (M-12: tally on target LTG persists for memory window; callers manage the timer outside this method).

**LTG failed-logon tally memory window (rules p. 218):** Public LTGs retain the accumulated security tally from a failed logon attempt for `1D3 × 5` minutes. If the decker attempts to log on again from the **same jackpoint** before this window expires, the tally continues from its current value (it is not reset to 0). If the decker switches to a **different jackpoint** before the next attempt, the LTG starts a fresh security tally at 0 for that attempt — the prior tally is not carried over. The timer and jackpoint identity are held by the caller (game engine); this method does not track them internally.

---

### 5. `logonToPltg(pltg: PLTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06, M-08, M-11, M-12

Dedicated method for navigating to a PLTG. Because `PLTG` and `LTG` are sibling types (neither is a subtype of the other), callers invoke this method directly when the destination is a PLTG — it is never dispatched from `logonToLtg`.

**Preconditions:**
- `currentLocation is OnLTG` and `pltg` is attached to that LTG, **or**
- `currentLocation is OnPLTG` (PLTG-to-PLTG hop)
- Any other location (including `OnHost`) is not a valid origin — callers must first logoff to the grid. The implementation throws `IllegalStateException` for these cases.

**Logic:**
1. Determine `inheritedTally`:
   - If current location is `OnLTG`: inherit the LTG's **parent RTG's** `securityTally` (M-11: tally carry-over comes from the RTG, not the LTG itself).
   - If current location is `OnPLTG` (PLTG-to-PLTG hop): `inheritedTally = 0`. No tally is carried from the source PLTG — each PLTG maintains an independent tally.
2. Run `SystemTestResolver.resolve(decker, LOGON_TO_LTG, pltg.subsystemRatings.access, pltg.securityRating.value, diceRoller)`.
3. Build updated PLTG: `securityTally = inheritedTally + outcome.hostSuccesses`.
4. If `outcome.deckerWins`: `currentLocation = OnPLTG(updatedPltg)` → `LogonResult.Success`.
5. Otherwise: `LogonResult.Failure`.

---

### 6. `logonToHost(host: Host, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06, M-13, M-14, M-15

**Preconditions:**
- `currentLocation is OnLTG` and `host` is attached to that LTG (open-access), **or**
- `currentLocation is OnPLTG` and `host` is in that PLTG (private-grid, M-15), **or**
- `currentLocation is OnHost` and `host` is in `currentHost.connectedHosts` (tiered/host-host, M-13/M-14)

**Tiered topology guard (M-13):** If the current host is a second-tier host and the target is another second-tier host of the same first-tier host, return `LogonResult.Failure` (must re-enter first-tier host first; enforced as a precondition violation, not a dice roll).

**Logic:**
1. Run `SystemTestResolver.resolve(decker, LOGON_TO_HOST, host.subsystemRatings.access, host.securityRating.value, diceRoller)`.
2. Increment `host.securityTally` by `outcome.hostSuccesses`.
3. If `outcome.deckerWins`: `currentLocation = OnHost(updatedHost)` → `LogonResult.Success`.
4. Otherwise: `LogonResult.Failure`.

---

### 7. `gracefulLogoff(diceRoller: DiceRoller): LogoffResult`

**PRD:** M-16

**Preconditions:**
- `currentLocation != null`

**Logic:**
1. Resolve the current grid/host's Access Rating and Security Value from `currentLocation`.
2. Determine effective TN = `accessRating`. If `decker.trackState != null`, add `trackState.trackingIcRating` to TN (CC-33: Graceful Logoff TN is raised by Track Rating while a location cycle is running).
3. Run `SystemTestResolver.resolve(decker, GRACEFUL_LOGOFF, effectiveTn, securityValue, diceRoller)`.
4. If `outcome.deckerWins`: clear persona, set `currentLocation = null` → `LogoffResult.GracefulSuccess`. (Traces cleared — security tally conceptually expunged; the caller discards the grid/host object or resets its tally.)
5. If not: decker must fall back to jack out → return `LogoffResult.JackOut(decker, dumpShock = !decker.cyberdeck.isCyberterminal)`.

**Passcode devalidation (rules p. 226):** If the decker's `PersonaStatus` is `LEGITIMATE` (acquired via a planted or stolen host passcode), the host devalidates that passcode upon successful logoff — the decker's cover is blown. The caller must set `decker.hasValidPasscode(host) = false` after a `GracefulSuccess`. The passcode is **not** devalidated if the decker used Legitimate status only against other intruding deckers (i.e., never exploited it against the host's own IC); the GM tracks this distinction narratively.

---

### 8. `jackOut(): LogoffResult`

**PRD:** M-17, M-18

**Preconditions:**
- `currentLocation != null`
- Decker is **not** pinned by Black IC (caller must check; this method does not know about combat state; a precondition `IllegalStateException` is thrown if `pinnedByBlackIC == true`)

**Logic:**
1. Clear persona, set `currentLocation = null`.
2. Return `LogoffResult.JackOut(updatedDecker, dumpShock = !decker.cyberdeck.isCyberterminal)`.

Dump shock damage (Power = host Security Value, damage level from Security Code) is applied separately by the caller using the existing combat/damage infrastructure.

**Passcode devalidation (rules p. 226):** Same rule as `gracefulLogoff` applies on jack-out: if `PersonaStatus == LEGITIMATE`, the host devalidates the passcode. The caller must mark the passcode invalid after jack-out completes.

---

## Security Tally Summary

| Scenario | Tally Behavior |
|---|---|
| Logon to LTG (attempt, win or lose) | Add `hostSuccesses` to LTG/parent RTG tally |
| Switch to sibling LTG (same RTG) | RTG tally unchanged; LTG shares RTG tally |
| Move to different RTG | New RTG tally starts at 0 |
| Enter PLTG from public grid | PLTG tally starts at current RTG tally value |
| PLTG-to-PLTG hop | New PLTG tally starts at 0 — no carry-over from source PLTG |
| Logon to Host | Add `hostSuccesses` to host tally (separate from grid tally) |
| Graceful Logoff (success) | System tally cleared (caller responsibility) |
| New decker logs on while host is mid-reset | Tally starts at the current reduced value at time of intrusion (not at 0) |
| Failed LTG logon; retry from same jackpoint within memory window | LTG tally continues from current value; not reset to 0 |
| Failed LTG logon; retry from different jackpoint | LTG tally reset to 0 for the new attempt |

---

## System Reset Mechanics

After a decker logs off (gracefully or by jack-out), grids and hosts reset their security tallies on the following schedule (rules p. 212):

**Blue systems:** Reset completely in 2D6 minutes; security tally drops to 0.

**Green / Orange / Red systems (no alert triggered):** Begin to reset after 3D6 minutes, provided no Passive or Active Alert was triggered during the run.

**Green / Orange / Red systems (alert was triggered):** Roll 1D6 every:

- **Green:** 5 minutes
- **Orange:** 10 minutes
- **Red:** 15 minutes

Reduce the security tally by the roll result each interval. Any IC that was still running when the decker logged off remains active until the tally drops below the trigger step that activated it.

If a new decker logs on illegally **before** the reset finishes, that decker's initial tally is the current reduced value (not 0). This is already captured in the Security Tally Summary table above.

The reset timer and interval logic are held entirely by the game engine (caller); no `Host` or `LTG` method is responsible for self-decrementing the tally over time.

---

## Topology Navigation Rules

| Topology | Navigation rule (M-13 / M-14) |
|---|---|
| Open Access | Any host on the LTG is reachable directly |
| Tiered | First-tier host only; second-tier requires going through first-tier first |
| Host-Host | Must traverse in chain order; no shortcuts |
| Private Grid | Any host on the PLTG is reachable directly once on the PLTG |

The `logonToHost` method enforces topology by validating that `host` appears in the reachable set derived from `currentLocation`. A helper `reachableHosts(location: MatrixLocation): Set<Host>` can encapsulate this.

---

## Verification

| Scenario | Expected Result |
|---|---|
| Telecom jackpoint calls `jackInToLtg` | Precondition OK; dice rolled |
| Workstation jackpoint calls `jackInToHost` | `LogonResult.Success`; `persona.currentNode` = Access node |
| Remote-device jackpoint calls `jackInToHost` | `LogonResult.Success`; `persona.currentNode` = Slave node |
| Console jackpoint calls `jackInToHost` | `LogonResult.Success`; `persona.currentNode` = Control node |
| Illegal junction-box jackpoint calls `jackInToHost` | `LogonResult.Success`; `persona.currentNode` = Access node |
| Telecom jackpoint calls `jackInToHost` | `IllegalStateException` (jackpoint type not allowed) |
| Workstation jackpoint calls `jackInToLtg` | `IllegalStateException` |
| Successful `jackInToLtg` | `LogonResult.Success`, `currentLocation = OnLTG`, tally incremented |
| Failed `jackInToLtg` | `LogonResult.Failure`, persona null, tally incremented |
| `logonToRtg` to different RTG | New RTG, prior tally not carried |
| `logonToLtg` same-RTG sibling | RTG tally unchanged |
| `logonToPltg` called directly from OnLTG | PLTG tally initialized from RTG tally |
| `logonToPltg` called from OnPLTG (PLTG-to-PLTG hop) | New PLTG tally starts at 0 |
| `logonToHost` from second-tier to sibling second-tier | Precondition violation returned |
| `gracefulLogoff` success | `GracefulSuccess`, `currentLocation = null`, no dump shock |
| `gracefulLogoff` with Track rating 4 active | Effective TN = `accessRating + 4`; standard test otherwise (CC-33) |
| `gracefulLogoff` failure | `JackOut(dumpShock = !cyberdeck.isCyberterminal)` |
| `jackOut()` with no graceful logoff | `JackOut(dumpShock = !cyberdeck.isCyberterminal)` |
| `jackOut()` pinned by Black IC | `IllegalStateException` |
| `gracefulLogoff` with Legitimate passcode | Caller marks passcode invalid after `GracefulSuccess` |
| `jackOut()` with Legitimate passcode | Caller marks passcode invalid after jack-out |
| Failed LTG logon; retry same jackpoint within 1D3×5 min | LTG tally continues from current value |
| Failed LTG logon; retry from different jackpoint | LTG tally reset to 0 for new attempt |
