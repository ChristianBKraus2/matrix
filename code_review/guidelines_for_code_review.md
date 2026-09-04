# Code Review Guidelines — Matrix of Shadowrun

A repeatable process for thorough, complete code review of the three-layer architecture:
**Kotlin game engine** → **Ktor WebSocket server** → **React UI**.
Completeness must be **provable by artifact**, not by assertion.

---

## 1. Philosophy — Completeness Over Sampling

This is a **full-codebase review**, not a spot check. The one file with the race condition is
invisible to any risk ranking. Two principles are non-negotiable:

- **Every file is read in full.** No file may be declared clean without applying the per-file
  checklist to every method in it.
- **No risk-based skipping.** "This file looks simple" is not a valid skip reason. Patterns
  found in similar files are a reason to be faster, never a reason to skip.

### Prohibited patterns

Never write:
- "The remaining files follow the same pattern"
- "Other files are assumed consistent"
- "I checked representative files from this layer"
- Grouping multiple files into one manifest row ("all files in combat/ — no issues")
- Declaring a file clean without having read it in full in the current session
- A manifest excerpt that is a behavioral description ("method X sets field Y") rather than
  a code token copied verbatim from the file
- A manifest excerpt reused from a prior review run without re-reading the file this session

If you find yourself writing one of these, stop and read the skipped files.

---

## 2. Project Architecture

| Layer | Source | Role |
|---|---|---|
| **game_logic** | `src/main/kotlin/…` except `server/` | Core game engine: domain model, resolvers, IC, Decker |
| **server** | `src/main/kotlin/…/server/` | Ktor WebSocket server, session management, DTOs |
| **ui** | `frontend/src/` | React UI: components, hooks, WebSocket client |

Intended dependency direction:

```
React UI (frontend/)
    ↓ WebSocket / JSON
Ktor Server (server/)
    ↓ function calls
Game Engine (game_logic/) — domain model, no framework deps
```

A finding that reverses this direction (server importing UI concepts, domain model importing
Ktor types, etc.) is an architecture violation.

---

## 3. Review Process

### Phase 0 — Setup (before reading any source file)

1. **Run the toolchain.** Compile errors, type mismatches, and failing tests are free findings.
   ```
   powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test integrationTest"
   cd frontend && npx tsc --noEmit
   ```
   Log every failure as a finding before proceeding.

2. **Build the Coverage Manifest.** Run the following and record every result:
   ```
   find src -name "*.kt"
   find frontend/src \( -name "*.ts" -o -name "*.tsx" \)
   ```
   The manifest is a living artifact updated throughout the review. An audit is not complete
   until every row is ✓ or has a justified Skip.

3. **Identify deferred items.** Read `design/deferred.md` in full. Any file whose feature is
   explicitly deferred may be marked `Skip:deferred` — cite the entry.

### Phase 1 — Architecture Pass (all files, broad view)

Read every file once to map dependencies and responsibilities. Do not write findings yet.
Answer:
- Does the dependency direction match the diagram above?
- Are there classes doing more than one job?
- Does the server layer own game state that belongs in the engine?
- Does the game engine depend on transport types?

### Phase 2 — Category Deep Dives

Run each category below across all three layers. Recommended order matches severity impact:

| # | Category | Why first |
|---|---|---|
| 1 | Correctness | Silent logic bugs are the hardest to detect |
| 2 | Security | Input validation and auth must be checked before concurrency |
| 3 | Concurrency | Race conditions are invisible without deliberate search |
| 4 | Error handling | Swallowed exceptions mask all other bugs |
| 5 | Architecture | Layer violations enable future bugs |
| 6 | API quality | Public surface correctness |
| 7 | Performance | Only after correctness is confirmed |
| 8 | Testing | Verify behavioral coverage, not just line coverage |
| 9 | Maintainability | Naming, complexity, dead code |

### Phase 3 — Cross-Layer Checks

After per-layer passes, trace each interaction path end-to-end (see section 8).

### Phase 4 — Completion Gate

Verify all six conditions in section 11 before declaring the review done.

---

## 4. Coverage Manifest

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| _(populate from find output before starting)_ | | | |

**Status values:**
- `✓ Read — N lines` — file was read in full; line count proves it was opened to the end
- `Skip:deferred` — feature is in `deferred.md` (cite the entry)
- `Skip:infra` — build/tooling file with no reviewable logic (state why)

No other skip reason is valid.

**Verbatim excerpt requirements** (minimum; more is always acceptable):
- Files ≤ 100 lines: one excerpt from anywhere in the file
- Files 101–300 lines: two excerpts — one from the opening third, one from the closing third
- Files > 300 lines: three excerpts — one from each third, each ≥ 50 source lines apart

For any file with findings, at least one excerpt must come from the specific finding location,
not from a passing line.

For `Resolver`, `Extensions`, or `Controller` files, list every method/function checked:
`methods: resolveBlackHammer, resolveCrippler, resolveRipper`.

For frontend hook files and `.tsx` files with multiple exports, list every export checked:
`exports: useWebSocket, join, connect, reset`.

---

## 5. Per-File Checklist

Apply to **every method** in every file, not to the file as a whole.

### Correctness
- [ ] Implementation matches its design-doc contract (if one exists)
- [ ] All edge cases handled: empty collections, null, zero, negative numbers, duplicate elements
- [ ] State transitions are correct and complete
- [ ] No off-by-one errors in loops or ranges
- [ ] No lost updates (two reads, one write, second overwrites the first)
- [ ] No duplicate processing paths
- [ ] Return values from resolvers and compound operations are fully consumed — `.first`-only
      calls on a `Pair<Result, Handle>` are a finding unless the second element is documented as discardable
- [ ] No hardcoded success flags (`success = true`) that ignore actual computation results

### Kotlin-specific
- [ ] `!!` usage justified by a provable invariant; otherwise replaced by safe call or early return
- [ ] `lateinit` property is always initialized before first read
- [ ] `val` used instead of `var` where reassignment is not required
- [ ] Mutable collections not exposed through public APIs
- [ ] `when` on a sealed type is exhaustive (no `else -> Unit` swallowing new variants)
- [ ] `data class` fields are `val` unless mutation is genuinely needed
- [ ] No sensitive data in a `data class` that generates `toString()` (e.g. tokens, secrets)
- [ ] Scope functions (`let`, `apply`, `also`, `run`, `with`) improve readability, not obscure it
- [ ] No deeply nested scope functions where explicit control flow is clearer

### Coroutines
- [ ] No `GlobalScope.launch` — coroutines tied to a lifecycle scope
- [ ] No `runBlocking` in production code (acceptable in tests and `main()`)
- [ ] `CancellationException` is never swallowed in a `catch (e: Exception)` block
- [ ] Dispatcher is appropriate: `Dispatchers.IO` for blocking I/O, `Dispatchers.Default` for CPU
- [ ] Blocking calls (`Thread.sleep`, raw JDBC) never run on the wrong dispatcher
- [ ] Long-running loops inside coroutines check for cancellation (`isActive`)
- [ ] `async { }` has a corresponding `await` or error handler — no silent fire-and-forget
- [ ] `Flow` collection is lifecycle-safe; collector disappears cleanly when the owner is gone
- [ ] Shared mutable state accessed from coroutines is protected by `Mutex` or `ConcurrentHashMap`
- [ ] `CompletableFuture.get()` inside a coroutine replaced by `CompletableDeferred.await()`

### Error Handling
- [ ] No empty `catch` blocks
- [ ] No `catch (e: Exception)` at a low level that returns `null` and loses context
- [ ] `runCatching { }` has an `.onFailure` handler that at minimum logs the exception
- [ ] Unchecked casts (`as SomeType`) replaced by safe casts (`as? SomeType`) with a fallback
- [ ] Preconditions use `require()`; state invariants use `check()`; unreachable branches use `error()`
- [ ] `finally` blocks run cleanup even on unexpected exceptions (especially: session deregistration,
      controller demotion, resource release)

### Security
- [ ] No hard-coded secrets, tokens, or passwords anywhere (source, resources, test fixtures)
- [ ] All external input validated before use (enum parsing, size limits, format checks)
- [ ] Enum parsing from client input uses a safe lookup, not `valueOf()` (which throws on invalid input)
- [ ] No client-supplied booleans that bypass server-side authorization or game-state checks
- [ ] No raw exception messages returned to clients (leaks internals)
- [ ] Sensitive data excluded from logs, `toString()`, and error messages

### Architecture
- [ ] Class has one responsibility; dependencies flow in the intended direction
- [ ] Server layer does not own game state that belongs in the engine
- [ ] Domain model does not import Ktor, Android, or other framework types
- [ ] WebSocket controller implements a thin port, not a game participant
- [ ] Per-session state (interrogation accumulators, turn state) lives in the game engine, not in
      the WebSocket session object

### API Design
- [ ] Visibility is intentional — `internal` where appropriate
- [ ] Public methods have explicit return types
- [ ] Nullable types are meaningful, not a convenience shortcut
- [ ] Parameters cannot be accidentally omitted to change behavior (no `fun foo(pin: Boolean = false)` 
      where the default bypasses a safety check)

### Performance
- [ ] No accidental O(n²) patterns (`.contains()` on a `List` inside a loop — use a `Set`)
- [ ] No unnecessary repeated collection materialization in hot paths
- [ ] No blocking I/O on the main/UI dispatcher

### Testing
- [ ] Tests assert on changed game state, not just on message type or string content
- [ ] No `Thread.sleep` in coroutine tests — use `kotlinx.coroutines.test`
- [ ] Test helpers (dice rollers) do not return values that trigger infinite dice-explosion loops
- [ ] Happy paths, failure paths, and boundary cases are covered
- [ ] Tests do not pass trivially by construction (e.g. `assertEquals(0, x.coerceAtMost(0))`)

---

## 6. Game Engine Layer — Specific Checks

In addition to the per-file checklist, apply these to `game_logic` files.

### Resolver files (`CombatResolver`, `SystemTestResolver`, …)

- Every `resolve*` function: cross-check the dice-pool formula against `design_core/` docs
- Multi-phase operations (e.g. damage + CM reduction): verify **both** phases are applied, not just the first
- Result values returned by resolvers are consumed by callers — not silently discarded with `_` or `.first`

### IC action files (`IC.kt`, individual IC classes)

- Each `action()` method: verify all side effects in the design doc are applied  
  (security-tally update, persona CM damage, physical damage, MPCP reduction — as applicable)
- Alert triggers: `checkTriggers(oldTally, newTally)` called after every tally modification

### Decker operations (`Decker.kt`, `DeckerExtensions.kt`)

- Safe casts when reading `currentLocation` — `ClassCastException` on `as MatrixLocation.OnHost`
  propagates through all IC combat and crashes the turn for every participant
- `persona!!` — replace with `requireNotNull(persona) { "..." }` wherever used
- TN formula matches design doc exactly — double-applying a utility rating is a common mistake
- Each `resolve*` call passes fresh values from the live context, not from a stale embedded snapshot

### GameContext (`GameContext.kt`)

- Mutable collections (`deckers`, `activeIc`) are not exposed directly as public `MutableList`
- Tally deltas applied to `context.host.securityTally` (live), not to a decker's embedded host snapshot
- After any operation that modifies context, callers re-read their authoritative copy from the context

### Config loaders

- Field names in loaded config match domain model field names exactly
- Missing optional fields fall back to the design-specified defaults, not to `null` or `0`

---

## 7. Ktor Server Layer — Specific Checks

### Session lifecycle (`MatrixServer.kt`, `SessionRegistry.kt`)

- Session registered **before** entering the message loop
- Session deregistered in a `finally` block — not only in the happy path
- `ClosedReceiveChannelException` treated as a normal close, not logged as an error
- `activeController` and `pendingAction` transitions are atomic (no TOCTOU window between
  authorization check and state mutation)
- Controller demotion (`demoteAfterTurn`) called unconditionally in `finally`, not only on success

### WebSocket controller (`WebSocketDeckerController.kt`)

- `runBlocking` absent — `action()` should be `suspend`; `CompletableFuture` replaced by `CompletableDeferred`
- No hard casts (`as MatrixObject.IcProgram`) — every cast is `as?` with a graceful fallback
- `runCatching { }` has `.onFailure { e -> logger.error(...); session.send(ErrorMessage(...)) }`
- Enum values parsed from client input use safe lookup (`entries.firstOrNull { it.name == x }`)
- Client-supplied fields that gate authorization (e.g. `hasValidPasscode`) are removed; authorization
  is derived server-side from verified game state
- Stub operations (`SWAP_MEMORY`, `LOCATE_DECKER`) either have a real implementation or are filtered
  from `availableActions` before broadcast so clients cannot waste turns on them
- `AnalyzeSecurityResult.toDispatch()` and similar converters use actual outcome fields, not hardcoded literals

### DTOs (`dto/`)

- Every `@SerialName` matches the protocol doc field name exactly
- All DTO fields are present and non-null where the protocol specifies a required field
- No raw exception messages or internal stack traces in `ErrorMessage` content
- `maxFrameSize` configured on the Ktor `WebSockets` plugin to prevent memory exhaustion

### Concurrency (`SessionRegistry.kt`)

- Session registry backed by `ConcurrentHashMap` or `Mutex`-guarded `Map`
- `send()` not called while holding a lock (can deadlock if send suspends)
- Full read-authorize-act sequence under a single lock (not split across volatile reads + synchronized blocks)

---

## 8. React UI Layer — Specific Checks

### `useWebSocket.ts`

- `useEffect` cleanup closes the WebSocket and nulls `onclose`/`onerror` before closing
  (prevents ghost reconnect loop on unmount)
- Reconnect guard includes `WebSocket.CONNECTING` state (prevents duplicate sockets during
  React StrictMode double-mount)
- `gameState` reset to `null` in the `DISCONNECTED` reducer case (prevents stale data from a
  prior session rendering as current after reconnect)
- Reducer handles all message types exhaustively — no implicit `else` that silently drops new messages

### Components

- Each component has single responsibility; data fetching and presentation are separated
- Props interfaces use explicit TypeScript types — no `any` or `object`
- Lists use stable, unique keys (entity ID, not array index)
- Semantic HTML: `<button>` for actions, `<a>` for navigation; `<div onClick>` has `role="button"`,
  `tabIndex={0}`, and keyboard handler

### TypeScript types (`types/messages.ts`)

- All enum-like string unions exactly match the server's sealed class variants
  (e.g. `QueryPrecision`: `'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'`,
  not ad-hoc approximations like `'NORMAL' | 'HIGH'`)
- Required fields are non-optional — remove `?` where the server always provides the field
- No fields that the server derives internally (authorization flags, possession booleans) in
  the `ActionParams` type — the server ignores them at best, is bypassed by them at worst

### `useEffect` discipline

- Every `useEffect` that opens a connection, timer, or async operation returns a cleanup function
- Dependency arrays are exhaustive (enforced by `eslint-plugin-react-hooks`)
- No `useEffect` with missing dependencies that creates a stale closure

---

## 9. Cross-Layer Checks

These are the most dangerous failures because each layer passes its own review while the
interaction is broken. Run these after the per-layer passes.

### Message contract parity

For every message type: the sealed class on the Kotlin side must exactly match the TypeScript
union type on the React side. A single mismatched `@SerialName` causes silent parse failure.
Trace bidirectionally: each incoming client message and each outgoing server message.

### Disconnection end-to-end

Trace all three disconnection scenarios (normal close, network error, server restart):
1. React `useEffect` cleanup closes the WebSocket
2. Ktor `ClosedReceiveChannelException` caught and treated as normal close
3. Session `finally` block fires and removes the entry from the registry
4. `activeController` is demoted if the disconnecting session held the turn
5. Any per-session game state (interrogation state, pending futures) is cleaned up

### Error propagation path

An exception thrown in game logic must reach the React UI as a typed `ErrorMessage` frame,
not as a dropped connection with no feedback. Verify the full path:
`throw` → `catch` in controller → `ErrorMessage` serialized → client reducer handles `ERROR`
type → UI shows feedback.

### Stale-state drift

After any operation that modifies `context.host` or `context.deckers`:
1. The WebSocket controller re-reads its `decker` reference from the context
2. All clients receive a fresh `StateMessage` broadcast
3. The IC and decker `visibleObjects()` / `availableActions()` calls use the updated snapshot

### Turn-promotion atomicity

The sequence `pendingAction = future` → `promoteForTurn(decker)` must be atomic:
the future must be set **before** the client is told it is their turn, or a fast client
can send an action and find `pendingAction == null`.

### Coroutine scope vs. application scope

Per-session coroutines (anything touching one decker's state) must live in the session scope.
Application-level coroutines (broadcast loops) must not hold direct references to per-session
mutable state that can outlive the session.

---

## 10. Finding Format

```markdown
### [SEVERITY] Short title
**File:** relative/path/to/File.kt:line
**Layer:** game_logic | server | ui | cross-layer
**Issue:** What is wrong and why it matters at runtime.
**Recommendation:** Specific fix with code sketch where helpful.
```

**Severity levels:**

| Level | Meaning | Example |
|---|---|---|
| 🔴 CRITICAL | Data loss, crash, security bypass, broken core mechanic | Race condition permanently hangs game loop |
| 🔴 HIGH | Significant correctness or security problem | Resolver result silently discarded; auth bypassed |
| 🟠 MEDIUM | Degraded behavior or latent bug | Stale snapshot causes wrong alert status |
| 🟡 LOW | Should improve but doesn't break functionality | Confusing API, unnecessary complexity |
| 🔵 INFO | Optional improvement | Naming preference, minor duplication |

Avoid turning style preferences into HIGH or CRITICAL. A good finding explains *why* it is the
stated severity.

---

## 11. Completion Gate

Before declaring the review complete, verify all six conditions:

1. **Count match** — count of files in the manifest equals count of ✓ + justified Skip rows.
   State both counts explicitly.
2. **Toolchain clean** — the build, tests, and TypeScript type-check all pass (or every failure
   is logged as a finding).
3. **Cross-layer checks complete** — all five paths in section 9 have been traced.
4. **Adversarial check** — answer explicitly: "If I had stopped after the first N interesting
   findings, what would I have missed?" If anything is revealed, go examine it.
5. **Deferred currency** — for every `Skip:deferred` row, the `deferred.md` entry still matches
   the current code state. An entry describing absent functionality that now exists is a finding,
   not a valid skip.
6. **Root-cause consolidation** — group confirmed findings into their underlying causes
   (e.g. "server layer owns game state; four findings trace to this"). This does not drop
   any finding but turns a flat list into actionable causes and often reveals sibling issues.

---

## 12. Lessons from Prior Reviews — Anti-Patterns Specific to This Codebase

The following failure modes were found in previous reviews of this project.
They require explicit attention in every future review.

### Game engine

| Anti-pattern | Where to look |
|---|---|
| Multi-phase resolver result: only phase 1 applied, phase 2 silently discarded (`_`) | `IC.kt` action methods, `Decker.kt` compound operations |
| `as MatrixLocation.OnHost` hard cast — crashes all IC combat if decker is not on host | `DeckerExtensions.kt`, any `asDefenderParticipant` call |
| `persona!!` — crashes if persona not initialized | `DeckerExtensions.kt`, `Decker.kt` |
| Tally delta applied to stale embedded host snapshot, not to `context.host` | `GameContext.kt`, `applyDeckerOperationResult` |
| TN formula double-applies a utility rating (correct: pass raw subsystem rating) | `Decker.kt` analyze/system-test functions |
| `diceRoller.roll(1, 2).first() % 6 + 1` — wrong flat-d6 distribution | Any flat die roll that should not explode |
| Controller's `decker` field goes stale after alert transitions update context | `WebSocketDeckerController.kt`, post-`applyDeckerOperationResult` |

### Server

| Anti-pattern | Where to look |
|---|---|
| `runCatching { }` without `.onFailure` — all parse/dispatch errors invisible | `MatrixServer.kt` frame handler |
| `QueryPrecision.valueOf(string)` — crashes game-loop thread on invalid client input | `WebSocketDeckerController.kt` param extraction |
| `hasValidPasscode: true` from client skips system test — auth bypass | `ActionParams`, `ActionCommand` |
| `runBlocking` + `CompletableFuture.get()` blocks Ktor thread for full turn timeout | `WebSocketDeckerController.kt action()` |
| `promoteForTurn` sent before `pendingAction` is set — TOCTOU | `WebSocketDeckerController.kt` / `SessionRegistry.kt` |
| `demoteAfterTurn` not in `finally` — hung game after any unhandled exception | `WebSocketDeckerController.kt` |
| `AnalyzeSecurityResult.toDispatch()` hardcodes `success = true` | `WebSocketDeckerController.kt` DTO converters |
| Stub operations (`SWAP_MEMORY`, `LOCATE_DECKER`) offered in `availableActions` | `WebSocketDeckerController.kt` |

### React UI

| Anti-pattern | Where to look |
|---|---|
| `ws.onclose`/`ws.onerror` not nulled before `ws.close()` — ghost reconnect loop | `useWebSocket.ts` cleanup |
| `|| state === WebSocket.CONNECTING` missing in reconnect guard — duplicate sockets | `useWebSocket.ts` connect guard |
| `gameState` not cleared on `DISCONNECTED` — stale data after reconnect | `useWebSocket.ts` reducer |
| TypeScript string unions that diverge from Kotlin enum names | `types/messages.ts` — check every enum-like union |
| Optional `?` on fields the server always sends — masks contract violations | `types/messages.ts` required fields |

### Testing

| Anti-pattern | Where to look |
|---|---|
| IC action test asserts only message type, not actual state change | `GameTest.kt` IC action assertions |
| Assertion trivially true by construction (`assertEquals(0, x.coerceAtMost(0))`) | All test assertions on bounded values |
| Test dice roller returns face=6 for all rolls — exploding dice → infinite loop | Every test that uses a stub DiceRoller |
| `Thread.sleep` in coroutine test instead of `runTest` / virtual time | Any async test |

---

## 13. Execution Checklist

```
[ ] Phase 0: Run toolchain — log all failures
[ ] Phase 0: Build coverage manifest from find output
[ ] Phase 0: Read deferred.md in full

[ ] Phase 1: Architecture pass — dependency directions, layer violations

[ ] Phase 2a: Correctness — game_logic, server, ui
[ ] Phase 2b: Security — game_logic, server, ui
[ ] Phase 2c: Concurrency — game_logic, server, ui
[ ] Phase 2d: Error handling — game_logic, server, ui
[ ] Phase 2e: Architecture details — game_logic, server, ui
[ ] Phase 2f: API quality — game_logic, server, ui
[ ] Phase 2g: Performance — game_logic, server, ui
[ ] Phase 2h: Testing — game_logic, server, ui
[ ] Phase 2i: Maintainability — game_logic, server, ui

[ ] Phase 3: Cross-layer checks (all 5 paths in section 9)

[ ] Phase 4: Completion gate — all 6 conditions verified
[ ] Phase 4: Root-cause consolidation
[ ] Phase 4: Finding list published in code_review/ folder
```

Each category review produces one file per layer plus one cross-cutting summary, e.g.:
```
code_review/correctness_game_logic.md
code_review/correctness_server.md
code_review/correctness_ui.md
code_review/correctness_complete.md
```

---

## 14. Primary References

- `code_review/code_review_guidelines.md` — comprehensive Kotlin review guide
- `code_review/code_review_guide.md` — project-specific enforce/flag patterns
- `design/align.md` — design-vs-code alignment process (completeness methodology)
- `design/design_core/`, `design/design_game/`, `design/design_ui/` — spec against which correctness is judged
- `design/prd_core.md`, `design/prd_game.md`, `design/prd_ui.md` — authoritative rule source
- `design/protocol.md` — wire format spec for cross-layer checks
