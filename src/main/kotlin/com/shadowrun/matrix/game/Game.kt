package com.shadowrun.matrix.game

import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.advanceCombatTurn
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

class Game(
    val context: GameContext,
    val diceRoller: DiceRoller
) {
    private val logger = KotlinLogging.logger {}

    suspend fun runOutOfCombatTurn() {
        for (decker in context.deckers.toList()) {
            val count = decker.persona?.let { decker.actionsPerTurn } ?: continue
            repeat(count) {
                try { decker.action(context, diceRoller) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error(e) { "Out-of-combat action error for ${decker.name}" }
                }
            }
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
            val state = states.filter { it.currentInitiative > 0 }.maxBy { it.currentInitiative }
            val idx = states.indexOf(state)
            try { state.icon.action(context, diceRoller) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error(e) { "Combat action error" }
            }
            states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
        }
        // Advance utility upload timers and track state for each decker (CD-11, CC-33)
        for (decker in context.deckers.toList()) {
            context.updateDecker(decker, decker.advanceCombatTurn())
        }
    }

    private fun buildInitiativeList(): List<ActiveIconState> {
        val icons: List<ActiveIcon> = context.deckers.toList() + context.activeIc.toList()
        return icons.map { icon ->
            ActiveIconState(icon, icon.initiative(context, diceRoller).score)
        }.sortedByDescending { it.currentInitiative }
    }
}
