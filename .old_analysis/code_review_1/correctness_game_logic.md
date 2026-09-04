---
# Correctness Review — game_logic

## Summary

The game logic layer is structurally sound and the data-flow model (immutable Decker copies carrying tally in their location, GameContext propagating via `updateHost`) is coherent. However, several concrete correctness defects exist. The most impactful are: three IC types (Blaster, Sparky, Probe) have combat effects that are computed but silently discarded so they never reach game state; `jackOut()` trusts a caller-supplied boolean instead of the decker's own internal `isPinnedByBlackIc`, making Black IC pins trivially bypassable; and `analyzeIcon` double-applies the Analyze utility to the target number by computing the reduction manually and then also passing an operation type that causes the resolver to subtract it a second time. A dice-formula error in `resolvePointerChain` produces an incorrect distribution (chain length 1 is structurally impossible). These findings are concentrated in `IC.kt`, `Decker.kt`, and `CombatResolver.kt`.

---

## Findings

### [HIGH] `jackOut()` Black IC pin guard trusts caller parameter, not internal state
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:328
**Issue:** The function signature is `fun jackOut(pinnedByBlackIc: Boolean = false)` and the guard is `check(!pinnedByBlackIc)`. The default is `false`, so any call to `decker.jackOut()` (without the named argument) passes the guard unconditionally, even when `decker.blackIcPin != null`. Black IC pins can be bypassed on every standard jack-out invocation.
**Recommendation:** Remove the parameter entirely and replace the check with the decker's own internal state: `check(!isPinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }`.

---

### [HIGH] `Blaster.action()` resolves an attack but never applies the icon/persona CM damage
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:117
**Issue:** On a hit, `Blaster.action` calls `resolveBlasterMpcpTest` (MPCP rating reduction) and stores the result, but the `AttackResult.Hit` from `resolveBlaster`/`resolveAttack` is never passed to `applyIcDamage`. The persona condition-monitor damage — the primary combat outcome of the attack — is silently discarded. The decker's icon BOD roll is used to defend against an attack whose damage is never recorded.
**Recommendation:** After `resolveBlasterMpcpTest`, also call `CombatResolver.applyIcDamage(target, result, this, diceRoller)` on the original `result`, then compose both updates (persona CM + MPCP) into a single `updateDecker` call.

---

### [HIGH] `Sparky.action()` discards both icon CM damage and physical body damage
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:147
**Issue:** Same pattern as Blaster: on a hit, only `resolveSparkyMpcpTest` is called. The return is `Pair<Decker, Int>` where the `Int` is sparky-successes needed for `resolveSparkyBodyDamage`; that integer is explicitly discarded with `_`. Neither the icon persona CM damage from the attack nor the physical body damage from `resolveSparkyBodyDamage` is ever applied.
**Recommendation:** Capture both return values from `resolveSparkyMpcpTest`, apply icon damage via `applyIcDamage`, and call `resolveSparkyBodyDamage` with the captured sparky-success count. Compose all three updates before calling `context.updateDecker`.

---

### [HIGH] `Probe.action()` computes tally successes but never applies them to security tally
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:86
**Issue:** `CombatResolver.resolveProbe` returns the number of successes that should be added to the security tally. The result is stored in `tallyPoints` and referenced only in a log message. The security tally on the host is never incremented. Probe IC's primary game-mechanical purpose — raising the security tally to trigger alert transitions and activate new IC — is completely inoperative.
**Recommendation:** After resolving the probe, update the decker's tally via context: `val updatedDecker = target.withUpdatedTally(tallyPoints)` (make `withUpdatedTally` internal or add a helper), then call `context.updateDecker(target, updatedDecker)` followed by `context.checkTriggers(oldTally, newTally)`.

---

### [HIGH] `analyzeIcon` double-applies the Analyze utility to the target number
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:672
**Issue:** The method manually computes `tn = maxOf(2, control - sensor - analyze.currentRating)` and passes that as `accessRating` to `SystemTestResolver.resolve`. The resolver then looks up `ANALYZE_ICON.utility = UtilityType.ANALYZE` and subtracts `analyze.currentRating` a second time, producing an effective TN of `control - sensor - 2 × analyze_rating`. All other analyze operations (e.g., `analyzeIc`, `analyzeSubsystem`) pass the raw subsystem rating and let the resolver apply the single utility modifier.
**Recommendation:** Remove the manual utility subtraction. Pass `host.subsystemRatings.control` as `accessRating` and let the resolver handle utility reduction. If the sensor bonus is a valid PRD rule, pass `host.subsystemRatings.control - persona.sensor` but omit the analyze rating from the manual computation.

---

### [HIGH] `resolvePointerChain` dice formula makes chain length 1 structurally impossible
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1017
**Issue:** `diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 }` is intended as 1D6 (range 1–6). However, `DiceRoller.rollOne()` uses exploding d6: a face of 6 adds 6 to total and rolls again, so the stored dice value is never exactly 6 (it becomes 7+). Values that satisfy `value % 6 == 0` (i.e., multiples of 6 = 6, 12, 18…) are all structurally impossible with the exploding mechanic. Therefore `value % 6 + 1 == 1` (chain length 1) is never produced. Chain length distribution is also non-uniform because higher values (7–11, 13–17, …) accumulate extra probability mass on specific residues.
**Recommendation:** Use a non-exploding die for this purpose. Inject a separate `Random.nextInt(1, 7)` call, or add a `rollFlat(faces: Int): Int` method to `DiceRoller` that rolls without the explosion mechanic.

---

### [MEDIUM] `analyzeSecurity` returns stale `alertStatus` from parameter host, not live game context
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:689
**Issue:** `return AnalyzeSecurityResult(updatedDecker, outcome, host.securityRating, newTally, host.alertStatus)` uses `host.alertStatus` from the caller-supplied parameter. If an alert transition has been applied to the game-context host since the caller obtained their `host` reference, the returned `alertStatus` is stale. Similarly, `tallyFor(host)` compares via data-class equality including the `securityTally` field; a caller with a stale host reference will get `0` back, causing `newTally` in the result to report only the successes from this one test rather than the accumulated total.
**Recommendation:** Derive the return values from `updatedDecker.currentLocation` (which carries the live tally) rather than from the parameter. Either accept a `GameContext` reference, or document clearly that callers must pass the exact same host instance held by the decker's current location.

**Resolution (Phase 2.1):**
`GameContext.kt` now reads `oldTally` from `context.host.securityTally` (the live host in the game context) rather than from the decker snapshot, so tally accumulation and IC-trigger checks are computed against the current baseline rather than a potentially stale value.

---

### [MEDIUM] `maintainMonitoredOperation` is a no-op; monitored operation abort mechanic is unimplemented
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:936
**Issue:** The documented contract (PRD SO-13, SO-14) states that failing to supply a Free Action each initiative pass aborts the operation. The current implementation returns the handle unchanged regardless of whether the maintenance free action was supplied. There is no game-engine hook that sets `handle.active = false` if the decker skips their maintenance action. The abort mechanic is therefore inoperative.
**Recommendation:** The game engine's initiative loop needs to track whether each active monitored-operation handle was maintained each pass. Implement a per-pass maintenance flag or have the engine deactivate unmaintained handles at the end of each pass before the next icon acts.

---

### [MEDIUM] `bufferMessage` word-count check over-counts words when text has leading or trailing whitespace
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1177
**Issue:** `text.split("\\s+".toRegex())` produces empty-string tokens for leading and trailing whitespace (e.g., `" hello world "` splits to `["", "hello", "world", ""]`, size 4 instead of 2). A 100-word message with surrounding spaces would incorrectly be rejected.
**Recommendation:** Use `text.trim().split("\\s+".toRegex())` or `"\\S+".toRegex().findAll(text).count()` to count non-whitespace tokens correctly.

---

### [LOW] `Cyberdeck.init` active-memory capacity check excludes `pendingUploads`
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt:73
**Issue:** The constructor validates `activeUtilities.sumOf { it.mpSize } <= activeMemoryMp` but does not include `pendingUploads`. The `usedActiveMemoryMp` property (used at runtime) correctly sums both. A `Cyberdeck` constructed directly with both `activeUtilities` and `pendingUploads` that together exceed `activeMemoryMp` will pass the constructor check but violate the capacity invariant at runtime.
**Recommendation:** Change the init check to use `usedActiveMemoryMp <= activeMemoryMp` so the constructor and the runtime property are consistent.

---

### [LOW] `effectiveRating` comment misstates which decks receive the -1 utility penalty
**File:** src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116
**Issue:** The comment reads "Cyberterminal users have all utility ratings reduced by 1 (CT-03), floored at 0" but the code checks `deck.immuneToDumpShock`, whose own KDoc says "True for cyberterminals and hitchers." Hitchers are passive observers who cannot perform system tests, so the incorrect application is academic in practice, but the mismatch between the comment and the flag semantic creates confusion and would produce incorrect behavior if a decker cyberdeck were ever configured with `immuneToDumpShock = true` for a non-cyberterminal reason.
**Recommendation:** Add a dedicated `isCyberterminal: Boolean` flag to `Cyberdeck` and use it in `effectiveRating`. Keep `immuneToDumpShock` for the dump-shock/biofeedback immunity, which is separate.

---

## Clean Areas
- `Game.runCombatTurn` / `buildInitiativeList`: initiative ordering and 10-point decrement loop are correct.
- `GameContext.checkTriggers`: ordinal guard against alert downgrade is correct; multiple-step-in-one-jump processing iterates against the live `this.host` after each `updateHost` call.
- `GameContext.updateHost`: correctly propagates host updates to all deckers whose location references the old host instance.
- `CombatResolver.stage`: damage-level staging (net/2 shift, clamped to valid ordinals) is correct.
- `CombatResolver.resolveLethalBlackIc` / `resolveNonLethalBlackIc`: dual CM damage (icon + physical/mental), conditional MPCP kill-shot, and pin state are all handled consistently.
- `Decker.loadUtility` / `advanceCombatTurn`: upload countdown, promotion to active, auto-unload on depletion, and storage cleanup are logically consistent.
- `Decker.performLogon`: persona construction from persona programs on first logon is correct; tally is carried into the new location via `buildLocation(outcome.hostSuccesses)`.
- `SystemTestResolver.resolve` and `resolveInterrogation`: TN computation (subsystem rating minus utility, floored at 2), cyberterminal penalty, and accumulation of interrogation successes are consistent with each other.
- `DiceRoller`: exploding-d6 mechanic and success counting are correctly implemented.
- `DeckerLoader` / `GridLoader` / `HostLoader`: YAML parsing, catalog fallback, and active/stored utility partition are straightforward and correct for their scope.
---
