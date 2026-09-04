# Design-vs-Code Discrepancies (current audit)

Accumulates findings from the align.md conformance audit. Prior runs archived in
`discrepancies_old*.md`. This log is the current one referenced by `deferred.md` and `align.md`.

Prefix conventions: see `align.md` §Discrepancies Log Format.

---

## GL-1 — Align XV wired full Hacking Pool into System Tests but left 19 integration tests with stale dice-roller calibrations (RESOLVED — reverted to optional pool)

**Design / PRD:** `prd_core.md:118` — "Hacking Pool dice **may** be added to any test made in the
Matrix — System Tests, Attack or Defense tests, maneuvers, or Attribute Tests." The word *may*
denotes an **optional, player-allocated** resource (standard SR3: the pool refreshes per Combat
Turn and is split across all tests that turn; it is not auto-spent in full on every roll).

**Code:** Commit `d97e119` ("Align XV") changed `Decker.performLogon` and `Decker.gracefulLogoff`
to call `SystemTestResolver.resolve(..., hackingPoolDice = hackingPool)`
([DeckerNavigationExtensions.kt:301](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt#L301),
[:239](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt#L239)), and
threaded `hackingPoolDice` through every `resolve*` path in
[SystemTestResolver.kt](../src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt#L43).
`resolve` now rolls `computerSkill + hackingPoolDice` decker dice.

For the HIGH_END mock decker "Quicksilver" (Intelligence 7, MPCP 12), Hacking Pool =
⌊(7+12)/3⌋ = **6**, so each System Test now rolls **8 + 6 = 14** decker dice instead of 8.

**Impact:** The integration test harness stubs dice via `winThenRoller(zeroCalls = 26, thenValue = 3)`
etc. ([IntegrationTestBase.kt:54](../src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt#L54)):
the first `zeroCalls` dice roll face 0 (0 successes; ties → decker wins), subsequent dice roll `thenValue`.
`zeroCalls = 26` was calibrated for `hackingPool = 0` (jack-in 8+SV + logon 8+SV ≈ 26). With the +12
decker dice, jack-in+logon now consume ~38 dice, so the host's logon dice fall past call 26 and roll
face 3 → host wins the contest → the **setup logon unexpectedly fails**. Observed at runtime:
`logonToHost failed: remaining at Host(Mitsuhama Pagoda, tally=6)`.

Result: `gradlew.bat test integrationTest` → **19 integration tests FAIL** on the committed, clean tree:
- `ICActivationTest` (8), `AlertAndTallyTest` (2), integration `MovementTest` (6),
  integration `CombatTest` (1), `DeckerCombatTest` (1), `GrayCombatTest` (1).

Align XV updated some unit-level tests (unit `MovementTest`, `DeckerCombatTest`, `GrayCombatTest`,
`SystemOperationsTest`, `SystemTestResolverTest`, `ScenarioBuilder`) but **did not** update the
calibrated integration rollers in `ICActivationTest`, `AlertAndTallyTest`, integration `MovementTest`,
`CombatTest`. `IntegrationTestBase.kt` has not changed since the older "Sync PRD" commit.

**PRD verdict:** `prd_core.md:118` permits but does not mandate adding Hacking Pool to System Tests,
and gives no rule for auto-spending the full pool on every test. There is **no PRD clause** that
resolves whether a System Test should default to full-pool, zero-pool, or player-allocated dice.
`prd_game.md` (action economy) is silent on pool allocation. This is an unresolved design fork.

**Status:** RESOLVED (2026-09-03) via **Option B** — user decision: pool is optional per PRD "may".
Stripped `, hackingPoolDice = hackingPool` from all **32** external System Test call sites
(2 in `DeckerNavigationExtensions.kt`, 30 in `DeckerOperationsExtensions.kt`). The resolver keeps its
`hackingPoolDice: Int = 0` parameter (and internal threading) so an explicit caller can still opt in.
`CombatResolver`'s pre-existing `+ hackingPool` on Attack/maneuver pools was **not** touched (it
predates Align XV, is within PRD "may" for combat tests, and was not part of this regression).
`gradlew.bat test integrationTest` → **BUILD SUCCESSFUL** after also fixing GL-2 (below).

**Fix options considered:**
- (A) Treat the code as intended (decker always applies full Hacking Pool): recompute the integration
  roller calibrations. — *rejected.*
- (B) **[CHOSEN]** Treat the code as over-eager (pool is optional per PRD "may"): default
  `hackingPoolDice` to 0 at the call sites, keep the resolver parameter for explicit opt-in.
- (C) Model per-turn pool allocation properly (largest scope; likely a new design-doc item). — deferred.

---

## GL-2 — Align XV tightened `resolveSlow` test assertion to `> 0` but left an all-zero dice stub, making it unsatisfiable

**Code:** [DeckerCombatTest.kt](../src/test/kotlin/com/shadowrun/matrix/integration/DeckerCombatTest.kt)
`resolveSlow on proactive IC reduces actions lost when decker wins`. Commit `d97e119` ("Align XV")
changed the assertion from `result.actionsLost >= 0` (tautological — `actionsLost` is `net/2` of a
clamped non-negative net, so always ≥ 0) to `result.actionsLost > 0`, but left the dice stub as
`winRoller()`, which returns face 0 on every die → 0 successes for **both** contest sides.

**Behaviour:** In [CombatResolver.resolveSlow](../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt#L475)
the Slow effect needs `slowSuccesses > icSuccesses`; with all-zero rolls `net = 0`, so
`net <= 0 → SlowResult(0, false)` and `actionsLost = 0`. The tightened assertion can therefore never
pass. This is a **test-stub calibration bug**, not a production defect — the `resolveSlow` logic is
PRD-correct (Slow reduces proactive-IC actions only when the Slow side wins the Success Contest).
The failure was previously masked by GL-1: the scenario setup logon threw before the assertion ran.

**Status:** RESOLVED (2026-09-03) — recalibrated the stub to
`winThenRoller(zeroCalls = 5, thenValue = 5)`: `resolveSlow` rolls the IC's `securityValue = 5` dice
first (all 0 → 0 successes), then the Slow side's `slowRating = 4` dice (face 5 ≥ TN 5 → 4 successes),
giving `net = 4`, `actionsLost = 2`. Faithful to Align XV's intent ("positive when decker wins").

---

## GL-3 — The combat/out-of-combat game loop is unfinished and unwired; two real correctness bugs are dormant (D4G-3, D4G-4)

**Root cause:** `game/Game.kt`'s `runCombatTurn` / `runOutOfCombatTurn` were written for a future,
non-placeholder turn model, but production drives decker turns directly through
`WebSocketDeckerController` and never calls the `Game` loop (see `deferred.md` #1, D4G-1 —
`Decker.action()` is a `DeckerAction` placeholder). Because the loop is unreachable in production,
two genuine correctness defects sit latent inside it:

**Code:**
- **D4G-3 — IC move never persists.** `ic/IC.kt:49-55` `moveIfNeeded()` returns
  `ActionResult.IcMoved(...)` **without mutating** the IC's `guardedNode` or calling
  `removeIc`/`addIc`. The only "caller" that could honor the move — `game/Game.kt:43-48` — invokes
  `state.icon.action(...)` and **discards the returned `ActionResult`**, decrementing initiative only.
  design_game/game.md L221 states the caller "replaces this IC instance in `context.activeIc` with a
  copy at the new node." No code path does this: a proactive IC whose `guardedNode` ≠ target node
  announces a move every turn forever, never reaching the target and never attacking.
- **D4G-4 — crashed IC can re-act the same turn.** `game/Game.kt:39-49` builds the initiative
  `states` list once per turn and gates re-selection only on `currentInitiative > 0`. An IC that
  calls `context.removeIc(this)` mid-`action()` (`ic/IC.kt:136,224,248,269` — Blaster/Sparky/TarPit/
  Lethal/NonLethal Black IC) keeps its `ActiveIconState` with residual initiative and can be selected
  again, acting (and attacking) after removal.

**Impact:** None in production today (loop unreachable). Both become live the moment the game loop is
wired in for automated/NPC turns. Neither has test coverage — confirmed uncovered by D8T-3, D8TB-1,
D8TB-2, D8TC-3, D8TC-4 (all IC-turn tests drive `IC.action()` directly, never `runCombatTurn`).

**PRD verdict:** No PRD clause governs either edge. game.md L221 (move persistence) and L148-149
(per-turn list rebuild) describe the intended contract; the code does not honor it.

**Fix required (when the loop is wired):** capture `action()`'s result and, on `IcMoved`, replace the
IC in `context.activeIc` at the new node (or have `moveIfNeeded` mutate via `GameContext`); and skip
`states` whose icon is no longer in `context.activeIc`. **Status:** flagged, deferred with the loop.

---

## PR-1 — Grid `LOCATE_ACCESS_NODE` omits the first-call blank-query guard the host path enforces (D5S-3)

**Design:** protocol.md L85 — LOCATE_* `query` is "required on first call, ignored on continuation."

**Code:** `server/WebSocketDeckerController.kt:200-203` (`dispatchGridOperation`, LOCATE_ACCESS_NODE)
calls `locateWithState { prec, q -> decker.locateAccessNode(grid, q, prec, diceRoller) }` with **no**
first-call query check, passing a blank query straight into the resolver. The host path for the same
op (`:286-288`) does guard it: `if (query.isBlank() && decker.interrogationStates["LOCATE_ACCESS_NODE@HOST"] == null) return ... "requires a search term on the first call"`.

**Impact:** LIVE (grid dispatch is reached in normal play). A first-call grid LOCATE_ACCESS_NODE with
blank query is accepted instead of rejected — a grid-vs-host inconsistency (align.md Rule 10
partial-fix pattern). Untested (D8TE-4: no test sends `params` on the grid path).

**PRD verdict:** protocol L85 governs; the grid path violates it.

**Status:** RESOLVED (2026-09-04) — added a first-call blank-query guard to `dispatchGridOperation`
keyed on `"LOCATE_ACCESS_NODE@$gridTag"` (gridTag ∈ LTG/RTG/PLTG from the decker's current location),
matching the domain's `locateAccessNode` state key and mirroring the existing host-path guard.

---

## RT-1 — reconnectToken never cleared server-side on graceful logoff (D5S-2)

**Design:** protocol.md L37 — "the token survives disconnect but **is cleared on intentional logout
(graceful logoff)**." UI-04 scopes clearing to the *client* only.

**Code:** `server/SessionRegistry.kt:29,73,81` write `reconnectTokens` on join/reconnect and **never**
remove them (`deregister` at :100-111 deliberately retains for reconnect). `GracefulLogoff` is a
decker action (`WebSocketDeckerController.kt:153-156`) and never notifies the registry, so the token
survives graceful logoff.

**Impact:** Ambiguous. If L37 is a server-side guarantee, a logged-off slot stays reclaimable with the
old token and `reconnectTokens` grows unbounded over process lifetime. If UI-04 governs (client-only),
the server is compliant and L37's wording is stale. Untested (D8TE-5).

**PRD verdict:** No PRD clause resolves *server-side* token clearing; protocol L37 and prd_ui UI-04
conflict on scope.

**Status:** RESOLVED (2026-09-04) — confirmed server-side clearing is intended (`protocol.md L37`
governs). Added `SessionRegistry.clearReconnectToken(deckerName)` and call it from `conductTurn`
immediately after a `GracefulLogoff` dispatch, so the token is removed regardless of whether the
logoff succeeded cleanly or fell back to a jack-out.

---

## UI-1 — LocationPanel trusts the permanently-stubbed `locationIndex`; name-match fallback is dead (D6F-2)

**Design:** design_ui L (DOC-6) predicted the panel would prefer brittle name-parsing. The code
inverts this. See also `deferred.md` #4 (updated 2026-09-03 to match the current code).

**Code:** `frontend/src/components/LocationPanel.tsx:79-85` prefers
`visibleObjects[decker.locationIndex]` and keeps the `.name`-match branch only as fallback. Backend
`server/dto/DeckerStateDto.kt:28` sets `locationIndex = if (currentLocation != null) 0 else null` — a
permanent stub. Because `locationIndex` is never null while jacked in, the panel always renders
`visibleObjects[0]` and the name-match branch is unreachable dead code.

**Impact:** LIVE. The location strip renders whatever object sits at index 0 of `visibleObjects`,
correct only if the server guarantees the current location is element 0 — an ordering contract not
stated in the protocol.

**PRD verdict:** No PRD clause fixes the `visibleObjects` ordering; the real index lookup is deferred
(#4).

**Status:** DEFERRED — see `deferred.md` #4. Requires defining a stable ordering contract for
`visibleObjects` before a real `locationIndex` can be computed server-side. No code change
warranted until that design decision is made.

---

## CD-1 — Cyberterminal is never constructible from config (D7C-1)

**Design:** iter2_cyberdeck L68/L541 — "Loader: `type: cyberterminal` in `decks.yaml` → instantiate
`Cyberterminal`, else `Cyberdeck`." The `Cyberterminal` factory exists.

**Code:** `config/DeckerLoader.kt:73-85` always builds `Cyberdeck(...)` (leaving `isCyberterminal`
false) and reads no `type` field; `config/DeckCatalogLoader.kt:19-27` has no `type` either. No loader
calls the `Cyberterminal` factory.

**Impact:** CT-01..CT-05 (MPCP≤4 cap, RI=0, −1 rating, black-IC/dump-shock immunity) cannot be
produced through config. Untested (D8TF-3: DeckerConfigTest loads only a Cyberdeck).

**PRD verdict:** cyberterminal behavior is PRD (CT-nn); the *config path* to it is unimplemented.

**Status:** RESOLVED (2026-09-04) — `DeckerLoader.buildCyberdeck` now reads `type: cyberterminal`
and routes to the `Cyberterminal(...)` factory when present; all other `type` values (or absent
`type`) fall through to `Cyberdeck(...)` as before. `DeckCatalogLoader` is not a direct loader so
no change needed there.

---

## GR-1 — Config grid/host loaders implement only the happy path; several documented model features have no YAML→domain path (D7C-2/3/5/6/7)

**Root cause:** `config/GridLoader.kt` and `config/HostLoader.kt` parse the common fields and omit
optional/advanced schema, so documented model features are silently unreachable:

- **D7C-2 — `Host.connectedHosts` never populated.** `HostLoader.kt:85-99` / `GridLoader.buildHost`
  (:129-137) do no host-to-host wiring, yet `grid.yaml` declares `topology: TIERED` / `HOST_HOST`
  hosts. `Decker.availableActions` OnHost branch (`Decker.kt:160`
  `loc.host.connectedHosts.forEach { LogonToHost }`) is always empty → tiered/host-host navigation is
  impossible (violates ord.md L27/L249). **LIVE.**
  **Status: RESOLVED (2026-09-04)** — `GridLoader.load` now does a third pass after building the
  matrix: `collectHostConnectionSpec` reads `connected_hosts: [name, ...]` from inline host YAML
  (source-host declaration); `wireHostConnections` resolves names to `Host` objects and rebuilds
  the matrix tree. Config-file hosts (`config:` path) declare `connected_hosts` alongside `config:` in
  `grid.yaml` — the topology lives in the grid file, the host internals in their own YAML.
- **D7C-3 — grid `security_sheaf` not loadable.** `GridLoader` builds RTG/LTG/PLTG (:49/:90/:119)
  parsing no `security_sheaf`; each grid's `securitySheaf` falls to the empty default, so grid-level
  tally escalation can never fire (ord.md L40-43/L235-241). HostLoader has the parser; GridLoader does
  not. **LIVE.**
- **D7C-5 — PLTG does not inherit security from parent RTG.** `GridLoader.buildPltg` (:111-113)
  requires an explicit `security`, whereas `buildLtg` (:87-88) inherits (ord.md L19). Inconsistent
  with the sibling LTG loader. Low (grid.yaml always provides it).
  **Status: RESOLVED (2026-09-04)** — `buildPltg` now treats `security` and `ratings` as optional,
  falling back to `parentLtg.securityRating` / `parentLtg.subsystemRatings` when omitted, matching
  the `buildLtg` pattern.
- **D7C-6 — DataFile pointer fields not loadable.** `HostLoader.buildDataFile` (:127-132) sets only
  name/isScrambleProtected/sizeMp; `pointerToHost`/`pointerTargetFile` default null (ord.md L56-62).
  Distributed-database scenarios are not configurable. **Not tracked in `deferred.md`** — flag: either
  defer explicitly or implement.
  **Status: RESOLVED (2026-09-04)** — `GridLoader.load` now does a fourth pass: `collectDataFilePointerSpec`
  reads `pointer_to_host` and `pointer_target_file` fields from inline host YAML; `wireDataFilePointers`
  resolves them to `Host`/`DataFile` object references and rebuilds the matrix. Skips config-file
  hosts (`config:` path) — those would declare pointer fields in their own YAML and require a
  `HostLoader` extension to carry the specs forward.
- **D7C-7 — duplicate host subsystem nodes pass through.** `HostLoader.buildFromMap` dedups only into
  `nodesByType` (warns at :60-63) but hands the *raw* `nodes` list to `Host` (:93); `Host.init` checks
  only set-coverage, so duplicate `type:` entries survive (ord.md L249 "exactly one per subsystem
  type"). Low.
  **Status: RESOLVED (2026-09-04)** — `buildFromMap` now passes `nodesByType.values.toList()` to
  `Host` instead of the raw `nodes` list, so the dedup that was already logged is now also enforced.

Also **D7C-4** (intrusion_difficulty/topology loader-defaulted to AVERAGE/OPEN_ACCESS rather than
validated) — low; domain treats them as required but no schema mandates presence.

**Impact:** D7C-3 is the remaining material gap (grid-level tally escalation unreachable via config);
D7C-2 and D7C-6 (now resolved) were the other material ones. D7C-4 is low-severity (loader defaults
to AVERAGE/OPEN_ACCESS without validation). All gaps were uncovered by tests (D8TF-3).

**PRD verdict:** No PRD clause mandates these YAML keys; they realize documented domain/ord.md model
features. Design-vs-code gaps, not PRD violations.

**Remaining fix:** implement a grid `security_sheaf` parser (D7C-3) once the semantics are
specified in `ord.md` and a YAML example exists (see Open Issues).

---

## MC-1 — Test-suite coverage gaps concentrated on the hardest paths and the two known dormant bugs

**Status: PARTIALLY RESOLVED** — comment/quality defects and most untested wire paths addressed in this session; dormant-bug coverage and two weak-assertion cases remain open.

**Finding (Rule 6, consolidated from Iteration 8):** the test suite asserts protocol-/spec-correct
behavior wherever it asserts, but never drives several important paths:

- **Known real bugs untested:** D4G-3 and D4G-4 (see GL-3) have zero coverage — all IC-turn tests call
  `IC.action()` directly, never `Game.runCombatTurn` (D8T-3, D8TB-1/2, D8TC-3/4). **Still open.**
- **Known real gaps untested:** D5S-3 grid blank-query covered (PR-1 + D8TE-4 ✓); D5S-2 graceful-logoff
  token clear covered (RT-1 + D8TE-5 ✓); config-loader gaps D7C-1/2/5/6/7 resolved, D7C-3 still
  open (D8TF-3). **Still open:** tests for D7C-3 require that loader feature first.
- **Wire paths:** D8TE-1 (UNKNOWN_MESSAGE_TYPE via Ktor integration test) ✓; D8TE-2 (registry
  capacity — `maxConnections=1` unit test) ✓; D8TE-3 (action timeout with `actionTimeoutSeconds=1`)
  ✓; D8TE-4 (blank LOCATE_ACCESS_NODE sends `params` object) ✓; D8TE-5 (clearReconnectToken frees
  reconnect without token) ✓. **Still open:** D8TE-2 does not test the full wire SERVER_FULL frame
  (that requires `matrixModule` with configurable MAX_CONNECTIONS).
- **Comment/quality defects resolved:** D8T-1 (CC-21→CC-24 test names) ✓; D8T-2 (false "IC dice
  succeed" comment) ✓; D8TB-4 (empty runCombatTurn test now asserts decker still in context) ✓;
  D8TB-6 (face=6→face=5 comment) ✓; D8TC-2 (wrong "evade succeeds" comment) ✓; D8TC-5 (wrong IC
  attack dice comment — uses SV pool, not ic.rating) ✓; D8TD-3 (failRoller face=4→face=3) ✓;
  D8TF-1 (dead `winRoller`/`buildDecker` helpers removed from GridLoadTest) ✓; D8TF-2 (test name
  now reads "detection factor formula uses masking and sleaze rating") ✓.
- **Weak assertions still open:** D8TB-3 (runCombatTurn test still reimplements loop inline —
  requires production change to inject custom `ActiveIcon`s); D8TB-5 (Crippler/Ripper tests still
  assert `<=` — dice setup doesn't guarantee IC net > 0; the test comment explicitly allows no
  reduction); D8TC-1 (both-zero disjunct still in AlertAndTallyTest — removing it breaks when host
  TN > 3 and failRoller scores 0 successes).

**Impact:** The two dormant production bugs (GL-3) and the remaining live gap (GR-1 D7C-3) would not
be caught by any regression. The remaining weak assertions (D8TB-3/5, D8TC-1) allow false-pass
scenarios for IC attribute reduction and tally independence, but no production logic is broken.

**Fix required:** add coverage for the game-loop bugs (once the loop is wired); wire D8TF-3 once
D7C-3 is implemented; fix D8TB-3 by either accepting the test as a loop-ordering spec or redesigning
to use real icons; fix D8TB-5 by guaranteeing IC net > 0 (e.g., use [6,1,...] exploding dice at TN≤6
after verifying Crippler/Ripper reduction threshold); fix D8TC-1 by using a roller where host TN ≤ 3
or asserting `ucasTally == 0 && aztTally > 0` separately.

---

## Open Issues (as of 2026-09-04)

The following findings were not resolved because the fix approach is not clear — either the desired
behavior is contradicted or unspecified in the design documents, or implementing a fix requires
infrastructure that does not yet exist. Each entry describes exactly what is missing before a fix
can be written.

---

### GL-3 — dormant game-loop bugs (D4G-3: IC move not persisted, D4G-4: crashed IC re-acts)

**Why not fixed:** Both bugs live inside `game/Game.kt:runCombatTurn`, which is never called from
production code. The WebSocket controller drives decker turns directly; IC turns are also driven
directly from tests via `IC.action()`. Fixing the bugs now would change code that is unreachable —
the fixes would be untestable and unverifiable. The right moment to fix them is when
`runCombatTurn` is wired in.

**What's needed before fixing:**
- D4G-3 (IC move): the caller must capture `ActionResult.IcMoved` and update the IC's `guardedNode`
  in `context.activeIc`. `GameContext` has no mutation API for IC position today — that API needs
  to be designed first, or `moveIfNeeded` needs to be changed to mutate via `GameContext` directly.
- D4G-4 (crashed IC re-acts): the initiative list must be rebuilt or filtered after each `action()`
  call so that a removed IC is skipped. This is a straightforward code change, but it only makes
  sense after the loop is wired, because otherwise there is no test harness to verify the fix.

---

### UI-1 — `LocationPanel` always shows `visibleObjects[0]` (D6F-2)

**Status: DEFERRED** — see `deferred.md` #4. The stub (`locationIndex = 0`) is intentional until
the `visibleObjects` ordering contract is defined. No code change warranted.

---

### GR-1 / D7C-3 — Grid `security_sheaf` not loadable from YAML

**Why not fixed:** `HostLoader` already has `buildSecuritySheaf` / `buildTriggerStep` which parse
the same structure for hosts. Mechanically, the parser could be lifted into `GridLoader`. However:

1. The `buildTriggerStep` parser calls `nodesByType` — a host-specific map of subsystem nodes that
   grids do not have. The trigger-step model as written assumes a host context.
2. No entry in `grid.yaml` declares a `security_sheaf`, so there is no concrete example to validate
   against and no test would exercise the new code.
3. The semantics of a grid-level security sheaf (which tally threshold counts, which IC spawns on a
   grid vs. a host) are not described in `ord.md` separately from the host case.

**What's needed before fixing:** decide what a grid `security_sheaf` entry looks like (trigger thresholds,
what action fires — presumably a different action than host IC since grids have no subsystem nodes),
document it in `ord.md`, add at least one example to `grid.yaml`, and adapt the parser accordingly.

---

### MC-1 — Residual test coverage gaps

**Why not fully resolved:** See the MC-1 finding section. Remaining open items:

- **D4G-3/D4G-4 coverage (D8T-3, D8TB-1/2, D8TC-3/4):** require the game loop to be wired (GL-3).
- **D7C-3 loader tests (D8TF-3):** require the grid `security_sheaf` feature to be implemented.
- **D8TB-3 (inline loop):** the test drives custom anonymous `ActiveIcon` objects that cannot be
  passed to `Game.runCombatTurn()` via `GameContext` (which only accepts `Decker`/`IC`). Replacing
  the inline loop with an actual `game.runCombatTurn()` call would require either a production-code
  change to accept extra icons, or rewriting the test with real deckers at specific initiative scores.
- **D8TB-5 (weak `<=` assertions):** the dice setup `[5,1,5,1,...]` does not guarantee IC net > 0 —
  the test comment explicitly says "net=0 means no reduction, but result is still IcAttack." Fixing
  requires choosing dice where IC succeeds (face ≥ TN) and decker fails (face < resist TN), with no
  tie. Use `[6,1,1,1,...]` (exploding first die gives total 7 ≥ TN 6 for Crippler/RED; verify TN
  for Ripper/ORANGE) then assert `< `.
- **D8TC-1 (both-zero disjunct):** `failRoller()` returns face=3; if AZT RTG security value > 3,
  host scores 0 successes and both tallies remain 0. Removing the disjunct then breaks the test.
  Fix: use a roller where host TN ≤ 3 for the AZT logon, OR assert the tallies separately
  (`ucasTally == 0` and `aztTally > 0`).
