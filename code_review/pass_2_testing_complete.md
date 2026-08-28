# Testing Review — complete (cross-cutting)

## Summary

The backend test suite is substantial and well-structured: roughly 40 Kotlin test files span
unit tests for individual operations and IC types, domain-level integration tests driven through
a DSL-based scenario builder, and server-level tests for the WebSocket protocol layer. The
integration/unit split is healthy — game logic is driven end-to-end through the real domain
model with deterministic dice rollers, and the server layer is covered by both
FakeWebSocketSession unit tests and real Ktor testApplication WebSocket tests. Coverage gaps
exist but are surgical: the action-timeout path is untested, the reconnect flow has no
integration test, and only one WebSocket integration test exercises an actual game-state change
(navigation). The frontend has zero test infrastructure — not a single test file exists and no
test runner is installed — which is the most significant gap in the project.

---

## Findings

### [CRITICAL] Frontend has no test infrastructure at all

**File:** `frontend/package.json`

**Issue:** The `scripts` block contains only `dev`, `build`, and `preview`. There is no test
runner (Vitest, Jest), no component testing library (@testing-library/react), and no test files
anywhere under `frontend/src/`. The entire React layer — including the non-trivial
`useWebSocket` hook — is completely untested.

`useWebSocket.ts` implements a meaningful state machine (reducer with CONNECTED /
DISCONNECTED / CONTROL / STATE / RESULT / ERROR actions), exponential-backoff reconnect logic,
a `pendingNameRef` that buffers a join name across a connection cycle, and role-based rendering
decisions in `App.tsx`. None of this is verified by any automated test.

**Recommendation:** Install Vitest + @testing-library/react as devDependencies. Write unit tests
for the `useWebSocket` reducer (pure function, easy to test in isolation). Add component tests
for the role-based rendering branches in `App.tsx` (join screen vs. game grid vs. waiting
screen) and the error-label mapping in `JoinScreen`. The WebSocket browser API can be mocked
with a simple fake (`new WebSocket` can be replaced in the test environment).

---

### [HIGH] Action-timeout path is never tested

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt`
**Also:** `src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt:126`

**Issue:** `WebSocketDeckerController` accepts an `actionTimeoutSeconds` parameter. The only
timeout-adjacent test (`action with no registered session broadcasts turn-skipped ResultMessage`
in `WebSocketServerTest`) covers the case where no session has joined for that decker name —
meaning the turn-skip is immediate, not a timeout. The case where a decker IS registered,
receives a `StateMessage` with available actions, but never sends an `ActionCommand` within the
timeout window is completely untested. This path exercises distinct server code: it must unblock
the coroutine suspension, broadcast a "timed out" result, and demote the controller without a
`receiveAction` call.

**Recommendation:** Add a test in `WebSocketServerTest` or `WebSocketServerIntegrationTest`
that registers a decker, starts a `WebSocketDeckerController.action()` call on a background
thread, and deliberately does not send an action. Use `actionTimeoutSeconds = 1` and assert that
a turn-skipped `ResultMessage` is received within ~2 seconds and the decker is subsequently
demoted to `registered_decker` role.

---

### [HIGH] Server-side reconnect flow has no integration test

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt`

**Issue:** The frontend `useWebSocket.ts` (lines 92–97) handles the `reconnect: true` flag in
`ControlMessage` by re-sending the join message automatically after a disconnect/reconnect
cycle, and `App.tsx` renders a "SESSION RESTORED" banner when `reconnected` is true. The server
presumably emits `reconnect: true` when a previously-registered decker name is used to join
again. No integration test exercises this sequence: client joins → client disconnects → client
reconnects with the same name → server sends `reconnect: true` → client receives correct state.
If this server-side path does not exist or is broken, the reconnect banner will never appear and
decker state will be lost on network hiccups.

**Recommendation:** Add a `WebSocketServerIntegrationTest` that opens a connection, joins as
"Kylie", closes the WebSocket, opens a new connection, joins as "Kylie" again, and asserts that
the received `ControlMessage` has `role = "registered_decker"` and `reconnect = true`.

---

### [HIGH] Only one WebSocket integration test exercises actual game-state change

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt`

**Issue:** Of the six tests in `WebSocketServerIntegrationTest`, five test protocol mechanics
(connection roles, join flow, state broadcast, action relay). Only the sixth — `decker
navigating to UCAS RTG sees all four LTGs as available actions` — exercises real game-state
mutation (jackInToLtg, then logonToRtg through a live controller action). The entire set of
gameplay scenarios — host intrusion, system operations (analyzeSubsystem, decryptAccess), IC
encounters, combat turns, alert transitions, data operations — is tested through the domain
model directly but never through the WebSocket server. A bug in DTO construction for
combat-related state (e.g. serialising `isPinnedByBlackIc`, `activeUtilities`, `conditionMonitor`
correctly into `DeckerStateDto`) would not be caught by any existing test.

**Recommendation:** Add at least two further `WebSocketServerIntegrationTest` scenarios:
(1) a host intrusion sequence that drives a `WebSocketDeckerController` through jack-in → logon
→ one system operation, asserting the resulting `StateMessage.decker` DTO reflects the updated
state; (2) an IC-present combat turn, asserting `availableActions` and decker condition monitor
values are serialised correctly.

---

### [MEDIUM] `ScriptedDeckerIcon.action()` always returns `ActionResult.DeckerAction` — stepResults assertion is vacuously true

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt:222`

**Issue:** The `ScriptedDeckerIcon.action()` override (line 222) unconditionally returns
`ActionResult.DeckerAction` regardless of what the step lambda actually did:

```kotlin
return ActionResult.DeckerAction.also { stepResults += it }
```

The assertion two lines after `runActions` (line 93 in the `scenario()` overloads):

```kotlin
assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
```

is therefore trivially true and tests nothing. Actual operation outcomes (OperationResult,
LogonResult, LogoffResult) are opaque to the harness; the only way to verify them is via
side-effect assertions on decker or context state. This is adequate given the existing assert
helpers, but the `stepResults` collection is vestigial and the assertion creates a false
impression of meaningful verification.

**Recommendation:** Either remove `stepResults` and the assertion entirely, or change
`ScriptedDeckerIcon.action()` to record the actual result returned by the step lambda (which
requires the step to return an `ActionResult`, or use a side-channel).

---

### [MEDIUM] `navigateToNode` in ScenarioBuilder teleports the decker — bypasses navigation logic

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/ScenarioBuilder.kt:241-249`

**Issue:** The `navigateToNode` DSL step directly mutates `persona.currentNode` via a `copy()`:

```kotlin
updateCurrentDecker(d.copy(persona = d.persona!!.copy(currentNode = if (succeed) node else null)))
```

It does not invoke any game operation. Tests that use this step (such as the Killer IC threshold
test in `CombatTest`) therefore do not exercise the actual in-host navigation operation. Any
bug in "decker moves to node" logic (access control checks, tally increments, node-locked
actions) would not be caught by tests that rely on this shortcut.

**Recommendation:** If a real navigation operation exists, add a `navigateToNodeViaOperation`
step that invokes it and asserts success. Reserve the teleport form only as a named
`teleportToNode` for setup convenience, making the intent unambiguous.

---

### [MEDIUM] `winRoller` name implies active success but relies on a 0-vs-0 tie-break rule

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt:35-38`

**Issue:** `winRoller()` returns face=0 for every die call, which (per project memory notes)
yields 0 successes for all participants. Navigation steps "succeed" because the resolution rule
gives ties to the decker, not because the decker rolled positively. This is the correct
mechanical behaviour to test, but the name `winRoller` strongly implies the decker rolls
successes. A test author who applies `winRoller` to a new scenario expecting the decker to
accumulate a positive success count (e.g. for operations whose success threshold requires at
least N successes) will get a 0-success result and a confusing test failure.

The `hitRoller` (face=5) and `winThenRoller` helpers have clearer semantics and are documented
with inline comments. `winRoller` has no comment.

**Recommendation:** Rename to `tieRoller` or `zeroRoller` to reflect the actual mechanic, and
add a KDoc comment explaining when it applies. Alternatively, keep the name but add a comment:
`// Both sides roll 0 successes; decker wins by default (tie goes to decker).`

---

### [MEDIUM] `HostMock.build()` always produces the same narrow fixture regardless of name

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/HostMock.kt`

**Issue:** `HostMock.build(name)` creates a Host with `SecurityCode.GREEN`, all subsystem
ratings = 3, `IntrusionDifficulty.AVERAGE`, and `TopologyType.TIERED` regardless of the `name`
argument. Tests that use `buildDefaultContext` (which calls `HostMock.build("placeholder")`)
operate against this single host profile. RED-code hosts, HARD difficulty, OPEN_ACCESS or
STAR topology, and high-rating subsystems are never exercised by any test that builds its
context from `HostMock`. The integration tests that go through `scenario()` use real grid hosts
(via GridMock), which provides more variety, but unit-level WebSocket tests always get the same
flat fixture.

**Recommendation:** Add named factory methods such as `HostMock.buildRed()`,
`HostMock.buildHighSecurity()`, or accept `SecurityCode` / `TopologyType` parameters. Use the
RED/HARD variant in at least one `WebSocketServerIntegrationTest` to verify alert-sensitive DTO
paths.

---

### [LOW] `winFailWinRoller` is defined but never used

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt:62-71`

**Issue:** `winFailWinRoller` is declared in `IntegrationTestBase` but does not appear in any
test file in the reviewed set. It inflates the base class and signals intent that was either
superseded by `winThenRoller` or belongs to a planned test that was never written.

**Recommendation:** Remove the method or, if it is needed for an in-progress test, add a
`// TODO:` comment noting where it will be used.

---

### [LOW] `STANDARD` decker tier is never used in any test

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/DeckerMock.kt:13`

**Issue:** `DeckerMock` defines `HIGH_END`, `STANDARD`, and `LOW_END` tiers. Searching all test
scenarios confirms only HIGH_END (the default) and LOW_END are ever passed to `scenario()`. The
STANDARD tier (mid-range deck and attributes) is never exercised. Dice outcomes and threshold
crossings that only manifest at medium stats would be invisible to the test suite.

**Recommendation:** Add at least one scenario in `CombatTest` or `MovementTest` using
`deckerTier = DeckerMock.STANDARD` to cover the mid-range code path.

---

### [LOW] `name_too_long` error code exists in the frontend but is never tested server-side

**File:** `frontend/src/App.tsx:15` (ERROR_LABELS map)

**Issue:** `App.tsx` maps `name_too_long` to "Decker name too long (max 32 characters)", and
the join input has `maxLength={32}`. However, none of the server-side tests in
`WebSocketServerTest` or `SessionRegistryTest` exercise a join with a name exceeding 32
characters. If the server does not enforce this limit (or enforces it at a different threshold),
the frontend label silently misleads.

**Recommendation:** Add a test in `WebSocketServerTest` that sends a `JoinMessage` with a
33-character name and asserts the response is `ErrorCode.NAME_TOO_LONG`.

---

### [INFO] `GridMock.matrix` is a shared `object val` — latent test-pollution risk

**File:** `src/test/kotlin/com/shadowrun/matrix/integration/utility/GridMock.kt:10`

**Issue:** `GridMock.matrix = GridInitializer.initialize()` is initialised once for the JVM
lifetime. All test classes that reference it share the same instance. The current design appears
safe because `buildDefaultContext` and `scenario()` work with copies of hosts rather than the
live matrix, and the RTG/LTG/Host graph appears to be treated as immutable after
initialisation. However, if a future test mutates a host directly through the shared matrix
object (e.g. by calling `host.alertStatus = ...` on a reference obtained from
`GridMock.matrix.getHost(...)`), it would silently pollute test ordering.

**Recommendation:** No immediate action required. Consider making `GridInitializer` return
immutable data structures, or annotate `GridMock.matrix` with a comment warning against direct
mutation: `// Treat as read-only; tests must copy before modifying.`

---

## No Issues Found In

- **FakeWebSocketSession** — clean, minimal, correctly implements `DefaultWebSocketServerSession`
  with an unbounded channel; `nextText()` helper makes assertions readable.
- **DiceRoller stub design** — `winThenRoller` and `hitRoller` have accurate inline documentation;
  the `[6,1]` sequence for Black IC tests (avoiding exploding-dice infinite loops) is correct
  and documented.
- **ScenarioBuilder DSL** — comprehensive coverage of all navigation actions (LTG, RTG, PLTG,
  Host, logoff paths) with both success and failure branches; visibility and actionability
  pre-checks mirror real game guard conditions.
- **SessionRegistryTest** — thorough edge-case coverage: deregister with null/completed
  pendingAction, double-join, non-existent decker demotion, two-session broadcast.
- **DtoMappingTest** — covers all `MatrixObject` and `AvailableAction` variants including null
  `guardedNode` and null-target operations; uses real GridMock data rather than stubs.
- **DeckerOperationsTest** — win/lose test pair for every operation; correct use of `fixedRoller`
  rather than the ambiguous `winRoller`; passcode-bypass path verified with a roller that throws
  if called.
- **WebSocketServerTest** — covers turn flow, forfeit on disconnect, invalid action index,
  `NOT_YOUR_TURN`/`NAME_ALREADY_TAKEN`/`ALREADY_REGISTERED` error codes, and two-session
  broadcasting.
