# Concurrency Review — game_logic

## Summary

The game logic layer is effectively single-threaded in execution: `Game.runCombatTurn()` and `Game.runOutOfCombatTurn()` call each `action()` sequentially, and no implementation of `ActiveIcon.action()` contains a real suspension point or launches a child coroutine. Within this model there are no data races, deadlocks, or ordering hazards in the domain code itself. The meaningful structural risk is concentrated in `GameContext`: its two mutable backing lists (`_deckers`, `_activeIc`) and the mutable `var host` property carry no synchronisation, no threading contract, and no confinement annotation. If the surrounding Ktor/coroutine server layer ever allows a WebSocket handler coroutine to touch the same `GameContext` concurrently with the game loop, data corruption or `ConcurrentModificationException` becomes possible with no compile-time signal. A secondary latent hazard is an inconsistency in defensive copying between `runOutOfCombatTurn()` (which correctly snapshots via `toList()`) and `buildInitiativeList()` (which does not). All other game-logic classes — domain value objects, resolvers, extension functions — are either immutable or stateless and are safe regardless of concurrency level.

## Findings

### [MEDIUM] GameContext mutable state has no synchronisation or documented threading contract
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:21-27
**Issue:** `_deckers` and `_activeIc` are plain `MutableList<T>` (backed by `ArrayList`) and `var host` is an unprotected field. All six mutating methods (`addIc`, `removeIc`, `resetDeckers`, `updateDecker`, `updateHost`, `checkTriggers`, `addToSecurityTally`) read and write this state without a lock, `Mutex`, or confining dispatcher annotation. No documentation states which thread or dispatcher owns the instance. In a Ktor application where a WebSocket frame handler (running on `Dispatchers.IO` or the default dispatcher) could be dispatched to the same `GameContext` while the game-loop coroutine is executing a turn, the underlying `ArrayList` is unsafely published: concurrent reads and writes produce undefined list state or a fast-fail `ConcurrentModificationException`.
**Recommendation:** Choose one of: (a) document and enforce a single-owner dispatcher contract — run the game loop and every external mutation on a dedicated `newSingleThreadContext` or `Dispatchers.Default.limitedParallelism(1)` so the `GameContext` is always owned by one coroutine at a time; (b) guard all mutating methods with a `kotlinx.coroutines.sync.Mutex`; or (c) at minimum annotate the class `@NotThreadSafe` and add a KDoc note so callers understand the assumption.

**[DEFERRED]** — `GameContext` threading contract not formalised; out of scope for this session.

### [LOW] buildInitiativeList() iterates live context collections without a defensive copy
**File:** src/main/kotlin/com/shadowrun/matrix/game/Game.kt:33-41
**Issue:** `buildInitiativeList()` iterates `context.deckers` and `context.activeIc` using the live backing views. By contrast, `runOutOfCombatTurn()` (line 14) defensively calls `context.deckers.toList()` before iterating. If any code path that can be reached while `buildInitiativeList()` is executing were to trigger an `addIc()` or `updateDecker()` call — for example via a tally-check side effect from a concurrent coroutine — the fail-fast `ArrayList` iterator would throw. The inconsistency also makes the code harder to reason about because one site is safe and the other is not.
**Recommendation:** Apply the same defensive-copy pattern used in `runOutOfCombatTurn()`:
```kotlin
for (decker in context.deckers.toList()) { ... }
for (ic    in context.activeIc.toList()) { ... }
```

**[RESOLVED]** — Fixed in `Game.kt`: `buildInitiativeList` now uses `.toList()` defensive copies for both `context.deckers` and `context.activeIc`.

### [LOW] IC action() read-modify-write is non-atomic and fragile under any future parallelism
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:55-210 (all IC subclass action() bodies)
**Issue:** Every IC action follows a read-compute-write pattern: (1) `findTarget()` reads the current `Decker` reference from context, (2) a resolver computes a new `Decker`, (3) `context.updateDecker(old, new)` writes it back by `indexOf(old)` identity lookup. Under strictly sequential execution this is correct. If two coroutines were ever to execute IC actions concurrently, the second write would throw `check(idx >= 0)` if the first write had already replaced the reference — or, worse, silently overwrite the first IC's damage update if both coroutines read the same stale decker before either writes back.
**Recommendation:** No change is required while the game loop remains sequential. Add a comment to `GameContext.updateDecker()` and the IC `action()` pattern noting the sequential-only assumption explicitly, so any future parallelism attempt raises an immediate flag.

**[DEFERRED]** — Sequential-only contract comment not added; out of scope for this session.

### [INFO] suspend modifier on action() adds overhead and implies a safety guarantee that is not enforced
**File:** src/main/kotlin/com/shadowrun/matrix/game/ActiveIcon.kt:6
**Issue:** `ActiveIcon.action()` is declared `suspend`, and all implementations (`Decker`, `Crippler`, `Killer`, `Probe`, `Blaster`, `Ripper`, `Sparky`, `TarBaby`, `TarPit`, `LethalBlackIC`, `NonLethalBlackIC`) carry the modifier but never call a suspending function. This causes the compiler to generate state-machine boilerplate for each call with no benefit. More importantly, the `suspend` interface signals to future maintainers that adding a real suspension point inside an action implementation is safe. Doing so would allow the coroutine to be suspended mid-action while the game loop proceeds, creating a window where `GameContext` is partially updated — a correctness hazard invisible at compile time.
**Recommendation:** Either remove `suspend` from the interface and all implementations if no actual suspension is planned, or add an explicit KDoc contract: "Implementations must not introduce real suspension points; the game loop assumes each action completes atomically with respect to `GameContext`."

**[DEFERRED]** — `suspend` modifier not removed and contract not documented; out of scope for this session.

### [INFO] DiceRoller is safe for any level of concurrent use
**File:** src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:14
**Issue:** None. `DiceRoller` holds a single `Random` reference (defaulting to `Random.Default`). Kotlin's `Random.Default` is backed by a thread-local source on the JVM and is safe to call from any thread or coroutine without synchronisation. No instance-level mutable state is accumulated.
**Recommendation:** No action needed.

**[RESOLVED]** — Confirmed correct by design; no fix required.

### [INFO] All domain value objects are immutable
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt, Cyberdeck.kt, Persona.kt, network/Host.kt, network/Grid.kt
**Issue:** None. Every domain object is a `data class` with `val`-only fields. All "mutations" return new instances via `copy()`. The single source of intentional mutability is `GameContext`, which is the correct and minimal scope for shared state. This design eliminates the most pervasive class of concurrency bugs at the domain level.
**Recommendation:** No action needed.

**[RESOLVED]** — Confirmed correct by design; no fix required.

### [INFO] CombatResolver and SystemTestResolver are stateless
**File:** src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:31, operations/SystemTestResolver.kt:10
**Issue:** None. Both are `object` declarations whose functions are pure: inputs map to outputs with no shared mutable state. They are safe to invoke from any coroutine or thread without locks.
**Recommendation:** No action needed.

**[RESOLVED]** — Confirmed correct by design; no fix required.

## No Issues Found In
- `Decker` and all extension files (`DeckerOperationsExtensions.kt`, `DeckerNavigationExtensions.kt`, `DeckerMemoryExtensions.kt`) — stateless pure transforms returning new `Decker` copies via `copy()`
- `CombatResolver` — stateless `object`, no shared mutable state
- `SystemTestResolver` — stateless `object`, no shared mutable state
- `DiceRoller` — thread-safe random source
- `Host`, `RTG`, `LTG`, `PLTG`, `Node`, `SecuritySheaf`, `TriggerStep` — immutable data classes
- `Persona`, `Cyberdeck`, `ConditionMonitor` — immutable data classes
- `ActionResult`, `ActiveIconState`, `OperationResult` — immutable sealed/data classes
- `AvailableAction`, `MatrixObject`, `SystemOperation` — immutable sealed/enum types
- `Program.kt` — immutable abstract class
- `Enums.kt`, `SharedTypes.kt` — pure constants and enums
