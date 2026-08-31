package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.programs.Utility
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
     *
     * Cyberterminal users have all utility ratings reduced by 1 at test resolution time (CT-03).
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
            decker.cyberdeck.activeUtilities
                .firstOrNull { it.type == utilityType }
                ?.let { effectiveRating(it, decker.cyberdeck) }
                ?: 0
        else
            0
        val effectiveTn = maxOf(2, accessRating - utilityRating)

        val deckerResult = diceRoller.roll(decker.computerSkill, effectiveTn)
        logger.info { "[${decker.name}] Decker rolls: ${decker.computerSkill} dice vs TN $effectiveTn (base=$accessRating, ${operation.name} modifier=$utilityRating) → ${deckerResult.successes} successes" }

        val detectionFactor = decker.effectiveDetectionFactor
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

    /**
     * Resolves a Null Operation. The inactivity bonus is added to the host's Security Value
     * (not to the decker's TN). PRD: SO individual table (Null Operation)
     */
    fun resolveNullOperation(
        decker: Decker,
        host: Host,
        inactivitySeconds: Int,
        diceRoller: DiceRoller
    ): SystemTestOutcome {
        val bonus = NullOperationModifier.totalBonusForDuration(inactivitySeconds)
        val effectiveSecurityValue = host.securityRating.value + bonus
        logger.info { "[${decker.name}] Null Operation: inactivity=${inactivitySeconds}s, SV bonus=+$bonus → effectiveSV=$effectiveSecurityValue" }
        return resolve(decker, SystemOperation.NULL_OPERATION, host.subsystemRatings.control, effectiveSecurityValue, diceRoller)
    }

    /**
     * Resolves one step of an interrogation operation and updates the accumulated success state.
     * Utility reduction is applied first, then [queryPrecision] modifies the result (SO-07).
     * Returns the raw test outcome and the updated [InterrogationState].
     * PRD: SO-05 through SO-09
     */
    fun resolveInterrogation(
        decker: Decker,
        operation: SystemOperation,
        host: Host,
        state: InterrogationState,
        queryPrecision: QueryPrecision,
        diceRoller: DiceRoller
    ): Pair<SystemTestOutcome, InterrogationState> {
        val baseSubsystemRating = host.subsystemRatings.get(operation.testType)
        // Reduce TN by utility rating first, then apply query-precision modifier; clamp to ≥ 2 at each step
        val utilityRating = if (operation.utility != null)
            decker.cyberdeck.activeUtilities
                .firstOrNull { it.type == operation.utility }
                ?.let { effectiveRating(it, decker.cyberdeck) } ?: 0
        else 0
        val clampedBase = maxOf(2, baseSubsystemRating - utilityRating)
        val adjustedTn = maxOf(2, clampedBase + queryPrecision.modifier)

        val deckerResult = diceRoller.roll(decker.computerSkill, adjustedTn)
        logger.info { "[${decker.name}] Interrogation ${operation.name}: TN=$adjustedTn (base=$baseSubsystemRating precision=${queryPrecision.modifier} utility=$utilityRating) → ${deckerResult.successes} successes" }

        val hostResult = diceRoller.roll(host.securityRating.value, decker.effectiveDetectionFactor)
        logger.info { "[${decker.name}] Host Security Test: ${host.securityRating.value} dice vs DF=${decker.effectiveDetectionFactor} → ${hostResult.successes} successes" }

        val outcome = SystemTestOutcome(
            deckerSuccesses = deckerResult.successes,
            hostSuccesses = hostResult.successes,
            deckerWins = deckerResult.successes >= hostResult.successes
        )
        val newState = state.copy(accumulatedSuccesses = state.accumulatedSuccesses + maxOf(0, deckerResult.successes - hostResult.successes))
        logger.info { "[${decker.name}] Interrogation accumulated successes: ${newState.accumulatedSuccesses}" }
        return Pair(outcome, newState)
    }

    /**
     * Returns the effective rating of [utility] for TN reduction.
     * Cyberterminal users have all utility ratings reduced by 1 (CT-03), floored at 0.
     */
    internal fun effectiveRating(utility: Utility, deck: Cyberdeck): Int =
        if (deck.immuneToDumpShock) maxOf(0, utility.currentRating - 1)
        else utility.currentRating
}
