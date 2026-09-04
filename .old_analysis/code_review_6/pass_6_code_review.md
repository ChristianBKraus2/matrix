# Pass 6 — Fresh Code Review

**Scope:** Full codebase review applying `code_review/code_review_guidelines.md`. All pass-5 🔴 bugs have been fixed; this pass re-reviews the corrected code with fresh eyes.

**Test baseline:** 537 tests, BUILD SUCCESSFUL.

Legend: 🔴 Blocker · 🟠 Major · 🟡 Minor · 🔵 Nit

---

## 1 — Architecture

The project follows a clean layered architecture:

```
WebSocketDeckerController / MatrixServer    ← I/O layer
         ↓
    GameContext / Game                      ← Game loop / mutable state
         ↓
  Decker (extension fns) / CombatResolver  ← Domain logic (pure / stateless)
         ↓
  Grid / Host / IC / Utility               ← Domain model (immutable data classes)
         ↓
  SystemTestResolver / DiceRoller          ← Infrastructure (resolver + RNG)
```

Dependency direction is correct throughout. No infrastructure concerns leak into domain objects. The pure-function / immutable-copy model is consistently applied in all `Decker.*Extensions.kt` files and `CombatResolver`.

**Positive patterns observed:**
- Sealed classes for all return types (`LogonResult`, `LogoffResult`, `LocateResult`, `ActionResult`, `OperationResult`) — invalid states are unrepresentable.
- `CombatResolver` is a pure stateless object; no shared mutable state.
- `GameContext` is the single mutable boundary; mutation methods are explicit and centralized.
- `@Volatile` on `WebSocketDeckerController.decker` is correct for single-reference reads/writes of an immutable value type.

---

## 2 — Correctness

### 🟠 MAJOR-01 — `LogonResult.Failure.location` semantics changed; comment is stale

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/MovementResult.kt:10`

The M-05 fix changed `performLogon` to return `LogonResult.Failure(this, newLocation)` instead of `Failure(this, currentLocation)`. The `newLocation` is the *attempted destination* with its tally incremented — not the decker's current position.

`MovementResult.kt` line 10 still says:
```kotlin
/** Decker lost the System Test; still at [location] (null when attempting initial jack-in). */
data class Failure(val decker: Decker, val location: MatrixLocation?) : LogonResult()
```

The comment is now incorrect. For a failed `jackInToLtg`, `location` is `OnLTG(updatedLtg)` (not null). Any caller that inspects `Failure.location` as "where the decker is" will misread it as a move that already occurred.

**Fix:** Update the comment to reflect new semantics.

---

### 🟡 MINOR-01 — `locateAccessNode` NotFound matching always finds a result for SubsystemType names

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:238`

The NotFound check matches against `it.subsystemType.name` (the Kotlin enum name — e.g., `"ACCESS"`, `"FILES"`, `"SLAVE"`, `"CONTROL"`, `"INDEX"`). Since every `Host` is guaranteed to have one node per `SubsystemType` (enforced in `Host.init`), and the enum covers all 5 standard types, a query of `"control"` or `"access"` will always find a match and never return `NotFound`.

In practice, a decker searching for a specific named sub-node by description would need to search by `it.description`, but for standard subsystem names the current logic can never produce `LocateResult.NotFound`. The design intent (PRD §SO) is to find a specific address/node. Consider whether the query should be matched against a dedicated `address` or `label` field added to `Node`, or whether `description`-only matching is intended for now.

**Fix:** Document the matching behaviour (or add a node address field). For now, add a comment explaining that subsystem-type names always exist on a valid host.

---

### 🟡 MINOR-02 — `Game.runCombatTurn()` uses unnecessary intermediate allocation

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:34`

```kotlin
val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative }!!
```

Two issues:
1. The `filter` creates a new list immediately before a max — `maxByOrNull` on the original list with a predicate would avoid the allocation.
2. The `!!` is safe (the `while` guard ensures non-empty), but it is not self-documenting. Kotlin 1.7+ `maxBy{}` (throws on empty) or an explicit `requireNotNull` with a message is clearer.

**Suggested:**
```kotlin
val state = requireNotNull(states.maxByOrNull { if (it.currentInitiative > 0) it.currentInitiative else Int.MIN_VALUE }) {
    "Initiative list unexpectedly empty — this is a programming error"
}
```
or more simply:
```kotlin
val state = states.filter { it.currentInitiative > 0 }.maxBy { it.currentInitiative }
```
(`maxBy` throws on empty, which is fine here since the while guard is the gate.)

---

### 🟡 MINOR-03 — M-09 unified RTG tally not implemented (known deferred)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:37`

Per `design/design_core/missing.md`, the unified RTG-wide tally (M-09) is intentionally deferred. Each LTG/PLTG/Host maintains an independent `securityTally`. The design mentions a `mergeRtgTally()` helper that does not exist. This gap means tally accumulated on LTG-A is never propagated to RTG or LTG-B.

No action required now, but this is the most significant remaining game-rules gap. Flagged for backlog.

---

## 3 — Concurrency and Coroutines

### ✅ CancellationException re-throw

All `catch (e: Exception)` blocks in suspend functions (`Game.runCombatTurn`, `Game.runOutOfCombatTurn`, `MatrixServer`, `WebSocketDeckerController`) correctly re-throw `CancellationException`. This is the most common coroutine bug and the codebase handles it everywhere.

---

### 🟠 MAJOR-02 — `TurnCoordinator.pendingAction` not guarded by `@Volatile`; `@Volatile activeController` bypassed in `currentControllerUnsafe()`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt:11-13`

```kotlin
@Volatile private var activeController: DefaultWebSocketServerSession? = null
private var pendingAction: CompletableDeferred<ActionCommand>? = null
```

- `pendingAction` is updated under `mutex.withLock` in `setPendingAction`, `cancelIfActive`, and `claimAction`. This is correct. However, `pendingAction` has no `@Volatile` annotation. Without it, writes are not guaranteed visible to other threads that do not hold the mutex (e.g., if any code ever reads `pendingAction` outside the mutex). Currently no code reads it outside the mutex, so this is safe — but the inconsistency with `activeController` (which has `@Volatile`) is a trap for future maintainers.

- `currentControllerUnsafe()` (line 24) reads `activeController` without the mutex. The `@Volatile` annotation makes the read atomic and visible, but any compound operation relying on `currentControllerUnsafe()` has a TOCTOU window. The method name signals the contract, but callers should be documented about when it is safe to use.

**Fix:** Either add `@Volatile` to `pendingAction` for consistency, or add a comment explaining the mutex-only protection. Add a KDoc to `currentControllerUnsafe` clarifying the safety contract.

---

### 🟡 MINOR-04 — `GameContext` mutable collections are not coroutine-safe

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:21-27`

`_deckers` and `_activeIc` are `MutableList` instances. The `Game.runCombatTurn()` loop iterates `context.activeIc.toList()` (snapshot), but methods like `checkTriggers` call `_activeIc.addAll(...)` during the same suspension context. If `Game.runCombatTurn()` and `WebSocketDeckerController.conductTurn()` are run in different coroutines on the same `GameContext` simultaneously, concurrent modification is possible.

In current usage, the design appears to be single-coroutine sequential (one `Game` instance drives the loop), but this is not enforced. A comment or `@NotThreadSafe` annotation would clarify the contract.

---

## 4 — Error Handling

### ✅ Correct use of `require`/`check`/`requireNotNull`

Domain invariants are enforced with `require`/`check` throughout. Callers are responsible for guarding preconditions before calling domain methods. No silent failures observed.

### 🟡 MINOR-05 — `MatrixServer.kt` inner `try` block misindented

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:46-68`

```kotlin
for (frame in incoming) {
    if (frame is Frame.Text) {
    try {       // ← should be indented under if
        ...
    }
    }
}
```

The inner `try { }` block is at the same indentation level as `if (frame is Frame.Text)` instead of being nested one level deeper. This is a style nit but can mislead readers about the control flow.

---

## 5 — Kotlin Idioms and API Design

### 🟡 MINOR-06 — `LogonResult.Failure.location` is misleadingly typed

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/MovementResult.kt:10`

After the M-05 fix, `Failure.location` now carries the *attempted destination with updated tally*, not the decker's current location. The field name `location` suggests the decker's position, but it is actually the result of the logon attempt (needed by callers to extract the updated tally). Consider renaming to `attemptedLocation` or `tallyUpdatedLocation`, and updating the KDoc accordingly.

---

### 🔵 NIT-01 — `ControlSlave` creates a copy just to pass `effectiveSkill` to `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:305`

```kotlin
val deckerForTest = if (effectiveSkill != null) copy(computerSkill = skill) else this
val outcome = SystemTestResolver.resolve(deckerForTest, ...)
```

The copy is a full `Decker` deep copy (data class). It is functionally correct and only created when `effectiveSkill != null`. For the common case (`effectiveSkill == null`) the `else this` branch avoids the allocation. Acceptable, but `SystemTestResolver.resolve()` could accept an optional skill override to avoid the copy entirely. Not a blocking concern.

---

### 🔵 NIT-02 — Unused `SystemTestOutcome` direct construction removed from `ControlSlave` but import may remain

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt`

After refactoring `controlSlave` to use `SystemTestResolver.resolve()`, verify that the `SystemTestOutcome` import is no longer directly used in the file (it was previously constructed manually there). Kotlin does not fail on unused imports by default, so this may silently remain. The compiler's `-Xlint` flag or an IDE inspection would catch it.

---

## 6 — Testing

### 🟡 MINOR-07 — No test for M-03 fix (ILLEGAL_JUNCTION_BOX → jackInToHost)

`HOST_JACKPOINT_TYPES` now includes `ILLEGAL_JUNCTION_BOX`, but no test verifies that `jackInToHost` with an illegal-junction-box jackpoint succeeds (and that other jackpoint types still fail). This is a correctness regression risk.

**Suggested test:** In `MovementTest` or equivalent, create a `Jackpoint` with `type = JackpointType.ILLEGAL_JUNCTION_BOX`; call `jackInToHost`; assert `LogonResult.Success`.

---

### 🟡 MINOR-08 — No test for M-05 fix (tally preserved on failed logon)

After the M-05 fix, `performLogon` returns `Failure(decker, newLocation)` where `newLocation` carries the incremented tally. No test verifies that on a failed `jackInToLtg`, the returned `Failure.location` reflects the host's tally increment. Without this test, a future refactor could reintroduce the bug silently.

**Suggested test:** Use a `hitRoller` (host always wins) for `jackInToLtg`; assert that `(result as Failure).location` is `OnLTG(ltg_with_incremented_tally)`.

---

### 🟡 MINOR-09 — No test for `locateAccessNode` NotFound branch

The new `NotFound` branch in `locateAccessNode` (fires when ≥3 accumulated successes but no matching node) has no test. All existing locate-operation tests cover `Ongoing` and `Located` paths only.

**Suggested test:** Call `locateAccessNode` with a query that matches no `subsystemType.name` or `description`; accumulate ≥3 successes; assert `LocateResult.NotFound`.

---

### 🟡 MINOR-10 — No test for CC-32 side-effect: physical CM unchanged after dump shock

The CC-32 fix changes dump shock to affect `mentalConditionMonitor` instead of `physicalConditionMonitor`. The existing updated tests verify that the mental CM receives damage, but no test verifies that the physical CM is *not* modified. Without this assertion, a future regression (accidentally touching both) would pass the existing tests.

**Suggested addition:** In `resolveDumpShock` tests, add `assertEquals(0, result.physicalConditionMonitor.damage)`.

---

## 7 — Security

### ✅ No secrets in source or test fixtures

No hard-coded passwords, tokens, or API keys found.

### ✅ External input validated at WebSocket boundary

`SessionRegistry.receiveJoin()` validates decker name length (≤32) and character set (`[A-Za-z0-9 _\\-]{1,32}`) before processing. Malformed JSON is caught and returns `BAD_REQUEST`.

### 🟡 MINOR-11 — `actionIndex` from WebSocket client not bounds-checked at the point of use

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:97`

```kotlin
val chosen = availableActions.getOrNull(cmd.actionIndex)
if (chosen == null) {
    broadcastFail("Invalid action index ${cmd.actionIndex}")
```

The `cmd.actionIndex` is an Int from an untrusted client. `getOrNull` handles out-of-range gracefully. However, if `actionIndex` is a very large positive integer, it still falls through to the null check. This is safe because `getOrNull` handles it, but there is no explicit validation that `actionIndex >= 0`. A negative index would also return null and be caught. Current handling is correct; a comment would make the intent explicit.

---

## 8 — Performance

### ✅ No O(n²) hotspots

Collection operations in `CombatResolver`, `GameContext`, and the `Decker.*Extensions` files are all O(n) or better. The largest collections in practice are `activeIc` (typically < 20) and `dataFiles` / `remoteDevices` (typically < 100).

### 🔵 NIT-03 — `locateAccessNode` NotFound check iterates `host.nodes` twice

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:238`

The ≥5 and ≥3 branches each call `host.nodes.any { ... }` independently. This is negligible in practice (nodes ≤ 5 per host), but a shared `val nodeExists = host.nodes.any { ... }` would make the intent clearer and avoid the duplication.

---

## 9 — Summary Table

| ID | Severity | File | Line | Description |
|----|----------|------|------|-------------|
| MAJOR-01 | 🟠 | MovementResult.kt | 10 | `Failure.location` comment stale after M-05 semantics change |
| MAJOR-02 | 🟠 | TurnCoordinator.kt | 11 | `pendingAction` not `@Volatile`; `currentControllerUnsafe` undocumented |
| MINOR-01 | 🟡 | DeckerOperationsExtensions.kt | 238 | locateAccessNode NotFound: subsystem names always exist → NotFound impossible for standard queries |
| MINOR-02 | 🟡 | Game.kt | 34 | Unnecessary `filter` + `maxByOrNull!!` — use `maxBy` or `requireNotNull` |
| MINOR-03 | 🟡 | DeckerNavigationExtensions.kt | 37 | M-09 unified RTG tally not implemented (known deferred) |
| MINOR-04 | 🟡 | GameContext.kt | 21 | MutableList collections not coroutine-safe; contract not documented |
| MINOR-05 | 🟡 | MatrixServer.kt | 48 | Inner `try` block misindented under `if (frame is Frame.Text)` |
| MINOR-06 | 🟡 | MovementResult.kt | 10 | `Failure.location` misleading name; should be `attemptedLocation` |
| MINOR-07 | 🟡 | MovementTest | — | No test for M-03 fix: ILLEGAL_JUNCTION_BOX in jackInToHost |
| MINOR-08 | 🟡 | MovementTest | — | No test for M-05 fix: tally preserved on failed logon |
| MINOR-09 | 🟡 | DeckerOperationsTest | — | No test for locateAccessNode NotFound branch |
| MINOR-10 | 🟡 | CombatResolverTest | — | No assertion that physicalConditionMonitor unchanged after dump shock |
| MINOR-11 | 🟡 | WebSocketDeckerController.kt | 97 | `actionIndex` not validated ≥ 0; should add comment about getOrNull safety |
| NIT-01 | 🔵 | DeckerOperationsExtensions.kt | 305 | ControlSlave copies full Decker just to pass effectiveSkill |
| NIT-02 | 🔵 | DeckerOperationsExtensions.kt | — | Verify no unused `SystemTestOutcome` direct import remains |
| NIT-03 | 🔵 | DeckerOperationsExtensions.kt | 238 | `host.nodes.any` called twice in locateAccessNode |

---

## 10 — Post-Review Action Plan

**Fix now (correctness / documentation):**
1. Update `MovementResult.kt:Failure.location` KDoc comment (MAJOR-01 / MINOR-06)
2. Add `@Volatile` to `TurnCoordinator.pendingAction` and KDoc to `currentControllerUnsafe` (MAJOR-02)
3. Fix `Game.kt:34` to use `maxBy` instead of `maxByOrNull!!` (MINOR-02)
4. Fix `MatrixServer.kt` indentation (MINOR-05)
5. Fix `locateAccessNode` double iteration with shared `nodeExists` variable (NIT-03)

**Add tests (regression coverage):**
6. M-03: `jackInToHost` with `ILLEGAL_JUNCTION_BOX` succeeds (MINOR-07)
7. M-05: failed `jackInToLtg` returns tally-incremented location (MINOR-08)
8. `locateAccessNode` NotFound branch (MINOR-09)
9. CC-32: `physicalConditionMonitor` unchanged after `resolveDumpShock` (MINOR-10)
