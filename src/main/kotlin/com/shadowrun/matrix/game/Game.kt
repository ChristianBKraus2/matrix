package com.shadowrun.matrix.game

import com.shadowrun.matrix.common.IcBehavior
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
        val (meatworldDeckers, proactiveStates) = try {
            buildCombatSets()
        } catch (e: Exception) {
            logger.error(e) { "Failed to build initiative list — aborting combat turn" }
            return
        }

        // Main initiative loop: proactive icons only (CC-01, CC-02)
        val states = proactiveStates.toMutableList()
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

        // Physical segment: meatworld-comm deckers act after all Matrix actions (CC-04)
        for (decker in meatworldDeckers) {
            try { decker.action(context, diceRoller) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error(e) { "Meatworld-comm decker action error for ${decker.name}" }
            }
        }

        // End-of-turn: reactive IC act after all decker actions (CC-02)
        for (ic in context.activeIc.filter { it.behavior == IcBehavior.REACTIVE }) {
            try { ic.action(context, diceRoller) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error(e) { "Reactive IC end-of-turn action error for ${ic.name}" }
            }
        }

        // Advance utility upload timers and track state for each decker (CD-11, CC-33)
        for (decker in context.deckers.toList()) {
            context.updateDecker(decker, decker.advanceCombatTurn())
        }
    }

    /**
     * Returns (meatworldDeckers, proactiveInitiativeList).
     * Reactive IC are excluded from the initiative list — they act at end-of-turn (CC-02).
     * Meatworld-comm deckers are excluded from the initiative list — they act in the physical segment (CC-04).
     */
    private fun buildCombatSets(): Pair<List<Decker>, List<ActiveIconState>> {
        val meatworldDeckers = context.deckers.filter { it.meatworldComm }
        val proactiveIcons: List<ActiveIcon> =
            context.deckers.filter { !it.meatworldComm } +
            context.activeIc.filter { it.behavior != IcBehavior.REACTIVE }
        val proactiveStates = proactiveIcons.map { icon ->
            ActiveIconState(icon, icon.initiative(context, diceRoller).score)
        }.sortedByDescending { it.currentInitiative }
        return Pair(meatworldDeckers, proactiveStates)
    }
}
