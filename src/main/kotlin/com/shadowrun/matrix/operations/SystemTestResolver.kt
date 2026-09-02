package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.Grid
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

object SystemTestResolver {

    private val logger = KotlinLogging.logger {}

    /**
     * Resolves one Success Contest:
     *   - Decker rolls [computerSkill] + [hackingPoolDice] dice vs [accessRating] (reduced by the utility associated
     *     with [operation] if it is fully active in active memory, min TN 2).
     *   - Host rolls [hostSecurityValue] dice vs decker's Detection Factor.
     * Returns a [SystemTestOutcome]; host successes must be added to the security tally by the caller.
     * PRD: CD-14, CD-15, CC-01
     *
     * Cyberterminal users have all utility ratings reduced by 1 at test resolution time (CT-03).
     */
    fun resolve(
        decker: Decker,
        operation: SystemOperation,
        accessRating: Int,
        hostSecurityValue: Int,
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
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

        val totalDeckerDice = decker.computerSkill + hackingPoolDice
        val deckerResult = diceRoller.roll(totalDeckerDice, effectiveTn)
        logger.info { "[${decker.name}] Decker rolls: $totalDeckerDice dice vs TN $effectiveTn (base=$accessRating, ${operation.name} modifier=$utilityRating, hackingPool=$hackingPoolDice) → ${deckerResult.successes} successes" }

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
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
    ): SystemTestOutcome {
        val bonus = NullOperationModifier.totalBonusForDuration(inactivitySeconds)
        val effectiveSecurityValue = host.securityRating.value + bonus
        logger.info { "[${decker.name}] Null Operation: inactivity=${inactivitySeconds}s, SV bonus=+$bonus → effectiveSV=$effectiveSecurityValue" }
        return resolve(decker, SystemOperation.NULL_OPERATION, host.subsystemRatings.control, effectiveSecurityValue, diceRoller, hackingPoolDice)
    }

    fun resolveNullOperation(
        decker: Decker,
        grid: Grid,
        inactivitySeconds: Int,
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
    ): SystemTestOutcome {
        val bonus = NullOperationModifier.totalBonusForDuration(inactivitySeconds)
        val effectiveSecurityValue = grid.securityRating.value + bonus
        logger.info { "[${decker.name}] Null Operation: inactivity=${inactivitySeconds}s, SV bonus=+$bonus → effectiveSV=$effectiveSecurityValue" }
        return resolve(decker, SystemOperation.NULL_OPERATION, grid.subsystemRatings.control, effectiveSecurityValue, diceRoller, hackingPoolDice)
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
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
    ): Pair<SystemTestOutcome, InterrogationState> =
        resolveInterrogationCore(
            decker, operation,
            baseSubsystemRating = host.subsystemRatings.get(
                requireNotNull(operation.testType) {
                    "${operation.name} has a dynamic test type — pass an explicit subsystem rating instead of using resolveInterrogation"
                }
            ),
            securityValue = host.securityRating.value,
            state, queryPrecision, diceRoller, hackingPoolDice
        )

    fun resolveInterrogation(
        decker: Decker,
        operation: SystemOperation,
        grid: Grid,
        state: InterrogationState,
        queryPrecision: QueryPrecision,
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
    ): Pair<SystemTestOutcome, InterrogationState> =
        resolveInterrogationCore(
            decker, operation,
            baseSubsystemRating = grid.subsystemRatings.get(
                requireNotNull(operation.testType) {
                    "${operation.name} has a dynamic test type — pass an explicit subsystem rating instead of using resolveInterrogation"
                }
            ),
            securityValue = grid.securityRating.value,
            state, queryPrecision, diceRoller, hackingPoolDice
        )

    private fun resolveInterrogationCore(
        decker: Decker,
        operation: SystemOperation,
        baseSubsystemRating: Int,
        securityValue: Int,
        state: InterrogationState,
        queryPrecision: QueryPrecision,
        diceRoller: DiceRoller,
        hackingPoolDice: Int = 0
    ): Pair<SystemTestOutcome, InterrogationState> {
        val utilityRating = if (operation.utility != null)
            decker.cyberdeck.activeUtilities
                .firstOrNull { it.type == operation.utility }
                ?.let { effectiveRating(it, decker.cyberdeck) } ?: 0
        else 0
        val clampedBase = maxOf(2, baseSubsystemRating - utilityRating)
        val adjustedTn = maxOf(2, clampedBase + queryPrecision.modifier)

        val totalDeckerDice = decker.computerSkill + hackingPoolDice
        val deckerResult = diceRoller.roll(totalDeckerDice, adjustedTn)
        logger.info { "[${decker.name}] Interrogation ${operation.name}: TN=$adjustedTn (base=$baseSubsystemRating precision=${queryPrecision.modifier} utility=$utilityRating hackingPool=$hackingPoolDice) → ${deckerResult.successes} successes" }

        val hostResult = diceRoller.roll(securityValue, decker.effectiveDetectionFactor)
        logger.info { "[${decker.name}] Host Security Test: $securityValue dice vs DF=${decker.effectiveDetectionFactor} → ${hostResult.successes} successes" }

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
        if (deck.isCyberterminal) maxOf(0, utility.currentRating - 1)
        else utility.currentRating
}
