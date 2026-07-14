package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

object SystemTestResolver {

    private val logger = KotlinLogging.logger {}

    /**
     * Resolves one Success Contest:
     *   - Decker rolls [computerSkill] dice vs [accessRating] (reduced by loaded Deception utility, min 2).
     *   - Host rolls [hostSecurityValue] dice vs decker's Detection Factor.
     * Returns a [SystemTestOutcome]; host successes must be added to the security tally by the caller.
     */
    fun resolve(
        decker: Decker,
        accessRating: Int,
        hostSecurityValue: Int,
        diceRoller: DiceRoller
    ): SystemTestOutcome {
        val deceptionRating = decker.cyberdeck.activeUtilities
            .firstOrNull { it.type == UtilityType.DECEPTION }?.rating ?: 0
        val effectiveTn = maxOf(2, accessRating - deceptionRating)

        val deckerResult = diceRoller.roll(decker.computerSkill, effectiveTn)
        logger.info { "[${decker.name}] Decker rolls: ${decker.computerSkill} dice vs TN $effectiveTn → ${deckerResult.successes} successes" }

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
