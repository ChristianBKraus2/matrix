package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

object SystemTestResolver {

    private val logger = KotlinLogging.logger {}

    /**
     * Resolves one Success Contest:
     *   - Decker rolls [computerSkill] dice vs [accessRating] (reduced by the utility associated
     *     with [operation] if it is fully active in active memory, min TN 2).
     *   - Host rolls [hostSecurityValue] dice vs decker's Detection Factor.
     * Returns a [SystemTestOutcome]; host successes must be added to the security tally by the caller.
     * PRD: CD-14, CD-15
     */
    fun resolve(
        decker: Decker,
        operation: SystemOperation,
        accessRating: Int,
        hostSecurityValue: Int,
        diceRoller: DiceRoller
    ): SystemTestOutcome {
        val utilityType = operation.utility
        val utilityRating = if (utilityType != null)
            decker.cyberdeck.activeUtilities.firstOrNull { it.type == utilityType }?.currentRating ?: 0
        else
            0
        val effectiveTn = maxOf(2, accessRating - utilityRating)

        val deckerResult = diceRoller.roll(decker.computerSkill, effectiveTn)
        logger.info { "[${decker.name}] Decker rolls: ${decker.computerSkill} dice vs TN $effectiveTn (base=$accessRating, ${operation.name} modifier=$utilityRating) → ${deckerResult.successes} successes" }

        val detectionFactor = decker.detectionFactor
        val hostResult = diceRoller.roll(hostSecurityValue, detectionFactor)
        logger.info { "[${decker.name}] Host rolls: $hostSecurityValue dice vs TN $detectionFactor → ${hostResult.successes} successes" }

        val outcome = SystemTestOutcome(
            deckerSuccesses = deckerResult.successes,
            hostSuccesses = hostResult.successes,
            deckerWins = deckerResult.successes >= hostResult.successes
        )
        logger.info { "[${decker.name}] System Test outcome: ${if (outcome.deckerWins) "decker wins" else "host wins"} (${outcome.deckerSuccesses} vs ${outcome.hostSuccesses})" }
        return outcome
    }
}
