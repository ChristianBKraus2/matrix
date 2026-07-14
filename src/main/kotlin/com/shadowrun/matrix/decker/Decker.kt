package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.operations.SystemTestResolver
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

data class Decker(
    val name: String,
    val intelligence: Int,
    val body: Int,
    val willpower: Int,
    val reaction: Int,
    val computerSkill: Int,
    val deckingSpecialization: Boolean = false,
    val cyberdeck: Cyberdeck,
    val physicalConditionMonitor: ConditionMonitor = ConditionMonitor(),
    val mentalConditionMonitor: ConditionMonitor = ConditionMonitor(),
    val persona: Persona? = null,
    val jackpoint: Jackpoint? = null,
    val currentLocation: MatrixLocation? = null
) {
    val hackingPool: Int get() = (intelligence + cyberdeck.mcpRating) / 3

    /** Detection Factor = (Masking + Sleaze) / 2 rounded up; or Masking / 2 if no Sleaze loaded. */
    val detectionFactor: Int get() {
        val masking = cyberdeck.personaPrograms
            .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.MASKING }
            ?.rating ?: 0
        val sleaze = cyberdeck.activeUtilities
            .firstOrNull { it.type == UtilityType.SLEAZE }?.rating
        return cyberdeck.detectionFactor(masking, sleaze)
    }

    // ── Initial jack-in ──────────────────────────────────────────────────────────

    /**
     * Jack in via a telecom-class jackpoint and log onto [ltg].
     * Allowed jackpoint types: LEGAL_ACCESS, ILLEGAL_ACCESS, TELECOM, ILLEGAL_JUNCTION_BOX.
     * PRD: M-01, M-04, M-05
     */
    fun jackInToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] jackInToLtg → ${ltg.name}" }
        requireNotJackedIn()
        val jp = requireJackpoint()
        require(jp.type in LTG_JACKPOINT_TYPES) {
            "Jackpoint type ${jp.type} cannot be used to jack in to an LTG"
        }
        return performLogon(
            accessRating = ltg.subsystemRatings.access,
            securityValue = ltg.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { updatedTally ->
                MatrixLocation.OnLTG(ltg.copy(securityTally = ltg.securityTally + updatedTally))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] jackInToLtg succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] jackInToLtg failed: remaining at ${result.location.label()}" }
            }
        }
    }

    /**
     * Jack in via a host-direct jackpoint and log onto [host].
     * Allowed jackpoint types: WORKSTATION, CONSOLE, REMOTE_DEVICE.
     * The jackpoint must connect directly to [host].
     * PRD: M-02, M-04, M-05
     */
    fun jackInToHost(host: Host, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] jackInToHost → ${host.name}" }
        requireNotJackedIn()
        val jp = requireJackpoint()
        require(jp.type in HOST_JACKPOINT_TYPES) {
            "Jackpoint type ${jp.type} cannot be used to jack in directly to a host"
        }
        require(jp.connectsToHost == host) {
            "Jackpoint connects to a different host"
        }
        return performLogon(
            accessRating = host.subsystemRatings.access,
            securityValue = host.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { updatedTally ->
                MatrixLocation.OnHost(host.copy(securityTally = host.securityTally + updatedTally))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] jackInToHost succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] jackInToHost failed: remaining at ${result.location.label()}" }
            }
        }
    }

    // ── Grid navigation ──────────────────────────────────────────────────────────

    /**
     * Move from the current LTG to its parent RTG, or hop between RTGs (long-distance).
     * - From OnLTG: target must be the parent RTG of the current LTG.
     * - From OnRTG: target must appear in [currentRtg.connectedRtgs].
     * Moving to a different RTG resets the security tally on that RTG.
     * PRD: M-06, M-07, M-10
     */
    fun logonToRtg(rtg: RTG, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] logonToRtg → ${rtg.name} (from ${currentLocation.label()})" }
        requireJackedIn()
        when (val loc = currentLocation) {
            is MatrixLocation.OnLTG -> require(loc.ltg.parentRtg == rtg) {
                "Target RTG is not the parent of the current LTG"
            }
            is MatrixLocation.OnRTG -> require(loc.rtg.connectedRtgs.contains(rtg)) {
                "Target RTG is not connected to the current RTG"
            }
            else -> throw IllegalStateException("Cannot logon to RTG from $currentLocation")
        }
        return performLogon(
            accessRating = rtg.subsystemRatings.access,
            securityValue = rtg.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { hostTallyDelta ->
                // Tally starts fresh on the new RTG (M-10); only the successes from this logon attempt count.
                MatrixLocation.OnRTG(rtg.copy(securityTally = hostTallyDelta))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] logonToRtg succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] logonToRtg failed: remaining at ${result.location.label()}" }
            }
        }
    }

    /**
     * Move from an RTG to an attached LTG, or from a PLTG to a sibling LTG (M-08).
     * Security tally: same-RTG LTG switches do not reset the RTG tally (M-09).
     * PRD: M-06, M-07, M-08, M-09
     */
    fun logonToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] logonToLtg → ${ltg.name} (from ${currentLocation.label()})" }
        requireJackedIn()
        when (val loc = currentLocation) {
            is MatrixLocation.OnRTG -> require(loc.rtg.ltgs.contains(ltg)) {
                "Target LTG is not attached to the current RTG"
            }
            is MatrixLocation.OnPLTG -> Unit // PLTG supports all LTG operations (M-08)
            else -> throw IllegalStateException("Cannot logon to LTG from $currentLocation")
        }
        return performLogon(
            accessRating = ltg.subsystemRatings.access,
            securityValue = ltg.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { hostTallyDelta ->
                MatrixLocation.OnLTG(ltg.copy(securityTally = ltg.securityTally + hostTallyDelta))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] logonToLtg succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] logonToLtg failed: remaining at ${result.location.label()}" }
            }
        }
    }

    /**
     * Log onto a PLTG from the current LTG (M-06) or from another PLTG (M-08).
     * The accumulated RTG security tally carries over into the PLTG (M-11).
     * PRD: M-06, M-08, M-11, M-12
     */
    fun logonToPltg(pltg: PLTG, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] logonToPltg → ${pltg.name} (from ${currentLocation.label()})" }
        requireJackedIn()
        val inheritedTally: Int = when (val loc = currentLocation) {
            is MatrixLocation.OnLTG -> {
                require(loc.ltg.pltgs.contains(pltg)) {
                    "Target PLTG is not attached to the current LTG"
                }
                loc.ltg.parentRtg.securityTally // RTG tally carries over (M-11)
            }
            is MatrixLocation.OnPLTG -> 0
            else -> throw IllegalStateException("Cannot logon to PLTG from $currentLocation")
        }
        return performLogon(
            accessRating = pltg.subsystemRatings.access,
            securityValue = pltg.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { hostTallyDelta ->
                MatrixLocation.OnPLTG(pltg.copy(securityTally = inheritedTally + hostTallyDelta))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] logonToPltg succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] logonToPltg failed: remaining at ${result.location.label()}" }
            }
        }
    }

    /**
     * Log onto [host] from the current grid or from a directly-connected host.
     * Valid from: OnLTG (open-access host on that LTG), OnPLTG (any host in the PLTG),
     * OnHost (connected host reachable via topology — tiered or host-host).
     * Tiered topology: a second-tier host cannot jump directly to a sibling second-tier host (M-13).
     * PRD: M-06, M-13, M-14, M-15
     */
    fun logonToHost(host: Host, diceRoller: DiceRoller): LogonResult {
        logger.info { "[$name] logonToHost → ${host.name} (from ${currentLocation.label()})" }
        requireJackedIn()
        when (val loc = currentLocation) {
            is MatrixLocation.OnLTG -> require(loc.ltg.hosts.contains(host)) {
                "Host is not attached to the current LTG"
            }
            is MatrixLocation.OnPLTG -> require(loc.pltg.hosts.contains(host)) {
                "Host is not connected to the current PLTG"
            }
            is MatrixLocation.OnHost -> require(loc.host.connectedHosts.contains(host)) {
                "Host is not directly connected from the current host (check topology)"
            }
            else -> throw IllegalStateException("Cannot logon to a host from $currentLocation")
        }
        return performLogon(
            accessRating = host.subsystemRatings.access,
            securityValue = host.securityRating.value,
            diceRoller = diceRoller,
            buildLocation = { hostTallyDelta ->
                MatrixLocation.OnHost(host.copy(securityTally = host.securityTally + hostTallyDelta))
            }
        ).also { result ->
            when (result) {
                is LogonResult.Success -> logger.info { "[$name] logonToHost succeeded: now at ${result.location.label()}" }
                is LogonResult.Failure -> logger.warn { "[$name] logonToHost failed: remaining at ${result.location.label()}" }
            }
        }
    }

    // ── Logging off ──────────────────────────────────────────────────────────────

    /**
     * Attempt a Graceful Logoff (Complex Action, Access Test).
     * On success: clears persona and location, no dump shock.
     * On failure: falls back to jack-out with dump shock.
     * PRD: M-16
     */
    fun gracefulLogoff(diceRoller: DiceRoller): LogoffResult {
        logger.info { "[$name] gracefulLogoff attempt from ${currentLocation.label()}" }
        requireJackedIn()
        val (accessRating, securityValue) = accessRatingAndSecurityValue()
        val outcome = SystemTestResolver.resolve(this, accessRating, securityValue, diceRoller)
        return if (outcome.deckerWins) {
            LogoffResult.GracefulSuccess(copy(persona = null, currentLocation = null)).also {
                logger.info { "[$name] gracefulLogoff succeeded: traces cleared, no dump shock" }
            }
        } else {
            LogoffResult.JackOut(copy(persona = null, currentLocation = null), dumpShock = true).also {
                logger.warn { "[$name] gracefulLogoff failed: falling back to jack-out with dump shock" }
            }
        }
    }

    /**
     * Immediately jack out (Free Action). Always causes dump shock unless [pinnedByBlackIc] is false.
     * Throws [IllegalStateException] when the decker is pinned by Black IC.
     * PRD: M-17, M-18
     */
    fun jackOut(pinnedByBlackIc: Boolean = false): LogoffResult {
        logger.info { "[$name] jackOut (pinnedByBlackIc=$pinnedByBlackIc) from ${currentLocation.label()}" }
        requireJackedIn()
        check(!pinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }
        return LogoffResult.JackOut(copy(persona = null, currentLocation = null), dumpShock = true).also {
            logger.info { "[$name] jackOut complete: dumpShock=true" }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun requireJackpoint(): Jackpoint =
        checkNotNull(jackpoint) { "Decker has no jackpoint set" }

    private fun requireNotJackedIn() =
        check(persona == null && currentLocation == null) { "Decker is already jacked in" }

    private fun requireJackedIn() =
        check(currentLocation != null) { "Decker is not jacked in" }

    /** Run the System Test and return a LogonResult; [buildLocation] maps host tally delta → new MatrixLocation. */
    private fun performLogon(
        accessRating: Int,
        securityValue: Int,
        diceRoller: DiceRoller,
        buildLocation: (Int) -> MatrixLocation
    ): LogonResult {
        val outcome = SystemTestResolver.resolve(this, accessRating, securityValue, diceRoller)
        val newLocation = buildLocation(outcome.hostSuccesses)
        return if (outcome.deckerWins) {
            val newPersona = persona ?: Persona(
                bod = cyberdeck.personaPrograms
                    .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.BOD }?.rating ?: 0,
                evasion = cyberdeck.personaPrograms
                    .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.EVASION }?.rating ?: 0,
                masking = cyberdeck.personaPrograms
                    .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.MASKING }?.rating ?: 0,
                sensor = cyberdeck.personaPrograms
                    .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.SENSORS }?.rating ?: 0
            )
            LogonResult.Success(copy(persona = newPersona, currentLocation = newLocation), newLocation)
        } else {
            LogonResult.Failure(this, currentLocation)
        }
    }

    private fun accessRatingAndSecurityValue(): Pair<Int, Int> = when (val loc = currentLocation) {
        is MatrixLocation.OnLTG -> Pair(loc.ltg.subsystemRatings.access, loc.ltg.securityRating.value)
        is MatrixLocation.OnRTG -> Pair(loc.rtg.subsystemRatings.access, loc.rtg.securityRating.value)
        is MatrixLocation.OnPLTG -> Pair(loc.pltg.subsystemRatings.access, loc.pltg.securityRating.value)
        is MatrixLocation.OnHost -> Pair(loc.host.subsystemRatings.access, loc.host.securityRating.value)
        null -> throw IllegalStateException("Decker is not jacked in")
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private fun MatrixLocation?.label(): String = when (this) {
            is MatrixLocation.OnLTG  -> "LTG(${ltg.name}, tally=${ltg.securityTally})"
            is MatrixLocation.OnRTG  -> "RTG(${rtg.name}, tally=${rtg.securityTally})"
            is MatrixLocation.OnPLTG -> "PLTG(${pltg.name}, tally=${pltg.securityTally})"
            is MatrixLocation.OnHost -> "Host(${host.name}, tally=${host.securityTally})"
            null                     -> "null"
        }

        private val LTG_JACKPOINT_TYPES = setOf(
            JackpointType.LEGAL_ACCESS,
            JackpointType.ILLEGAL_ACCESS,
            JackpointType.TELECOM,
            JackpointType.ILLEGAL_JUNCTION_BOX
        )
        private val HOST_JACKPOINT_TYPES = setOf(
            JackpointType.WORKSTATION,
            JackpointType.CONSOLE,
            JackpointType.REMOTE_DEVICE
        )
    }
}
