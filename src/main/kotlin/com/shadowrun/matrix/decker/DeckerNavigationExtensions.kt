package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.operations.SystemTestResolver
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private val LTG_JACKPOINT_TYPES = setOf(
    JackpointType.LEGAL_ACCESS,
    JackpointType.ILLEGAL_ACCESS,
    JackpointType.TELECOM,
    JackpointType.ILLEGAL_JUNCTION_BOX
)
private val HOST_JACKPOINT_TYPES = setOf(
    JackpointType.WORKSTATION,
    JackpointType.CONSOLE,
    JackpointType.REMOTE_DEVICE,
    JackpointType.ILLEGAL_JUNCTION_BOX
)

private fun MatrixLocation?.label(): String = when (this) {
    is MatrixLocation.OnLTG  -> "LTG(${ltg.name}, tally=${ltg.securityTally})"
    is MatrixLocation.OnRTG  -> "RTG(${rtg.name}, tally=${rtg.securityTally})"
    is MatrixLocation.OnPLTG -> "PLTG(${pltg.name}, tally=${pltg.securityTally})"
    is MatrixLocation.OnHost -> "Host(${host.name}, tally=${host.securityTally})"
    null                     -> "null"
}

/** PRD: M-01, M-04, M-05 */
fun Decker.jackInToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult {
    logger.info { "[$name] jackInToLtg → ${ltg.name}" }
    requireNotJackedIn()
    val jp = requireJackpoint()
    require(jp.type in LTG_JACKPOINT_TYPES) {
        "Jackpoint type ${jp.type} cannot be used to jack in to an LTG"
    }
    return performLogon(
        operation = SystemOperation.LOGON_TO_LTG,
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

/** PRD: M-02, M-04, M-05 */
fun Decker.jackInToHost(host: Host, diceRoller: DiceRoller): LogonResult {
    logger.info { "[$name] jackInToHost → ${host.name}" }
    requireNotJackedIn()
    val jp = requireJackpoint()
    require(jp.type in HOST_JACKPOINT_TYPES) {
        "Jackpoint type ${jp.type} cannot be used to jack in directly to a host"
    }
    require(jp.connectsToHost == host) { "Jackpoint connects to a different host" }
    val result = performLogon(
        operation = SystemOperation.LOGON_TO_HOST,
        accessRating = host.subsystemRatings.access,
        securityValue = host.securityRating.value,
        diceRoller = diceRoller,
        buildLocation = { updatedTally ->
            MatrixLocation.OnHost(host.copy(securityTally = host.securityTally + updatedTally))
        }
    )
    return if (result is LogonResult.Success) {
        logger.info { "[$name] jackInToHost succeeded: now at ${result.location.label()}" }
        val startSubsystem = when (jp.type) {
            JackpointType.WORKSTATION, JackpointType.ILLEGAL_JUNCTION_BOX -> SubsystemType.ACCESS
            JackpointType.REMOTE_DEVICE -> SubsystemType.SLAVE
            JackpointType.CONSOLE -> SubsystemType.CONTROL
            else -> SubsystemType.ACCESS
        }
        val startNode = host.nodes.first { it.subsystemType == startSubsystem }
        val updatedDecker = result.decker.copy(persona = result.decker.persona!!.copy(currentNode = startNode))
        LogonResult.Success(updatedDecker, result.location)
    } else {
        logger.warn { "[$name] jackInToHost failed: remaining at ${(result as LogonResult.Failure).location.label()}" }
        result
    }
}

/** PRD: M-06, M-07, M-10 */
fun Decker.logonToRtg(rtg: RTG, diceRoller: DiceRoller): LogonResult {
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
        operation = SystemOperation.LOGON_TO_RTG,
        accessRating = rtg.subsystemRatings.access,
        securityValue = rtg.securityRating.value,
        diceRoller = diceRoller,
        buildLocation = { hostTallyDelta ->
            MatrixLocation.OnRTG(rtg.copy(securityTally = rtg.securityTally + hostTallyDelta))
        }
    ).also { result ->
        when (result) {
            is LogonResult.Success -> logger.info { "[$name] logonToRtg succeeded: now at ${result.location.label()}" }
            is LogonResult.Failure -> logger.warn { "[$name] logonToRtg failed: remaining at ${result.location.label()}" }
        }
    }
}

/** PRD: M-06, M-07, M-08, M-09 */
fun Decker.logonToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult {
    logger.info { "[$name] logonToLtg → ${ltg.name} (from ${currentLocation.label()})" }
    requireJackedIn()
    when (val loc = currentLocation) {
        is MatrixLocation.OnRTG  -> require(loc.rtg.ltgs.contains(ltg)) {
            "Target LTG is not attached to the current RTG"
        }
        is MatrixLocation.OnPLTG -> Unit // PLTG supports all LTG operations (M-08)
        else -> throw IllegalStateException("Cannot logon to LTG from $currentLocation")
    }
    return performLogon(
        operation = SystemOperation.LOGON_TO_LTG,
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

/** PRD: M-06, M-08, M-11, M-12 */
fun Decker.logonToPltg(pltg: PLTG, diceRoller: DiceRoller): LogonResult {
    logger.info { "[$name] logonToPltg → ${pltg.name} (from ${currentLocation.label()})" }
    requireJackedIn()
    val inheritedTally: Int = when (val loc = currentLocation) {
        is MatrixLocation.OnLTG -> {
            require(loc.ltg.pltgs.contains(pltg)) { "Target PLTG is not attached to the current LTG" }
            loc.ltg.parentRtg.securityTally
        }
        is MatrixLocation.OnPLTG -> 0
        else -> throw IllegalStateException("Cannot logon to PLTG from $currentLocation")
    }
    return performLogon(
        operation = SystemOperation.LOGON_TO_LTG,
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

/** PRD: M-06, M-13, M-14, M-15 */
fun Decker.logonToHost(host: Host, diceRoller: DiceRoller): LogonResult {
    logger.info { "[$name] logonToHost → ${host.name} (from ${currentLocation.label()})" }
    requireJackedIn()
    when (val loc = currentLocation) {
        is MatrixLocation.OnLTG  -> require(loc.ltg.hosts.contains(host)) {
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
        operation = SystemOperation.LOGON_TO_HOST,
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

/** PRD: M-16 */
fun Decker.gracefulLogoff(diceRoller: DiceRoller): LogoffResult {
    logger.info { "[$name] gracefulLogoff attempt from ${currentLocation.label()}" }
    requireJackedIn()
    val (accessRating, securityValue) = accessRatingAndSecurityValue()
    // CC-33: Graceful Logoff TN is raised by Track utility rating while a location cycle is running
    val trackPenalty = trackState?.trackingIcRating ?: 0
    val effectiveTn = accessRating + trackPenalty
    if (trackPenalty > 0) logger.info { "[$name] gracefulLogoff: Track penalty +$trackPenalty applied to TN" }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.GRACEFUL_LOGOFF, effectiveTn, securityValue, diceRoller)
    return if (outcome.deckerWins) {
        LogoffResult.GracefulSuccess(copy(persona = null, currentLocation = null, interrogationStates = emptyMap())).also {
            logger.info { "[$name] gracefulLogoff succeeded: traces cleared, no dump shock" }
        }
    } else {
        val shock = !cyberdeck.immuneToDumpShock
        LogoffResult.JackOut(copy(persona = null, currentLocation = null, interrogationStates = emptyMap()), dumpShock = shock).also {
            logger.warn { "[$name] gracefulLogoff failed: falling back to jack-out (dumpShock=$shock)" }
        }
    }
}

/** PRD: M-17, M-18 */
fun Decker.jackOut(): LogoffResult {
    logger.info { "[$name] jackOut from ${currentLocation.label()}" }
    requireJackedIn()
    check(!isPinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }
    val shock = !cyberdeck.immuneToDumpShock
    return LogoffResult.JackOut(copy(persona = null, currentLocation = null, interrogationStates = emptyMap()), dumpShock = shock).also {
        logger.info { "[$name] jackOut complete: dumpShock=$shock" }
    }
}

// ── Private helpers ────────────────────────────────────────────────────────────

private fun Decker.requireNotJackedIn() =
    check(persona == null && currentLocation == null) { "Decker is already jacked in" }

private fun Decker.requireJackpoint() =
    checkNotNull(jackpoint) { "Decker has no jackpoint set" }

private fun Decker.performLogon(
    operation: SystemOperation,
    accessRating: Int,
    securityValue: Int,
    diceRoller: DiceRoller,
    buildLocation: (Int) -> MatrixLocation
): LogonResult {
    val outcome = SystemTestResolver.resolve(this, operation, accessRating, securityValue, diceRoller)
    val newLocation = buildLocation(outcome.hostSuccesses)
    return if (outcome.deckerWins) {
        val newPersona = persona ?: run {
            val bod     = cyberdeck.personaPrograms.firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.BOD }?.rating ?: 0
            val evasion = cyberdeck.personaPrograms.firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.EVASION }?.rating ?: 0
            val masking = cyberdeck.personaPrograms.firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.MASKING }?.rating ?: 0
            val sensor  = cyberdeck.personaPrograms.firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.SENSORS }?.rating ?: 0
            require(bod >= 1 && evasion >= 1 && masking >= 1 && sensor >= 1) {
                "Cannot log on: all persona attributes must be ≥ 1 " +
                "(bod=$bod, evasion=$evasion, masking=$masking, sensor=$sensor) — " +
                "ensure matching persona programs are installed"
            }
            Persona(
                bod = bod, evasion = evasion, masking = masking, sensor = sensor,
                reaction = reaction + cyberdeck.responseIncrease * 2,
                status = com.shadowrun.matrix.common.PersonaStatus.INTRUDING
            )
        }
        LogonResult.Success(copy(persona = newPersona, currentLocation = newLocation), newLocation,
            deckerSuccesses = outcome.deckerSuccesses, hostSuccesses = outcome.hostSuccesses)
    } else {
        LogonResult.Failure(this, newLocation,
            deckerSuccesses = outcome.deckerSuccesses, hostSuccesses = outcome.hostSuccesses)
    }
}

private fun Decker.accessRatingAndSecurityValue(): Pair<Int, Int> = when (val loc = currentLocation) {
    is MatrixLocation.OnLTG  -> Pair(loc.ltg.subsystemRatings.access, loc.ltg.securityRating.value)
    is MatrixLocation.OnRTG  -> Pair(loc.rtg.subsystemRatings.access, loc.rtg.securityRating.value)
    is MatrixLocation.OnPLTG -> Pair(loc.pltg.subsystemRatings.access, loc.pltg.securityRating.value)
    is MatrixLocation.OnHost -> Pair(loc.host.subsystemRatings.access, loc.host.securityRating.value)
    null -> throw IllegalStateException("Decker is not jacked in")
}
