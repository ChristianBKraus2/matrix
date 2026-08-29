package com.shadowrun.matrix.game

import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

class Game(
    val context: GameContext,
    val diceRoller: DiceRoller,
    val inCombat: Boolean
) {
    private val logger = KotlinLogging.logger {}

    suspend fun runOutOfCombatTurn() {
        for (decker in context.deckers.toList()) {
            try { decker.action(context, diceRoller) }
            catch (e: Exception) { logger.error(e) { "Out-of-combat action error for ${decker.name}" } }
        }
    }

    suspend fun runCombatTurn() {
        val states = try {
            buildInitiativeList().toMutableList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to build initiative list — aborting combat turn" }
            return
        }
        while (states.any { it.currentInitiative > 0 }) {
            val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative } ?: break
            val idx = states.indexOf(state)
            try { state.icon.action(context, diceRoller) }
            catch (e: Exception) { logger.error(e) { "Combat action error" } }
            states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
        }
    }

    private fun buildInitiativeList(): List<ActiveIconState> {
        val list = mutableListOf<ActiveIconState>()
        for (decker in context.deckers) {
            val init = CombatResolver.rollDeckerInitiative(decker, meatworldComm = false, diceRoller)
            list += ActiveIconState(decker, init.score)
        }
        for (ic in context.activeIc) {
            val init = CombatResolver.rollIcInitiative(ic, context.securityCode, diceRoller)
            list += ActiveIconState(ic, init.score)
        }
        return list.sortedByDescending { it.currentInitiative }
    }
}
