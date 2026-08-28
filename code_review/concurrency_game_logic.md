---
# Concurrency Review — game_logic

## Summary

The game-logic layer is built almost entirely on immutable Kotlin `data class` value types with copy-on-write semantics — `Decker`, `Persona`, `Cyberdeck`, `Host`, and all network objects are immutable after construction, and every operation returns a new copy. This is a strong foundation. However, `GameContext` sits at the center of the runtime and breaks this pattern: it holds two publicly accessible `MutableList` fields (`deckers`, `activeIc`) and a mutable `var host` field, all of which are mutated by IC actions, trigger-step processing, and the server layer without any synchronization. Because `MatrixServer.kt` exists and has active WebSocket integration, game state can plausibly be touched from multiple coroutine contexts. The findings below range from a structural lost-update race in the security-tally path down to a latent injectable-`Random` hazard in tests.

---

## Findings

### HIGH — GameContext exposes MutableList fields publicly with no synchronization

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:15-16`

**Issue:** `val deckers: MutableList<Decker>` and `val activeIc: MutableList<IC>` are declared `val` but the list object itself is mutable and fully accessible to all callers. Code in `Game.kt`, every `IC.action()` override, and any server-layer code that holds a `GameContext` reference can call `.add()`, `.remove()`, or `.replaceAll()` directly, bypassing all consistency guarantees provided by `updateDecker`, `removeIc`, and `checkTriggers`. If `Game.runCombatTurn()` or any IC action is ever dispatched from more than one coroutine or thread concurrently — which the WebSocket server makes plausible — every read/write on these lists is an unsynchronized data race.

**Recommendation:** Replace the public `MutableList` constructor parameters with private backing fields. Expose read-only views (`val deckers: List<Decker> get() = _deckers`) and route all mutations through the existing context methods. Protect those methods with a `kotlinx.coroutines.sync.Mutex` (if the server uses coroutines) or `@Synchronized` (if it uses threads), so the full read-modify-write cycle for any single mutation is atomic. `Game.runCombatTurn()` and `runOutOfCombatTurn()` should be called under the same lock.

---

### HIGH — Lost-update on securityTally in applyDeckerOperationResult

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:55-63`

**Issue:** The method reads tally values out of stale decker-embedded host snapshots, then calls `updateDecker` followed by `updateHost`. Under concurrent execution (two decker operations dispatched as separate coroutines before either completes):

1. Operation A reads the decker's embedded `host.securityTally = 0`, computes `new` with tally = 2.
2. Operation B reads its decker's embedded `host.securityTally = 0` (context not yet updated by A), computes `new` with tally = 3.
3. A's `applyDeckerOperationResult` calls `updateHost(host{tally=2})` — context host is now 2.
4. B's `applyDeckerOperationResult` calls `updateHost(host{tally=3})` — context host is overwritten to 3.

Final tally is 3; correct value is 5. One full decker detection increment is silently discarded. Trigger-step boundaries can be crossed without firing `checkTriggers`, leaving IC activation and alert transitions permanently skipped.

**Recommendation:** Store a tally *delta* rather than an absolute value inside each operation's result. Inside `applyDeckerOperationResult` (within a lock), apply the delta to the current `context.host.securityTally` atomically:

```kotlin
// inside a Mutex-protected block:
val delta = newTally - oldTally
if (delta > 0) {
    val authoritative = host.securityTally          // read inside the lock
    updateHost(host.copy(securityTally = authoritative + delta))
    checkTriggers(authoritative, authoritative + delta)
}
```

---

### MEDIUM — updateHost is a non-atomic two-step mutation

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:32-41`

**Issue:** `updateHost` first assigns `host = new` and then calls `deckers.replaceAll { ... }` to re-point each decker's `currentLocation` to the new host object. These are two separate writes with no fence between them. A thread reading `host` after the first write but before `replaceAll` completes sees the new host while deckers still carry a reference to the old host — an internally inconsistent state. A thread iterating `deckers` concurrently can observe a mix of old- and new-host decker entries.

**Recommendation:** Group both mutations inside a single synchronized section. If the `Mutex` recommendation from Finding 1 is adopted, both writes naturally fall inside the same critical section and the race is eliminated.

---

### MEDIUM — check-then-act on alertStatus inside checkTriggers

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:48-51`

**Issue:**

```kotlin
if (transition.ordinal > host.alertStatus.ordinal)
    updateHost(applyAlertTransition(host, transition))
```

The guard reads `host.alertStatus`, decides the transition is applicable, and then calls `updateHost`. Under concurrent access another goroutine/coroutine could apply the same or a higher-severity transition between the check and the write. The result can be a double application of `PASSIVE_ALERT` (adding +4 instead of +2 to every subsystem rating) or a downgrade of a higher alert back to a lower one.

**Recommendation:** Perform the check and the write inside the same lock that protects `updateHost`. Because `host` is always read from `this.host` inside the lock, the guard will reflect the authoritative current state at the moment of mutation.

---

### MEDIUM — MutableList.toList() snapshot missing in buildInitiativeList

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:31-38`

**Issue:** `runOutOfCombatTurn` defensively snapshots with `context.deckers.toList()` before iteration (line 14). `buildInitiativeList`, called from `runCombatTurn`, does *not* snapshot — it iterates `context.deckers` and `context.activeIc` directly. During combat-turn processing, `checkTriggers` can call `activeIc.addAll(...)` (GameContext.kt:47), and any IC action can call `updateDecker`. If a `ConcurrentModificationException` is not thrown first, the initiative-list build can observe a partially mutated list and produce an initiative order that misses or double-counts entries.

**Recommendation:** Snapshot both lists at the top of `buildInitiativeList`:

```kotlin
val snapDeckers = context.deckers.toList()
val snapIc      = context.activeIc.toList()
```

and iterate the snapshots. This is already the correct pattern used in `runOutOfCombatTurn`; apply it consistently.

---

### LOW — Injected Random in DiceRoller is not guaranteed thread-safe

**File:** `src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:14`

**Issue:** `DiceRoller(private val random: Random = Random.Default)`. The default `kotlin.random.Random.Default` is backed by `ThreadLocalRandom` on the JVM and is safe for concurrent use. However, tests inject seeded `Random(seed)` instances, which use a single unsynchronized PRNG state. If a test-configured `DiceRoller` were accidentally shared between parallel test threads (e.g., via a `@BeforeAll`-scoped field), the PRNG state would be a data race that produces non-deterministic dice sequences and can corrupt the seeded-reproducibility guarantee the tests rely on.

**Recommendation:** Document on `DiceRoller` that only `Random.Default`-backed instances are safe for concurrent use. In tests, construct a fresh `DiceRoller` per test method rather than sharing across a class. Consider adding a factory method `DiceRoller.seeded(seed: Long)` that makes the single-threaded contract explicit.

---

### INFO — IC.Probe discards resolved tally points (functional gap adjacent to tally-mutation path)

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:84-89`

**Issue:** `CombatResolver.resolveProbe` returns a tally-point count that represents detection successes the host scored. `Probe.action()` receives this value but neither applies it to the host via `context.applyDeckerOperationResult` nor returns it in a way the `Game` loop can act on. The tally is silently discarded. This is a functional gap rather than a concurrency defect, but it is adjacent to the fragile multi-path tally mutation design (which is itself a concurrency concern): the fact that tally changes flow through at least three different paths (`withUpdatedTally` inside `Decker`, `applyDeckerOperationResult` in `GameContext`, and direct IC-side context mutation) makes it easy to add a new code path that forgets to apply the tally, as happened here.

**Recommendation:** Consolidate all tally mutations into one path. `applyDeckerOperationResult` (or a similarly named method) should be the only legal way to change `context.host.securityTally`. IC actions that produce tally points should return a structured result that the `Game` loop passes to that method, rather than writing directly or silently discarding.

---

## Clean Areas

- **All domain value objects** (`Decker`, `Persona`, `Cyberdeck`, `Host`, `IC` subtypes, `DataFile`, grid types): universally `data class` with copy-on-write semantics. No mutable fields. IC action implementations compute entirely on value copies and hand results back to `GameContext`; they do not hold their own mutable state.
- **CombatResolver and SystemTestResolver**: stateless `object` singletons with no shared mutable fields. Fully re-entrant and safe for any number of concurrent callers.
- **DiceRoller with default Random**: `Random.Default` on JVM is `ThreadLocalRandom`-backed; the default configuration is thread-safe without any additional work.
- **ActiveIconState and all sealed result types** (`ActionResult`, `OperationResult`, `AttackResult`, `LogonResult`, etc.): immutable sealed classes; safe to pass across thread boundaries.
- **Config loaders** (`GridLoader`, `HostLoader`, `DeckerLoader`, `GridInitializer`): stateless `object` loaders used only at startup; no shared mutable state; safe for concurrent reads.
- **Matrix and network topology objects**: `Matrix` wraps an immutable `List<RTG>`; the entire network graph is read-only after loading.
- **`Game.runCombatTurn` initiative loop**: builds a local snapshot list of `ActiveIconState` copies before the loop begins; IC actions cannot corrupt the loop's iteration variable even if they mutate `context.activeIc`, because the initiative list is a separate object. This is correct defensive construction — it just does not extend to the underlying `MutableList` fields inside `context`.
---
