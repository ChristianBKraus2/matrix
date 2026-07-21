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
    fun action(context: GameContext, diceRoller: DiceRoller): ActionResult
}
```

`action()` applies its result directly to `GameContext` and returns an `ActionResult` describing what happened.

Initiative rolls are **not** part of this interface because IC initiative requires `securityCode` (an IC-specific concern). The `Game` class calls `CombatResolver.rollDeckerInitiative()` and `CombatResolver.rollIcInitiative()` directly during the initiative phase, using `when (icon) { is Decker -> ...; is IC -> ... }`.

---

### `ActionResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/game/ActionResult.kt`

```kotlin
sealed class ActionResult {
    data class IcAttack(val message: String) : ActionResult()
    data class IcMoved(val message: String)  : ActionResult()
    object NoTarget                          : ActionResult()
    object DeckerAction                      : ActionResult()
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
    val host: Host,
    val securityCode: SecurityCode,
    val deckers: MutableList<Decker>,
    val activeIc: MutableList<IC>
) {
    fun unauthorizedDeckerInNode(node: Node): Decker?
    fun unauthorizedDeckerInHost(): Decker?
    fun updateDecker(old: Decker, new: Decker)
    fun removeIc(ic: IC)
}
```

- `unauthorizedDeckerInNode(node)` — returns the first decker whose `persona.currentNode == node` and `persona.status == INTRUDING`, or `null`.
- `unauthorizedDeckerInHost()` — returns the first intruding decker regardless of node, or `null`.
- `updateDecker(old, new)` — replaces `old` with `new` in `deckers`. Used by IC `action()` to apply damage or other state changes to a decker after a `CombatResolver` call.
- `removeIc(ic)` — removes a crashed IC from `activeIc`.

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
    val diceRoller: DiceRoller,
    val inCombat: Boolean
)
```

#### Out-of-combat mode (`inCombat = false`)

```kotlin
fun runOutOfCombatTurn()
```

Iterates `context.deckers` and calls `decker.action(context, diceRoller)` once per decker. No IC participation.

#### In-combat mode (`inCombat = true`)

```kotlin
fun runCombatTurn()
```

1. **Roll initiative** for every active icon. For deckers call `CombatResolver.rollDeckerInitiative(decker, meatworldComm = false, diceRoller)`; for ICs call `CombatResolver.rollIcInitiative(ic, context.securityCode, diceRoller)`. Build a `MutableList<ActiveIconState>` sorted descending by `currentInitiative`.

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

    override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult =
        ActionResult.DeckerAction
}
```

`DeckerAction` is a placeholder. A future callback to the user will be added here.

---

### `IC` — implements `ActiveIcon` (abstract)

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt`

`IC` declares `action()` abstract. Each leaf subclass implements it. Two protected helpers on the base handle the shared target-finding and move logic so each subclass only writes its CombatResolver call.

```kotlin
sealed class IC(...) : ActiveIcon {

    abstract override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult

    // Returns the intruding decker this IC should act against, or null if none found.
    // Proactive IC searches the whole host; reactive IC searches only its guardedNode.
    protected fun findTarget(context: GameContext): Decker? =
        if (guardedNode != null) context.unauthorizedDeckerInNode(guardedNode)
        else context.unauthorizedDeckerInHost()

    // If the target is not already in this IC's node, move toward them (proactive only).
    // Returns IcMoved if a move was needed, null if the IC is already co-located with the target.
    protected fun moveIfNeeded(target: Decker, context: GameContext): ActionResult.IcMoved? {
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
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
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
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveCrippler(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Crippler reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
}
```

**`Probe`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val tallyPoints = CombatResolver.resolveProbe(this, target, diceRoller)
    // tally increase applied by caller via context.host
    return ActionResult.IcAttack("Probe added $tallyPoints tally against ${target.name}")
}
```

**`Scramble`**

Scramble triggers on logon (reactive gate), not as an active attack. Returns `NoTarget` — no combat action.
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult = ActionResult.NoTarget
```

**`TarBaby`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val utility = target.cyberdeck.activeUtilities.firstOrNull()
        ?: return ActionResult.IcAttack("TarBaby: no active utility to trap on ${target.name}")
    val result = CombatResolver.resolveTarBaby(target, this, utility, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("TarBaby trapped utility on ${target.name}")
}
```

#### Gray IC subclasses

**`Blaster`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val attacker = CombatResolver.icAttackParticipant(this, context.securityCode)
    val result = CombatResolver.resolveBlaster(attacker, target.asDefenderParticipant(), diceRoller)
    if (result is AttackResult.Hit) {
        val updated = CombatResolver.resolveBlasterMpcpTest(target, this, diceRoller)
        context.updateDecker(target, updated)
        return ActionResult.IcAttack("Blaster hit ${target.name}")
    }
    return ActionResult.IcAttack("Blaster missed ${target.name}")
}
```

**`Ripper`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveRipper(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Ripper reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
}
```

**`Sparky`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val attacker = CombatResolver.icAttackParticipant(this, context.securityCode)
    val result = CombatResolver.resolveSparky(attacker, target.asDefenderParticipant(), diceRoller)
    if (result is AttackResult.Hit) {
        val (updated, _) = CombatResolver.resolveSparkyMpcpTest(target, this, diceRoller)
        context.updateDecker(target, updated)
        return ActionResult.IcAttack("Sparky hit ${target.name}")
    }
    return ActionResult.IcAttack("Sparky missed ${target.name}")
}
```

**`TarPit`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val utility = target.cyberdeck.activeUtilities.firstOrNull()
        ?: return ActionResult.IcAttack("TarPit: no active utility to trap on ${target.name}")
    val result = CombatResolver.resolveTarPit(target, this, utility, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("TarPit trapped utility on ${target.name}")
}
```

#### Black IC subclasses

**`LethalBlackIC`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    val target = findTarget(context) ?: return ActionResult.NoTarget
    moveIfNeeded(target, context)?.let { return it }
    val result = CombatResolver.resolveLethalBlackIc(target, this, context.securityCode, diceRoller)
    context.updateDecker(target, result.updatedDecker)
    return ActionResult.IcAttack("Lethal Black IC hit ${target.name}: ${result.iconDamage}")
}
```

**`NonLethalBlackIC`**
```kotlin
override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
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

## Verification

```
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test"
```

Unit tests to add (separate from this design doc):
- `GameTest`: initiative ordering with multiple icons, decrement-by-10 loop, turn boundary
- `GameContextTest`: `unauthorizedDeckerInNode`, `unauthorizedDeckerInHost`, `updateDecker`, `removeIc`
- `IcActionTest`: `action()` dispatch for each IC subtype — verify `context.deckers` is updated correctly and correct `ActionResult` subtype is returned
