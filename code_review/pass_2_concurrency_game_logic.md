# Concurrency Review — game_logic

## Summary

The game_logic component is designed around a single-threaded game loop, and the code comments acknowledge this explicitly (`"Game-loop thread only — no concurrent access"`). All domain objects (`Decker`, `Host`, network types, handles, combat results) are immutable `data class` instances, which is an excellent foundation. However, the two `MutableList` fields in `GameContext` are exposed with no synchronization, no enforcement of the stated threading contract, and with the constructor accepting caller-owned mutable references. The `var host` field has no `@Volatile` guard. IC actions follow an unguarded read-modify-write pattern against the shared list. Several of these issues are inert under the current sequential game loop but would silently corrupt state or throw under any future concurrency (background AI processing, async event delivery, UI read access on another thread). The architecture is one thin thread-boundary away from a class of bugs that would be very hard to diagnose.

---

## Findings

### [HIGH] `GameContext.deckers` and `activeIc` are unsynchronized `MutableList` with no enforcement of single-thread contract

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:16-18`

**Issue:** Both lists are plain `ArrayList`-backed `MutableList` values. The KDoc says "Game-loop thread only — no concurrent access," but this is advisory only — there is no `@GuardedBy` annotation, no access check, and no `ThreadLocal` guard. The lists are mutated in `updateDecker()` (`deckers[idx] = new`), `updateHost()` (`deckers.replaceAll {…}`), `checkTriggers()` (`activeIc.addAll(…)`), and `removeIc()` (`activeIc.remove(ic)`). They are iterated in `buildInitiativeList()` (in `Game.kt`), `runOutOfCombatTurn()`, `unauthorizedDeckerInNode()`, and `unauthorizedDeckerInHost()`. Any concurrent read during a write (e.g., a UI thread reading `deckers` while the game loop calls `replaceAll`) causes undefined behaviour on `ArrayList` — structural corruption, `ConcurrentModificationException`, or silently returning stale data.

**Recommendation:** If single-threaded access is guaranteed end-to-end, add an `init`-time `Thread` capture and a private `checkThread()` called at the top of each mutating method. If any cross-thread access is ever needed, switch to `CopyOnWriteArrayList` for read-heavy iteration (IC list) or wrap mutations in `synchronized(this)` blocks and document the lock order.

---

### [HIGH] Constructor accepts caller-owned `MutableList` references — aliased mutable state

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:13-19`

**Issue:** The `GameContext` constructor parameters `deckers: MutableList<Decker>` and `activeIc: MutableList<IC>` are stored directly. The caller retains the original mutable reference and can add, remove, or clear the list from outside `GameContext` at any time, bypassing all internal bookkeeping in `updateDecker`, `applyDeckerOperationResult`, and `checkTriggers`. This violates ownership and makes "game-loop thread only" unenforceable in practice.

**Recommendation:** Copy the lists on construction: `val deckers: MutableList<Decker> = deckers.toMutableList()` (private backing property). Expose them as `List<Decker>` if read access from outside is needed, with mutation only via `GameContext` methods.

---

### [HIGH] `GameContext.host` is a plain `var` — no memory-visibility guarantee

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:20-21`

**Issue:** `var host: Host` has `private set` but no `@Volatile` annotation. On the JVM, writes to a non-volatile field by one thread are not guaranteed to be visible to other threads without a happens-before relationship. If any component ever reads `context.host` from a thread other than the writer (e.g., a UI layer reading alert status, or a test asserting tally after an async event), it may observe a stale value.

**Recommendation:** Annotate with `@Volatile`: `@Volatile var host: Host = host` — or, if `GameContext` is ever shared across threads, move the field into a `@GuardedBy`-annotated structure and synchronize all reads and writes together.

---

### [MEDIUM] IC action read-modify-write against `context.deckers` is non-atomic

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:54-60`, `67-77`, and all other IC `action()` overrides

**Issue:** Every IC `action()` follows this pattern:
1. `val target = findTarget(context)` — reads the current `Decker` from `context.deckers`
2. Computes a `result` using the snapshot of `target`
3. `context.updateDecker(target, result.updatedDecker)` — writes back by index found via `deckers.indexOf(target)`

The three steps are not atomic. In any concurrent scenario where two IC programs act simultaneously (e.g., on a thread pool, or if `runCombatTurn` were ever parallelised for performance), both ICs read the same stale `target`, both compute an update against the same base state, and one write silently clobbers the other. This is the classic lost-update race. `indexOf` compares by reference; if the list slot has already been replaced by the first IC's write, the second IC's `indexOf(target)` returns -1, triggering the `System.err.println` warning and leaving the decker's state diverged.

**Recommendation:** For the current sequential loop this is inert, but the design should make the hazard explicit. Document with a comment that the game loop must remain single-threaded. If parallelism is ever desired, IC actions must pass through a single-writer queue or a transaction around find-update pairs.

---

### [MEDIUM] `applyDeckerOperationResult()` is a non-atomic compound operation

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:61-71`

**Issue:** The method reads `host.securityTally`, calls `updateDecker()`, then conditionally calls `updateHost()` and `checkTriggers()`. Each of those is a separate, unsynchronized mutation. If a second caller (another thread or a re-entrant callback) interleaves between `updateDecker` and `updateHost`, the tally delta computed at line 63 no longer matches the list state, `checkTriggers` may fire against stale bounds, and IC may be activated twice or not at all.

**Recommendation:** Wrap the entire method body in a `synchronized(this)` block if cross-thread access is ever added. At minimum, document clearly that the method must not be re-entered.

---

### [MEDIUM] `checkTriggers()` mutates `activeIc` with `addAll` — would throw under concurrent iteration

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:49-58`

**Issue:** `activeIc.addAll(step.activatedIc)` structurally modifies the list. In the current single-threaded flow, `buildInitiativeList()` completes its iteration of `activeIc` before `checkTriggers()` is ever called, so no `ConcurrentModificationException` occurs. However, if `checkTriggers()` were called from a callback or event handler that fires while `buildInitiativeList()` is mid-iteration (e.g., an event-driven future refactor), a `ConcurrentModificationException` would be thrown. There is no guard.

**Recommendation:** Collect the ICs to add into a local list and apply them at a safe point, or use `CopyOnWriteArrayList` for `activeIc` to tolerate structural modifications during iteration.

---

### [LOW] `buildInitiativeList()` iterates `context.deckers` and `context.activeIc` without a defensive snapshot

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:31-39`

**Issue:** `runOutOfCombatTurn()` in the same file defensively calls `context.deckers.toList()` before iterating. `buildInitiativeList()` iterates both `context.deckers` and `context.activeIc` directly without a snapshot. The inconsistency is a latent hazard: any future code that modifies the lists during the initiative build phase (even on the game-loop thread, via a callback) would cause a `ConcurrentModificationException` or skip entries.

**Recommendation:** Apply the same pattern used in `runOutOfCombatTurn()` — use `.toList()` snapshots in `buildInitiativeList()` for both lists, or document why snapshots are unnecessary there and redundant in `runOutOfCombatTurn()`.

---

### [LOW] `GameContext.updateDecker()` failure path uses `System.err` and silently diverges state

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:29-36`

**Issue:** When `indexOf(old)` returns -1 (decker not found), the method prints to `System.err` and returns without applying the update. This silent divergence can happen today if an IC's read-modify-write races with another writer (see MEDIUM finding above), or if a caller passes a stale `Decker` snapshot. The failure is not propagated to the caller, so game state becomes inconsistent with no exception thrown and no way for the caller to react.

**Recommendation:** Throw an `IllegalStateException` (or return a typed result) so callers can detect and handle the failure rather than silently continuing with diverged state.

---

### [INFO] All domain value types are immutable — a strong concurrency foundation

**Files:** `Decker.kt`, `Host.kt`, `Grid.kt` (`RTG`, `LTG`, `PLTG`), `Node.kt`, `ActiveIconState.kt`, `TrackState.kt`, `DownloadHandle.kt`, `MonitoredOperationHandle.kt`, `InterrogationState.kt`, `Combat.kt`, `SharedTypes.kt`

All these types are Kotlin `data class` with only `val` fields and no mutable collections as members. They are safe to read from any thread without synchronization. The copy-on-write update pattern used throughout `Decker` and `CombatResolver` is idiomatic and correct.

---

### [INFO] `CombatResolver` is a stateless `object` — inherently thread-safe

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt`

The singleton holds no state whatsoever. All methods are pure functions over their arguments. No concurrency concern.

---

### [INFO] No coroutines, `Flow`, or `suspend` functions present

No coroutine primitives were found in any of the reviewed files. There are no `StateFlow`, `SharedFlow`, `Channel`, `Mutex`, or `suspend` keywords. Concurrency risks are purely thread-safety concerns, not coroutine-safety concerns.

---

## No Issues Found In

- `Decker` state machine methods — all return new `data class` copies; no internal mutation
- `CombatResolver` — fully stateless object
- `ActiveIconState` — immutable, local to `runCombatTurn` stack
- `DownloadHandle`, `MonitoredOperationHandle`, `InterrogationState`, `TrackState` — all immutable value types
- `Host`, `Node`, `Grid` subtypes — fully immutable data classes
- `SystemOperation` enum — immutable by definition
- `IC` sealed class hierarchy — instance fields are `val`; all mutation goes through `GameContext`
- `Cyberdeck` — immutable data class; `init` validation is read-only
- `ActiveMemory` / `PendingUpload` — immutable data classes
