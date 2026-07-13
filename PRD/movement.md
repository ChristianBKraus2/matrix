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
        val location: MatrixLocation
    ) : LogonResult()

    /** Decker failed the System Test; still at previous location. */
    data class Failure(
        val decker: Decker,
        val location: MatrixLocation   // unchanged previous location (or null if jacking in)
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

The seven public movement methods are added to this class (see below).

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
        targetNumber: Int,          // subsystem rating (Access Rating)
        hostSecurityValue: Int,     // host/grid Security Value dice
        diceRoller: DiceRoller
    ): SystemTestOutcome
}
```

**Algorithm** (per rulebook p. 209–210):
1. Roll `decker.computerSkill` dice; count successes ≥ `targetNumber` → `deckerSuccesses`.
   - Applied modifier: subtract the rating of a loaded Deception utility (if any) from `targetNumber` (minimum 2).
2. Roll `hostSecurityValue` dice; count successes ≥ `decker.detectionFactor` → `hostSuccesses`.
3. `deckerWins = deckerSuccesses >= hostSuccesses`.
4. Return `SystemTestOutcome(deckerSuccesses, hostSuccesses, deckerWins)`.

`Decker.detectionFactor` is derived from `cyberdeck.masking + sleaze utility rating / 2` (already implied by ORD; add as computed property).

---

## Public Methods on `Decker`

All seven methods are **pure**: they take immutable inputs and return new `Decker` instances (via `.copy()`) plus a `MatrixLocation` carrying updated security tallies. The caller is responsible for persisting the returned objects.

### 1. `jackInToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-01, M-04, M-05

**Preconditions:**
- `persona == null` (not already jacked in)
- `jackpoint != null`
- `jackpoint.type` ∈ `{LEGAL_ACCESS, ILLEGAL_ACCESS, TELECOM, ILLEGAL_JUNCTION_BOX}`

**Logic:**
1. Run `SystemTestResolver.resolve(decker, ltg.subsystemRatings.access, ltg.securityRating.value, diceRoller)`.
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
- `jackpoint.type` ∈ `{WORKSTATION, CONSOLE, REMOTE_DEVICE}` (M-02)
- `jackpoint.connectsToHost == host` (can only log onto the controlling host)

**Logic:**
1. Run `SystemTestResolver.resolve(decker, host.subsystemRatings.access, host.securityRating.value, diceRoller)`.
2. Increment `host.securityTally` by `outcome.hostSuccesses`.
3. If `outcome.deckerWins`: create persona, set `currentLocation = OnHost(updatedHost)` → `LogonResult.Success`.
4. Otherwise: `LogonResult.Failure`.

---

### 3. `logonToRtg(rtg: RTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06 (from LTG), M-07 (from another RTG), M-10 (tally reset)

**Preconditions:**
- `currentLocation is MatrixLocation.OnLTG` — the parent RTG of that LTG, **or** any RTG connected via `connectedRtgs`
- **or** `currentLocation is MatrixLocation.OnRTG` — another RTG (long-distance hop)

**Logic:**
1. Determine whether the target `rtg` is the parent of the current LTG, or a peer RTG.
2. Run `SystemTestResolver.resolve(decker, rtg.subsystemRatings.access, rtg.securityRating.value, diceRoller)`.
3. Increment `rtg.securityTally` by `outcome.hostSuccesses`.
4. If different RTG (M-10): carry **no** prior RTG tally; start fresh on target RTG.
5. If `outcome.deckerWins`: `currentLocation = OnRTG(updatedRtg)` → `LogonResult.Success`.
6. Otherwise: `LogonResult.Failure`.

---

### 4. `logonToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06, M-07, M-08, M-09, M-11, M-12

Covers both public LTG (from RTG) and PLTG (from LTG/RTG, using `Logon to LTG` operation on a PLTG target).

**Preconditions:**
- `currentLocation is OnRTG` and `ltg` is attached to that RTG, **or**
- `currentLocation is OnLTG` and `ltg` is a PLTG (`ltg is PLTG`) attached to that LTG, **or**
- `currentLocation is OnPLTG` (PLTG supports all LTG operations, M-08)

**Logic:**
1. Run `SystemTestResolver.resolve(decker, ltg.subsystemRatings.access, ltg.securityRating.value, diceRoller)`.
2. Increment `ltg.securityTally` by `outcome.hostSuccesses`.
3. **Tally inheritance (M-11):** if target `ltg` is a PLTG and current location is on an RTG or LTG, carry over the accumulated RTG tally into the PLTG's initial tally.
4. **Tally persistence (M-09):** if target `ltg` shares the same parent RTG as current LTG, the RTG tally is unchanged.
5. If `outcome.deckerWins`: `currentLocation = OnLTG(updatedLtg)` (or `OnPLTG` if the type is `PLTG`) → `LogonResult.Success`.
6. Otherwise: `LogonResult.Failure` (M-12: tally on target LTG persists for memory window; callers manage the timer outside this method).

---

### 5. `logonToHost(host: Host, diceRoller: DiceRoller): LogonResult`

**PRD:** M-06, M-13, M-14, M-15

**Preconditions:**
- `currentLocation is OnLTG` and `host` is attached to that LTG (open-access), **or**
- `currentLocation is OnPLTG` and `host` is in that PLTG (private-grid, M-15), **or**
- `currentLocation is OnHost` and `host` is in `currentHost.connectedHosts` (tiered/host-host, M-13/M-14)

**Tiered topology guard (M-13):** If the current host is a second-tier host and the target is another second-tier host of the same first-tier host, return `LogonResult.Failure` (must re-enter first-tier host first; enforced as a precondition violation, not a dice roll).

**Logic:**
1. Run `SystemTestResolver.resolve(decker, host.subsystemRatings.access, host.securityRating.value, diceRoller)`.
2. Increment `host.securityTally` by `outcome.hostSuccesses`.
3. If `outcome.deckerWins`: `currentLocation = OnHost(updatedHost)` → `LogonResult.Success`.
4. Otherwise: `LogonResult.Failure`.

---

### 6. `gracefulLogoff(diceRoller: DiceRoller): LogoffResult`

**PRD:** M-16

**Preconditions:**
- `currentLocation != null`

**Logic:**
1. Resolve the current grid/host's Access Rating and Security Value from `currentLocation`.
2. Run `SystemTestResolver.resolve(decker, accessRating, securityValue, diceRoller)`.
3. If `outcome.deckerWins`: clear persona, set `currentLocation = null` → `LogoffResult.GracefulSuccess`. (Traces cleared — security tally conceptually expunged; the caller discards the grid/host object or resets its tally.)
4. If not: decker must fall back to jack out → return `LogoffResult.JackOut(decker, dumpShock = true)`.

---

### 7. `jackOut(): LogoffResult`

**PRD:** M-17, M-18

**Preconditions:**
- `currentLocation != null`
- Decker is **not** pinned by Black IC (caller must check; this method does not know about combat state; a precondition `IllegalStateException` is thrown if `pinnedByBlackIC == true`)

**Logic:**
1. Clear persona, set `currentLocation = null`.
2. Return `LogoffResult.JackOut(updatedDecker, dumpShock = true)`.

Dump shock damage (Power = host Security Value, damage level from Security Code) is applied separately by the caller using the existing combat/damage infrastructure.

---

## Security Tally Summary

| Scenario | Tally Behavior |
|---|---|
| Logon to LTG (attempt, win or lose) | Add `hostSuccesses` to LTG/parent RTG tally |
| Switch to sibling LTG (same RTG) | RTG tally unchanged; LTG shares RTG tally |
| Move to different RTG | New RTG tally starts at 0 |
| Enter PLTG from public grid | PLTG tally starts at current RTG tally value |
| Logon to Host | Add `hostSuccesses` to host tally (separate from grid tally) |
| Graceful Logoff (success) | System tally cleared (caller responsibility) |

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
| Telecom jackpoint calls `jackInToHost` | `IllegalStateException` (jackpoint type not allowed) |
| Workstation jackpoint calls `jackInToLtg` | `IllegalStateException` |
| Successful `jackInToLtg` | `LogonResult.Success`, `currentLocation = OnLTG`, tally incremented |
| Failed `jackInToLtg` | `LogonResult.Failure`, persona null, tally incremented |
| `logonToRtg` to different RTG | New RTG, prior tally not carried |
| `logonToLtg` same-RTG sibling | RTG tally unchanged |
| `logonToLtg` targeting PLTG | PLTG tally initialized from RTG tally |
| `logonToHost` from second-tier to sibling second-tier | Precondition violation returned |
| `gracefulLogoff` success | `GracefulSuccess`, `currentLocation = null`, no dump shock |
| `gracefulLogoff` failure | `JackOut(dumpShock = true)` |
| `jackOut()` with no graceful logoff | `JackOut(dumpShock = true)` |
| `jackOut()` pinned by Black IC | `IllegalStateException` |
