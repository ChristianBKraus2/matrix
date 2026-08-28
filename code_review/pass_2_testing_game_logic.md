# Testing Review — game_logic component
**Pass:** 2 | **Focus:** Testing | **Reviewer:** Claude Code

---

## Scope

Files reviewed:

**Source under test:**
- `src/main/kotlin/com/shadowrun/matrix/game/Game.kt`
- `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt`
- `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt`
- `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`
- `src/main/kotlin/com/shadowrun/matrix/network/AlertTransitions.kt`
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt`

**Unit tests:**
- `src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/game/GameContextTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/decker/DeckerTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/decker/DeckerVisibilityTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/decker/CyberdeckAndProgramMechanicsTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/ic/IcTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/network/AlertTransitionsTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/network/NetworkTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/operations/SystemTestResolverTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/programs/ProgramTest.kt`

**Integration tests:**
- `src/test/kotlin/com/shadowrun/matrix/integration/AlertAndTallyTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/integration/CombatTest.kt`
- `src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt`

---

## Findings

### CRITICAL

#### C-01 — GameTest `runOutOfCombatTurn` test does not exercise dispatch at all

**File:** `src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt`
**Test:** `` `runOutOfCombatTurn calls action on each decker` ``

The test constructs two `ActiveIcon` tracking stubs (lambdas that set a flag), then adds two real `Decker` objects to the `GameContext`. The game loop iterates over `ctx.deckers`, which holds the `Decker` instances — not the stubs. The stubs are never stored anywhere the loop can reach them. The assertion `assertEquals(2, ctx.deckers.size)` only confirms the list was not mutated; it says nothing about whether `action()` was called on either icon.

**Impact:** The primary dispatch guarantee of `runOutOfCombatTurn` — that it calls `action()` on every registered icon — is entirely untested.

**Fix:** Place the stub icons directly into `ctx.deckers` (replacing or wrapping the real Decker), or redesign the fixture so the icon list and the decker list are the same objects.

---

#### C-02 — GameTest `runCombatTurn` test does not call `game.runCombatTurn()` at all

**File:** `src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt`
**Test:** `` `runCombatTurn gives higher initiative icon more actions` ``

The test manually replicates the initiative loop logic inline — decrementing counters and calling `action()` itself — without ever invoking `game.runCombatTurn()`. As a result, the test validates the test author's understanding of the algorithm, not the production implementation. Any regression in the actual loop body would not be caught.

**Impact:** Zero coverage of `Game.runCombatTurn()` and `Game.buildInitiativeList()` at the unit test level.

**Fix:** Construct a `Game` instance with stub icons in the context and call `game.runCombatTurn()`, then assert on how many times each stub's `action()` was invoked.

---

### HIGH

#### H-01 — Dead assertion in `resolveNullOperation` test

**File:** `src/test/kotlin/com/shadowrun/matrix/operations/SystemTestResolverTest.kt`
**Test:** `` `resolveNullOperation under 10 seconds applies 0 bonus to SecurityValue` ``

The assertion is:
```kotlin
assertEquals(0, outcome.hostSuccesses.coerceAtMost(0))
```
`coerceAtMost(0)` on any non-negative integer always returns 0. The assertion can never fail regardless of what `outcome.hostSuccesses` actually is. The intent was presumably to assert that `hostSuccesses == 0`, which would be written as `assertEquals(0, outcome.hostSuccesses)`.

**Impact:** A change that accidentally returned non-zero host successes for a null operation would not be detected.

**Fix:** Replace with `assertEquals(0, outcome.hostSuccesses)`.

---

#### H-02 — `jackInToLtg accumulates security tally on failure` never checks the tally

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt`
**Test:** `` `jackInToLtg accumulates security tally on failure` ``

The test comment references M-05 tally accumulation behavior, but the only assertion is:
```kotlin
assertNull(result.decker.persona)
```
This is trivially true for any failure result (jack-in failures always leave `persona = null`) and would be satisfied even if the implementation never touched the security tally at all.

**Impact:** The LTG security tally accumulation path — a core PRD requirement — has no unit-level assertion.

**Fix:** After the failure, extract `result.decker.currentLocation` as `MatrixLocation.OnLTG` and assert `ltg.securityTally > 0` (or a specific value if the roller is deterministic enough to predict the host's success count).

---

#### H-03 — `deception utility lowers effective access rating` only verifies the utility is loaded

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt`
**Test:** `` `deception utility lowers effective access rating` ``

The test's only assertion after equipping the Deception utility is:
```kotlin
assertEquals(1, d.cyberdeck.activeUtilities.size)
```
This confirms the utility was attached to the deck, but does not verify that any logon operation resolves using a reduced target number. The TN-reduction effect of Deception — the actual behavior the test name promises — is never exercised.

**Impact:** Deception utility TN reduction could be silently broken without this test catching it.

**Fix:** Perform a logon operation with a borderline roller (one where the normal TN causes failure but the reduced TN would succeed) and assert on the outcome, or assert directly on the `effectiveAccessRating` property exposed by the decker.

---

#### H-04 — `noticeTriggeredIc` TypeKnown (2-success) path has no unit test

**File:** `src/test/kotlin/com/shadowrun/matrix/ic/IcTest.kt`

`IcTest.kt` tests the zero-success (NoContact) and one-success (TypeDetected) outcomes. The two-success (TypeKnown) path — where the decker identifies both the IC type and rating — is not covered. This is the outcome that unlocks targeted countermeasures in the PRD.

**Impact:** A regression in the TypeKnown branch (e.g., returning TypeDetected instead) would go undetected.

**Fix:** Add a test with a roller that returns exactly 2 successes and assert `assertIs<NoticeResult.TypeKnown>()` on the result, verifying both `icType` and `rating` fields.

---

#### H-05 — `applyDeckerOperationResult` null-location fallback path is not tested

**File:** `src/test/kotlin/com/shadowrun/matrix/game/GameContextTest.kt`

`GameContext.applyDeckerOperationResult` contains this fallback:
```kotlin
val newTally = (new.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: oldTally
```
When the decker's new location is not `OnHost` (e.g., after a failed logon that left the decker on an LTG), `newTally` equals `oldTally` and the method skips `updateHost` and `checkTriggers` entirely. No test covers this branch.

**Impact:** If a future refactor removes the null coalescing or changes the fallback logic, no test will catch it.

**Fix:** Add a `GameContextTest` case where the decker's `new` state has `currentLocation = MatrixLocation.OnLTG(...)` and assert that the context host is not modified and `activeIc` remains empty.

---

### MEDIUM

#### M-01 — `resolveBlackHammer` and `resolveKilljoy` do not verify `dumpShockTriggered` when CM overflows

**File:** `src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt`

Tests for `resolveBlackHammer` and `resolveKilljoy` verify that damage is applied, but no test drives the damage to the point where the condition monitor is completely filled and then asserts `result.dumpShockTriggered == true`. The MPCP death blow path in `resolveLethalBlackIc` / `resolveNonLethalBlackIc` (triggered when `dumpShockTriggered`) is similarly untested.

**Impact:** The dump-shock-on-crash invariant — a critical safety rule for the decker — could regress silently.

**Fix:** Set up a persona with a condition monitor at `maxDamage - 1` boxes, apply a hit sufficient to fill it, and assert `assertTrue(result.dumpShockTriggered)`. Add a parallel test for the non-lethal variant.

---

#### M-02 — Sparky IC body damage path is not tested via the IC action path

**File:** `src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt`

`CombatResolverTest` tests Sparky's persona damage but does not test that physical body damage is applied when Sparky hits. The integration test in `CombatTest.kt` covers `LethalBlackIC` physical damage but not Sparky. The unit test for `resolveSparky` should assert on `result.physicalDamageDealt > 0`.

**Impact:** Sparky's physical damage channel could be silently zeroed out.

**Fix:** Add a unit test that resolves a Sparky hit with a deterministic roller, then asserts `assertTrue(result.physicalDamageDealt > 0)`.

---

#### M-03 — `locateFile` multi-step accumulation across turns is not tested

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt`

`locateFile` uses an interrogation state machine (`fileInterrogationProgress`) that accumulates successes across multiple operations until the threshold is crossed. Tests confirm the first-call and success cases, but no test calls `locateFile` twice in sequence with partial results and verifies that the second call builds on the accumulated progress from the first.

**Impact:** A regression in progress-accumulation (e.g., always starting from zero) would not be detected.

**Fix:** Call `locateFile` with a sub-threshold roller, capture the returned decker, call again on the returned decker, and assert that the final success requires fewer cumulative successes than two independent first-calls would.

---

#### M-04 — `downloadData` turn-count floor of 1 is not tested

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt`

The file size / IO speed calculation uses `maxOf(1, ceil(sizeMp / ioSpeed))`. Tests verify typical multi-turn downloads, but no test uses a tiny file (e.g., 1 MP with ioSpeed 100 MP/turn) to confirm the floor prevents a zero-turn download.

**Impact:** If the `maxOf(1, ...)` guard is removed, `downloadData` for tiny files would complete in zero turns (or throw), going undetected.

**Fix:** Add a test with `sizeMp = 1` and a high IO speed, asserting that `result.turnsRemaining >= 1` on initiation.

---

#### M-05 — `gracefulLogoff` failure path on Cyberterminal is not tested

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/CyberdeckAndProgramMechanicsTest.kt`
**PRD:** CT-04

`CT-04` verifies that `jackOut` on a Cyberterminal does not trigger dump shock. No parallel test exists for `gracefulLogoff` failure on a Cyberterminal — a PRD requirement that the graceful-logoff failure path is also dump-shock-immune for Cyberterminal users.

**Fix:** Add a CT-04b test: set up a Cyberterminal decker, call `gracefulLogoff` with a `failRoller()`, and assert that no dump shock marker is set and the persona is removed cleanly.

---

#### M-06 — `checkTriggers` same-level ordinal (PASSIVE→PASSIVE) has no explicit test

**File:** `src/test/kotlin/com/shadowrun/matrix/game/GameContextTest.kt`

`GameContextTest` tests that alert status does not regress (e.g., ACTIVE→PASSIVE is blocked). But no test explicitly checks the equal-ordinal case: when `transition.ordinal == host.alertStatus.ordinal`, the guard `transition.ordinal > host.alertStatus.ordinal` is false, so no update occurs. This is a silent no-op that could surprise future maintainers if they misread the guard as `>=`.

**Fix:** Add a test that fires a trigger step with `alertTransition = PASSIVE_ALERT` on a host already at `PASSIVE_ALERT`, and assert that `updateHost` was not called (subsystem ratings remain unchanged).

---

#### M-07 — Integration test tally-independence assertion is vacuously satisfiable

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/AlertAndTallyTest.kt`
**Test:** `` `security tally on source RTG is independent from tally on destination RTG` ``

The assertion is:
```kotlin
assertTrue(aztTally != ucasTally || (aztTally == 0 && ucasTally == 0))
```
The disjunction's right arm (`aztTally == 0 && ucasTally == 0`) makes the assertion trivially pass whenever both tallies are zero — which is exactly the case with `winRoller()` (host scores 0 successes). The assertion provides no evidence that tallies are tracked independently; it only avoids a false positive when both happen to be zero.

**Fix:** Use a roller that produces a non-zero tally on at least one RTG, then assert `aztTally != ucasRtgTally` with specific expected values, or accumulate tally deliberately on UCAS before switching and assert the AZT tally is lower.

---

### LOW

#### L-01 — `resolveCrippler` suppression test does not verify the DF difference in dice rolled

**File:** `src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt`
**Test:** `` `resolveCrippler uses effectiveDetectionFactor when IC is suppressed` ``

The test asserts:
```kotlin
assertEquals(baseline.reduction, withSuppression.reduction)
```
With the same deterministic roller returning constant values, both rolls produce the same reduction regardless of how many dice are thrown. The assertion says nothing about whether the suppressed IC actually used fewer dice — it only confirms that the result structure has the same shape.

**Fix:** Replace the deterministic roller with one that counts invocations, then assert that the suppressed run called `nextInt` fewer times than the baseline. Alternatively, use a stochastic comparison: with a roller that returns 0 (always hits), a suppressed IC should still win just as often but with fewer dice (reduce the IC's DF pool). Assert on a property that would differ if dice count changes.

---

#### L-02 — `resolveDumpShock` tests do not cover RED and ORANGE security codes

**File:** `src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt`

`resolveDumpShock` tests cover `SecurityCode.GREEN` (base damage) but do not include `RED` or `ORANGE` security codes, which should scale the damage differently per PRD. If the security code branching logic in dump shock is incorrect for higher-security environments, no unit test will detect it.

**Fix:** Parameterize the dump shock test across `SecurityCode.entries` and assert the expected damage level for each code.

---

#### L-03 — `analyzeHost fails when decker is not on target host` uses bare try/catch

**File:** `src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt`

```kotlin
try {
    analyzeHost(...)
    assertTrue(false, "Expected IllegalArgumentException")
} catch (e: IllegalArgumentException) {
    // pass
}
```
This pattern fails to re-throw unexpected exception types and cannot use JUnit 5's built-in exception assertion formatting. Kotlin test provides `assertFailsWith<T>` specifically for this pattern.

**Fix:** Replace with `assertFailsWith<IllegalArgumentException> { analyzeHost(...) }`.

---

#### L-04 — `ProgramTest` has no boundary or invalid-input coverage

**File:** `src/test/kotlin/com/shadowrun/matrix/programs/ProgramTest.kt`

Three tests cover the happy path for `mpSize` calculations. Missing cases:
- Rating = 0 (edge: `0 * 0 * multiplier = 0`)
- Rating = 1 (boundary at minimum usable rating)
- `ATTACK` utility with `attackDamageLevel = null` (defensive: what is the multiplier for a non-attack utility instantiated with no damage level?)

---

#### L-05 — Upload state machine transitions are not unit tested

**File:** `src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt`

`Decker` contains an upload countdown (`pendingUpload`, `uploadTurnsRemaining`) that transitions through several states: not started → in progress (N turns) → complete → abort. Tests verify a happy-path multi-turn upload, but the abort path (calling a different operation mid-upload) and the already-uploading guard (calling `upload` when `pendingUpload != null`) have no coverage.

**Fix:** Add tests for (a) calling `cancelUpload` or starting a new operation while an upload is in progress, asserting the pending upload is cleared; and (b) calling `upload` again when `pendingUpload` is already set, asserting the expected error or idempotent behavior.

---

### INFO

#### I-01 — Fixture helpers are duplicated across 8+ test files

The same deck/decker/host builder pattern appears with minor variations in `DeckerTest`, `DeckerOperationsTest`, `DeckerVisibilityTest`, `MovementTest`, `CyberdeckAndProgramMechanicsTest`, `SystemOperationsTest`, `CombatResolverTest`, and `IcTest`. `IntegrationTestBase` already demonstrates a clean shared-fixture pattern with `DeckerMock`, `HostMock`, and `GridMock` helpers. The unit test suite has no equivalent.

**Suggestion:** Extract a `TestFixtures` object (or a `UnitTestBase` open class mirroring `IntegrationTestBase`) into `src/test/kotlin/com/shadowrun/matrix/common/TestFixtures.kt` providing canonical `defaultDecker()`, `defaultHost()`, and `defaultPersona()` builders. This would reduce fixture noise and make structural decker changes a one-file fix.

---

#### I-02 — `winThenRoller` and `winFailWinRoller` are not available in unit tests

`IntegrationTestBase` defines the useful `winThenRoller(zeroCalls, thenValue)` and `winFailWinRoller(winCalls, failCalls)` rollers. Unit test files each roll their own inline `DiceRoller` lambdas that duplicate this logic. If the shared roller helpers were lifted into a common test utility, unit tests could express complex dice sequences more clearly (e.g., "IC hits on first roll, misses on second").

---

#### I-03 — `CombatTest` integration tests rely on opaque roller offset arithmetic

Several integration tests in `CombatTest.kt` use `winThenRoller(zeroCalls = 26, thenValue = 3)` where the `26` encodes a precise count of setup dice rolls that must succeed before the failure is injected. This magic number will silently break if any upstream operation is added or reordered. A comment explaining how `26` was derived would reduce maintenance risk, or alternatively the test could be restructured to inject the tally directly rather than accumulating it through successful dice rolls.

---

## Summary

| Severity | Count | Key Themes |
|----------|-------|------------|
| CRITICAL | 2 | GameTest tests do not exercise their subjects |
| HIGH     | 5 | Dead assertions, no-op test bodies, missing paths |
| MEDIUM   | 7 | Uncovered state machine paths, integration assertion weakness |
| LOW      | 5 | Test style, boundary coverage, missing parameterization |
| INFO     | 3 | Fixture duplication, integration test fragility |

The most urgent concern is that `GameTest.kt` provides zero coverage of `Game.runOutOfCombatTurn()` dispatch and `Game.runCombatTurn()` — the two entry points that orchestrate the entire game loop. All other findings are real quality gaps but are isolated to specific features. Addressing C-01 and C-02 first will establish that the game loop wiring actually works; the HIGH findings (particularly H-01 dead assertion and H-02/H-03 no-op test bodies) should follow immediately because they give false confidence that tested behaviors are verified.
