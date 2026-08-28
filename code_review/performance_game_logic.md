---
# Performance Review — game_logic

## Summary

The game logic is well-structured and correct, and for a single-session tabletop RPG simulator the absolute performance impact of most findings is low. However, the dice-rolling path — which executes multiple times per combat action — allocates a heap-backed boxed-integer `List<Int>` on every single call, which is the most concrete source of unnecessary GC pressure. Several computed properties (`detectionFactor`, `usedActiveMemoryMp`) perform linear list scans on every System Test invocation rather than caching or using O(1) structures; with small but growing entity counts these add up turn by turn. The combat turn loop also performs redundant allocations on every initiative pass. The config loaders are properly bounded to startup/load time and carry no runtime concerns.

---

## Findings

### [HIGH] DiceRoller.roll() allocates a boxed List<Int> on every dice throw

**File:** src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:20

**Issue:** `List(numberOfDice) { rollOne() }` creates a new `ArrayList` backed by a heap array and boxes each `Int` as a `java.lang.Integer` object. This happens on every call to `roll()`. A single combat action triggers between four and eight `roll()` calls (decker initiative, IC initiative, attack, defense, damage staging, optional MPCP test, optional Willpower test). For a combat turn with six active icons each taking two initiative passes, that is approximately 50–100 `DiceResult` and `List<Int>` allocations per turn. The `DiceResult` struct then retains the full dice list for the entire lifetime of the result object, even though the vast majority of callers only ever read `result.successes` (the `dice` list is only consumed in `rollDeckerInitiative`, `rollIcInitiative`, and `resolvePointerChain`).

**Recommendation:** Replace the `List<Int>` storage with an `IntArray` to eliminate boxing. Better still, compute `successes` inline during rolling and only store the `IntArray` when a caller genuinely needs the individual faces. A dual-mode design works cleanly: one internal `rollForSuccesses(numberOfDice, targetNumber): Int` that rolls and counts without allocating a list, and the existing `roll()` method retained (but using `IntArray`) for the rare callers that need face values. The `DiceResult` class's `dice: List<Int>` field should become `dice: IntArray` and be filled lazily or only for the callers that actually use it.

---

### [MEDIUM] Decker.detectionFactor re-scans two lists on every System Test

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:82

**Issue:** `detectionFactor` is a computed `val` (getter, no caching) that scans `cyberdeck.personaPrograms` for the `MASKING` entry and `cyberdeck.activeUtilities` for `SLEAZE` on every access. `effectiveDetectionFactor` calls it directly, and `effectiveDetectionFactor` is referenced in every single System Test: `SystemTestResolver.resolve()` (line 44), `SystemTestResolver.resolveInterrogation()` (line 99), `CombatResolver.resolveCrippler()` (line 156), `CombatResolver.resolveRipper()` (line 207), and `CombatResolver.resolveProbe()` (line 173). Every action in the game triggers at least one System Test, so this linear scan runs dozens of times per combat turn. The `personaPrograms` list is immutable after jack-in; the `activeUtilities` list changes only on load/unload operations.

**Recommendation:** Cache the masking rating from `personaPrograms` at jack-in time directly on `Persona` (it is already computed in `performLogon` at lines 1222–1232 but then discarded). Store masking as a field on `Persona` so the scan happens once. For the Sleaze lookup, change `cyberdeck.activeUtilities` from `List<Utility>` to `Map<UtilityType, Utility>` (see the finding below); the SLEAZE lookup then becomes `cyberdeck.activeUtilities[UtilityType.SLEAZE]?.currentRating`, which is O(1) with no allocation.

---

### [MEDIUM] activeUtilities stored as List requires O(N) scan on every System Test

**File:** src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:33 and src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:672

**Issue:** `cyberdeck.activeUtilities` is a `List<Utility>`. Every call to `SystemTestResolver.resolve()` performs `activeUtilities.firstOrNull { it.type == utilityType }` to find the relevant utility's rating for TN reduction. The same pattern appears in `resolveInterrogation()`, `analyzeIcon()`, `controlSlave()`, `editFile()`, `tapComcall()`, `relocateIcon()`, and `invokeMediac()`. Across a single combat turn with multiple actions these scans accumulate. The `UtilityType` enum acts as a natural key; there is never more than one utility of each type loaded simultaneously (enforced in `loadUtility()`).

**Recommendation:** Change `Cyberdeck.activeUtilities` from `List<Utility>` to `Map<UtilityType, Utility>` (an `EnumMap` is ideal — zero-overhead for enum keys). Update `loadUtility`, `unloadUtility`, `swapUtility`, `advanceCombatTurn`, and `invokeMediac` to use map operations. All `firstOrNull { it.type == X }` lookups become `activeUtilities[X]`, reducing every System Test from O(N) scan to O(1) lookup. `usedActiveMemoryMp` becomes `activeUtilities.values.sumOf { it.mpSize }` with identical semantics.

---

### [MEDIUM] Game.runCombatTurn() allocates a filtered List on every initiative pass

**File:** src/main/kotlin/com/shadowrun/matrix/game/Game.kt:21

**Issue:** The combat turn loop reads:
```kotlin
while (states.any { it.currentInitiative > 0 }) {
    val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative } ?: break
    val idx = states.indexOf(state)
```
Three separate O(N) traversals happen per iteration: `any {}`, `filter {}` (which allocates a new `ArrayList`), and `indexOf()`. The `filter {}` allocation is the most wasteful because it creates a fresh heap object per initiative pass. If a combat turn has six entities each with two initiative passes, twelve `ArrayList` objects are allocated just for this purpose. The `any {}` check is also redundant because `maxByOrNull` on the filtered list already produces `null` when nothing is positive.

**Recommendation:** Collapse to a single pass that avoids the intermediate list:
```kotlin
while (true) {
    val (idx, state) = states.withIndex()
        .filter { it.value.currentInitiative > 0 }
        .maxByOrNull { it.value.currentInitiative } ?: break
    state.icon.action(context, diceRoller)
    states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
}
```
Or, since `states` is a `MutableList<ActiveIconState>`, track the index directly with a manual loop using `indexOfFirst`. This eliminates the `filter` allocation and the redundant `indexOf` scan in one change.

---

### [LOW] advanceCombatTurn() makes three separate passes over pending/active lists

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:407

**Issue:** `advanceCombatTurn()` performs: (1) `map` over `pendingUploads` to decrement, (2) `filter` for completed uploads, (3) another `filter` for still-pending, (4) `partition` on `allActive`, (5) `filterNot` on `storedUtilities` with an inner `any { }` scan over `depleted` — an O(M × N) nested check at line 414. While the lists are small in practice (pending uploads rarely exceed 4–5 items), the pattern is worth cleaning up because `advanceCombatTurn` is called by the game engine at the start of every combat turn for every decker.

**Recommendation:** Combine steps (2) and (3) into a single partitioning pass. Replace the O(M × N) `depleted.any { it.type == su.type }` check with a `HashSet` of depleted types built once:
```kotlin
val (completed, stillPending) = decremented.partition { it.turnsRemaining <= 0 }
val nowActive = completed.map { it.utility }
val depletedTypes = allActive.filter { it.currentRating <= 0 }.mapTo(HashSet()) { it.type }
val newStored = cyberdeck.storedUtilities.filterNot { it.type in depletedTypes }
```

---

### [LOW] bufferMessage() compiles a Regex on every invocation

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1177

**Issue:** `text.split("\\s+".toRegex())` calls `toRegex()` on a string literal at runtime on every invocation of `bufferMessage`. `toRegex()` constructs and compiles a `java.util.regex.Pattern`, which is non-trivial. While `bufferMessage` is not called in a tight loop (at most once per initiative pass), the pattern object creation is entirely unnecessary since the same pattern is used every time.

**Recommendation:** Hoist the compiled Regex into the companion object:
```kotlin
companion object {
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    // ...
}
// then in bufferMessage:
require(text.split(WHITESPACE_REGEX).size <= 100) { ... }
```

---

### [LOW] Game.buildInitiativeList() sorts a list that is already being built in order

**File:** src/main/kotlin/com/shadowrun/matrix/game/Game.kt:39

**Issue:** `buildInitiativeList()` appends all deckers then all IC entries into a mutable list, then calls `sortedByDescending { it.currentInitiative }` which creates a second copy of the list just to sort it. With a typical count of 2–8 combatants this is functionally free, but there is a missed opportunity to use `sortWith` in-place to avoid the copy.

**Recommendation:** Call `list.sortByDescending { it.currentInitiative }` (in-place mutation) and return `list` directly, or simply keep the call as-is and accept it — this is INFO-level at realistic entity counts.

---

### [INFO] DiceResult carries the full List<Int> past the point it is needed

**File:** src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:8

**Issue:** `DiceResult(dice: List<Int>, targetNumber: Int, successes: Int)` stores the complete face-value list. Callers in `CombatResolver.rollDeckerInitiative()` and `rollIcInitiative()` use `roll.dice.sum()` rather than `roll.successes`, meaning they need the list. However, all other callers (the vast majority) only read `result.successes` and never touch `result.dice`. The retained list prevents GC of the integer objects until the `DiceResult` itself is collected.

**Recommendation:** Once the boxing issue (HIGH finding above) is resolved by switching to `IntArray`, the memory impact is reduced to a single array reference. A further improvement would be to separate the "sum" use-case from the "successes" use-case: pre-compute `val sum: Int` in `DiceResult` so `rollDeckerInitiative`/`rollIcInitiative` can use `roll.sum` and the list can be dropped from the public API, retaining the array only when explicitly requested.

---

### [INFO] Cyberdeck.init validates with multiple independent list passes

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt:42

**Issue:** The `init` block iterates over `personaPrograms` once to check per-program rating, once via `sumOf` to check total rating, over `activeUtilities` once to check ratings, over `storedUtilities` once to check ratings, once via `sumOf` for active memory, and once via `sumOf` for storage memory — six passes in total, some overlapping. This is purely an initialization concern (called once per Cyberdeck construction) so there is zero runtime impact during a game session.

**Recommendation:** No action needed during active play. If Cyberdeck construction ever appears in a hot path (e.g., many `copy()` calls per turn), these could be merged into two passes (one for personaPrograms, one for utilities). Currently `copy()` skips `init` checks... actually Kotlin `data class copy()` does re-run `init`, so each `decker.copy(cyberdeck = ...)` triggers all six passes. Given that several methods call `decker.copy(cyberdeck = cyberdeck.copy(...))` per turn (e.g., `resolveTarPit`, `advanceCombatTurn`, `invokeMediac`), consider moving the heavier sum-checks to a separate factory/builder function that runs only at construction time, not on every `copy`.

---

## Clean Areas

- **Combat resolution math** (`CombatResolver.kt`): All arithmetic helpers (`stage`, `attackTn`, `securityValue`) are pure functions with no allocations. Correct use of early returns avoids unnecessary computation.
- **Network topology traversal** (`Matrix.kt`, `Decker.logonToHost` chain): Each hop validates exactly the required relationship without full-graph traversal. O(N) scans over small adjacency lists are appropriate here.
- **SecuritySheaf trigger evaluation** (`GameContext.checkTriggers`): A single `filter` over a tiny list of trigger steps; this fires at most once per action and is negligible.
- **DiceRoller.rollOne()**: The exploding-dice loop is minimal — one `Random.nextInt` call per face, sum accumulated in a local `Int`, no allocations inside the loop.
- **Config loaders** (`GridLoader`, `HostLoader`, `DeckerLoader`): YAML parsing happens once at startup and is not called during game turns. The loaders correctly use `use {}` for stream cleanup. The `Yaml()` instance per `load()` call is acceptable at initialization time.
- **Immutable value objects throughout**: The pervasive use of `data class` with `copy()` avoids shared-mutable-state bugs and keeps most game state easy to reason about from a correctness standpoint.
- **AlertTransitions** (`AlertTransitions.kt`): Pure function, single `copy()` call per alert transition, no loops.
---
