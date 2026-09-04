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

2. **Run static analysis.**
   ```
   powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat detekt"
   cd frontend && npx eslint src --ext .ts,.tsx
   ```
   Log every complexity, naming, or rule violation above detekt's thresholds (see section 5).
   Static analysis finds mechanical issues so the human review can focus on semantic ones.

3. **Build the Coverage Manifest.** Run the following and record every result:
   ```
   find src -name "*.kt"
   find frontend/src \( -name "*.ts" -o -name "*.tsx" \)
   ```
   The manifest is a living artifact updated throughout the review. An audit is not complete
   until every row is ✓ or has a justified Skip.

4. **Identify deferred items.** Read `design/deferred.md` in full. Any file whose feature is
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

**Nullability and `!!`**
- [ ] `!!` avoided — prefer `requireNotNull(x) { "message" }` (throws with context) or `?: return`/`?: error()`
      over the generic `NullPointerException` that `!!` produces
- [ ] `requireNotNull` / `checkNotNull` used at call sites instead of silent `?.` chains where
      null is an unexpected error, not a valid absence
- [ ] Platform types from Java eliminated immediately — specify explicit Kotlin type on assignment
      (`val x: Foo = javaObj.foo`, not `val x = javaObj.foo`) so NPE occurs at assignment, not later
- [ ] Platform types never propagated through interfaces or public APIs — return type inferred from
      platform type becomes `Foo!` (unknown nullability) for all callers

**Mutability**
- [ ] `val` used instead of `var` where reassignment is not required
- [ ] No dual mutation point: `var list = mutableListOf()` avoids single-point reasoning;
      choose either `val list = mutableListOf()` (mutate the collection) or
      `var list: List<T> = listOf()` (reassign the property), never both on the same field
- [ ] Read-only collections not downcast to mutable (`list as MutableList` may throw
      `UnsupportedOperationException` — use `list.toMutableList()` instead)
- [ ] Mutable collections not exposed through public APIs — return `List<T>`, not `MutableList<T>`
- [ ] Mutable data class fields not placed in `HashSet` / `HashMap` — mutation after insertion
      makes the element unreachable (hash repositioning)
- [ ] `copy()` used to produce modified instances of data classes rather than `var` fields

**Sealed classes**
- [ ] `when` on a sealed type has no `else` branch — lets the compiler catch missing cases when
      new variants are added; `else -> Unit` silently swallows unhandled variants
- [ ] `when` used as an expression (assigned to a value) to enforce exhaustiveness at compile time
- [ ] Stateless sealed subclasses declared as `object`, not `class` — one instance for all usages
- [ ] Adding new subtypes to a **public** sealed hierarchy is a breaking change — document stability

**General Kotlin idioms**
- [ ] `data class` fields are `val` unless mutation is genuinely needed
- [ ] No sensitive data in a `data class` that generates `toString()` (e.g. tokens, secrets)
- [ ] Custom `equals()` implementations respect the five contracts: reflexive, symmetric, transitive,
      consistent, and never-equal-to-null; dynamic state (e.g. `System.currentTimeMillis()`) in
      `equals()` breaks consistency
- [ ] Scope functions (`let`, `apply`, `also`, `run`, `with`) improve readability, not obscure it
- [ ] No deeply nested scope functions (> 1 level) — replace with explicit control flow
- [ ] `lateinit` property is always initialized before first read

### Coroutines
- [ ] No `GlobalScope.launch` — coroutines tied to a lifecycle scope
- [ ] No `runBlocking` in production code (acceptable in tests and `main()`)
- [ ] `CancellationException` is never swallowed in a `catch (e: Exception)` block — it is
      transparent and must propagate; catching it breaks structured cancellation semantics
- [ ] Dispatcher is appropriate: `Dispatchers.IO` for blocking I/O, `Dispatchers.Default` for CPU
- [ ] Blocking calls (`Thread.sleep`, raw JDBC) never run on the wrong dispatcher
- [ ] Long-running loops inside coroutines call `ensureActive()` or check `isActive` each iteration
- [ ] `async { }` has a corresponding `await` — exceptions inside `async` only surface at `await()`;
      a fire-and-forget `async` that never calls `await` silently discards all failures
- [ ] `CoroutineExceptionHandler` installed on root coroutines only, not child coroutines
      (children delegate exception handling up the hierarchy; a handler on a child has no effect)
- [ ] `supervisorScope` used instead of `coroutineScope` when child failures must not cancel siblings
- [ ] `Flow` collection is lifecycle-safe; collector disappears cleanly when the owner is gone
- [ ] Shared mutable state: `@Volatile` is not a concurrency fix — it guarantees visibility only;
      compound operations (check-then-act, read-modify-write) still require `Mutex` or `AtomicXxx`
- [ ] Prefer coarse-grained confinement (`withContext(singleThreadCtx) { massiveRun {} }`) over
      per-operation `Mutex.withLock {}` for high-frequency state mutation
- [ ] `ConcurrentHashMap` for session registry; `Mutex` for domain objects requiring atomic
      multi-field updates
- [ ] `CompletableFuture.get()` inside a coroutine replaced by `CompletableDeferred.await()`

### Error Handling
- [ ] No empty `catch` blocks
- [ ] No `catch (e: Exception)` at a low level that returns `null` and loses context
- [ ] `runCatching { }` has an `.onFailure` handler that at minimum logs the exception
- [ ] Unchecked casts (`as SomeType`) replaced by safe casts (`as? SomeType`) with a fallback
- [ ] Argument preconditions use `require(condition) { "message" }` at the top of the function —
      placed first so state is not partially modified before the check fires; smart-cast to
      non-null type after `require(x != null)` without needing `!!`
- [ ] Object state invariants use `check(condition) { "message" }` — throws `IllegalStateException`
- [ ] Unreachable branches use `error("message")` — self-documenting intent
- [ ] `requireNotNull(x) { "message" }` preferred over `x!!` — throws with context, not a generic NPE
- [ ] `finally` blocks run cleanup even on unexpected exceptions (especially: session deregistration,
      controller demotion, resource release)

### Security
- [ ] No hard-coded secrets, tokens, or passwords anywhere (source, resources, test fixtures)
- [ ] All external input validated before use (enum parsing, size limits, format checks)
- [ ] Enum parsing from client input uses a safe lookup, not `valueOf()` (which throws on invalid input)
- [ ] No client-supplied booleans that bypass server-side authorization or game-state checks
- [ ] No raw exception messages returned to clients (leaks internals)
- [ ] Sensitive data excluded from logs, `toString()`, and error messages
- [ ] WebSocket endpoint: authentication verified before the upgrade completes (reject early,
      before the connection is established — sending a close frame after upgrade is less safe)
- [ ] `Origin` header validated to prevent cross-site WebSocket hijacking (browser clients send it;
      if a client from an unexpected origin connects, reject the upgrade)
- [ ] Production deployment uses WSS (TLS) — plain WS is unacceptable for authenticated sessions

### Architecture
- [ ] Class has one responsibility; dependencies flow in the intended direction
- [ ] Server layer does not own game state that belongs in the engine
- [ ] Domain model does not import Ktor, Android, or other framework types
- [ ] WebSocket controller implements a thin port, not a game participant
- [ ] Per-session state (interrogation accumulators, turn state) lives in the game engine, not in
      the WebSocket session object
- [ ] Inheritance used only for genuine "is-a" relationships where Liskov substitution holds;
      code reuse via `by` delegation preferred (inheriting from a class and overriding behaviour
      can break encapsulation because superclass methods may call each other internally)
- [ ] `open` on a class or method is a deliberate API decision, not a default — all classes
      and methods are `final` unless explicitly opened for extension

### API Design
- [ ] Visibility is intentional — `internal` where appropriate; no accidentally-public helpers
- [ ] All public functions have explicit return types (prevents unintended API changes during refactoring)
- [ ] All public properties have explicit types
- [ ] Nullable types are meaningful, not a convenience shortcut
- [ ] Parameters cannot be accidentally omitted to change behavior (no `fun foo(pin: Boolean = false)`
      where the default bypasses a safety check)
- [ ] Functions with multiple `Boolean` parameters or multiple parameters of the same primitive type
      require named arguments at call sites to prevent accidental reordering:
      `drawSquare(x = 10, y = 10, width = 100, height = 100)` not `drawSquare(10, 10, 100, 100)`
- [ ] Public functions accept `Set<T>` where semantics require uniqueness, `List<T>` where order matters
- [ ] Factory functions preferred over multiple overloaded constructors when constructors would not
      call different superclass constructors
- [ ] `infix` functions only for operations between objects of similar roles (`and`, `to`, `zip`);
      never on mutating functions

### Performance
- [ ] No accidental O(n²) patterns (`.contains()` on a `List` inside a loop — use a `Set`)
- [ ] No unnecessary repeated collection materialization in hot paths
- [ ] No blocking I/O on the main/UI dispatcher
- [ ] Collection pipelines (`filter().map().filter()`) not over-materalized — use `Sequence`
      only when the data volume genuinely warrants it and only when it actually improves performance;
      do not introduce `asSequence()` purely for appearance

### Maintainability and complexity

The following thresholds are detekt defaults; findings above them warrant extraction or simplification:

| Metric | Threshold | Action |
|---|---|---|
| Cyclomatic complexity per method | > 14 | Extract sub-methods |
| Method length | > 60 lines | Extract sub-methods |
| Class length | > 600 lines | Split responsibilities |
| Parameter count | > 5 (fun) / 6 (constructor) | Introduce parameter object |
| Nesting depth | > 4 levels | Flatten with early returns or extracted functions |
| Functions per class/file | > 11 | Split responsibilities |
| Scope function nesting | > 1 level | Replace with explicit control flow |

Additional checks:
- [ ] No magic numbers — extract named constants
- [ ] No `@Suppress` annotations without an explanatory comment
- [ ] No `TODO`/`FIXME` left in code that the design treats as complete
- [ ] No duplicated logic across similar classes (e.g. two IC types sharing an identical formula
      that is not extracted to a shared resolver)

### Testing
- [ ] Tests assert on changed game state, not just on message type or string content
- [ ] Coroutine tests use `runTest { }` — delays are automatically skipped; no `Thread.sleep`
- [ ] `StandardTestDispatcher` when precise dispatch ordering matters;
      `UnconfinedTestDispatcher` when ordering is irrelevant and eager execution is preferable
- [ ] `Dispatchers.setMain(testDispatcher)` in `@Before` / `resetMain()` in `@After` for UI-layer tests
- [ ] Continuous background work launched in `backgroundScope` — cancelled automatically at test end
- [ ] `kotlinx-coroutines-test` is a test-only dependency; it must not appear in main sources
- [ ] Test helpers (dice rollers) do not return values that trigger infinite dice-explosion loops
- [ ] Happy paths, failure paths, and boundary cases are covered
- [ ] Tests do not pass trivially by construction (e.g. `assertEquals(0, x.coerceAtMost(0))`,
      `assertTrue(n >= 0)` for a value guaranteed non-negative by construction)
- [ ] After each IC action with an all-success roller, assert the specific domain field changed
      (not just the message type or return class)

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
- Ktor `WebSockets` plugin configured with all three safety parameters:
  - `pingPeriod` — keep-alive; 15 s is a reasonable default; without it zombie connections accumulate
  - `timeout` — close idle connections; 15 s pairs well with `pingPeriod`
  - `maxFrameSize` — hard cap on inbound frame size; prevents memory exhaustion from a single large message

### Concurrency (`SessionRegistry.kt`)

- Session registry backed by `ConcurrentHashMap` or `Mutex`-guarded `Map`
- `send()` not called while holding a lock (can deadlock if send suspends)
- Full read-authorize-act sequence under a single lock (not split across volatile reads + synchronized blocks)
- Broadcast loops use `SharedFlow` or a snapshot copy of the session collection rather than
  iterating the live registry map — prevents concurrent modification during broadcast
- `@Volatile` on `pendingAction` is not sufficient for compound operations; check-then-act
  sequences must be protected by the same `lock` used for `activeController`

---

## 8. React UI Layer — Specific Checks

### `useWebSocket.ts`

- `useEffect` cleanup closes the WebSocket and nulls `onclose`/`onerror` before closing
  (prevents ghost reconnect loop on unmount)
- Reconnect guard includes `WebSocket.CONNECTING` state (prevents duplicate sockets during
  React StrictMode double-mount — Strict Mode intentionally mounts, unmounts, and remounts
  every component to verify cleanup works; a guard missing `CONNECTING` creates a second socket
  before the first cleanup fires)
- `gameState` reset to `null` in the `DISCONNECTED` reducer case (prevents stale data from a
  prior session rendering as current after reconnect)
- Reducer handles all message types exhaustively — no implicit `else` that silently drops new messages
- Async operations inside `useEffect` use an `ignore` flag or `AbortController` to prevent
  stale responses from updating state after cleanup:
  ```ts
  useEffect(() => {
    let ignore = false;
    fetchData().then(r => { if (!ignore) setData(r); });
    return () => { ignore = true; };
  }, [dep]);
  ```
- `useEffect` is not used for: state initialization (do during render), state derived from other
  state (compute inline), or business-logic events like submitting an action (use event handlers)

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
- Complex multi-field state modelled as discriminated unions, not as a flat object with nullable
  fields that can represent impossible combinations:
  ```ts
  // Bad: loading=true and data present simultaneously is representable
  type State = { loading: boolean; error?: string; data?: GameState }
  // Good: invalid combinations are unrepresentable
  type State = { status: 'idle' } | { status: 'loading' }
             | { status: 'success'; data: GameState } | { status: 'error'; message: string }
  ```
- `children` props typed as `React.ReactNode` (accepts strings, numbers, JSX elements) unless
  JSX-only children are explicitly intended (`React.ReactElement`)

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

Web references consulted during authoring of this guide:
- [Effective Kotlin (kt.academy)](https://kt.academy/book/effectivekotlin) — 51 best-practice items covering safety, readability, class design, and efficiency; key items: limit mutability, platform types, expectations/contracts, sealed classes, equals/hashCode, composition over inheritance
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html) — structured concurrency, dispatchers, cancellation, Flow
- [Kotlin Coroutine Exception Handling](https://kotlinlang.org/docs/exception-handling.html) — `async` vs `launch` propagation, `SupervisorJob`, `CoroutineExceptionHandler`
- [Kotlin Shared Mutable State](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) — `@Volatile` limits, `Mutex`, thread confinement patterns
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) — naming, API visibility, explicit types, named arguments
- [Kotlin API Guidelines](https://kotlinlang.org/docs/api-guidelines-introduction.html) — minimising mental complexity, backward compatibility
- [kotlinx.coroutines.test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) — `runTest`, `StandardTestDispatcher`, `UnconfinedTestDispatcher`, virtual time
- [detekt Complexity Rules](https://detekt.dev/docs/rules/complexity) — cyclomatic complexity, method length, class size thresholds
- [Ktor WebSockets](https://ktor.io/docs/server-websockets.html) — `pingPeriod`, `timeout`, `maxFrameSize`, `SharedFlow` for broadcast
- [React – Synchronizing with Effects](https://react.dev/learn/synchronizing-with-effects) — cleanup, stale closures, `ignore` flag, when not to use `useEffect`
- [React – TypeScript](https://react.dev/learn/typescript) — prop typing, event handlers, discriminated unions, `React.ReactNode`
- [React – Rules of Hooks](https://react.dev/reference/rules) — purity, immutability, Strict Mode double-mount
