package com.shadowrun.matrix.game

import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.utility.DiceRoller

class Game(
    val context: GameContext,
    val diceRoller: DiceRoller,
    val inCombat: Boolean
) {
    fun runOutOfCombatTurn() {
        for (decker in context.deckers.toList()) {
            decker.action(context, diceRoller)
        }
    }

    fun runCombatTurn() {
        val states = buildInitiativeList().toMutableList()
        while (states.any { it.currentInitiative > 0 }) {
            val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative } ?: break
            val idx = states.indexOf(state)
            state.icon.action(context, diceRoller)
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
