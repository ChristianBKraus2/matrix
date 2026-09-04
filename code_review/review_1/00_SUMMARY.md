# Code Review — Matrix of Shadowrun — `review_1`

**Date:** 2026-09-04
**Guideline:** [guidelines_for_code_review.md](../guidelines_for_code_review.md)
**Scope:** Full three-layer review — Kotlin game engine → Ktor WebSocket server → React UI, plus tests.

---

## ✅ Coverage — COMPLETE

Every source, server, frontend, and test file in scope has been read in full (initial spend-limit
blocker was resolved by the user raising the limit; the three failed subagent scopes were re-run to
completion). Full manifest and gate status: [99_coverage_and_gate.md](99_coverage_and_gate.md).

- **Engine** (`decker`, `combat`, `game`, `ic`, `network`, `operations`, `programs`, `config`, `common`,
  `utility`) — all files read
- **Server** (`server/**`, DTOs, `Main.kt`) — all files read
- **Frontend** (`frontend/src/**`, 9 files) — all files read; `tsc --noEmit` clean; **no XSS**
- **Tests** (45 files, unit + integration + utilities) — all read; §12 hazard sweep clean

---

## Headline findings

| # | Sev | Layer | Finding |
|---|-----|-------|---------|
| S-1 | 🔴 HIGH | server↔engine | Client-supplied `hasValidPasscode` bypasses the passcode System Test entirely |
| T-1 | 🔴 HIGH | tests | A test *enshrines* the S-1 bypass as correct — will break (correctly) when S-1 is fixed |
| S-2 | 🟠 MED | server | Client-supplied `scannerDeviceRating` lets the client set a game-mechanic input |
| S-3 | 🟠 MED | server | WebSocket has no `pingPeriod`/`timeout` — half-open connections leak and can stall turns |
| S-4 | 🟠 MED | server | Raw exception text leaked to clients (`details = e.message?.take(256)`) |
| S-5 | 🟠 MED | server | No Origin / authentication check on the WS endpoint |
| E-1 | 🟠 MED | engine | `resolvePointerChain` uses an **exploding** die as a flat 1D6 (wrong, unbounded distribution) |
| X-1 | 🟠 MED | cross-layer | Stringly-typed `"not jacked in"` sentinel duplicated across Kotlin & TS (3 call sites) |
| X-2 | 🟠 MED | cross-layer | LocationPanel renders name (string-parsed) and stat-fields (index-0 stub) from two sources |
| F-1 | 🟠 MED | frontend | In-progress turn input silently wiped on every state broadcast |
| T-2 | 🟠 MED | tests | No test covers the E-1 exploding-die bug (coverage gap) |
| T-3 | 🟠 MED | tests | `tapComcall` tests leave the S-2 trust boundary untested |
| S-6..7, E-2..3, E-7..8, F-2..4, T-4..6 | 🟡 LOW | all | See per-layer files |
| various | 🔵 INFO | all | Enum-parity coupling (in sync), holder validation, test-craft, tooling gaps |

Deferred-feature defects (D4G-3/4, `locationIndex` stub, DownloadDestination routing, ICC-10, etc.) were
checked for **currency** against [deferred.md](../../design/deferred.md) and are accurately documented
there — **not** reported as bugs.

## Overall assessment

The engine, combat, and data-holder code is **high quality**: consistent immutability (`val` + `copy()`),
sealed hierarchies, `requireNotNull(...) { "message" }` over bare `!!` almost everywhere, resolver logic
that traces faithfully to the ruleset's cited pages. The frontend has no XSS and correct `paramKind`
mapping. The **test suite is unusually disciplined** about the project's exploding-dice hazard (no
constant-6 stubs, no `Thread.sleep`, no tautologies).

The weak spot is a single, consistent theme: **the server trusts the client across the security
boundary.** S-1 (auth), S-2 (mechanic input), S-5 (no auth/Origin), the `hasValidPasscode` UI toggle, and
T-1 (a test defending the bypass) are all facets of it. This is the priority to fix, as one coordinated
change: derive passcode possession and scanner rating server-side, remove those client fields from
`ActionParams`/DTO/`messages.ts`/UI, and update the T-1 test.

The second theme is smaller: **UI state keyed by array position/reference rather than stable DTO
identity** (F-1, F-2, F-3, X-2) — one root-cause fix addresses the input-wipe, focus jump, list-key
churn, and part of the LocationPanel inconsistency.

## Per-layer files
- [01_findings_server.md](01_findings_server.md) — S-1..S-9
- [02_findings_engine.md](02_findings_engine.md) — E-1..E-9 (+ subagent packages)
- [03_findings_frontend_crosslayer.md](03_findings_frontend_crosslayer.md) — X-1..X-4, F-1..F-6
- [04_findings_tests.md](04_findings_tests.md) — T-1..T-8
- [99_coverage_and_gate.md](99_coverage_and_gate.md) — coverage manifest + §11 gate
