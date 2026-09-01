package com.shadowrun.matrix.ic

import com.shadowrun.matrix.combat.AttackResult
import com.shadowrun.matrix.combat.CombatInitiative
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.common.IcBehavior
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.asDefenderParticipant
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.utility.DiceRoller

sealed class IC(
    val name: String,
    val rating: Int,
    val behavior: IcBehavior,
    val guardedNode: Node? = null
) : ActiveIcon {

    abstract override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult

    override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
        CombatResolver.rollIcInitiative(this, context.securityCode, diceRoller)

    fun initiativeDice(securityCode: SecurityCode): Int = when (securityCode) {
        SecurityCode.BLUE   -> 1
        SecurityCode.GREEN  -> 2
        SecurityCode.ORANGE -> 3
        SecurityCode.RED    -> 4
    }

    protected fun findTarget(context: GameContext): Decker? =
        if (guardedNode != null) {
            context.unauthorizedDeckerInNode(guardedNode) ?: context.unauthorizedDeckerInHost()
        } else {
            context.unauthorizedDeckerInHost()
        }

    protected fun moveIfNeeded(target: Decker, context: GameContext): ActionResult.IcMoved? {
        if (guardedNode == null) return null
        val targetNode = target.persona?.currentNode ?: return null
        if (targetNode == guardedNode) return null
        if (behavior == IcBehavior.REACTIVE) return null
        return ActionResult.IcMoved("$name moved to $targetNode")
    }
}

sealed class WhiteIC(name: String, rating: Int, behavior: IcBehavior, guardedNode: Node? = null) :
    IC(name, rating, behavior, guardedNode)

class Crippler(rating: Int, val targetAttribute: PersonaAttributeType, guardedNode: Node? = null) :
    WhiteIC("Crippler-${targetAttribute.name}", rating, IcBehavior.PROACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val result = CombatResolver.resolveCrippler(target, this, context.host.securityRating.value, diceRoller)
        context.updateDecker(target, result.updatedDecker)
        return ActionResult.IcAttack("Crippler reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
    }
}

class Killer(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Killer", rating, IcBehavior.PROACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val attacker = CombatResolver.icAttackParticipant(this, context.securityCode, context.host.securityRating.value)
        val result = CombatResolver.resolveKiller(attacker, target.asDefenderParticipant(), diceRoller)
        if (result is AttackResult.Hit) {
            val dmg = CombatResolver.applyIcDamage(target, result, this, diceRoller)
            context.updateDecker(target, dmg.updatedDecker)
            return ActionResult.IcAttack("Killer hit ${target.name}: ${dmg.iconDamage}")
        }
        return ActionResult.IcAttack("Killer missed ${target.name}")
    }
}

class Probe(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Probe", rating, IcBehavior.REACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val tallyPoints = CombatResolver.resolveProbe(this, target, diceRoller)
        if (tallyPoints > 0) context.addToSecurityTally(tallyPoints)
        return ActionResult.IcAttack("Probe added $tallyPoints tally against ${target.name}")
    }
}

/**
 * Scramble is a reactive IC that does not perform proactive actions. It responds to decker
 * operations (e.g. destructing a file) via the game engine, not through the standard action
 * turn. This action implementation is intentionally a no-op.
 */
class Scramble(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Scramble", rating, IcBehavior.REACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult = ActionResult.NoTarget
}

class TarBaby(rating: Int, val targetCategory: UtilityCategory = UtilityCategory.OPERATIONAL, guardedNode: Node? = null) :
    WhiteIC("Tar Baby", rating, IcBehavior.REACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        // ICC-05: passive utilities (Armor, Sleaze) are not valid targets
        val passiveTypes = setOf(UtilityType.ARMOR, UtilityType.SLEAZE)
        val utility = target.cyberdeck.activeUtilities
            .firstOrNull { it.type.category == targetCategory && it.type !in passiveTypes }
            ?: return ActionResult.IcAttack("TarBaby: no $targetCategory utility to trap on ${target.name}")
        val result = CombatResolver.resolveTarBaby(target, this, utility, diceRoller)
        context.updateDecker(target, result.updatedDecker)
        if (result.bothCrashed) context.removeIc(this)
        return ActionResult.IcAttack("TarBaby trapped utility on ${target.name}")
    }
}

sealed class GrayIC(name: String, rating: Int, behavior: IcBehavior, guardedNode: Node? = null) :
    IC(name, rating, behavior, guardedNode)

class Blaster(rating: Int, guardedNode: Node? = null) :
    GrayIC("Blaster", rating, IcBehavior.PROACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val attacker = CombatResolver.icAttackParticipant(this, context.securityCode, context.host.securityRating.value)
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
}

class Ripper(rating: Int, val targetAttribute: PersonaAttributeType, guardedNode: Node? = null) :
    GrayIC("Ripper-${targetAttribute.name}", rating, IcBehavior.PROACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val result = CombatResolver.resolveRipper(target, this, context.host.securityRating.value, diceRoller)
        var finalDecker = result.updatedDecker
        if ((finalDecker.persona?.attribute(result.targetAttribute) ?: 0) == 0) {
            finalDecker = CombatResolver.resolveRipperMpcpTest(finalDecker, this, diceRoller)
        }
        context.updateDecker(target, finalDecker)
        return ActionResult.IcAttack("Ripper reduced ${target.name} ${result.targetAttribute} by ${result.reduction}")
    }
}

class Sparky(rating: Int, guardedNode: Node? = null) :
    GrayIC("Sparky", rating, IcBehavior.PROACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val attacker = CombatResolver.icAttackParticipant(this, context.securityCode, context.host.securityRating.value)
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
}

class TarPit(rating: Int, val targetCategory: UtilityCategory = UtilityCategory.OPERATIONAL, guardedNode: Node? = null) :
    GrayIC("Tar Pit", rating, IcBehavior.REACTIVE, guardedNode) {

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        // ICC-05/ICC-09: passive utilities (Armor, Sleaze) are not valid targets
        val passiveTypes = setOf(UtilityType.ARMOR, UtilityType.SLEAZE)
        val utility = target.cyberdeck.activeUtilities
            .firstOrNull { it.type.category == targetCategory && it.type !in passiveTypes }
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
}

sealed class BlackIC(name: String, rating: Int, guardedNode: Node? = null) :
    IC(name, rating, IcBehavior.PROACTIVE, guardedNode)

class LethalBlackIC(rating: Int, guardedNode: Node? = null) :
    BlackIC("Lethal Black IC", rating, guardedNode) {

    fun withRatingBonus(bonus: Int) = LethalBlackIC(rating + bonus, guardedNode)

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val result = CombatResolver.resolveLethalBlackIc(target, this, context.securityCode, diceRoller)
        context.updateDecker(target, result.updatedDecker)
        if (result.personaOnlyCrashed) {
            context.removeIc(this)
            context.addIc(withRatingBonus(2))
        }
        return ActionResult.IcAttack("Lethal Black IC hit ${target.name}: ${result.iconDamage}")
    }
}

class NonLethalBlackIC(rating: Int, guardedNode: Node? = null) :
    BlackIC("Non-Lethal Black IC", rating, guardedNode) {

    fun withRatingBonus(bonus: Int) = NonLethalBlackIC(rating + bonus, guardedNode)

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val target = findTarget(context) ?: return ActionResult.NoTarget
        moveIfNeeded(target, context)?.let { return it }
        val result = CombatResolver.resolveNonLethalBlackIc(target, this, context.securityCode, diceRoller)
        context.updateDecker(target, result.updatedDecker)
        if (result.personaOnlyCrashed) {
            context.removeIc(this)
            context.addIc(withRatingBonus(2))
        }
        return ActionResult.IcAttack("Non-Lethal Black IC hit ${target.name}: ${result.iconDamage}")
    }
}
