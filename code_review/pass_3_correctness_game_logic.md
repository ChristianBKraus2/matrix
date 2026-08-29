# Correctness Review — game_logic

## Summary

The game logic layer is broadly sound: immutable data patterns are used consistently, null-safety is enforced with `require`/`check`, and the System Test resolver correctly applies utility modifiers and Detection Factor. However, four genuine correctness defects were identified. The most severe is in navigation: `logonToRtg` discards the RTG's pre-existing security tally instead of adding to it, silently losing tally state every time a decker traverses an RTG node. A second structural bug is that `TarPit.action` never invokes `resolveTarPitMpcpTest`, meaning the MPCP-test and stored-utility corruption step documented for TarPit is permanently skipped. Interrogation operations (`locateFile`, `locateSlave`, `locateAccessNode`) default to an empty query string that matches every file or device name, making the locate-by-name mechanic effectively non-functional unless callers manually pre-seed the `InterrogationState`. One additional tally-inheritance inconsistency exists in `logonToPltg`. The remaining areas are clean and the core combat and initiative logic matches expected Shadowrun 2e semantics.

## Findings

---

### [HIGH] `logonToRtg` resets RTG tally instead of accumulating it

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:103`

**Issue:** The `buildLocation` lambda in `logonToRtg` sets the new RTG's `securityTally` to `hostTallyDelta` alone:

```kotlin
buildLocation = { hostTallyDelta ->
    MatrixLocation.OnRTG(rtg.copy(securityTally = hostTallyDelta))
}
```

Every other navigation function adds the delta to the existing tally (e.g., `logonToLtg` line 131: `ltg.securityTally + hostTallyDelta`; `logonToHost` line 191: `host.securityTally + hostTallyDelta`). For `logonToRtg` the existing `rtg.securityTally` is simply discarded. A decker traversing an already-alarmed RTG resets its security tally to a tiny logon delta, silently erasing tally history.

**Recommendation:** Change the lambda body to:
```kotlin
MatrixLocation.OnRTG(rtg.copy(securityTally = rtg.securityTally + hostTallyDelta))
```
This matches the pattern used in every other navigation function.

---

### [HIGH] `TarPit.action` never invokes `resolveTarPitMpcpTest` — stored-utility corruption step is skipped

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:173`

**Issue:** `TarPit.action` calls `CombatResolver.resolveTarPit` and applies `result.updatedDecker`, but it never calls `CombatResolver.resolveTarPitMpcpTest`. The resolver for `resolveTarPit` only removes the trapped utility from *active* memory. The separate `resolveTarPitMpcpTest` function handles the MPCP dice roll and, on IC success, corrupts the utility from *both* active and stored memory permanently. Because `TarPit.action` does not call it, TarPit can never permanently corrupt a utility in storage — the decker can simply reload the utility next turn as if nothing happened. By contrast, `resolveTarPitMpcpTest` exists specifically for this purpose and is tested in isolation, but is never wired into the IC's action loop.

**Recommendation:** After `context.updateDecker(target, result.updatedDecker)`, when `result.bothCrashed` is true, call `resolveTarPitMpcpTest` on the updated decker and commit the result:

```kotlin
val result = CombatResolver.resolveTarPit(target, this, utility, diceRoller)
val finalDecker = if (result.bothCrashed)
    CombatResolver.resolveTarPitMpcpTest(result.updatedDecker, this, utility, diceRoller)
else result.updatedDecker
context.updateDecker(target, finalDecker)
```

---

### [HIGH] Interrogation operations default to empty query — `locateFile`/`locateSlave` match any target

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:175`

**Issue:** All three interrogation functions (`locateFile`, `locateSlave`, `locateAccessNode`) seed their `InterrogationState` with an empty query string when no prior state exists:

```kotlin
val state = interrogationStates.getOrDefault(
    SystemOperation.LOCATE_FILE,
    InterrogationState(SystemOperation.LOCATE_FILE, "")
)
```

The search performed on reaching the success threshold is:
```kotlin
val file = host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
```

Because `"".contains("")` is always `true`, every filename matches the empty query. The operation returns whichever file happens to be first in `host.dataFiles`, regardless of what the decker is actually searching for. The same applies to `locateSlave`. There is no API that allows a caller to set a non-empty initial query without manually constructing a `Decker` copy that pre-populates `interrogationStates` — a step that is easy to miss and not documented at the call site. The query-driven locate mechanic is therefore non-functional by default.

**Recommendation:** Add a `query: String` parameter to `locateFile` and `locateSlave` (and `locateAccessNode` where applicable) and use it as the initial state when no prior state exists. For example:

```kotlin
fun Decker.locateFile(host: Host, query: String, precision: QueryPrecision, diceRoller: DiceRoller): ...
val state = interrogationStates.getOrDefault(
    SystemOperation.LOCATE_FILE,
    InterrogationState(SystemOperation.LOCATE_FILE, query)
)
```

This makes the intent explicit and prevents silent mis-hits.

---

### [MEDIUM] `logonToPltg` inherits RTG tally instead of current LTG tally

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:148`

**Issue:** When a decker on an LTG logs on to a PLTG, the `inheritedTally` is set to `loc.ltg.parentRtg.securityTally` — the grandparent RTG's tally — rather than the current LTG's tally:

```kotlin
is MatrixLocation.OnLTG -> {
    require(loc.ltg.pltgs.contains(pltg)) { ... }
    loc.ltg.parentRtg.securityTally   // <-- should be loc.ltg.securityTally
}
```

A decker who has been operating on an LTG (accruing tally there) and then steps into an attached PLTG will carry the RTG's tally (which may be zero) rather than the LTG's tally. The `OnPLTG` case correctly uses `0` when already on a PLTG (a fresh sub-context), but the `OnLTG` case should presumably carry the LTG tally since the LTG is the direct parent of the PLTG.

**Recommendation:** Change `loc.ltg.parentRtg.securityTally` to `loc.ltg.securityTally` to carry the LTG tally forward consistently with how other upward/downward navigation handles existing tally.

---

### [LOW] Dead code in `loadUtility`: `turnsRequired == 0` branch is unreachable for any real utility

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt:24`

**Issue:** `turnsRequired` is computed as `ceil(utility.mpSize / ioSpeedMpPerTurn)`. For any utility with `mpSize > 0` and `ioSpeedMpPerTurn > 0`, `ceil(positive / positive) >= 1`, so `turnsRequired` can never be zero. The branch `if (turnsRequired == 0)` that would add the utility instantly to `activeUtilities` is dead code. Every utility, even one with 1 Mp on a fast deck, goes through `pendingUploads` and requires at least one call to `advanceCombatTurn` before it becomes active.

**Recommendation:** Remove the `if (turnsRequired == 0)` branch (or replace with `if (utility.mpSize == 0)` if truly instant-load semantics are desired for zero-size utilities) to avoid misleading readers about when instant loading occurs.

---

### [INFO] `applyIcDamage` contains a dead `ic is BlackIC` branch — Black IC never routes through this method

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:96`

**Issue:** `applyIcDamage` has a branch `when { ic is BlackIC -> ... }` that pins the decker. In practice, `LethalBlackIC.action` and `NonLethalBlackIC.action` use `resolveLethalBlackIc` / `resolveNonLethalBlackIc` directly (which apply the pin themselves) and never call `applyIcDamage`. The other callers (`Killer.action`, `Blaster.action`) pass `WhiteIC` / `GrayIC` instances for which `ic is BlackIC` is false. The Black IC pin logic in `applyIcDamage` is therefore unreachable in the current codebase.

**Recommendation:** Remove the `ic is BlackIC` branch from `applyIcDamage` to prevent confusion about which code path actually pins the decker (the dedicated resolvers). Add a comment on the method signature noting it is not intended for Black IC types.

## No Issues Found In

- `Game.kt` — initiative list construction, combat turn loop, and out-of-combat turn loop are correct; the `toList()` defensive copy in `runOutOfCombatTurn` correctly avoids concurrent modification.
- `GameContext.kt` — `updateDecker`, `updateHost`, `checkTriggers`, `addToSecurityTally`, and `applyDeckerOperationResult` are correct; trigger range `(oldTally + 1)..newTally` correctly fires each step exactly once per monotonically increasing tally.
- `DeckerExtensions.kt` (game package) — `asDefenderParticipant` is correct.
- `SystemTestResolver.kt` — utility modifier application, Detection Factor lookup, interrogation accumulation, and cyberterminal rating reduction are all correct.
- `CombatResolver.kt` — initiative rolls, attack/defence staging, `stage()` function, Probe, Crippler, Ripper, Blaster, Sparky, Black Hammer, Killjoy, resolveLethalBlackIc, resolveNonLethalBlackIc, resolveJackOutWithPin, Track lock, and IC suppression are all correct.
- `Decker.kt` — `hackingPool`, `detectionFactor`, `effectiveDetectionFactor`, `suppressionDfPenalty`, `actionsPerTurn`, `visibleObjects`, `availableActions`, and `withUpdatedTally` are correct.
- `DeckerNavigationExtensions.kt` — `jackInToLtg`, `jackInToHost`, `logonToLtg`, `logonToHost`, `gracefulLogoff`, `jackOut`, and `performLogon` persona construction are correct (aside from the RTG tally and PLTG tally issues noted above).
- `DeckerMemoryExtensions.kt` — `unloadUtility`, `swapUtility`, `advanceCombatTurn` (upload promotion and Track state decay) are correct.
- `Cyberdeck.kt` — memory accounting, MPCP constraints, and `detectionFactor` formula are correct.
- `Persona.kt` — attribute accessors are correct.
- `DiceRoller.kt` — exploding-dice mechanic and success counting are correct.
- `IC.kt` — `WhiteIC`, `GrayIC`, `LethalBlackIC`, `NonLethalBlackIC` action dispatch (except TarPit MPCP test gap noted above).
- `Host.kt`, `Grid.kt`, `Node.kt`, `SecuritySheaf.kt` — data model definitions are correct.
- `Enums.kt`, `SharedTypes.kt` — enum definitions, `ConditionMonitor` damage application, `SubsystemRatings.get` are correct.
- `SystemOperation.kt`, `OperationResult.kt`, `AvailableAction.kt`, `MatrixObject.kt` — data definitions are correct.
