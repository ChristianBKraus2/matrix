# Performance Review — game_logic

## Summary

The game logic layer is generally well-structured for a turn-based tabletop engine where the number of active participants is always small (typically 1–4 deckers, a handful of IC programs). No algorithmic disasters are present. The most actionable issues are: redundant list allocations inside the combat turn loop (`Game.kt`), repeated `init`-block validation on every `Cyberdeck` data-class copy (triggered by every decker operation that touches the cyberdeck), a `DiceResult` that always allocates a boxed `List<Int>` even when callers only need the success count, and a regex compiled fresh on every `bufferMessage` call. The rest of the code is clean at the scale it targets.

---

## Findings

### [MEDIUM] Combat turn loop allocates a new filtered list and runs three O(n) scans per pass

**File:** src/main/kotlin/com/shadowrun/matrix/game/Game.kt:22–24

**Issue:** `runCombatTurn` enters its while-loop with `states.any { it.currentInitiative > 0 }` (scan 1), then immediately calls `states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative }` (scan 2 + heap allocation for the filtered list), and then calls `states.indexOf(state)` (scan 3). The `any` guard and the `filter` are redundant: `maxByOrNull` already returns `null` when the predicate matches nothing. The `indexOf` walk can be avoided entirely by tracking the index alongside the value. At 10 combatants and 4 initiative passes each this is ~120 wasted iterations and ~40 list allocations per combat turn.

**Recommendation:** Collapse the three operations into one and track the index directly:
```kotlin
while (true) {
    val idx = states.indexOfFirst { it.currentInitiative > 0 }
        .takeIf { it >= 0 } ?: break
    // … find the actual max by scanning once …
    val (idx, state) = states.withIndex()
        .filter { it.value.currentInitiative > 0 }
        .maxByOrNull { it.value.currentInitiative } ?: break
    …
    states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
}
```
Or, cleaner: use `states.withIndex().maxByOrNull { if (it.value.currentInitiative > 0) it.value.currentInitiative else Int.MIN_VALUE }`, check that the winner's initiative is > 0, and use the returned index directly. Either way the `filter` allocation and the `indexOf` scan are eliminated.

**[DEFERRED]** — Combat loop allocation not optimised; out of scope for this session.

---

### [MEDIUM] `Cyberdeck.init` validation reruns on every data-class copy

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt:42–83

**Issue:** `Cyberdeck` is a `data class`. Every `.copy()` call constructs a new instance and therefore reruns the entire `init` block, which performs: two `forEach` rating checks over `personaPrograms` (O(p)), two `forEach` rating checks over `activeUtilities` and `storedUtilities` (O(u+s)), and three `sumOf` aggregations (O(p + u + s)). A typical decker operation that updates the cyberdeck — IC attack reducing MPCP, loading/unloading a utility, `advanceCombatTurn`, Medic invocation — calls `decker.copy(cyberdeck = cyberdeck.copy(...))`, triggering the inner `Cyberdeck.init` on every such event. In a combat turn with several IC attacks this runs repeatedly for what are logically runtime invariants (persona program totals, memory bounds) that were already enforced at construction time.

**Recommendation:** Extract the invariant checks (persona program ratings ≤ MPCP, `totalPersonaRatings ≤ MPCP×3`, `activeMp ≤ activeMemoryMp`, `storageMp ≤ storageMemoryMp`) into a separate `validate()` function and call it only from factory/builder entry points, not from `init`. For mutations that can only affect a single field (e.g. reducing `mcpRating`), a targeted assertion is cheaper than a full re-scan. Alternatively, replace the data class with a plain class that uses a private constructor plus a companion factory.

**[DEFERRED]** — `Cyberdeck.init` not refactored to skip re-validation on copy; out of scope for this session.

---

### [LOW] `DiceResult` always allocates a boxed `List<Int>` even when only successes are needed

**File:** src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt:19–24

**Issue:** `DiceRoller.roll` is the innermost hot path — every system test, every IC attack, and every combat resolution calls it, typically multiple times per action. It always builds a `List<Int>` of individual face values and stores it on the heap in `DiceResult`. Inspection of all callers shows that the dice list is used in only two places: `roll.dice.sum()` in `CombatResolver.rollDeckerInitiative` / `rollIcInitiative` (sum of raw faces for initiative score), and `roll.dice.first()` in `resolvePointerChain` (1d6 chain length). Every other caller — all system tests, all attack rolls, all willpower checks — accesses only `.successes`. The list is an unnecessary allocation in the vast majority of calls.

**Recommendation:** Replace the stored `List<Int>` with the pre-computed `sum` and keep the list construction only when explicitly requested, or add a `rollSum` overload that returns `(sum, successes)` without persisting the list. At minimum, replace `List(numberOfDice) { rollOne() }` with a plain `IntArray` to avoid boxing.

**[DEFERRED]** — `DiceResult` list allocation not optimised; out of scope for this session.

---

### [LOW] `bufferMessage` compiles a new `Regex` on every invocation

**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:518

**Issue:** `text.split("\\s+".toRegex())` creates and compiles a fresh `Regex` object each time `bufferMessage` is called. Regex compilation is non-trivial work.

**Recommendation:** Hoist the pattern to a file-level `private val`:
```kotlin
private val WHITESPACE_REGEX = "\\s+".toRegex()
```
and reference it in the word-count check.

**[RESOLVED]** — Fixed in `DeckerOperationsExtensions.kt`: `WORD_SPLIT_REGEX` hoisted to a file-level constant; `bufferMessage` now references it instead of calling `toRegex()` on every invocation.

---

### [LOW] `addGridSystemActions` builds the full visible-objects list just to extract IC programs

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:155–156

**Issue:** `addGridSystemActions()` calls `visibleObjects()`, which allocates a `buildList` of all visible Matrix objects, only to immediately call `.filterIsInstance<MatrixObject.IcProgram>()` on it — allocating a second filtered list. On a grid location there are no IC programs anyway, making this doubly wasteful in the common case.

**Recommendation:** When on a grid location there are no `IcProgram` objects to enumerate (only RTG/LTG/PLTG nodes and hosts appear on grids). The call can be removed, or if it is intentional for completeness, pass the already-known location's IC list directly rather than calling `visibleObjects()`.

**[DEFERRED]** — `addGridSystemActions` redundant allocation not removed; out of scope for this session.

---

### [INFO] `advanceCombatTurn` performs nested `any` inside `filterNot` for utility depletion

**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt:64

**Issue:** `cyberdeck.storedUtilities.filterNot { su -> depleted.any { it.type == su.type } }` is O(stored × depleted). In practice both lists are tiny (≤ 10 items), so this is not measurable, but it is a code-smell pattern.

**Recommendation:** Build a `Set<UtilityType>` from `depleted` first (`depleted.mapTo(mutableSetOf()) { it.type }`) and use an O(1) set lookup in the `filterNot`.

**[DEFERRED]** — Nested `any` in `filterNot` not optimised; out of scope for this session.

---

### [INFO] `Host.init` allocates a list and a set on every copy for subsystem-type validation

**File:** src/main/kotlin/com/shadowrun/matrix/network/Host.kt:34–37

**Issue:** `nodes.map { it.subsystemType }.toSet()` allocates an intermediate `List` and a `HashSet` every time a `Host` is constructed or copied. `Host` is a `data class` copied frequently (every tally update, every `updateHost` call, every logon). The check enforces a structural invariant that should not change across copies.

**Recommendation:** Skip the check on copy paths, or validate only when `nodes` itself changes. A short-circuit using `nodes.size >= SubsystemType.entries.size && nodes.all { ... }` avoids the intermediate collection, though the cleanest fix is the same factory-vs-copy pattern suggested for `Cyberdeck`.

**[DEFERRED]** — `Host.init` copy-path validation not optimised; out of scope for this session.

---

## No Issues Found In

- `GameContext.kt` — tally propagation, IC activation, and decker replacement logic are all straightforward linear scans on legitimately small collections; no hidden complexity.
- `CombatResolver.kt` — all resolution functions are O(1) arithmetic plus a fixed number of dice rolls; no collections work beyond single `filterNot` on small lists.
- `SystemTestResolver.kt` — two dice rolls and a `firstOrNull` on a small active-utilities list; clean.
- `DeckerNavigationExtensions.kt` — logon/logoff helpers are single-operation; no loops.
- `DeckerOperationsExtensions.kt` (excluding `bufferMessage`) — all operations are single system-test resolutions; no repeated scanning.
- `Persona.kt`, `ActiveIcon.kt`, `ActionResult.kt`, `ActiveIconState.kt`, `DeckerExtensions.kt` — pure data holders or trivial delegation; nothing to flag.
- `IC.kt` — each IC action does one `findTarget` scan (O(deckers)), one dice resolution, and one `context.updateDecker`; appropriate for the scale.
- `Enums.kt`, `SharedTypes.kt`, `MatrixObject.kt`, `AvailableAction.kt`, `OperationResult.kt`, `SystemOperation.kt`, `Node.kt`, `SecuritySheaf.kt`, `Program.kt` — pure value types with no performance concerns.
- `Grid.kt` (RTG/LTG/PLTG) — plain data containers; no logic to review.
