# Correctness Review — game_logic

## Summary

The game_logic component is well-structured and the core system-test resolution, tally tracking, and initiative loop are largely correct. However, several serious gameplay bugs exist in the IC action implementations: Blaster, Sparky, and Ripper each fail to apply one or more required damage phases (persona CM damage, body damage, or MPCP reduction), meaning entire categories of IC combat damage are silently dropped. Probe IC never writes its tally successes back to the host. The `jackOut` pin-check relies on a caller-supplied boolean instead of the decker's own internal `blackIcPin` state, making it trivially bypassable. `analyzeIcon` double-applies the Analyze utility modifier by pre-computing the TN and then passing it to `SystemTestResolver.resolve`, which applies the same modifier again. Interrogation operations default to an empty query string that matches every file name, breaking the `NotFound` branch. A smaller but real arithmetic error in `NullOperationModifier.totalBonusForDuration` fires the 12-hour extra increment 11 hours too early. Several medium-severity issues round out the review: `performLogon` always creates an INTRUDING persona with no path for legitimate access, triggered IC is invisible to the decker's `visibleObjects`, and tally successes from a failed graceful logoff are lost.

## Findings

---

### [HIGH] Blaster.action drops persona CM damage — only MPCP is reduced

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:114–129
**Issue:** When Blaster hits, `resolveBlaster` returns an `AttackResult.Hit` carrying staged damage levels. The code calls `resolveBlasterMpcpTest` to reduce MPCP, then writes the updated decker back to context. The `AttackResult.Hit` result is type-checked but never used: `applyIcDamage` is never called, so the persona's condition monitor is never decremented. Every Blaster hit silently discards the icon damage and only reduces the MPCP.
**Recommendation:** After `resolveBlasterMpcpTest` returns the MPCP-reduced decker, pass that decker and the original `result` into `CombatResolver.applyIcDamage` to apply persona CM damage. Use the returned `IcDamageResult.updatedDecker` as the value written to context.

---

### [HIGH] Sparky.action drops persona CM damage and body damage — MPCP successes discarded

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:143–158
**Issue:** `resolveSparkyMpcpTest` returns `Pair<Decker, Int>` where the second element is `sparkySuccesses` needed by `resolveSparkyBodyDamage`. The action destructures this as `val (updated, _)`, discarding the success count. Neither `applyIcDamage` (persona CM) nor `resolveSparkyBodyDamage` (physical body damage) is ever called. Every Sparky hit only reduces MPCP; the persona and physical body take no damage.
**Recommendation:** Capture the sparky successes, call `CombatResolver.applyIcDamage` on the MPCP-reduced decker to apply persona damage, then call `CombatResolver.resolveSparkyBodyDamage` with the saved successes if the initial hit registered, and write the fully-updated decker back to context.

---

### [HIGH] Ripper.action never calls resolveRipperMpcpTest — MPCP attack phase missing

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:131–141
**Issue:** `resolveRipper` applies the attribute reduction and returns a `CripplerResult`. The second phase of a Ripper attack — `CombatResolver.resolveRipperMpcpTest` — is never called. Every Ripper hit reduces an attribute but leaves the MPCP entirely unaffected.
**Recommendation:** After applying `resolveRipper`, call `CombatResolver.resolveRipperMpcpTest(result.updatedDecker, this, diceRoller)` and write the final decker (with both attribute reduction and MPCP damage) back to context.

---

### [HIGH] Probe.action logs tally but never applies it to the host

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:80–89
**Issue:** `CombatResolver.resolveProbe` returns the number of successes. The action logs the value in the return message ("Probe added $tallyPoints tally against ...") but never calls `context.updateDecker`, `context.updateHost`, or any tally-increment path. The security tally is never actually incremented; Probe IC has zero mechanical effect.
**Recommendation:** After resolving probe successes, update the decker's location-host tally (via `GameContext.applyDeckerOperationResult` or equivalent) and call `context.checkTriggers` with the old and new tally values so trigger steps fire correctly.

---

### [HIGH] Decker.jackOut pin-check uses caller-supplied boolean, ignores internal blackIcPin state

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:329–337
**Issue:** `jackOut(pinnedByBlackIc: Boolean = false)` checks `!pinnedByBlackIc` rather than `!this.isPinnedByBlackIc`. Any caller that passes `false` (including the default) bypasses the pin entirely, even when `this.blackIcPin != null`. A decker with an active `BlackIcPinState` can call `jackOut()` (default arg) and escape without a fight.
**Recommendation:** Replace the parameter check with `check(!isPinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }` and remove the `pinnedByBlackIc` parameter entirely, or at least use the internal state as the authoritative source.

---

### [HIGH] Decker.analyzeIcon double-applies Analyze utility modifier

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:670–679
**Issue:** `analyzeIcon` pre-computes `tn` as `max(2, control - sensor - analyzeRating)`, then passes that `tn` as `accessRating` to `SystemTestResolver.resolve`. Inside `resolve`, the Analyze utility is looked up again (because `ANALYZE_ICON.utility == UtilityType.ANALYZE`) and subtracted a second time: `effectiveTn = max(2, tn - analyzeRating)`. For example, with control=10, sensor=2, analyze=3: pre-computed tn=5, resolver then produces effectiveTn=max(2,5-3)=2 instead of the correct 5.
**Recommendation:** Either pass the raw `host.subsystemRatings.control` to `SystemTestResolver.resolve` and let the resolver handle all modifier subtraction (including sensor, if appropriate), or pass the pre-computed `tn` but call `diceRoller.roll` directly rather than going through `resolve`.

---

### [HIGH] Decker.locateFile / locateSlave — default empty query matches every name

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:750–771, 778–802
**Issue:** When there is no existing interrogation state, a new `InterrogationState` is created with `query = ""`. Because `String.contains("")` always returns `true`, `locateFile` will match the first file on the host when 5+ successes accumulate, regardless of what the decker is searching for. Symmetrically, the `LocateResult.NotFound` branch (`accumulatedSuccesses >= 3 && host.dataFiles.none { contains(query) }`) can never fire when query is `""`, since every file name contains the empty string. `locateSlave` has the identical defect for remote devices.
**Recommendation:** Require the caller to supply a non-empty query string as a parameter to `locateFile` and `locateSlave`, and populate the initial `InterrogationState` with it. Guard against an empty query with a `require(query.isNotBlank())` check.

---

### [MEDIUM] NullOperationModifier.totalBonusForDuration — off-by-one in 12-hour increment boundary

**File:** src/main/kotlin/com/shadowrun/matrix/operations/NullOperationModifier.kt:26–32
**Issue:** The extra-increment formula is `(seconds - 3600) / 43200`. The early-return guard is `if (seconds < 43200) return base`, meaning extras only apply at 12 h+. But `(43200 + any_positive - 3600) / 43200` gives the first extra increment at 46 800 s (13 h) rather than at 86 400 s (24 h = first full additional 12-hour window). The formula should subtract `43200` (one full 12-hour window), not `3600` (one hour). As written, every 12-hour boundary fires 11 hours early.

Example: at 46 800 s (13 h) the code returns 5; at 86 400 s (24 h) it still returns 5 (no second increment yet). With the corrected formula the first extra would fire at 86 400 s and the second at 129 600 s.
**Recommendation:** Change the formula to `val extraIncrements = (seconds - 43200) / 43200`.

---

### [MEDIUM] Decker.performLogon always creates an INTRUDING persona — no legitimate logon path

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1235–1248
**Issue:** Every successful logon (jack-in to LTG, jack-in to host, hop between grids) creates a new `Persona` with `status = PersonaStatus.INTRUDING`. `PersonaStatus.LEGITIMATE` exists and is used in `CombatResolver.attackTn` (giving more favourable TN values) and in `GameContext.unauthorizedDeckerInHost`, but is unreachable in practice. Corporate employees with valid access codes, licensed deckers, and similar actors are all treated as intruders.
**Recommendation:** Add a `legit: Boolean = false` parameter to `performLogon` (and the public `jackIn*`/`logon*` methods where applicable) and set `status = if (legit) PersonaStatus.LEGITIMATE else PersonaStatus.INTRUDING` accordingly.

---

### [MEDIUM] GameContext.checkTriggers adds IC to activeIc but not to host.icPrograms — triggered IC invisible to decker

**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:49–58
**Issue:** IC activated by a trigger step is added to `context.activeIc` so it can take combat turns. However, `host.icPrograms` is not updated. `Decker.visibleObjects()` builds its IC list from `loc.host.icPrograms`, so newly spawned IC is never shown in the decker's visible objects list. The decker cannot notice, analyze, or react to IC that was just triggered.
**Recommendation:** When activating trigger-step IC, also call `updateHost(host.copy(icPrograms = host.icPrograms + step.activatedIc))` so the host's IC roster stays in sync with `activeIc`.

---

### [MEDIUM] Decker.gracefulLogoff discards host successes on failure — tally not incremented

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:303–322
**Issue:** On a failed graceful logoff, the decker's `currentLocation` is cleared to `null`, so `withUpdatedTally` cannot be applied (no location to attach the delta to). The host's security successes from the logoff System Test are silently lost. By contrast, all other operations call `withUpdatedTally` before returning.  There is also no `applyDeckerOperationResult` equivalent for logoff that would push the tally increase to `GameContext`.
**Recommendation:** Before clearing `currentLocation`, record the tally delta from the failed logoff and have the caller (game loop or `GameContext`) apply the increment to the live host, then call `checkTriggers`.

---

### [LOW] Decker.resolvePointerChain uses exploding dice for 1D6 — non-uniform chain length

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1030
**Issue:** The chain length is computed as `diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 }`. `DiceRoller.rollOne()` explodes on a 6 (rerolls and adds), so `it.dice.first()` can be 7 or higher. `value % 6 + 1` maps 7→2, 8→3, 12→1, 13→2, etc., producing a biased distribution rather than a uniform 1–6.
**Recommendation:** Generate the chain length directly: `val chainLength = (diceRoller.roll(1, 6).dice.first() - 1) % 6 + 1` — but more simply, inject a raw random value or use a dedicated helper that rolls a plain d6 without the exploding mechanic.

---

### [LOW] Decker.bufferMessage word count inflated by leading/trailing whitespace

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1190–1191
**Issue:** `text.split("\\s+".toRegex())` on a string with a leading space produces a leading empty token, inflating the apparent word count by 1 (or 2 with trailing space). A 100-word message padded with a leading space is rejected as 101 words.
**Recommendation:** Trim the string before splitting: `text.trim().split("\\s+".toRegex())`.

---

### [INFO] SystemTestResolver.effectiveRating uses immuneToDumpShock as cyberterminal proxy

**File:** src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116–118
**Issue:** The CT-03 –1 utility penalty is gated on `deck.immuneToDumpShock`, which the comment in `Cyberdeck` also lists as true for hitcher observers (ACC-03). If a non-cyberterminal deck were ever created with `immuneToDumpShock = true`, it would incorrectly receive the utility penalty. The flag name does not communicate the cyberterminal rating-reduction intent.
**Recommendation:** Add a dedicated `isCyberterminal: Boolean` field to `Cyberdeck` set by the `Cyberterminal()` factory, and key the CT-03 logic on that field.

---

### [INFO] Decker.loadUtility turnsRequired == 0 branch unreachable for any real utility

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:361–365
**Issue:** `turnsRequired = ceil(mpSize / ioSpeed)`. For any utility with `rating ≥ 1`, `mpSize = rating² × multiplier ≥ 1`, so `ceil(≥1 / ioSpeed) ≥ 1`. The `if (turnsRequired == 0)` branch — which would add the utility directly to `activeUtilities` — is dead code for all valid utilities. A zero-rated utility bypassing the pending-upload queue would be immediately auto-unloaded by `advanceCombatTurn` anyway.
**Recommendation:** Either remove the dead branch and always use `pendingUploads`, or add a code comment explaining that this is an intentional defensive guard for hypothetical zero-size utilities.

---

## No Issues Found In

- `Game.runCombatTurn` / `buildInitiativeList` — initiative scoring and turn ordering are correct; the -10 per pass is properly implemented.
- `GameContext.updateDecker` / `updateHost` — identity-based update with safe error logging is correct.
- `GameContext.checkTriggers` tally range — `(oldTally + 1)..newTally` correctly fires exactly once per threshold crossing per call.
- `SystemTestResolver.resolve` / `resolveInterrogation` — System Test mechanics (utility reduction, DF, decker-wins-ties) are correctly implemented.
- `CombatResolver.stage` — integer-division staging is correct for both positive and negative net successes.
- `CombatResolver.resolveLethalBlackIc` / `resolveNonLethalBlackIc` — dual-track damage (icon + body/mental) and the final MPCP blaster shot on kill are correctly sequenced.
- `CombatResolver.suppressIc` / `unsuppressIc` — suppression DF penalty and deferred tally are correctly modelled.
- `Decker.advanceCombatTurn` — upload countdown, promotion to active, depleted-utility cleanup, and track-state decrement are all correct.
- `Decker.withUpdatedTally` — location-specific tally accumulation correctly handles all four location types.
- `AlertTransitions.applyAlertTransition` — Passive Alert raises all five subsystem ratings by +2; Active Alert sets status only; NO_ALERT is a no-op; all correct.
- `Cyberdeck` init constraints — MPCP caps on persona and utility ratings, active memory capacity, and response-increase limits are all properly enforced.
- `DiceRoller.roll` — exploding-six mechanic and success counting are correct.
