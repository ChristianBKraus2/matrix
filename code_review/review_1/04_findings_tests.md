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

**Where:** [SystemOperationsTest.kt:474-483](../../src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt#L474-L483)

`resolvePointerChain returns PointerChain with links and finalFile` asserts only
`chain.links.isNotEmpty()` and `finalFile != null`. Neither observes the die value or hop count, so the
test passes regardless of the E-1 flat-vs-exploding distribution defect. **Coverage gap, not a lock-in.**
Add a test that pins the roll via a stubbed roller and asserts the resulting chain length so the
distribution becomes observable.

---

## 🟠 T-3 (MEDIUM) — `tapComcall` tests leave the `scannerDeviceRating` trust boundary untested (S-2)

**Where:** [DeckerOperationsTest.kt:443-492](../../src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt#L443-L492)

The five `tapComcall` tests pass client-supplied `scannerDeviceRating` (0/4/8) straight into the engine
and assert the contest arithmetic — correct math, but nothing asserts the rating *originates from
host/device state* rather than the client. Masks the S-2 finding by validating computation while leaving
the trust boundary uncovered. Keep the arithmetic tests; add coverage (or a linked TODO) for the
source-of-truth once the device model owns the rating.

---

## 🟡 T-4 (LOW) — Initiative test reimplements the SUT instead of calling it

**Where:** [GameTest.kt:528-565](../../src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt#L528-L565)

`runCombatTurn gives higher initiative icon more actions` never invokes `game.runCombatTurn()` — it copies
the selection loop inline and asserts against its own copy. Proves the test's arithmetic, not production
ordering. (Low impact: `runCombatTurn` is itself deferred-unreachable per deferred.md #1.) Drive the
assertion through the real machinery or delete the duplicated-logic test.

## 🟡 T-5 (LOW) — CD-14 "reduces target number" asserts only the setup value

**Where:** [CyberdeckAndProgramMechanicsTest.kt:323-346](../../src/test/kotlin/com/shadowrun/matrix/decker/CyberdeckAndProgramMechanicsTest.kt#L323-L346)

The test captures the effective TN then discards it, asserting only that the Deception utility's
`currentRating == 4` (a fixture value) — it never verifies the TN was reduced. The sibling `CD-14 TN floor
is 2` test (349-367) does it correctly and is the pattern to follow. Assert on the captured effective TN.

## 🟡 T-6 (LOW) — Dead helper + else-less success guards in MovementTest

**Where:** [MovementTest.kt:104-109](../../src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt#L104-L109) and success-path tests (~240-250, 280-296, 345-355, 436-446, 477-487)

`alwaysWinRoller()` documents "always returns 6" but its body is `DiceRoller(Random(0L))` (comment-only)
and is unused. Several "succeeds"-named tests wrap assertions in `if (result is LogonResult.Success) { … }`
with no `else`, so an unexpected failure silently skips the assertions (vacuous pass); one assertion is
only `assertIs<LogonResult>` (trivially true). Delete the dead helper; replace guards with
`assertIs<LogonResult.Success>(result)`.

## 🔵 T-7 (INFO) — Guarded/existence-only assertions in SystemOperationsTest

**Where:** [SystemOperationsTest.kt:153-160,265-273,385-388,405-412,450-458](../../src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt#L153-L160)

Several tests assert only `assertNotNull(result)` or place real assertions inside `if (net > 0)` / auth
guards, so the meaningful check can be skipped depending on the roll. Pin the roller so the branch is
deterministically taken.

## 🔵 T-8 (INFO) — ICActivation analyzeHost "reveals security rating" asserts only no-IC

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
