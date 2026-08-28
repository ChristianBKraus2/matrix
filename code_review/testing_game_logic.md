---
# Testing Review — game_logic

## Summary

The game-logic test suite is broad and reasonably well-structured. Most of the lower-level mechanics (stage helper, CombatModifiers, initiative rolling, IC attribute reduction, dump shock, Black IC kill shots, suppression/unsuppression, interrogation accumulation) are covered with deterministic dice stubs and exact assertions. However, two silent production bugs — Sparky never applying physical body damage and Blaster never applying persona icon damage in their `action()` implementations — go completely undetected because the IC action tests only check the return type and message string. Several helper-level assertions are vacuous (always-true regardless of game state). The `invokeMediac` operation has zero test coverage. The 1D6 simulation in `resolvePointerChain` is arithmetically wrong and untested. The `runCombatTurn` initiative ordering test drives a hand-rolled copy of the loop rather than the actual method. Taken together, the suite tests the lower-level resolver functions well but leaves the integration layer between IC `action()` and the resolver pipeline largely unverified.

## Findings

### CRITICAL — Sparky.action() silently drops physical body damage
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:152
**Issue:** `Sparky.action()` calls `CombatResolver.resolveSparkyMpcpTest(...)` and discards the second return value (the successes count via `val (updated, _) = ...`). It never calls `CombatResolver.resolveSparkyBodyDamage(...)`, so physical body damage from Sparky is never applied to the decker. The corresponding test in `GameTest` only asserts `assertIs<ActionResult.IcAttack>(result)` and `result.message.contains("Sparky")`, which passes regardless of whether body damage is applied or not.
**Recommendation:** Add an assertion to the Sparky test verifying `ctx.deckers[0].physicalConditionMonitor.damage > 0` after a hit with all-success dice. Also fix `Sparky.action()` to call `resolveSparkyBodyDamage` with the discarded successes value.

### CRITICAL — Blaster.action() silently drops persona icon damage
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:117
**Issue:** `Blaster.action()` calls `CombatResolver.resolveBlaster(...)` to determine a hit, then on `AttackResult.Hit` calls only `resolveBlasterMpcpTest` — it never calls `CombatResolver.applyIcDamage`. So a Blaster hit reduces the MPCP but leaves the persona condition monitor untouched. The test `Blaster hit updates decker` only checks that the result is `ActionResult.IcAttack` and that the message contains "Blaster", giving no signal about this omission.
**Recommendation:** Add an assertion checking that `ctx.deckers[0].persona!!.conditionMonitor.damage > 0` after a Blaster hit. Also audit `Blaster.action()` for the missing `applyIcDamage` call.

### HIGH — loseRoller produces decker wins when TN ≤ 3
**File:** src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt:107
**Issue:** `loseRoller = fixedRoller(3)` — all dice show face 3. At target numbers 2 or 3 the decker accumulates the same number of successes as the host (both succeed on every die). Because `SystemTestResolver` uses `deckerSuccesses >= hostSuccesses` to determine the winner, a tie is a decker win. Any test that asserts `Failure` using `loseRoller` against a TN ≤ 3 would produce a false Success. The same pattern appears in `SystemOperationsTest`. Current tests avoid this by pairing `loseRoller` with very high TNs (12, etc.), but the contract is fragile and could mislead future writers.
**Recommendation:** Rename to `hostWinsRoller` and add a comment warning that it only reliably produces decker failure when TN ≥ 4. Alternatively, use a two-phase stub (decker dice fail, host dice succeed) to make the intent unambiguous.

### HIGH — resolvePointerChain 1D6 simulation has wrong distribution
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1017
**Issue:** The comment says "1D6" but the expression is `diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 }`. `roll(1, 2)` rolls one d6 (exploding) with TN=2, returning values 1–12+. Applying `% 6 + 1`: face 1→2, 2→3, 3→4, 4→5, 5→6, 6→1, 7→2, 8→3 … The result 1 is only produced when the raw die is exactly 6, which is comparatively rare (it also ends the exploding chain, meaning the next roll would add further). Values 2–6 all occur at normal frequency; value 1 is underrepresented. No test verifies chain lengths or their range.
**Recommendation:** Use a dedicated `rollD6()` call (or just sample `nextInt(1, 7)` directly) instead of abusing `roll()` with modular arithmetic. Add a test asserting that a pointer chain always has 1–6 links.

### HIGH — invokeMediac has zero test coverage
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:974
**Issue:** `invokeMediac` is the only healing mechanism for persona damage. It has four distinct TN brackets (4/5/6 based on filled boxes), Medic utility depletion logic, auto-removal of the Medic from both active and stored memory when its rating reaches 0, and a guard against calling it at 10 boxes (Deadly). None of these paths have any unit or integration test.
**Recommendation:** Add tests covering: (a) TN=4 at 1–3 filled boxes, repairs correctly; (b) TN=5 at 4–6 filled boxes; (c) Medic depletes from rating 1 to 0, is removed from active and stored memory; (d) calling at 10 boxes throws IllegalArgumentException.

### HIGH — Gray IC action tests verify messages, not game state
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:382, 396, 410
**Issue:** The tests for Blaster, Ripper, and Sparky in `GameTest` follow the pattern: get a result, `assertIs<ActionResult.IcAttack>`, `assertTrue(result.message.contains("X"))`. They do not assert any change to decker game state (MPCP value, persona attribute, condition monitor, body damage). A complete no-op implementation of any of these IC actions would pass all three tests. The Ripper test also uses `bod <= original` which accepts no-change as a passing outcome.
**Recommendation:** After each IC action with an all-success dice roller, assert the specific affected field changed (e.g., `cyberdeck.mcpRating < original.mcpRating` for Blaster; `persona.evasion < original.evasion` for Ripper; `physicalConditionMonitor.damage > 0` for Sparky).

### HIGH — noticeTriggeredIc TypeKnown (2 successes) path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:606
**Issue:** `noticeTriggeredIc` returns `IcDetectionResult.TypeKnown` when exactly 2 successes are rolled. The test suite covers `Undetected` (0 successes), `PresenceOnly` (1 success), and `FullyLocated` (3+ successes) but has no test for the `TypeKnown` branch. A regression that collapsed TypeKnown into FullyLocated would go undetected.
**Recommendation:** Add a test using a sensor=2 decker against a Killer IC with TN=2 (or equivalent controlled stub) that produces exactly 2 successes and verifies `IcDetectionResult.TypeKnown` is returned.

### MEDIUM — runCombatTurn initiative ordering test bypasses the real method
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:486
**Issue:** The test `runCombatTurn gives higher initiative icon more actions` drives a hand-rolled copy of the combat loop inline rather than calling `game.runCombatTurn()`. It constructs `ActiveIconState` objects directly, runs the while-loop manually, and checks the `actionOrder` list. A refactor of `Game.runCombatTurn()` would not cause this test to fail unless the hand-rolled copy was also updated.
**Recommendation:** Rewrite the test to create a `Game` instance with controllable `ActiveIcon` implementations that log calls, supply a deterministic `DiceRoller` that produces specific initiative scores, then call `game.runCombatTurn()` and assert the ordering from the log.

### MEDIUM — runOutOfCombatTurn action invocation test is vacuous
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:465
**Issue:** The test `runOutOfCombatTurn calls action on each decker` calls `game.runOutOfCombatTurn()` and then asserts `assertEquals(2, ctx.deckers.size)`. This assertion would pass even if the method did nothing at all, since it only checks that the list still has 2 elements. The comment even acknowledges: "verify both were called via size".
**Recommendation:** Use a spy or a test-specific `ActiveIcon` that records invocations, and assert the invocation count equals the number of deckers.

### MEDIUM — resolveNullOperation "under 10 seconds" test has always-true assertion
**File:** src/test/kotlin/com/shadowrun/matrix/operations/SystemTestResolverTest.kt:87
**Issue:** The assertion `assertEquals(0, outcome.hostSuccesses.coerceAtMost(0))` is always 0: if `hostSuccesses > 0` then `coerceAtMost(0)` returns 0; if `hostSuccesses == 0` it also returns 0. The assertion can never fail regardless of what the host rolls. The comment says "just confirming no crash; real assertion below" but there is no subsequent assertion that checks the absence of the inactivity bonus.
**Recommendation:** Replace with a direct assertion verifying that the host dice pool was exactly `secValue` (4, no bonus) — e.g., check `outcome.hostSuccesses` at a known roller setting, or verify via a roller that fails at SV=4 but would succeed at SV=6, confirming no bonus was added.

### MEDIUM — checkTriggers ACTIVE_ALERT transition is untested
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameContextTest.kt:130
**Issue:** `checkTriggers` has tests for PASSIVE_ALERT (which also raises subsystem ratings by +2) but no test for ACTIVE_ALERT. The two transitions have different side-effects: PASSIVE_ALERT mutates subsystem ratings; ACTIVE_ALERT only sets `alertStatus`. A bug in the ACTIVE_ALERT branch of `applyAlertTransition` would go undetected.
**Recommendation:** Add a test with a TriggerStep carrying `alertTransition = AlertStatus.ACTIVE_ALERT` and verify `ctx.host.alertStatus == AlertStatus.ACTIVE_ALERT` and that subsystem ratings are unchanged.

### MEDIUM — locateSlave NotFound path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:780
**Issue:** `locateSlave` returns `LocateResult.NotFound` when `accumulatedSuccesses >= 3` but no matching device exists on the host. `locateFile` has an analogous test (`returns NotFound when data absent and 3 successes accumulated`) but there is no equivalent for `locateSlave`.
**Recommendation:** Add a test using a host with no matching remote devices, starting at accumulated successes 2, using a win roller to cross the threshold, and asserting `LocateResult.NotFound`.

### MEDIUM — applyIcDamage SERIOUS damage Willpower path is untested
**File:** src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt:421
**Issue:** `applyIcDamage` computes `overloadTn` based on staged damage level: LIGHT→2, MODERATE→3, SERIOUS→5, DEADLY→(handled separately). MODERATE (TN=3) and DEADLY are the only tested paths. LIGHT (TN=2) and SERIOUS (TN=5) are not tested, leaving the `2` and `5` branches uncovered. In particular, TN=5 for SERIOUS is a meaningful difficulty jump that could be mis-coded without detection.
**Recommendation:** Add tests for LIGHT damage (verifying TN=2 causes the Willpower roll to use 2) and SERIOUS damage (verifying TN=5 correctly makes most low-willpower deckers fail the overload check).

### MEDIUM — Cyberterminal utility rating reduction (CT-03) not integration tested
**File:** src/test/kotlin/com/shadowrun/matrix/operations/SystemTestResolverTest.kt:28
**Issue:** `SystemTestResolver.effectiveRating` reduces utility ratings by 1 when `deck.immuneToDumpShock` is true (CT-03 Cyberterminal rule). `DeckerTest` confirms `Cyberterminal.immuneToDumpShock == true`, but no test passes a Cyberterminal-equipped decker through `SystemTestResolver.resolve` and verifies that the TN is one higher than it would be with a normal Cyberdeck with the same utility rating.
**Recommendation:** Add a test comparing the effective TN of a normal Cyberdeck decker vs a Cyberterminal decker with the same utility, confirming the Cyberterminal TN is 1 higher.

### LOW — analyzeSecurity currentTally assertion is trivially true
**File:** src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt:292
**Issue:** `assertTrue(result.currentTally >= 0)` cannot fail because `currentTally = tallyFor(host) + outcome.hostSuccesses`, both of which are non-negative integers. This assertion provides no regression protection.
**Recommendation:** Assert a specific expected tally value. With `winRoller` (face=5) and secValue=2, the host rolls 2 dice at DF=3 and both succeed (5 >= 3), so `hostSuccesses = 2` and `currentTally` should equal `2`. Assert exactly that.

### LOW — analyzeHost failure-path test uses bare try/catch
**File:** src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt:278
**Issue:** The test `analyzeHost fails when decker is not on target host` uses a hand-coded `try { ... assertTrue(false) } catch (e: IllegalArgumentException) { ... }` block instead of `assertFailsWith<IllegalArgumentException>`. If the code is refactored to throw a different exception type, the test currently has a fallthrough that calls `assertTrue(false, "Expected exception")` — but this only fires if no exception is thrown; a wrong exception type would instead propagate and surface as an unexpected failure rather than a clean assertion.
**Recommendation:** Replace with `assertFailsWith<IllegalArgumentException> { d.analyzeHost(targetHost, emptyList(), winRoller) }`.

### LOW — maintainMonitoredOperation inactive-handle path untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:937
**Issue:** `maintainMonitoredOperation` returns the handle unchanged whether it is active or inactive — the only difference being a log warning on the inactive path. There is no test for calling this method on an already-inactive handle, nor is there any mechanism that auto-deactivates a handle when a turn passes without maintenance (the PRD comment says "missing one … aborts the operation" but no code enforces this).
**Recommendation:** Add a test asserting that calling `maintainMonitoredOperation` on an inactive handle returns the same inactive handle. Also add a contract test (or at minimum a comment) documenting that the enforcement of "must call each turn" is the responsibility of the game loop, not this method.

### INFO — allFaces(6) in any test would cause infinite loop
**File:** src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt:55
**Issue:** The `allFaces(face, count)` helper and the `fixedRoller(face)` helper in `SystemTestResolverTest` and `DeckerOperationsTest` would both cause `DiceRoller.rollOne()` to infinite-loop if passed face=6, because `rollOne()` loops while `face == 6`. The helpers have no guard. This is documented in project memory (`feedback_dice_roller_stub.md`), but there is no `require(face != 6)` assertion or comment in the helpers themselves.
**Recommendation:** Add `require(face != 6) { "Use [6,1] sequence for exploding-die stubs" }` to each helper, or document the constraint prominently in the helper's KDoc.

## Clean Areas
- `CombatResolver.stage()`: all six edge cases covered (shift up, shift down, clamp at LIGHT, clamp at DEADLY, zero net, odd net).
- `CombatResolver.icAttackParticipant()`: all four SecurityCode variants are tested with exact MODERATE/SERIOUS DamageLevel and SV values.
- `CombatResolver.suppressIc / unsuppressIc`: full lifecycle tested including accumulation, release of correct IC, no-op for unknown IC, and post-jack-out guard.
- `CombatResolver.resolveTrackLock`: evader-wins, evader-ties, and attacker-wins paths all covered with exact cycle-turn arithmetic.
- `CombatResolver.resolveCrippler` / `resolveRipper` / `resolveRipperMpcpTest`: attribute floor (at 1 for Crippler, 0 for Ripper), MPCP floor at 0, and no-reduction cases all present.
- `SystemTestResolver.resolveInterrogation`: precision modifier effects and TN floor at 2 are tested.
- `GameContext.checkTriggers`: threshold crossing, multi-step firing, no-retrofire, and PASSIVE_ALERT with subsystem bump all tested.
- `DiceRoller`: exploding-die behavior including chained explosions is correctly tested.
- `Cyberdeck` init constraints: MPCP cap on programs, total-rating cap, active-memory cap, and responseIncrease max are all tested.
- `Decker.gracefulLogoff` / `jackOut`: dump-shock flag and TrackState TN penalty are covered.
- `Decker.bufferMessage`: 100-word limit, persona-null guard, and exact-100-word boundary case are all present.
---
