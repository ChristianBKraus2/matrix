# Game Design Document

## Purpose

This document specifies the design for the game layer that drives the combat loop as defined in `prd_game.md`. It introduces:

- An `ActiveIcon` interface giving `Decker` and `IC` a common `action()` contract
- A `GameContext` class holding all mutable runtime state
- An `ActionResult` sealed class describing what an action produced
- An `ActiveIconState` data class pairing an icon with its current initiative score
- A `Game` class that drives out-of-combat and in-combat turns

This layer sits above `CombatResolver` (which remains a stateless resolver of individual interactions) and above `Decker` / `IC` (which remain immutable value types).

---

## New Types

### `ActiveIcon` (interface)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/ActiveIcon.kt`

```kotlin
interface ActiveIcon {
    fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative
    suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult
}
```

Both methods are required by every icon. `initiative()` encapsulates the Security-Code dependency for IC: the concrete IC implementation reads `context.securityCode` and delegates to `CombatResolver.rollIcInitiative()`; the Decker implementation delegates to `CombatResolver.rollDeckerInitiative()`.

---

### `ActionResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/ActionResult.kt`

```kotlin
sealed class ActionResult {
    data class IcAttack(val message: String) : ActionResult()
    data class IcMoved(val message: String)  : ActionResult()
    data object NoTarget                     : ActionResult()
    data object DeckerAction                 : ActionResult()
}
```

- `IcAttack` — IC found a target and acted; `message` describes the outcome.
- `IcMoved` — IC moved to a node containing an intruding decker but did not attack this action.
- `NoTarget` — IC found no intruding decker in host, or was reactive and could not move.
- `DeckerAction` — placeholder for future user callback; returned by `Decker.action()` for now.

---

### `GameContext` (class)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt`

Mutable holder of all runtime game state for one host encounter. Passed into every `action()` call.

```kotlin
class GameContext(
    host: Host,
    val securityCode: SecurityCode,
    deckers: List<Decker>,
    activeIc: List<IC> = emptyList()
) {
    var host: Host = host
        private set

    val deckers: List<Decker>  // read-only view; mutate via updateDecker()
    val activeIc: List<IC>     // read-only view; mutate via addIc()/removeIc()

    fun unauthorizedDeckerInNode(node: Node): Decker?
    fun unauthorizedDeckerInHost(): Decker?
    fun updateDecker(old: Decker, new: Decker)
    fun removeIc(ic: IC)
    fun addIc(ic: IC)
    fun resetToSingleDecker(decker: Decker)
    fun deckerByName(name: String): Decker?
    fun updateHost(new: Host)
    fun checkTriggers(oldTally: Int, newTally: Int)
    fun applyDeckerOperationResult(old: Decker, new: Decker)
    fun addToSecurityTally(points: Int)
}
```

- `unauthorizedDeckerInNode(node)` — returns the first decker whose `persona.currentNode == node` and `persona.status == INTRUDING`, or `null`.
- `unauthorizedDeckerInHost()` — returns the first intruding decker regardless of node, or `null`.
- `addIc(ic)` — adds a newly spawned IC program to `activeIc`. Used by `checkTriggers` when a trigger step activates new IC.
- `resetToSingleDecker(decker)` — replaces the entire deckers list with a single entry. Used by integration-test scaffolding to reset state.
- `deckerByName(name)` — looks up a decker by name, or `null` if not found.
- `updateHost(new)` — replaces the host reference and rewires any `OnHost` location references in the deckers list so they point to the new host instance.
- `checkTriggers(oldTally, newTally)` — checks the host's security sheaf for any trigger steps whose threshold falls in `(oldTally, newTally]`. For each newly crossed step: spawns activated IC via `addIc` and applies any alert-status transition via `updateHost` (AL-01/AL-02).
- `applyDeckerOperationResult(old, new)` — convenience wrapper: calls `updateDecker`, then detects any security-tally increase on the new decker's location and calls `updateHost` + `checkTriggers` accordingly.
- `addToSecurityTally(points)` — directly increments the host's security tally and calls `checkTriggers`. Used by Probe IC (ICC-03: successes added to tally immediately).

---

### `ActiveIconState` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/ActiveIconState.kt`

```kotlin
data class ActiveIconState(
    val icon: ActiveIcon,
    val currentInitiative: Int
)
```

Used by `Game` to track each icon's initiative score within a single combat turn. The list is rebuilt at the start of each turn.

---

### `Game` (class)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt`

```kotlin
class Game(
    val context: GameContext,
    val diceRoller: DiceRoller
)
```

#### Out-of-combat mode

```kotlin
suspend fun runOutOfCombatTurn()
```

Iterates `context.deckers` and calls `decker.action(context, diceRoller)` `decker.actionsPerTurn` times per decker (SO-01/SO-02: ⌈Persona Reaction ÷ 10⌉ + Response Increase). No IC participation.

#### In-combat mode

```kotlin
suspend fun runCombatTurn()
```

1. **Roll initiative** for every active icon by calling `icon.initiative(context, diceRoller)` on each entry. Build a `MutableList<ActiveIconState>` sorted descending by `currentInitiative`.

2. **Action loop**: find the entry with the highest `currentInitiative > 0`. Call `icon.action(context, diceRoller)`. Decrement that entry's `currentInitiative` by 10.

3. Repeat step 2 until all entries have `currentInitiative ≤ 0`. The turn is over.

4. Return to step 1 for the next turn. Combat ends when `context.activeIc` is empty (all IC crashed or suppressed) or when the caller signals resolution externally.

---

## Changes to Existing Classes

### `Decker` — implements `ActiveIcon`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

`Decker` is a `data class`; Kotlin data classes can implement interfaces.

```kotlin
data class Decker(...) : ActiveIcon {

    override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
        CombatResolver.rollDeckerInitiative(this, meatworldComm = false, diceRoller)

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult =
        ActionResult.DeckerAction
}
```

`DeckerAction` is a placeholder. In simulation and testing, the `Game` loop calls `decker.action()` which returns immediately without side effects. In production, the WebSocket server layer bypasses `Game` entirely for decker turns: **`WebSocketDeckerController.conductTurn(context, diceRoller)`** handles player input, dispatches the chosen action, and broadcasts result/state messages. It is never called via `ActiveIcon` and does not implement `ActiveIcon`.

This separation means the `Game` class handles IC turns (called via `action()`) while `WebSocketDeckerController.conductTurn()` handles player decker turns. The game loop in `Game.runCombatTurn()` therefore sees `Decker.action()` as a no-op placeholder for the player's turn slot.

---

### `IC` — implements `ActiveIcon` (abstract)

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt`

`IC` declares both methods abstract. Each leaf subclass implements `action()`; the base class provides a concrete `initiative()` that delegates to `CombatResolver`, so subclasses do not need to override it.

```kotlin
sealed class IC(...) : ActiveIcon {

    override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
        CombatResolver.rollIcInitiative(this, context.securityCode, diceRoller)

    abstract override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult

    // Returns the intruding decker this IC should act against, or null if none found.
    // If guardedNode is set, prefers a decker in that node; falls back to any intruding decker in the host.
    // If guardedNode is null, searches the whole host directly.
    protected fun findTarget(context: GameContext): Decker? =
        if (guardedNode != null) context.unauthorizedDeckerInNode(guardedNode) ?: context.unauthorizedDeckerInHost()
        else context.unauthorizedDeckerInHost()

    // If the IC has a guardedNode and the target is not already there, move toward them (proactive only).
    // Returns IcMoved if a move was performed this action, null otherwise.
    // Unanchored IC (guardedNode == null) never moves.
    protected fun moveIfNeeded(target: Decker, context: GameContext): ActionResult.IcMoved? {
        if (guardedNode == null) return null
        val targetNode = target.persona?.currentNode ?: return null
        if (targetNode == guardedNode) return null
        if (behavior == IcBehavior.REACTIVE) return null
        // IC moves: caller replaces this IC instance in context.activeIc with a copy at the new node
        return ActionResult.IcMoved("$name moved to $targetNode")
    }
}
```

#### White IC subclasses

Each subclass calls `findTarget()`, then `moveIfNeeded()`, then its specific `CombatResolver` method, and applies the result to `context`.

**`Killer`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val attacker = CombatResolver.icAttackParticipant(this, context.securityCode)
    val result = CombatResolver.resolveKiller(attacker, target.asDefenderParticipant(), diceRoller)
    if (result is AttackResult.Hit) {
        val dmg = CombatResolver.applyIcDamage(target, result, this, diceRoller)
        context.updateDecker(target, dmg.updatedDecker)
        return ActionResult.IcAttack("Killer hit ${target.name}: ${dmg.iconDamage}")
    }
    return ActionResult.IcAttack("Killer missed ${target.name}")
}
```

**`Crippler`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveCrippler(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Crippler reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
}
```

**`Probe`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val tallyPoints = CombatResolver.resolveProbe(this, target, diceRoller)
    context.addToSecurityTally(tallyPoints)
    return ActionResult.IcAttack("Probe added $tallyPoints tally against ${target.name}")
}
```

**`Scramble`**

Scramble triggers on logon (reactive gate), not as an active attack. Returns `NoTarget` — no combat action.
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult = ActionResult.NoTarget
```

**`TarBaby`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val utility = target.cyberdeck.activeUtilities.firstOrNull { it.type.category == targetCategory }
        ?: return ActionResult.IcAttack("TarBaby: no $targetCategory utility to trap on ${target.name}")
    val result = CombatResolver.resolveTarBaby(target, this, utility, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    if (result.bothCrashed) context.removeIc(this)
    return ActionResult.IcAttack("TarBaby trapped utility on ${target.name}")
}
```

#### Gray IC subclasses

**`Blaster`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val attacker = CombatResolver.icAttackParticipant(this, context.securityCode)
    val result = CombatResolver.resolveBlaster(attacker, target.asDefenderParticipant(), diceRoller)
    if (result is AttackResult.Hit) {
        val dmg = CombatResolver.applyIcDamage(target, result, this, diceRoller)
        val finalDecker = if (dmg.dumpShockTriggered)
            CombatResolver.resolveBlasterMpcpTest(dmg.updatedDecker, this, diceRoller)
        else dmg.updatedDecker
        context.updateDecker(target, finalDecker)
        return ActionResult.IcAttack("Blaster hit ${target.name}: ${dmg.iconDamage}")
    }
    return ActionResult.IcAttack("Blaster missed ${target.name}")
}
```

**`Ripper`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveRipper(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Ripper reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
}
```

**`Sparky`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val attacker = CombatResolver.icAttackParticipant(this, context.securityCode)
    val result = CombatResolver.resolveSparky(attacker, target.asDefenderParticipant(), diceRoller)
    if (result is AttackResult.Hit) {
        val dmg = CombatResolver.applyIcDamage(target, result, this, diceRoller)
        val finalDecker = if (dmg.dumpShockTriggered) {
            val (afterMpcp, sparkySuccesses) = CombatResolver.resolveSparkyMpcpTest(dmg.updatedDecker, this, diceRoller)
            CombatResolver.resolveSparkyBodyDamage(afterMpcp, this, sparkySuccesses, diceRoller)
        } else dmg.updatedDecker
        context.updateDecker(target, finalDecker)
        return ActionResult.IcAttack("Sparky hit ${target.name}: ${dmg.iconDamage}")
    }
    return ActionResult.IcAttack("Sparky missed ${target.name}")
}
```

**`TarPit`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val utility = target.cyberdeck.activeUtilities.firstOrNull { it.type.category == targetCategory }
        ?: return ActionResult.IcAttack("TarPit: no $targetCategory utility to trap on ${target.name}")
    val result = CombatResolver.resolveTarPit(target, this, utility, diceRoller)
    if (result.bothCrashed) {
        val afterMpcp = CombatResolver.resolveTarPitMpcpTest(result.updatedDecker, this, utility, diceRoller)
        context.updateDecker(target, afterMpcp)
        context.removeIc(this)
    } else {
        context.updateDecker(target, result.updatedDecker)
    }
    return ActionResult.IcAttack("TarPit trapped utility on ${target.name}")
}
```

#### Black IC subclasses

**`LethalBlackIC`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveLethalBlackIc(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Lethal Black IC hit ${target.name}: ${result.iconDamage}")
}
```

**`NonLethalBlackIC`**
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveNonLethalBlackIc(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Non-Lethal Black IC hit ${target.name}: ${result.iconDamage}")
}
```

---

#### Helper extension on `Decker`

`Decker.asDefenderParticipant()` — convenience extension used by IC subclasses to build a `DefenderParticipant`:

```kotlin
fun Decker.asDefenderParticipant(): DefenderParticipant = DefenderParticipant(
    bod = persona!!.bod,
    armorCurrentRating = 0,
    personaStatus = persona.status,
    securityCode = (currentLocation as MatrixLocation.OnHost).host.securityRating.code
)
```

This extension lives in `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt`.

---

## Available Actions — Location-Context Filtering

`Decker.availableActions()` must filter the returned list by the decker's current location context. Two operation sets apply:

- **Host context** (`currentLocation is MatrixLocation.OnHost`): full system operation table — `ANALYZE_HOST`, `LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_IC`, `ANALYZE_IC`, `DOWNLOAD_DATA`, `EDIT_FILE`, `CONTROL_SLAVE`, `DECRYPT_*`, etc.
- **Grid context** (`currentLocation is OnLTG / OnRTG / OnPLTG`): only the subset valid on a grid — `RELOCATE_ICON`, `NULL_OPERATION`, `LOCATE_ACCESS_NODE` (M-07: available from RTG), `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`.

Operations requiring host context must not appear in `availableActions` when the decker is on a grid node. The filter is applied inside `Decker.availableActions()`, not at the server dispatch point — offering an action and returning a failure is confusing to the player.

This rule is additive to the existing deferral rule in `prd_game.md`: SWAP_MEMORY and LOCATE_DECKER are excluded regardless of context.

---

## Verification

```
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test"
```

Implemented and passing in `src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt` (33 tests):
- `GameContext`: `unauthorizedDeckerInNode`, `unauthorizedDeckerInHost`, `updateDecker`, `removeIc`
- `Decker.action`: returns `DeckerAction`
- IC action dispatch: all 11 subtypes (`Killer`, `Crippler`, `Probe`, `Scramble`, `TarBaby`, `Blaster`, `Ripper`, `Sparky`, `TarPit`, `LethalBlackIC`, `NonLethalBlackIC`)
- Move vs. no-move logic for proactive and reactive IC
- `Game.runCombatTurn`: initiative ordering and decrement-by-10 loop
- `asDefenderParticipant`: correct participant fields from decker state
