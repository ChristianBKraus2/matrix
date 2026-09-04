# Test-Suite Findings

All 45 test files (unit + integration + test utilities, ~10,650 lines) read in full.

**§12 anti-pattern sweep — all clear:**
- ✅ **No** stub `DiceRoller` returning constant face=6 (the exploding-dice infinite-loop hazard). Every
  exploding-dice stub uses bounded sequences (`[6,1]`, `[6,3,2]`) or non-exploding faces 1–5.
  `IntegrationTestBase` rollers are `winRoller=0 / failRoller=3 / hitRoller=5` — none explode.
- ✅ **No** `Thread.sleep` in coroutine tests (server/WS tests use real threads with bounded `join(...)` +
  `withTimeout`, plus `runBlocking`; the Ktor test uses `testApplication`).
- ✅ **No** tautological `assertTrue(true)` / `assertFalse(false)`.

The bulk of the suite uses strong state-change and payload assertions. Findings are concentrated and
specific.

---

## 🔴 T-1 (HIGH, tied to S-1) — A test enshrines the `hasValidPasscode` auth-bypass as correct

> ✅ **RESOLVED (Step 1, 2026-09-04).** The test now builds the decker *with* a verified passcode
> (`decker(host = h, knownPasscodes = setOf(h.name))`) and calls `d.makeComcall(h, neverCalledRoller)`
> with no client flag. It proves the **server-derived** skip, not a client bypass — so it now
> defends the fix instead of the vulnerability. The two no-passcode `makeComcall` tests drop the old
> flag and rely on the empty-by-default `knownPasscodes` to exercise the System Test.
>
> **Follow-up (2026-09-04): the Make Comcall licensed-decker passcode exception was descoped.** This
> test (`makeComcall with valid passcode skips System Test and returns Success`) was **deleted**, the
> `knownPasscodes` param removed from the `decker()` test helper, and the surviving `makeComcall`
> tests renamed. With no passcode-skip path left in production, the S-1 attack surface no longer
> exists at all — nothing to enshrine.

**Category:** Test quality / Security
**Where:** [DeckerOperationsTest.kt:407-420](../../src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt#L407-L420)

The test `makeComcall with valid passcode skips System Test and returns Success` installs a
`neverCalledRoller` that throws if the dice are touched, then asserts `Success`. It **locks in** the known
HIGH auth-bypass (S-1): it guarantees that any regression which *added* server-side passcode validation
would break the suite. The test defends the vulnerability.

This is not double-counting the S-1 production bug — it is a distinct hazard: the test will actively
resist the fix. **When S-1 is fixed** (server derives passcode possession from verified state, not the
client flag), this test must be replaced with one that supplies a *verified* passcode. Until then, add a
comment / `@Disabled` note tying it to the open finding so it isn't mistaken for intended behavior.

---

## 🟠 T-2 (MEDIUM) — No test covers the `resolvePointerChain` exploding-die bug (E-1)

> ✅ **RESOLVED (Step 3, 2026-09-04).** Two new pinned tests make the distribution observable:
> `resolvePointerChain length tracks the flat 1D6 roll` (asserts `fixedRoller(n)` ⇒ exactly `n`
> links, via the now non-exploding `flat`) and `resolvePointerChain uses a non-exploding die and
> caps at 6 links` (`fixedRoller(6)` ⇒ 6 links — a case the old exploding `roll(1,6)` would have
> infinite-looped on). `DiceRollerTest` also gains direct `flat` coverage. The original
> existence-only test is retained.

**Where:** [SystemOperationsTest.kt:474-483](../../src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt#L474-L483)

`resolvePointerChain returns PointerChain with links and finalFile` asserts only
`chain.links.isNotEmpty()` and `finalFile != null`. Neither observes the die value or hop count, so the
test passes regardless of the E-1 flat-vs-exploding distribution defect. **Coverage gap, not a lock-in.**
Add a test that pins the roll via a stubbed roller and asserts the resulting chain length so the
distribution becomes observable.

---

## 🟠 T-3 (MEDIUM) — `tapComcall` tests leave the `scannerDeviceRating` trust boundary untested (S-2)

> ✅ **RESOLVED (Step 1, 2026-09-04).** The `tapComcall` tests now put the rating on the host
> (`host(..., datalineScannerRatings = listOf(4))`) and call `d.tapComcall(h, roller)` — proving the
> rating originates from **server-side host state**, not the client. A new test
> (`tapComcall uses only the highest of multiple dataline scanners`, `listOf(4,6,7)`) covers the
> `maxOrNull` path per the PRD case.

**Where:** [DeckerOperationsTest.kt:443-492](../../src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt#L443-L492)

The five `tapComcall` tests pass client-supplied `scannerDeviceRating` (0/4/8) straight into the engine
and assert the contest arithmetic — correct math, but nothing asserts the rating *originates from
host/device state* rather than the client. Masks the S-2 finding by validating computation while leaving
the trust boundary uncovered. Keep the arithmetic tests; add coverage (or a linked TODO) for the
source-of-truth once the device model owns the rating.

---

## 🟡 T-4 (LOW) — Initiative test reimplements the SUT instead of calling it

> ✅ **RESOLVED (2026-09-04).** The SUT-reimplementing test `runCombatTurn gives higher initiative
> icon more actions` (and its section header) was **deleted**. Driving the assertion through the real
> `game.runCombatTurn()` is not possible: it accepts only `Decker`/`IC` icons and `IC` is a sealed
> class, so a tracking/counting icon cannot be injected — and `runCombatTurn` is itself
> deferred-unreachable (deferred.md #1). Rather than keep a test that proves only its own inlined
> arithmetic, it was removed (finding-sanctioned option). The now-unused `CombatInitiative` import was
> dropped. `test` green.

**Where:** [GameTest.kt:528-565](../../src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt#L528-L565)

`runCombatTurn gives higher initiative icon more actions` never invokes `game.runCombatTurn()` — it copies
the selection loop inline and asserts against its own copy. Proves the test's arithmetic, not production
ordering. (Low impact: `runCombatTurn` is itself deferred-unreachable per deferred.md #1.) Drive the
assertion through the real machinery or delete the duplicated-logic test.

## 🟡 T-5 (LOW) — CD-14 "reduces target number" asserts only the setup value

> ✅ **RESOLVED (2026-09-04).** The CD-14 test now makes the TN reduction observable through the
> outcome. It uses a `[6,1]`-cycling roller (each dice call returns 6 then 1 → an exploding total of 7,
> avoiding the constant-face=6 infinite-loop hazard) and asserts `deckerSuccesses == 6`, a count only
> reachable if the Deception utility actually lowered the effective TN. The old assertion on the fixture
> `currentRating` is gone. `test` green.

**Where:** [CyberdeckAndProgramMechanicsTest.kt:323-346](../../src/test/kotlin/com/shadowrun/matrix/decker/CyberdeckAndProgramMechanicsTest.kt#L323-L346)

The test captures the effective TN then discards it, asserting only that the Deception utility's
`currentRating == 4` (a fixture value) — it never verifies the TN was reduced. The sibling `CD-14 TN floor
is 2` test (349-367) does it correctly and is the pattern to follow. Assert on the captured effective TN.

## 🟡 T-6 (LOW) — Dead helper + else-less success guards in MovementTest

> ✅ **RESOLVED (2026-09-04).** The dead `alwaysWinRoller()` helper was removed and replaced with a
> real `deckerWinsRoller()` (returns face 5 for the first 6 dice, then 0 — enough for the decker to win
> the logon System Tests deterministically). Six "succeeds"-named tests (jackInToLtg, jackInToHost ×2,
> logonToRtg, logonToLtg child, logonToHost ×2) now use it with an **unconditional**
> `assertIs<LogonResult.Success>(result)` instead of the else-less `if (result is …Success)` guard, so
> an unexpected failure fails the test instead of passing vacuously. `test` green.

**Where:** [MovementTest.kt:104-109](../../src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt#L104-L109) and success-path tests (~240-250, 280-296, 345-355, 436-446, 477-487)

`alwaysWinRoller()` documents "always returns 6" but its body is `DiceRoller(Random(0L))` (comment-only)
and is unused. Several "succeeds"-named tests wrap assertions in `if (result is LogonResult.Success) { … }`
with no `else`, so an unexpected failure silently skips the assertions (vacuous pass); one assertion is
only `assertIs<LogonResult>` (trivially true). Delete the dead helper; replace guards with
`assertIs<LogonResult.Success>(result)`.

## 🔵 T-7 (INFO) — Guarded/existence-only assertions in SystemOperationsTest

> ✅ **RESOLVED (2026-09-04).** The rollers are now pinned so the meaningful branch is always taken:
> `winRoller = fixedRoller(5)` and `loseRoller = fixedRoller(3)` are deterministic. The `if (net > 0)`
> and authentication guards were removed from `analyzeHost with 0 net successes`, `editFile with
> authentication`, and `controlSlave with effectiveSkill` (now `assertIs<OperationResult.Success>`);
> `nullOperation updates security tally` asserts `securityTally > 0`; MP-03 asserts
> `assertIs<SensorTestResult.Detected>`. `test` green.

**Where:** [SystemOperationsTest.kt:153-160,265-273,385-388,405-412,450-458](../../src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt#L153-L160)

Several tests assert only `assertNotNull(result)` or place real assertions inside `if (net > 0)` / auth
guards, so the meaningful check can be skipped depending on the roll. Pin the roller so the branch is
deterministically taken.

## 🔵 T-8 (INFO) — ICActivation analyzeHost "reveals security rating" asserts only no-IC

> ✅ **RESOLVED (2026-09-04).** The misleading test was corrected rather than forced. Verifying an
> actual reveal at the scenario level is **structurally impossible** here: the harness `winRoller` wins
> the System Test by the 0-0 tie rule (0 net successes), and the Quicksilver decker carries no ANALYZE
> utility, so the ANALYZE_HOST TN is `maxOf(2, control 8 − 0) = 8` — unreachable without exploding
> dice. The test is renamed `successful analyzeHost wins the test and activates no IC`, its comment now
> states honestly what it checks (deckerWins via `succeed = true`, plus `assertNoActiveIc()`) and
> points to `SystemOperationsTest`, where the net-successes → `revealedSecurityRating` mechanic **is**
> covered deterministically. The now-impossible `expectRevealedSecurityRating` DSL param was removed
> from `ScenarioBuilder`. `integrationTest` green.

**Where:** [ICActivationTest.kt:134-147](../../src/test/kotlin/com/shadowrun/matrix/integration/ICActivationTest.kt#L134-L147)

Comment reasons about revealing 2 info items; the test asserts only `assertNoActiveIc()`. Acceptable
(the `succeed=true` path already asserts `deckerWins`) but the revealed-items claim is unverified.

---

## Assessment

High-quality, disciplined suite — especially around the project's signature exploding-dice hazard. The
concerns are concentrated: **T-1 actively defends the HIGH auth-bypass** (must be updated with the S-1
fix), two coverage gaps leave the known E-1/S-2 bugs unobservable, and a handful of test-craft weaknesses
(SUT reimplementation, setup-only assertions, dead helper, guarded asserts). No test asserts *wrong*
behavior except the intentional T-1 lock-in, and no new production bugs surfaced beyond the known
findings.
