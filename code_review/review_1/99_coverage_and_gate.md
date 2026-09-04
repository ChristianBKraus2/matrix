# Coverage Manifest & Completion-Gate Status

Per guideline §11, a completeness-provable review must satisfy six conditions. **All in-scope files have
now been read in full** (the initial daily-spend-limit blocker was resolved by the user raising the limit;
the three failed subagent scopes — combat/decker/game data classes, frontend components, and the test
suite — were re-run to completion).

---

## Files READ IN FULL

### Server layer (10 files)
`MatrixServer.kt`, `SessionRegistry.kt`, `TurnCoordinator.kt`, `WebSocketDeckerController.kt`,
`DeckerDisconnectedException.kt`, `dto/Messages.kt`, `dto/DeckerStateDto.kt`, `dto/AvailableActionDto.kt`,
`dto/MatrixObjectDto.kt`, `Main.kt`.

### Game engine — logic-heavy files
`utility/DiceRoller.kt`, `decker/Decker.kt`, `decker/DeckerOperationsExtensions.kt`,
`decker/DeckerNavigationExtensions.kt`, `decker/DeckerMemoryExtensions.kt`, `game/DeckerExtensions.kt`,
`game/GameContext.kt`, `game/Game.kt`, `ic/IC.kt`, `combat/CombatResolver.kt`, `common/Enums.kt`.

### Game engine — data classes / holders (27 files) ✅ re-run complete
All combat holders (`CombatInitiative`, `ManeuverResult`, `ManeuverParticipant`, `DefenderParticipant`,
`AttackParticipant`, `CombatModifiers`, `BlackIcPinState`, `JackOutPinResult`, `SimsenseOverloadResult`,
`CripplerResult`, `TarBabyResult`, `SlowResult`, `IcSuppressionState`, `IcDamageResult`, `TrackState`,
`AttackResult`, `Combat`), decker holders (`Persona`, `Cyberdeck`, `Cyberterminal`, `ActiveMemory`,
`DownloadDestination`, `MedicResult`, `MovementResult`), game holders (`ActionResult`, `ActiveIcon`,
`ActiveIconState`).

### Engine — bulk packages (35 files, completed subagent, first pass)
`network/` (11), `operations/` (14), `programs/` (3), `config/` (7). Reported clean (1 LOW, 4 INFO).

### Engine — remaining leaf files (author)
`common/Enums.kt`, `common/SharedTypes.kt` (`SecurityRating`, `SubsystemRatings`, `ConditionMonitor` —
clean, exhaustive `when`, damage coerced to `maxBoxes`), `accessories/Accessory.kt` (trivial sealed class
+ enum — clean), `utility/DiceRoller.kt`, `Main.kt`. These two (`SharedTypes.kt`, `Accessory.kt`) fell
between the initial subagent scopes and were read directly to close the manifest.

**Reconciliation: 85/85 main Kotlin files** = server 9 + decker 11 + combat 18 + game 6 + ic 1 +
network/operations/programs/config 35 + common 2 + accessories 1 + utility 1 + Main 1.

### Frontend (9 files) ✅ re-run complete
`hooks/useWebSocket.ts`, `types/messages.ts`, `main.tsx`, `App.tsx`, `components/DeckerPanel.tsx`,
`components/LocationPanel.tsx`, `components/ActionsPanel.tsx`, `components/EntitiesPanel.tsx`,
`components/NarrativePanel.tsx`. `tsc --noEmit` clean; no `dangerouslySetInnerHTML` anywhere.

### Test suite (45 files) ✅ re-run complete
All unit tests (`common`, `programs`, `combat`, `utility`, `network`, `server`, `server/dto`, `game`,
`operations`, `decker`, `ic`, `config`) + all `integration/*` scenarios + `integration/utility` mocks,
base, and builders. §12 hazard sweep: no face=6 dice stubs, no `Thread.sleep` in coroutine tests, no
trivial assertions.

---

## Test suite — verification run

`./gradlew.bat test integrationTest` → `BUILD SUCCESSFUL` (exit 0). Note: `:test` / `:integrationTest`
reported `UP-TO-DATE` (Gradle cache — green at last execution, no source changes since). The frontend
`tsc && vite build` ran and succeeded (37 modules). Green ≠ reviewed — the tests were also read (above).

---

## Completion-gate status (§11)

| # | Condition | Status |
|---|-----------|--------|
| 1 | File count read == file count in scope | ✅ **PASS** — engine + server + frontend + tests all read in full |
| 2 | Toolchain clean / analysis run | ✅ **PASS** — `tsc` clean, build green; detekt (Kotlin, baselined) + eslint (TS flat config) configured and wired into GitHub Actions CI (E-6 resolved) |
| 3 | Cross-layer checks complete (5 paths) | ✅ message-contract parity, disconnect/reconnect, jack-out sentinel, error propagation (S-4→F-6), stale-state (F-1/F-2) all traced |
| 4 | Adversarial re-check of findings | ✅ S-1 & E-1 traced end-to-end; AttackResult.Hit sentinel (E-7) traced to no live caller; T-1 confirmed to defend S-1 |
| 5 | Deferred currency verified | ✅ D4G-3/4, locationIndex #4, DownloadDestination #6, ICC-10 #8 — all match current code |
| 6 | Root-cause consolidation | ✅ two themes: client-trust boundary (S-1/S-2/S-5/T-1/UI toggle); positional-vs-identity UI keying (F-1/F-2/F-3/X-2) |

**Verdict: the review is complete and the gate passes** on all six conditions. Condition 2 is now
fully met — the code compiles, `tsc`/build are green, and static analysis is configured and enforced:
detekt for Kotlin (grandfathered baseline so only new findings fail) and eslint for the TS frontend,
both run in GitHub Actions CI alongside the test suites (see E-6).

## Recommended remediation order

1. ✅ **DONE (2026-09-04) — Close the client-trust boundary (one coordinated change):** passcode
   possession now derives from `Decker.knownPasscodes` (`hasValidPasscode(host)`) and scanner rating
   from `Host.datalineScannerRatings` server-side; `hasValidPasscode` and `scannerDeviceRating` were
   removed from `ActionParams` (Messages.kt / messages.ts), the DTO `paramKind`, and the UI toggle;
   Origin validation added (S-1, S-2, S-5-Origin). T-1/T-3 updated to supply/derive verified state.
   `test integrationTest` + `tsc`/`vite build` green; design/PRD docs reconciled; deferred items
   (auth token, RTG-vs-host passcode key) recorded in [things_to_note.md](../things_to_note.md).
   **Superseded (2026-09-04):** the Make Comcall licensed-decker passcode exception was later
   **descoped** — the passcode-skip path and the `Decker.hasValidPasscode` helper were deleted, so
   `makeComcall` always runs the System Test. `Decker.knownPasscodes` now serves only host-level logon
   legitimacy, and the RTG-vs-host passcode-key divergence is resolved rather than deferred.
2. ✅ **DONE (2026-09-04) — Harden transport:** `install(WebSockets)` now sets `pingPeriod = 15s` /
   `timeout = 30s` so Ktor closes dead sessions (the close drives the existing `deregister` →
   `cancelIfActive`, unblocking a stalled turn) (S-3); the frame-dispatch catch now sends a generic
   `details = "malformed request"` instead of `e.message`, with the real exception still logged
   server-side (S-4). New integration test pins the S-4 non-leak contract; `test integrationTest` green.
3. ✅ **DONE (2026-09-04) — Fix `resolvePointerChain`:** added non-exploding `DiceRoller.flat(min,max)`
   and switched `resolvePointerChain` to `flat(1, 6)` (E-1); added pinned-length + non-exploding-cap
   tests and `DiceRollerTest.flat` coverage (T-2). `test` green.
4. ✅ **DONE (2026-09-04) — UI identity model:** card/focus state now resets on a semantic action
   signature not the array ref (F-1); EntitiesPanel tracks focus by DTO `index` (F-2); DeckerPanel
   keys programs by `type` and the event log keys by a monotonic reducer `id` (F-3); the
   `"not jacked in"` string sentinel is replaced by a typed `jackedIn` field on `DeckerStateDto`
   (X-1). **X-2 remains deferred** — driving LocationPanel name+fields from a single `locationObj`
   needs a real `locationIndex` (deferred.md #4), still the `0` stub. `test integrationTest` +
   `tsc`/`vite build` green.
5. ✅ **DONE (2026-09-04) — Tooling in CI (E-6):** detekt configured for Kotlin
   (`io.gitlab.arturbosch.detekt` 1.23.8; `config/detekt/detekt.yml` extends the default rule set,
   `config/detekt/baseline.xml` grandfathers 142 pre-existing findings so only NEW findings fail the
   build); eslint configured for the frontend (flat `frontend/eslint.config.js` with the recommended
   TypeScript + react-hooks rules, `npm run lint` script). Both are wired into a GitHub Actions
   workflow (`.github/workflows/ci.yml`): a Windows `backend` job runs
   `gradlew.bat test integrationTest detekt`, a `frontend` job runs `npm ci` → `lint` → `build`.
   `detekt` green against baseline; `npm run lint` clean; `test integrationTest` + `tsc`/`vite build`
   green. Gate condition 2 now fully satisfied.

## Open issues

The five-step remediation order above closed every HIGH and MEDIUM finding, and a follow-up pass
(2026-09-04) has since **resolved every remaining LOW/INFO finding** — leaving only two items that are
**deliberately deferred** (both gated on future design work, both confirmed **not** to violate the PRD).
Full detail lives in the per-layer files ([01](01_findings_server.md), [02](02_findings_engine.md),
[03](03_findings_frontend_crosslayer.md), [04](04_findings_tests.md)), each carrying a per-finding ✅ banner.

### Still deferred 🟡 (MED — the only open items)
- **S-5** — Origin check is in; a real authentication/handshake **token on join is still open**,
  deferred (recorded in [things_to_note.md](../things_to_note.md) and [deferred.md](../../design/deferred.md) #15;
  confirmed not a PRD requirement — the PRD's only "authentication" is the in-game Edit-File header
  mechanic + passcode ledger).
- **X-2** — LocationPanel renders name (string-parsed) and stat-fields from two sources; the fix is
  gated on the backend supplying a real `locationIndex` (deferred.md #4, still the `0` stub).

### Resolved — server (LOW / INFO)
- **S-6 (LOW)** ✅ — `promoteForTurn` now calls `turns.setActive(session)` **before** sending
  ACTIVE_CONTROLLER (SessionRegistry.kt:117-127), closing the NOT_YOUR_TURN race.
- **S-7 (LOW)** ✅ — demotion is now idempotent (`demoteOnce()`) inside a `finally` wrapped in
  `withContext(NonCancellable)`, so every exit path (incl. cancellation) demotes exactly once.
- **S-8 (INFO)** ✅ — `Main.kt` runs a single `runBlocking` loop with `MAX_CONSECUTIVE_ERRORS` (10) +
  `ERROR_BACKOFF_MS` (500 ms) `delay` backoff, breaking instead of busy-looping.
- **S-9 (INFO)** ✅ — the decode/encode `Json` asymmetry is now intentional and documented
  (`MatrixJson` encodes defaults; `MatrixJsonIn` uses `ignoreUnknownKeys = true` for forward compat).

### Resolved — engine (LOW / INFO)
- **E-2 (LOW)** ✅ — the bare `persona!!` (DeckerNavigationExtensions.kt:95) is now
  `requireNotNull(...) { "..." }`; no bare `!!` on `persona` remains.
- **E-3 (LOW)** ✅ — `applyDeckerOperationResult` propagates host state on `newTally != oldTally`;
  `checkTriggers` remains gated on `newTally > oldTally` per the ruleset.
- **E-7 (LOW)** ✅ — `AttackResult.Hit` carries an `attackerSuccessesMeaningful` flag; `resolveTrackLock`
  hard-`require`s it, so a Black-IC sentinel hit throws instead of cycling. New unit test pins it.
- **E-8 (LOW)** ✅ — `unsuppressIc` matches with `IC.matchesIdentity(...)` (type+name+rating+node,
  ignoring the mutable monitor), so a re-created instance still releases suppression. New unit test pins it.
- **E-9 (INFO)** ✅ — `require(>=0)` added to the rating/pool holders and `AttackParticipant.weaponPower`
  is now required; the two cosmetic items left as-is (no behavioral impact).
- **network / operations / programs / config packages** ✅ — the 1 LOW + 4 INFO were re-verified and
  all assessed **by-design / acceptable, no change** (interrogation TN double-floor per operations.md:273;
  AlertTransitions.kt:84 guard latent-consistent; INFO-3/4/5 no impact).

### Resolved — frontend (LOW / INFO)
- **F-4 (LOW)** ✅ — clickable action/entity `<div>`s now carry `role="button"`/`tabIndex`/`aria-disabled`
  + an Enter/Space `onKeyDown` handler (EntityCard applies them only when interactive). Lint/`tsc` clean.
- **F-5 (INFO)** ✅ — JoinScreen derives its error label inline during render via an `ackedEventCount`
  marker (replacing `useEffect`+`setError`), so a stale error clears on the next join attempt.

### Resolved — tests (LOW / INFO)
- **T-4 (LOW)** ✅ — the SUT-reimplementing `runCombatTurn` test was deleted (driving it through the real
  sealed-`IC` machinery is impossible; the loop is itself deferred-unreachable per deferred.md #1).
- **T-5 (LOW)** ✅ — CD-14 now asserts the TN reduction through the outcome (`[6,1]` roller ⇒
  `deckerSuccesses == 6`, only reachable if Deception lowered the effective TN).
- **T-6 (LOW)** ✅ — dead `alwaysWinRoller()` removed and replaced with a real `deckerWinsRoller()`;
  six success-path tests use unconditional `assertIs<LogonResult.Success>(result)`.
- **T-7 (INFO)** ✅ — SystemOperationsTest rollers pinned (`fixedRoller(5)`/`fixedRoller(3)`); the
  `if (net > 0)` / auth guards removed so the meaningful branch is always taken.
- **T-8 (INFO)** ✅ — the ICActivation `analyzeHost` test was renamed + given an honest comment (a
  reveal is structurally impossible at scenario level: winRoller 0-0 tie ⇒ 0 net successes, Quicksilver
  has no ANALYZE utility ⇒ TN 8); net-successes → reveal is covered deterministically in SystemOperationsTest.

### Informational — no defect (no action)
- **E-4** (deferred game-loop D4G-3/4, documented — not a bug), **E-5** (CombatResolver clean; caller
  obligation closed by E-7), **X-3** (enum parity in sync), **X-4** (`paramKind`→`ActionParams` mapping
  correct), **F-6** (resolved via the S-4 fix).
