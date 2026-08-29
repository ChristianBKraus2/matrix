# Testing Review — game_logic

## Summary

The game-logic test suite is broadly well-structured: unit tests cover the main happy/sad paths for combat resolution, system operations, navigation, and memory management, and the integration layer exercises realistic multi-step scenarios against a full mock matrix topology. However, several important code paths have no coverage at all — most notably `Decker.availableActions()` and `Decker.visibleObjects()`, which contain complex branching across all five `MatrixLocation` variants and are critical for correct game state, yet have zero tests. The Cyberterminal effective-rating penalty (CT-03) is also wholly untested. Beyond complete gaps, a number of boundary conditions are missing: the `TypeKnown` branch of `noticeTriggeredIc`, the zero-turns-required path in `loadUtility`, depleted-utility auto-unload in `advanceCombatTurn`, the `OnLTG`/`OnRTG`/`OnPLTG` tally-update arms of `withUpdatedTally`, and `invokeMediac` edge cases. One unit test for `runOutOfCombatTurn` has a structural defect: it verifies list size rather than action invocation, making it a non-test. Overall coverage is good for the combat resolver and system test resolver core paths; the gaps are concentrated in the Decker API surface and context utilities.

## Findings

### [HIGH] `Decker.availableActions()` has zero test coverage
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:114
**Issue:** `availableActions()` builds the full action menu across five `MatrixLocation` branches (`OnRTG`, `OnLTG`, `OnPLTG`, `OnHost`, null) plus two private helpers (`addGridSystemActions`, `addHostSystemActions`). The host branch alone adds 20+ action entries, including per-file scramble protection checks (line 187). No test in any file exercises this method. Regressions here would silently present the wrong action set to players.
**Recommendation:** Add unit tests for each location type: assert that the correct `AvailableAction` subtypes appear, that scramble-protected files produce `DECRYPT_FILE` actions while unprotected files do not, and that `null` location returns an empty list.

**[DEFERRED]** — Broad `availableActions()` coverage (per-location, scramble-file checks) not added. Partial: `availableActions on Host never includes LOCATE_DECKER or SWAP_MEMORY` was added to `DeckerVisibilityTest.kt`.

### [HIGH] `Decker.visibleObjects()` has zero test coverage
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:78
**Issue:** `visibleObjects()` similarly branches across all four active `MatrixLocation` types and is the source of truth for what a decker can perceive. The `OnHost` branch builds a list from nodes, IC programs, data files, remote devices, and connected hosts. None of these branches are covered by any test.
**Recommendation:** Add a parameterised unit test for each location type. At minimum verify: `OnHost` returns the host node, all sub-nodes, and each IC/file/device; `OnLTG` returns the parent RTG and any attached PLTGs and hosts; `null` returns an empty list.

**[DEFERRED]** — `visibleObjects()` unit tests not added; out of scope for this session.

### [HIGH] Cyberterminal effective-rating penalty (CT-03) is untested
**File:** src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116
**Issue:** `SystemTestResolver.effectiveRating()` reduces every utility rating by 1 for cyberterminals (`immuneToDumpShock == true`). This is the CT-03 rule. Every test uses a standard `Cyberdeck` (`immuneToDumpShock = false`). No test constructs a `Cyberterminal` (which sets `immuneToDumpShock = true`), calls any system operation, and verifies the TN is raised by 1 relative to the same operation on a standard deck.
**Recommendation:** Add a unit test in `SystemOperationsTest` or `DeckerOperationsTest` that runs `analyzeHost` (or any operation with a non-null utility) with a `Cyberterminal`-based decker and confirms the effective TN is 1 higher than with an equivalent standard deck.

**[DEFERRED]** — CT-03 penalty test not added; out of scope for this session.

### [HIGH] `Game.runOutOfCombatTurn` test does not actually verify actions are invoked
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:512
**Issue:** The test builds tracking `ActiveIcon` objects (`iconA`, `iconB`) that append to `actionLog`, but then creates a `Game` with *real* `Decker` instances — not the tracking icons. The assertion at line 527 checks `assertEquals(2, ctx.deckers.size)`, which only verifies the context list was not mutated. The decker `action()` implementation always returns `ActionResult.DeckerAction` immediately, so nothing observable is tested.
**Recommendation:** Either replace the real deckers with wrapper deckers that delegate to tracking logic, or use a subclass of `GameContext` that records invocations; then assert `actionLog.size == 2` (or similar) after the call.

**[DEFERRED]** — `runOutOfCombatTurn` test not strengthened; out of scope for this session.

### [HIGH] `Decker.withUpdatedTally` non-host location arms are untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:202
**Issue:** `withUpdatedTally` handles `OnLTG`, `OnRTG`, `OnPLTG`, and `OnHost`. Every test that exercises tally accumulation (all system operations, navigation tests) places the decker on a host. The three grid-location arms are dead from a test perspective. A regression that accidentally broke tally tracking on RTG/LTG would not be caught.
**Recommendation:** Add unit tests in `DeckerOperationsTest` or a new `DeckerTallyTest` that call `withUpdatedTally(n)` on a decker at each grid location and verify the tally on the underlying grid object increments correctly.

**[DEFERRED]** — `OnLTG`/`OnPLTG` tally arms still untested. Partial: `logonToRtg accumulates host successes on top of existing RTG security tally` added to `MovementTest.kt` covers the `OnRTG` arm.

### [MEDIUM] `noticeTriggeredIc` `TypeKnown` branch (exactly 2 successes) is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:65
**Issue:** `noticeTriggeredIc` maps successes to four result types: 0 → `Undetected`, 1 → `PresenceOnly`, 2 → `TypeKnown`, 3+ → `FullyLocated`. `SystemOperationsTest` tests the 0, 1, and 3+ branches. The `TypeKnown` branch (line 68) has no test. It is reachable code that returns a distinct result carrying IC type information.
**Recommendation:** Add a test using a 2-dice sensor persona and a roller that yields exactly 2 successes, asserting `assertIs<IcDetectionResult.TypeKnown>(result)` and that the IC reference is correct.

**[DEFERRED]** — `TypeKnown` branch test not added; out of scope for this session.

### [MEDIUM] `loadUtility` zero-turns-required immediate-activation path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt:24
**Issue:** Lines 23-28: if `turnsRequired == 0` (when `ceil(mpSize / ioSpeed)` rounds to 0, which occurs when `mpSize == 0` or when a program has `mpSize < ioSpeed`), the utility is added directly to `activeUtilities` without going through `pendingUploads`. All tests in `MemoryManagementTest` exercise the multi-turn upload path. The instant-activation branch is never hit.
**Recommendation:** Add a test where `ioSpeedMpPerTurn` greatly exceeds `utility.mpSize` (e.g., a rating-1 utility with multiplier 1 = 1 Mp, against IO = 1000) and verify the utility appears immediately in `activeUtilities` with an empty `pendingUploads`.

**[DEFERRED]** — `loadUtility` instant-activation test not added; out of scope for this session.

### [MEDIUM] `advanceCombatTurn` depleted-utility auto-unload path is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt:62
**Issue:** Lines 62-65 partition active utilities into live (`currentRating > 0`) and depleted (`currentRating <= 0`). Depleted utilities are removed from both active and stored memory. No test constructs a decker with an active utility at `currentRating = 0` and calls `advanceCombatTurn()` to verify the auto-unload. The `invokeMediac` path decrements Medic's rating and could reach 0, but the two are not connected in a test.
**Recommendation:** Add a test in `CombatResolverTest` or `MemoryManagementTest` that seeds a decker with a utility at `currentRating = 0`, advances one turn, and asserts the utility is absent from both `activeUtilities` and `storedUtilities`.

**[DEFERRED]** — Depleted-utility auto-unload test not added; out of scope for this session.

### [MEDIUM] `invokeMediac` boundary cases are untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:359
**Issue:** Two boundaries have no tests: (1) line 365 — `require(filled < 10)` throws when the persona condition monitor is at maximum damage (10 boxes); (2) the case where `newMedicRating <= 0` (lines 376-385) removes the Medic utility from both active and stored memory. `CombatTest.kt` tests the happy path but neither boundary.
**Recommendation:** Add a test that calls `invokeMediac` when `conditionMonitor.damage == 10` and expects `IllegalArgumentException`. Add a second test with a Medic at `currentRating = 1` where the roller yields 1 success, and verify Medic is absent from both `activeUtilities` and `storedUtilities` afterward.

**[DEFERRED]** — `invokeMediac` boundary tests not added; out of scope for this session.

### [MEDIUM] `GameContext` utility methods `resetDeckers` and `deckerByName` have no tests
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:33
**Issue:** `resetDeckers(decker)` replaces the entire decker list with a single decker. `deckerByName(name)` performs a name lookup. Neither is exercised in `GameContextTest` or elsewhere. Both are simple but `resetDeckers` mutates shared state and a wrong implementation could silently corrupt the decker list.
**Recommendation:** Add tests in `GameContextTest`: verify `resetDeckers` clears existing deckers and holds exactly the supplied one; verify `deckerByName` returns the correct decker by name and null when no match.

**[DEFERRED]** — `resetDeckers`/`deckerByName` tests not added; out of scope for this session.

### [MEDIUM] `GameContext.addToSecurityTally` has no direct unit test
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:85
**Issue:** `addToSecurityTally(points)` updates the host tally and calls `checkTriggers`. It is only reached through `Probe.action()` in integration tests. There is no unit test that calls it directly, verifies the tally increment, and confirms that `checkTriggers` fires correctly when the new tally crosses a threshold.
**Recommendation:** Add a test in `GameContextTest` that calls `addToSecurityTally(5)` on a context whose host sheaf has a threshold at 3, and verifies the IC is activated and the tally equals 5.

**[DEFERRED]** — `addToSecurityTally` direct unit test not added; out of scope for this session.

### [MEDIUM] `resolvePointerChain` multi-hop traversal is untested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:397
**Issue:** `resolvePointerChain` traverses a chain of connected hosts up to `chainLength` (1D6) hops. The test in `SystemOperationsTest` builds a single-host chain (`connectedHosts = emptyList()`), so the loop at line 403 (`current = current.connectedHosts.firstOrNull() ?: current`) always stays on the same host. Multi-hop traversal — including the fallback when `connectedHosts` is empty mid-chain — is never exercised.
**Recommendation:** Add a test that constructs a three-host chain, stubs the dice roller to return a chain length of 3, and verifies that `chain.links` contains all three hosts and `chain.finalFile` resolves to a non-pointer file on the final host.

**[DEFERRED]** — Multi-hop pointer chain test not added; out of scope for this session.

### [LOW] `Decker.detectionFactor` dynamic property not directly tested
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:54
**Issue:** `detectionFactor` reads the Masking persona program rating from `cyberdeck.personaPrograms` and the Sleaze utility rating from `cyberdeck.activeUtilities`, then delegates to `cyberdeck.detectionFactor(masking, sleaze)`. `DeckerTest` tests the `Cyberdeck.detectionFactor(Int, Int?)` method directly with literal arguments, but no test constructs a `Decker` with a specific Masking program and a loaded Sleaze utility and asserts the `decker.detectionFactor` property value. A regression in the property lookup logic would be missed.
**Recommendation:** Add a test in `DeckerTest` or `DeckerOperationsTest` that equips a decker with a Masking program (rating 4) and a SLEAZE utility (rating 2) and asserts `decker.detectionFactor == ceil((4+2)/2.0) = 3`.

**[DEFERRED]** — `Decker.detectionFactor` property test not added; out of scope for this session.

### [LOW] `IcMoved` result message content not verified
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:254
**Issue:** The test `Proactive IC moves when target is in different node` only checks `assertIs<ActionResult.IcMoved>(result)`. The message content (constructed in `IC.moveIfNeeded` as `"$name moved to $targetNode"`) and the fact that the move is semantically meaningful are not verified.
**Recommendation:** Assert that `result.message` contains the expected IC name and the target node's subsystem type string, to guard against regressions in the message construction.

**[DEFERRED]** — `IcMoved` message content assertions not added; out of scope for this session.

### [LOW] `NullOperationModifier` bonus scaling not tested at unit level
**File:** src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:60
**Issue:** `resolveNullOperation` adds `NullOperationModifier.totalBonusForDuration(inactivitySeconds)` to the host's security value. The two tests in `SystemOperationsTest` (5 s and 90 s) only check result type (`Success`/location not null), not whether the bonus is correctly applied to the host SV. A bug in `NullOperationModifier` that returned a wrong bonus would not be detected by these tests.
**Recommendation:** Add a test that uses a fixed roller, calls `nullOperation` with two different inactivity durations that produce different bonuses, and asserts that `outcome.hostSuccesses` differs between them (or use a spy on the dice roller to confirm the SV passed to `roll` changes).

**[DEFERRED]** — `NullOperationModifier` bonus scaling test not added; out of scope for this session.

## No Issues Found In
- `CombatResolver.stage` — all boundary cases (clamp at LIGHT, clamp at DEADLY, zero net, odd net, up/down shift) are thoroughly tested in `CombatResolverTest`
- `CombatResolver.resolveAttack` — all `attackTn` table entries (all four SecurityCode × PersonaStatus combinations) are covered
- `CombatResolver.applyIcDamage` — Black IC pin, Willpower test pass/fail, Deadly auto-crash, and CM-crash dump-shock trigger are all tested
- `CombatResolver.suppressIc` / `unsuppressIc` — all paths including no-op for unknown IC are tested
- `CombatResolver.resolveDumpShock` — Blue/Orange host damage levels and body success staging are tested
- `SystemTestResolver.resolve` — TN floor, utility modifier, and host detection factor interaction are tested through operation tests
- `ConditionMonitor.applyDamage` — box counts for all four `DamageLevel` values and the `isCrashed` boundary are tested
- `Cyberdeck` init-block invariants — MPCP ceiling for programs, total program sum, active/storage memory limits, responseIncrease max are all tested with negative cases
- `GameContext.checkTriggers` — threshold crossing, multi-step crossing, no-regression on already-passed threshold, and alert transition tests are present
- `GameContext.updateHost` — host replacement and decker-location repair (OnHost and non-host) are tested
- `DeckerNavigationExtensions` happy paths — jack-in to LTG/host, RTG traversal, PLTG layer, graceful logoff, and jack-out are covered by integration tests including failure paths
- `Persona.attribute` / `Persona.withAttribute` — all four attribute types tested in `CombatResolverTest`
- `SecuritySheaf` / `TriggerStep` — IC activation and alert transition via `checkTriggers` are well covered
