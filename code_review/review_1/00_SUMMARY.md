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

**Remediation status:** all five steps of the recommended order are **done** — Step 1 (client-trust
boundary: S-1, S-2, T-1, T-3; S-5 partial — Origin in, auth token deferred), Step 2 (transport
hardening: S-3, S-4), Step 3 (`resolvePointerChain` exploding-die fix: E-1, T-2), Step 4 (UI identity
model: F-1, F-2, F-3, X-1; X-2 deferred), and Step 5 (detekt + eslint in CI: E-6). **All remaining
LOW/INFO findings have since been resolved** (S-6, S-7, S-8, S-9, E-2, E-3, E-7, E-8, E-9, F-4, F-5,
T-4–T-8) or assessed as by-design/no-change (the 5 subagent network/operations findings). See the
per-finding ✅ banners and [../things_to_note.md](../things_to_note.md). **Only two findings remain
open, both deliberately deferred: S-5's auth/handshake token and X-2's split-source display** — each
gated on future design work (transport-auth model; a real `locationIndex`) and confirmed **not** to
violate the PRD (see [deferred.md](../../design/deferred.md) #15 and #4).

| # | Sev | Layer | Finding | Status |
|---|-----|-------|---------|--------|
| S-1 | 🔴 HIGH | server↔engine | Client-supplied `hasValidPasscode` bypasses the passcode System Test entirely | ✅ resolved → later **descoped** (Make Comcall passcode option removed entirely) |
| T-1 | 🔴 HIGH | tests | A test *enshrines* the S-1 bypass as correct — will break (correctly) when S-1 is fixed | ✅ resolved → test **removed** with the descope |
| S-2 | 🟠 MED | server | Client-supplied `scannerDeviceRating` lets the client set a game-mechanic input | ✅ resolved |
| S-3 | 🟠 MED | server | WebSocket has no `pingPeriod`/`timeout` — half-open connections leak and can stall turns | ✅ resolved |
| S-4 | 🟠 MED | server | Raw exception text leaked to clients (`details = e.message?.take(256)`) | ✅ resolved |
| S-5 | 🟠 MED | server | No Origin / authentication check on the WS endpoint | 🟡 partial (Origin done; auth token deferred) |
| E-1 | 🟠 MED | engine | `resolvePointerChain` uses an **exploding** die as a flat 1D6 (wrong, unbounded distribution) | ✅ resolved |
| X-1 | 🟠 MED | cross-layer | Stringly-typed `"not jacked in"` sentinel duplicated across Kotlin & TS (3 call sites) | ✅ resolved |
| X-2 | 🟠 MED | cross-layer | LocationPanel renders name (string-parsed) and stat-fields (index-0 stub) from two sources | 🟡 deferred (locationIndex) |
| F-1 | 🟠 MED | frontend | In-progress turn input silently wiped on every state broadcast | ✅ resolved |
| T-2 | 🟠 MED | tests | No test covers the E-1 exploding-die bug (coverage gap) | ✅ resolved |
| T-3 | 🟠 MED | tests | `tapComcall` tests leave the S-2 trust boundary untested | ✅ resolved |
| S-6, S-7, E-2, E-3, E-7, E-8, F-4, T-4, T-5, T-6 | 🟡 LOW | all | See per-layer files | ✅ resolved |
| S-8, S-9, E-9, F-5, T-7, T-8 | 🔵 INFO | all | Holder validation, transport hygiene, test-craft | ✅ resolved |
| network/operations subagent pass (1 LOW + 4 INFO) | 🟡🔵 | engine | Interrogation TN floor, grid-trigger guard, minor notes | ✅ by-design / no-change |
| X-3, X-4, E-4, E-5, F-6 | 🔵 INFO | all | Enum parity (in sync), correct mapping, deferred-loop currency, no defect | ✅ no action |

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
T-1 (a test defending the bypass) are all facets of it. This was the priority to fix, as one coordinated
change: derive passcode possession and scanner rating server-side, remove those client fields from
`ActionParams`/DTO/`messages.ts`/UI, and update the T-1 test.

> **Update (2026-09-04):** this coordinated change **has been implemented** (Step 1). Passcode
> possession now derives from `Decker.knownPasscodes` and scanner rating from
> `Host.datalineScannerRatings`; both client fields are gone from the wire contract and UI; the Origin
> guard is in; T-1/T-3 now defend the fix. `test integrationTest` + `tsc`/`vite build` are green. The
> design/PRD docs were reconciled to the secure model and the deferred RTG-vs-host passcode-key
> divergence recorded. The remaining open facet of S-5 (a real auth/handshake token) is deferred.
>
> **Follow-up (2026-09-04): the Make Comcall licensed-decker passcode exception was descoped** by
> product decision. The passcode-skip path in `makeComcall`, the `Decker.hasValidPasscode` helper,
> and the T-1 test were all removed; Make Comcall now always runs a plain System Test. This
> eliminates the S-1 attack surface entirely (there is no passcode input to trust). `Decker.knownPasscodes`
> remains, now serving only host-level logon legitimacy. The RTG-vs-host passcode-key divergence is
> thereby resolved (see [deferred.md](../../design/deferred.md) and
> [../things_to_note.md](../things_to_note.md)).

The second theme is smaller: **UI state keyed by array position/reference rather than stable DTO
identity** (F-1, F-2, F-3, X-2) — one root-cause fix addresses the input-wipe, focus jump, list-key
churn, and part of the LocationPanel inconsistency.

> **Update (2026-09-04):** this theme was addressed in Step 4. F-1 (signature-gated reset), F-2
> (focus by DTO `index`), and F-3 (program key by `type`, event log key by a monotonic reducer `id`)
> are resolved, and X-1's `"not jacked in"` string sentinel is replaced by a typed `jackedIn` DTO
> field. Only X-2's split-source *display* remains, correctly deferred until the backend supplies a
> real `locationIndex` (deferred.md #4). `test integrationTest` + `tsc`/`vite build` green.

## Per-layer files
- [01_findings_server.md](01_findings_server.md) — S-1..S-9
- [02_findings_engine.md](02_findings_engine.md) — E-1..E-9 (+ subagent packages)
- [03_findings_frontend_crosslayer.md](03_findings_frontend_crosslayer.md) — X-1..X-4, F-1..F-6
- [04_findings_tests.md](04_findings_tests.md) — T-1..T-8
- [99_coverage_and_gate.md](99_coverage_and_gate.md) — coverage manifest + §11 gate
- [../things_to_note.md](../things_to_note.md) — cross-cutting notes from Step 1 remediation
