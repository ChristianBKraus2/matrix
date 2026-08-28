# Performance Review — game_logic

## Summary

The game_logic component is a turn-based simulator operating on small, bounded state: a handful of deckers, a dozen or so IC, and utility lists of at most ten items. There are therefore no algorithmic catastrophes — no O(n²) blowups on large data sets. The performance problems that do exist are of two kinds: gratuitous intermediate allocations on hot paths that execute on every dice roll or every initiative step, and redundant recomputation of derived values (detection factor, active memory usage) on every access rather than once per construction. None of these will cause visible slowdowns in normal play, but several of them are easy to fix and reduce GC pressure in the hot path. The single sharpest issue is the combat initiative loop, which walks the `states` list three separate times per step when one walk would suffice.

---

## Findings

### [HIGH] Combat turn loop makes three O(n) passes per step, plus an intermediate list allocation

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:21-23`

**Issue:** `runCombatTurn` contains this inner loop:
```kotlin
while (states.any { it.currentInitiative > 0 }) {
    val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative } ?: break
    val idx = states.indexOf(state)
    ...
}
```
Each iteration executes: (1) `any { ... }` — one O(n) scan; (2) `filter { ... }` — a second O(n) scan that also allocates a new `List<ActiveIconState>`; (3) `maxByOrNull` on the filtered list — a third O(n) scan; (4) `indexOf(state)` — a fourth O(n) scan to recover the index of the object just found. The `filter` allocation is discarded immediately after. Total cost per step: 4 × O(n) + 1 list allocation.

**Recommendation:** Combine into a single indexed scan:
```kotlin
while (true) {
    val (idx, state) = states.withIndex()
        .filter { it.value.currentInitiative > 0 }
        .maxByOrNull { it.value.currentInitiative } ?: break
    state.icon.action(context, diceRoller)
    states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
}
```
Or, more efficiently, track the highest-initiative entry with a single `fold` or `reduceIndexed` over `states` to find both the index and the value in one pass, eliminating the intermediate list entirely.

---

### [MEDIUM] `detectionFactor` property rescans two lists on every access

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:83-90`

**Issue:** `detectionFactor` is a computed `get()` that scans `cyberdeck.personaPrograms` (up to 4 elements) and `cyberdeck.activeUtilities` (up to ~10 elements) on every invocation. `effectiveDetectionFactor` calls `detectionFactor`, and `effectiveDetectionFactor` is called on every single dice roll that involves the host — once in `SystemTestResolver.resolve` and again in `resolveInterrogation`. Over a combat turn with multiple deckers and several IC actions, this property is evaluated dozens of times, each time re-scanning the same unchanged lists.

Because `Decker` is an immutable `data class`, the persona programs and active utilities cannot change between accesses without producing a new `Decker` instance. Computing `detectionFactor` eagerly as a `val` in the class body (instead of a `get()`) would compute it once per instance:
```kotlin
val detectionFactor: Int = run {
    val masking = cyberdeck.personaPrograms
        .firstOrNull { it.attributeType == PersonaAttributeType.MASKING }?.rating ?: 0
    val sleaze = cyberdeck.activeUtilities
        .firstOrNull { it.type == UtilityType.SLEAZE }?.currentRating
    cyberdeck.detectionFactor(masking, sleaze)
}
```

**Recommendation:** Convert `detectionFactor` (and by extension `effectiveDetectionFactor`) from a recomputed `get()` property to an eagerly initialised `val` in the class body. Since `Decker` is a `data class`, every `copy(...)` that changes the relevant fields will produce a new instance and recompute the value exactly once.

---

### [MEDIUM] `DiceResult` always materialises a full `List<Int>` even when only the success count is needed

**File:** `src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:20-22`

**Issue:** Every call to `DiceRoller.roll` allocates a `List<Int>` of size `numberOfDice`:
```kotlin
val dice = List(numberOfDice) { rollOne() }
val successes = dice.count { it >= targetNumber }
```
The full list is stored in every `DiceResult`. Inspecting all callers: only `CombatResolver.rollDeckerInitiative` (line 39) and `rollIcInitiative` (line 46) ever access `roll.dice.sum()`. Every other call site — all of `SystemTestResolver`, all attack and defense rolls in `CombatResolver`, all the sensor/perception rolls in `Decker` — reads only `.successes` and discards the list. That is the large majority of all dice rolls in the game. Each of those rolls allocates a boxed-integer list that is immediately eligible for GC.

**Recommendation:** Add a fast path that accumulates only the success count without storing individual dice values:
```kotlin
fun rollCount(numberOfDice: Int, targetNumber: Int): Int {
    var successes = 0
    repeat(numberOfDice) { if (rollOne() >= targetNumber) successes++ }
    return successes
}
```
Keep the full `roll(...)` overload for the two initiative call sites that genuinely need the raw dice. Update all other call sites to use `rollCount`, or make `DiceResult.dice` a `lazy` property that only reifies the list when accessed.

---

### [MEDIUM] `Cyberdeck.usedActiveMemoryMp` and `freeActiveMemoryMp` are recomputed on every access

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt:36-40`

**Issue:**
```kotlin
val usedActiveMemoryMp: Int
    get() = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }

val freeActiveMemoryMp: Int
    get() = activeMemoryMp - usedActiveMemoryMp
```
Both are computed `get()` properties that re-traverse `activeUtilities` and `pendingUploads` on every read. `freeActiveMemoryMp` is accessed inside `Decker.loadUtility` on every load attempt, and `usedActiveMemoryMp` is also computed in the `init` block validation. Like `Decker.detectionFactor`, `Cyberdeck` is an immutable `data class` — the values cannot change without producing a new instance.

**Recommendation:** Convert both to eagerly initialised `val` properties in the class body so the sums are computed once per instance.

---

### [LOW] `runOutOfCombatTurn` copies the decker list defensively on every non-combat turn

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:14`

**Issue:** `context.deckers.toList()` creates a defensive copy of the decker list before iterating. The comment in `GameContext` already states that the list has no concurrent access. If `decker.action(...)` can mutate `context.deckers` during iteration, this copy is necessary for correctness; if it cannot (which appears to be the intent), it is a gratuitous allocation.

**Recommendation:** Verify whether any `action` implementation can add or remove deckers from `context.deckers`. If not, iterate `context.deckers` directly. If yes, document why the copy is required.

---

### [LOW] `addGridSystemActions` calls `visibleObjects()` to re-derive the IC list

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:527`

**Issue:** Inside `availableActions()`, the helper extension `addGridSystemActions()` internally calls `visibleObjects()` in full and then filters for IC programs:
```kotlin
visibleObjects().filterIsInstance<MatrixObject.IcProgram>()
    .forEach { add(AvailableAction.Operation(SystemOperation.ANALYZE_IC, it)) }
```
This reconstructs the entire visible-objects list (building a fresh `buildList { ... }` with several `forEach` loops) solely to extract the IC subset. The result is immediately discarded. Since `availableActions()` already knows the current location and could pass in the IC list directly, this is a redundant construction.

**Recommendation:** Pass the IC programs as a parameter to `addGridSystemActions`, or extract the IC programs from the location directly rather than rebuilding the full visibility list.

---

### [LOW] `GameContext.updateDecker` uses structural equality via `indexOf` on the full decker list

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:30`

**Issue:** `deckers.indexOf(old)` compares the `old` `Decker` instance against every element using `data class` structural equality (which compares all fields, including nested lists). For a typical game session with 2–4 deckers this is negligible, but the comparison cost grows with the depth of nested data.

**Recommendation:** Replace with `indexOfFirst { it === old }` (referential equality) or maintain a `Map<String, Int>` from decker name to list index. Referential equality is sufficient here because callers always pass the exact instance obtained from the context.

---

### [LOW] `advanceCombatTurn` builds three intermediate collections to process pending uploads

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:408-415`

**Issue:**
```kotlin
val decremented = cyberdeck.pendingUploads.map { ... }      // new list
val nowActive = decremented.filter { ... }.map { ... }       // two new lists
val stillPending = decremented.filter { ... }                // another new list
val allActive = cyberdeck.activeUtilities + nowActive        // yet another new list
val (live, depleted) = allActive.partition { ... }           // two more lists
val newStored = cyberdeck.storedUtilities.filterNot { ... }  // another new list
```
Seven intermediate `List` allocations for a `pendingUploads` list that typically has 0–3 entries.

**Recommendation:** Use `partition` on the decremented list to split into ready/pending in a single pass, avoiding the two separate `filter` calls. Use `sequence` or a single `fold` to derive `nowActive`, `stillPending`, `live`, and `depleted` together. Given the tiny list sizes the GC impact is minor, but the logic becomes clearer.

---

### [LOW] `invokeMediac` traverses `activeUtilities` and `storedUtilities` twice each

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1004-1013`

**Issue:** The method builds `newActive` and `newStored` with an `if/else` that issues either `filterNot` or `map` on each list. When `newMedicRating > 0`, both lists are fully traversed to locate and update the Medic entry. A subsequent `copy(...)` then replaces both. Each list is walked once more than necessary.

**Recommendation:** Use a single `map` that both filters the depleted case and updates the rating in the live case, so each list is traversed exactly once.

---

### [LOW] `bufferMessage` compiles a new `Regex` on every call

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1190`

**Issue:**
```kotlin
require(text.split("\\s+".toRegex()).size <= 100) { ... }
```
`"\\s+".toRegex()` constructs and compiles a `Regex` object on every invocation.

**Recommendation:** Hoist the regex to a `companion object` constant:
```kotlin
private val WHITESPACE_RE = Regex("\\s+")
```
and reference it in `bufferMessage`.

---

### [LOW] `performLogon` scans `personaPrograms` four times to build a new `Persona`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1236-1245`

**Issue:** On successful logon the initial `Persona` is constructed from four separate `firstOrNull` scans over `cyberdeck.personaPrograms` (a list of exactly 4 elements):
```kotlin
bod     = cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.BOD }?.rating ?: 0
evasion = cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.EVASION }?.rating ?: 0
masking = cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.MASKING }?.rating ?: 0
sensor  = cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.SENSORS }?.rating ?: 0
```
Each call walks up to 4 elements. Given the list is always exactly 4 elements long, this is trivially cheap — but a single `associateBy` into a map, or a `fold` over the list, would express intent more clearly and be marginally faster.

**Recommendation:** Build an intermediate map once:
```kotlin
val ppByType = cyberdeck.personaPrograms.associateBy { it.attributeType }
```
then read `ppByType[BOD]?.rating ?: 0`, etc. Low priority; mention primarily for readability.

---

### [INFO] `Host.init` allocates two `Set` objects purely for a validation check

**File:** `src/main/kotlin/com/shadowrun/matrix/network/Host.kt:34-35`

**Issue:**
```kotlin
val coveredTypes = nodes.map { it.subsystemType }.toSet()
require(coveredTypes == SubsystemType.entries.toSet()) { ... }
```
Two `Set` allocations (plus an intermediate `List` from `map`) just for the equality check. `SubsystemType.entries.toSet()` is called at every `Host` construction (config load time).

**Recommendation:** Precompute `SubsystemType.entries.toSet()` as a `companion object` constant on `Host` or in `SubsystemType`'s companion. Use `nodes.mapTo(HashSet()) { it.subsystemType }` to skip the intermediate list. Impact is confined to startup/config-load time.

---

### [INFO] `Matrix.getHost` chains three linear scans over the topology graph

**File:** `src/main/kotlin/com/shadowrun/matrix/network/Matrix.kt:6`

**Issue:** `getHost(rtgName, ltgName, hostName)` calls `getRTG`, then `getLTG`, then `firstOrNull` on the host list — three chained O(n) scans. In a large topology with many RTGs, LTGs, or hosts per LTG this degrades, but for the expected cardinalities (single-digit RTGs, tens of LTGs, dozens of hosts) it is perfectly acceptable.

**Recommendation:** If the topology grows significantly, replace with indexed maps (`Map<String, RTG>`, etc.) built at load time in `GridLoader`. No immediate action needed.

---

## No Issues Found In

- **`SystemOperation` enum** — static data; constant-time field access everywhere it is used.
- **`CombatResolver` attack/defense resolution** — individual methods are short, single-pass, and create only the result objects they return. No hot-path waste.
- **`IC` action dispatching** — `findTarget` and `moveIfNeeded` are simple, linear-scan lookups over very small lists. Correct and proportionate.
- **`DeckCatalogLoader` / `HostLoader` / `GridLoader`** — called once at startup; no runtime hot-path exposure.
- **`GameContext.checkTriggers`** — `triggerSteps` is tiny (< 10 entries in any realistic config); the `filter` allocation is inconsequential.
- **`PointerChain` / `DownloadHandle` / `MonitoredOperationHandle`** — simple value holders; no computation.
- **`SAN` / `Node`** — pure data; no logic.
