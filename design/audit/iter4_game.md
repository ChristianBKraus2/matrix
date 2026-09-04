# Iteration 4 — Business Logic (game turn loop, GameContext, memory management)

Conformance audit. Every file below read in full via `Read` from line 1 to last line in this
session (Rule 1/2). Line counts are the file totals. Excerpts are verbatim code tokens.

Reference baselines read first this session: `design/audit/iter2_move_game.md`,
`design/audit/iter2_cyberdeck.md`, `design/audit/spec_baseline.md`, and `design/align.md`
(§Methodology Rules 1-2, 9-11, Prohibited Patterns, Per-File Checklist).

## Coverage Table

| File | Lines | Verbatim excerpts (copied tokens) | Notes |
|---|---|---|---|
| `game/ActionResult.kt` | 8 | `data class IcMoved(val message: String) : ActionResult()` / `data object DeckerAction : ActionResult()` | Sealed variants `IcAttack`, `IcMoved`, `NoTarget`, `DeckerAction` match game.md L39-44 exactly. No finding. |
| `game/ActiveIcon.kt` | 9 | `suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult` / `fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative` | Interface matches game.md L24-27. `initiative()` returns `CombatInitiative` (feeds DOC-5, resolved below). No finding. |
| `game/ActiveIconState.kt` | 6 | `data class ActiveIconState(` / `val currentInitiative: Int` | Matches game.md L104-111. `currentInitiative` is `Int`; the `CombatInitiative`→`Int` mapping happens at the call site (see D4G-2). No finding in this file. |
| `game/DeckerExtensions.kt` | 19 | `val loc = requireNotNull(currentLocation as? MatrixLocation.OnHost) { ... }` / `armorRating = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }?.currentRating ?: 0` | `asDefenderParticipant()` requires non-null persona + `OnHost`, armor = ARMOR `currentRating ?: 0`. Matches game.md L405-416. Constructor supplies `bod, armorCurrentRating, personaStatus, securityCode` (Rule 9 — complete). No finding. |
| `game/Game.kt` | 90 | **(L19)** `val count = decker.persona?.let { decker.actionsPerTurn } ?: continue` — **(L40-48)** `while (states.any { it.currentInitiative > 0 }) { ... states[idx] = state.copy(currentInitiative = state.currentInitiative - 10) }` — **(L85-87)** `ActiveIconState(icon, icon.initiative(context, diceRoller).score)` | Turn loop: out-of-combat repeats `action()` `actionsPerTurn` times (D4G-1); combat proactive/physical/reactive segments correct; IcMoved return discarded (D4G-3); crashed-IC-can-re-act edge (D4G-4). |
| `game/GameContext.kt` | 94 | **(L13-19)** `class GameContext(host: Host, val securityCode: SecurityCode, deckers: List<Decker>, activeIc: List<IC> = emptyList(), val matrix: Matrix = Matrix())` — **(L64-66)** `.filter { it.tallyThreshold in (oldTally + 1)..newTally }` — **(L87-88)** `fun addToSecurityTally(points: Int) { require(points >= 0) { ... } }` | updateDecker/addToSecurityTally/removeIc/addIc/unauthorizedDeckerInNode/Host all match spec. Ctor param ORDER differs from iter2 distill (`activeIc` before `matrix`) — D4G-5. |
| `decker/DeckerMemoryExtensions.kt` | 105 | **(L13)** `logger.info { "[$name] loadUtility → ${utility.type} (rating=${utility.rating}, ${utility.mpSize} Mp)" }` — **(L103)** `completedDownloads.forEach { handle -> result = result.recordCompletedDownload(handle.file) }` | methods: `loadUtility`, `unloadUtility`, `swapUtility`, `advanceCombatTurn`. loadUtility (turns=⌈Mp/io⌉, io≤0→0, InsufficientMemory, turns==0 direct), unload (both lists by type), swap (unload→load), advanceCombatTurn (decrement/promote pending, CD-22 auto-unload from BOTH lists, trackState, downloads/uploads) all match iter2_cyberdeck spec. No finding. |

Excerpt spacing: DeckerMemoryExtensions L13→L103 (90 apart, 101-300 band needs 2 ≥30 apart) ✓.
Game.kt / GameContext.kt are ≤100 lines (1 excerpt minimum) — extra excerpts provided.

---

## Findings

### D4G-1 — Out-of-combat loop multiplies a side-effect-free `action()` (resolves MG-8 / DOC-8)

**Code:** `game/Game.kt:17-27` `runOutOfCombatTurn()`:
```
val count = decker.persona?.let { decker.actionsPerTurn } ?: continue
repeat(count) { try { decker.action(context, diceRoller) } ... }
```
`decker.action()` is a no-op placeholder — `Decker.kt:55`:
`override suspend fun action(...): ActionResult = ActionResult.DeckerAction`.

**Impact:** The action-economy multiplication (`actionsPerTurn` = ⌈Reaction÷10⌉ + Response Increase,
verified correct at `Decker.kt:85-89`) iterates a call with no side effects, so it accomplishes
nothing. iter2 (game.md L181-188) documents that player decker turns bypass `Game` in production and
`Decker.action()` is an intentional placeholder.

**Verdict:** MG-8 confirmed. Not a spec violation — the placeholder is intentional (game.md L181-188).
Code-quality / dead-multiplication (DC); the loop is written for a future non-placeholder `action()`.
No code change required; keep flagged.

### D4G-2 — `CombatInitiative`→`Int` mapping is `.score` in code (resolves MG-5 / DOC-5, doc-stale)

**Code:** `game/Game.kt:85-87`:
`ActiveIconState(icon, icon.initiative(context, diceRoller).score)`.
`ActiveIcon.initiative()` returns `CombatInitiative` (`game/ActiveIcon.kt:8`); `ActiveIconState.currentInitiative`
is `Int` (`game/ActiveIconState.kt:5`); `CombatInitiative.score: Int` (`combat/CombatInitiative.kt:4`).

**Verdict:** DOC-5 said the design doc never states *which* field of `CombatInitiative` becomes the
`Int`. The code answers this unambiguously and consistently: `.score`. This is **doc-stale** — code is
correct; game.md L147 should name the `.score` field. No code change.

### D4G-3 — IC move (`IcMoved`) is never persisted by the turn loop (resolves MG-4 / DOC-4 — REAL CODE BUG)

**Design:** game.md L221 comment: "caller replaces this IC instance in `context.activeIc` with a copy
at the new node."

**Code:** `ic/IC.kt:49-55` `moveIfNeeded()` returns `ActionResult.IcMoved("$name moved to $targetNode")`
without mutating any state (it does not change the IC's `guardedNode`, does not call `updateHost`/`removeIc`/`addIc`).
Every IC `action()` does `moveIfNeeded(target, context)?.let { return it }` (e.g. `ic/IC.kt:68`).
The turn loop that is the "caller" — `game/Game.kt:43-48` — calls `state.icon.action(...)` and **ignores the
returned `ActionResult`**, only decrementing initiative by 10. No code path inspects `IcMoved` or replaces the
IC instance in `context.activeIc`.

**Impact:** A proactive IC whose `guardedNode` differs from the target's node moves *every* turn forever:
the "move" is announced but the IC's `guardedNode` never updates, so `moveIfNeeded` fires again next turn.
The IC never reaches the target node and never attacks — the documented relocation never persists.

**Verdict:** MG-4 / DOC-4 confirmed as a **real code gap**, not doc-stale. The move contract in game.md is
not honored by any caller. Fix required: the initiative loop must capture `action()`'s result and, on
`IcMoved`, replace the IC in `context.activeIc` with one whose `guardedNode` is the target's node (or
`moveIfNeeded` must perform the replacement via `GameContext` itself).

### D4G-4 — Crashed/removed IC can still act again within the same turn

**Code:** `game/Game.kt:39-49`. The initiative list `states` is built once per turn. IC that crash mid-turn
call `context.removeIc(this)` inside their own `action()` (e.g. `ic/IC.kt:136, 224, 248, 269` — Blaster,
Sparky/TarPit, Lethal/NonLethal Black IC). Their `ActiveIconState` remains in `states` with residual
`currentInitiative`, so the loop's `states.filter { it.currentInitiative > 0 }.maxBy { ... }` can select
the same (now-removed) icon again on a later pass and invoke `action()` a second time.

**Impact:** An IC that has crashed / been removed from `activeIc` can take another action in the same turn
(and, for the removed instance, `findTarget`/attack again). The loop gates only on `currentInitiative > 0`,
not on continued membership in `context.activeIc`.

**Verdict:** Real correctness edge. game.md's action loop (L148-149) describes decrement-by-10 selection but
the "list rebuilt each turn" contract implies removed icons should not re-act. No PRD clause names this edge
directly. Flag for fix: skip states whose icon is no longer in `context.activeIc` (or is a decker no longer
in `context.deckers`).

### D4G-5 — `GameContext` constructor parameter order differs from the iter2 distill

**Code:** `game/GameContext.kt:13-19`:
```
class GameContext(host: Host, val securityCode: SecurityCode, deckers: List<Decker>,
                  activeIc: List<IC> = emptyList(), val matrix: Matrix = Matrix())
```
iter2_move_game.md L65 records the ctor as `(host, val securityCode, deckers, val matrix = Matrix(), activeIc = emptyList())`
— `matrix` before `activeIc`. The code places `activeIc` before `matrix`.

**Impact:** Both trailing params have defaults, so named-argument callers are unaffected; a positional caller
supplying the 4th/5th argument would bind to the wrong parameter. Low risk but a real order mismatch between
the distilled spec and code.

**Verdict:** Doc-stale (distill / any design table listing the ctor should swap the order to match code) OR
purely cosmetic — no PRD governs ctor arg order. Documentation fix; no runtime change. Note: all other
GameContext methods (`updateDecker`, `addToSecurityTally` with `require(points >= 0)`, `removeIc`, `addIc`,
`unauthorizedDeckerInNode` [`currentNode == node && status == INTRUDING`], `unauthorizedDeckerInHost`,
`checkTriggers` threshold `(oldTally+1)..newTally` == `(oldTally, newTally]`) match spec exactly.

### Resolution of remaining MG findings touching this layer

- **MG-6 / DOC-6** (`withRatingBonus` only on NonLethalBlackIC): **doc-stale**. Code defines it on BOTH —
  `ic/IC.kt:240` `fun withRatingBonus(bonus: Int) = LethalBlackIC(rating + bonus, guardedNode)` and
  `ic/IC.kt:260` for NonLethalBlackIC. (IC.kt is outside this iteration's assigned set but the reference is resolvable.)
- **MG-7 / DOC-7** (no interrogation state map in game.md): confirmed game-layer code carries interrogation
  state on `Decker.interrogationStates` (`decker/Decker.kt:50`), not on `GameContext`. game.md documents no
  interrogation coordination — documentation gap, not a code bug in the assigned files.

---

## Summary

- 11 IC subtypes dispatch through the shared `action()` pattern (findTarget → moveIfNeeded → resolver →
  apply) via the single `game/Game.kt` initiative loop; the loop itself is correct for segment ordering
  (proactive by initiative, then physical meatworld-comm, then reactive end-of-turn) and housekeeping
  (`advanceCombatTurn` per decker).
- Memory management (`DeckerMemoryExtensions.kt`) fully conforms to the cyberdeck spec, including CD-22
  auto-unload from both active and stored lists.
- Two real code bugs: **D4G-3** (IC move never persisted — the highest-impact finding) and **D4G-4**
  (crashed IC can re-act). D4G-1 and D4G-2 resolve MG-8/MG-5; D4G-5 is a cosmetic ctor-order note.
