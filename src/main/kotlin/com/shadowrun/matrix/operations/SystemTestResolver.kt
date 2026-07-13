package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller

object SystemTestResolver {

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

        val detectionFactor = decker.detectionFactor
        val hostResult = diceRoller.roll(hostSecurityValue, detectionFactor)

        return SystemTestOutcome(
            deckerSuccesses = deckerResult.successes,
            hostSuccesses = hostResult.successes,
            deckerWins = deckerResult.successes >= hostResult.successes
        )
    }
}
