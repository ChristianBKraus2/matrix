package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.ic.Scramble
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Grid
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.operations.AnalyzeHostResult
import com.shadowrun.matrix.operations.AnalyzeSecurityResult
import com.shadowrun.matrix.operations.BufferedMessage
import com.shadowrun.matrix.operations.DownloadHandle
import com.shadowrun.matrix.operations.EditFileResult
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.IcDetectionResult
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.LinkedObserver
import com.shadowrun.matrix.operations.LocateDeckerResult
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.LocatedTarget
import com.shadowrun.matrix.operations.Icon
import com.shadowrun.matrix.operations.MonitoredOperationHandle
import com.shadowrun.matrix.operations.MonitoredTarget
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.PointerChain
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.ScrambleDestructResult
import com.shadowrun.matrix.operations.SensorTestResult
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.operations.SystemTestResolver
import com.shadowrun.matrix.operations.UploadHandle
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}
private val WORD_SPLIT_REGEX = "\\s+".toRegex()

// ── Matrix Perception ──────────────────────────────────────────────────────────

/** PRD: MP-01 through MP-05, MP-09 */
fun Decker.noticeIcon(icon: Icon, diceRoller: DiceRoller, friendlyReveal: Boolean = false): SensorTestResult {
    logger.info { "[$name] noticeIcon: $icon (friendlyReveal=$friendlyReveal)" }
    check(persona != null) { "noticeIcon requires a jacked-in persona" }
    if (friendlyReveal) {
        logger.info { "[$name] noticeIcon: friendly reveal — skipping Sensor Test" }
        return SensorTestResult.Detected(icon, 1)
    }
    val tn = when (icon) {
        is Icon.PersonaIcon -> icon.persona.masking + icon.sleazeRating
        is Icon.IcIcon     -> icon.ic.rating
    }
    val p = persona
    val result = diceRoller.roll(p.sensor, maxOf(2, tn))
    logger.info { "[$name] noticeIcon: sensor=${p.sensor} dice vs TN=${maxOf(2, tn)} → ${result.successes} successes" }
    return if (result.successes == 0) SensorTestResult.Undetected
    else SensorTestResult.Detected(icon, result.successes)
}

/** PRD: MP-07, MP-08 */
fun Decker.noticeTriggeredIc(ic: IC, diceRoller: DiceRoller): IcDetectionResult {
    logger.info { "[$name] noticeTriggeredIc: IC=${ic.name} rating=${ic.rating}" }
    check(persona != null) { "noticeTriggeredIc requires a jacked-in persona" }
    val p = persona
    val result = diceRoller.roll(p.sensor, maxOf(2, ic.rating))
    logger.info { "[$name] noticeTriggeredIc: sensor=${p.sensor} dice vs TN=${maxOf(2, ic.rating)} → ${result.successes} successes" }
    return when {
        result.successes == 0 -> IcDetectionResult.Undetected
        result.successes == 1 -> IcDetectionResult.PresenceOnly(result.successes)
        result.successes == 2 -> IcDetectionResult.TypeKnown(ic, result.successes)
        else                  -> IcDetectionResult.FullyLocated(ic, result.successes)
    }
}

// ── Analyze operations ──────────────────────────────────────────────────────────

fun Decker.analyzeHost(host: Host, requestedItems: List<HostInfoItem>, diceRoller: DiceRoller): AnalyzeHostResult {
    logger.info { "[$name] analyzeHost → ${host.name}" }
    requireJackedIn()
    require(currentLocation is MatrixLocation.OnHost && currentLocation.host === host) {
        "analyzeHost requires the decker to be on the target host"
    }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_HOST, host.subsystemRatings.control, host.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    val net = outcome.deckerSuccesses - outcome.hostSuccesses
    val secRating: com.shadowrun.matrix.common.SecurityRating?
    val subsystems: Map<SubsystemType, Int>
    if (net >= 7) {
        secRating = host.securityRating
        subsystems = SubsystemType.entries.associateWith { host.subsystemRatings.get(it) }
    } else if (net <= 0) {
        secRating = null
        subsystems = emptyMap()
    } else {
        val chosen = requestedItems.distinct().take(net)
        secRating = if (chosen.any { it is HostInfoItem.SecurityRating }) host.securityRating else null
        subsystems = chosen.filterIsInstance<HostInfoItem.Subsystem>()
            .associate { it.type to host.subsystemRatings.get(it.type) }
    }
    return AnalyzeHostResult(updatedDecker, outcome, secRating, subsystems).also {
        logger.info { "[$name] analyzeHost: net=$net successes, revealed security=${secRating != null}, subsystems=${subsystems.keys}" }
    }
}

fun Decker.analyzeIc(ic: IC, host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] analyzeIc on ${host.name}: IC=${ic.name} rating=${ic.rating}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_IC, host.subsystemRatings.control, host.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updatedDecker.copy(analyzedIcNames = analyzedIcNames + ic.name), outcome)
    else OperationResult.Failure(updatedDecker, outcome).also {
        logger.info { "[$name] analyzeIc: ${if (outcome.deckerWins) "success" else "failure"}" }
    }
}

fun Decker.analyzeIcon(icon: Icon, host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] analyzeIcon" }
    requireJackedIn()
    val sensorRating = persona?.sensor ?: 0
    val tn = maxOf(2, host.subsystemRatings.control - sensorRating)
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_ICON, tn, host.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) {
        val withAnalysis = if (icon is Icon.IcIcon) updatedDecker.copy(analyzedIcNames = analyzedIcNames + icon.ic.name) else updatedDecker
        OperationResult.Success(withAnalysis, outcome)
    } else OperationResult.Failure(updatedDecker, outcome)
}

fun Decker.analyzeSecurity(host: Host, diceRoller: DiceRoller): AnalyzeSecurityResult {
    logger.info { "[$name] analyzeSecurity → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SECURITY, host.subsystemRatings.control, host.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    val newTally = tallyFor(host) + outcome.hostSuccesses
    return AnalyzeSecurityResult(updatedDecker, outcome, host.securityRating, newTally, host.alertStatus).also {
        logger.info { "[$name] analyzeSecurity: tally=$newTally alert=${host.alertStatus}" }
    }
}

fun Decker.analyzeSubsystem(host: Host, subsystem: SubsystemType, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] analyzeSubsystem → $subsystem on ${host.name}" }
    requireJackedIn()
    val tn = host.subsystemRatings.get(subsystem)
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SUBSYSTEM, tn, host.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome)
    else OperationResult.Failure(updatedDecker, outcome)
}

// ── Decrypt operations ─────────────────────────────────────────────────────────

fun Decker.decryptAccess(host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] decryptAccess → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_ACCESS, host.subsystemRatings.access, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.decryptAccess(grid: Grid, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] decryptAccess on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_ACCESS, grid.subsystemRatings.access, grid.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.decryptFile(file: DataFile, host: Host, diceRoller: DiceRoller): Pair<OperationResult, ScrambleDestructResult?> {
    logger.info { "[$name] decryptFile → ${file.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_FILE, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    var updated = withUpdatedTally(outcome.hostSuccesses)
    val scramble: ScrambleDestructResult? = if (!outcome.deckerWins) {
        host.icPrograms.filterIsInstance<Scramble>()
            .firstOrNull { it.guardedNode == null || it.guardedNode.subsystemType == SubsystemType.FILES }
            ?.let { ic ->
                val result = updated.resolveScrambleDestructTest(ic, file, diceRoller)
                if (result.dataDestroyed) updated = updated.withFileRemovedFromHost(file)
                result
            }
    } else null
    val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    return Pair(opResult, scramble)
}

private fun Decker.withFileRemovedFromHost(file: DataFile): Decker {
    val loc = currentLocation as? MatrixLocation.OnHost ?: return this
    val updatedHost = loc.host.copy(dataFiles = loc.host.dataFiles - file)
    return copy(currentLocation = MatrixLocation.OnHost(updatedHost))
}

fun Decker.decryptSlave(host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] decryptSlave → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

// ── Interrogation operations ───────────────────────────────────────────────────

fun Decker.locateFile(host: Host, query: String = "", precision: QueryPrecision, diceRoller: DiceRoller): Pair<OperationResult, LocateResult> {
    val existingState = interrogationStates["LOCATE_FILE@HOST"]
    require(existingState != null || query.isNotBlank()) { "Query must not be blank for a new locate operation" }
    val state = existingState ?: InterrogationState(SystemOperation.LOCATE_FILE, query)
    logger.info { "[$name] locateFile on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
    requireJackedIn()
    val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_FILE, host, state, precision, diceRoller)
    val locateResult = when {
        newState.accumulatedSuccesses >= 5 -> {
            val file = host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
            if (file != null) LocateResult.Located(LocatedTarget.FileTarget(file), newState.accumulatedSuccesses)
            else LocateResult.NotFound
        }
        newState.accumulatedSuccesses >= 3 && host.dataFiles.none { it.name.contains(state.query, ignoreCase = true) } ->
            LocateResult.NotFound
        else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
    }
    logger.info { "[$name] locateFile result: $locateResult" }
    val newStates = when (locateResult) {
        is LocateResult.Ongoing -> interrogationStates + ("LOCATE_FILE@HOST" to newState)
        else -> interrogationStates - "LOCATE_FILE@HOST"
    }
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses).copy(interrogationStates = newStates)
    val opResult = if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome) else OperationResult.Failure(updatedDecker, outcome)
    return Pair(opResult, locateResult)
}

fun Decker.locateSlave(host: Host, query: String = "", precision: QueryPrecision, diceRoller: DiceRoller): Pair<OperationResult, LocateResult> {
    val existingState = interrogationStates["LOCATE_SLAVE@HOST"]
    require(existingState != null || query.isNotBlank()) { "Query must not be blank for a new locate operation" }
    val state = existingState ?: InterrogationState(SystemOperation.LOCATE_SLAVE, query)
    logger.info { "[$name] locateSlave on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
    requireJackedIn()
    val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_SLAVE, host, state, precision, diceRoller)
    val locateResult = when {
        newState.accumulatedSuccesses >= 3 -> {
            val device = host.remoteDevices.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
            if (device != null) LocateResult.Located(LocatedTarget.SlaveTarget(device), newState.accumulatedSuccesses)
            else LocateResult.NotFound
        }
        else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
    }
    logger.info { "[$name] locateSlave result: $locateResult" }
    val newStates = when (locateResult) {
        is LocateResult.Ongoing -> interrogationStates + ("LOCATE_SLAVE@HOST" to newState)
        else -> interrogationStates - "LOCATE_SLAVE@HOST"
    }
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses).copy(interrogationStates = newStates)
    val opResult = if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome) else OperationResult.Failure(updatedDecker, outcome)
    return Pair(opResult, locateResult)
}

fun Decker.locateAccessNode(host: Host, query: String = "", precision: QueryPrecision, diceRoller: DiceRoller): Pair<OperationResult, LocateResult> {
    val existingState = interrogationStates["LOCATE_ACCESS_NODE@HOST"]
    require(existingState != null || query.isNotBlank()) { "Query must not be blank for a new locate operation" }
    val state = existingState ?: InterrogationState(SystemOperation.LOCATE_ACCESS_NODE, query)
    logger.info { "[$name] locateAccessNode on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
    requireJackedIn()
    val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_ACCESS_NODE, host, state, precision, diceRoller)
    val nodeExists = host.nodes.any {
        it.subsystemType.name.contains(state.query, ignoreCase = true) ||
        it.description.contains(state.query, ignoreCase = true)
    }
    val locateResult = when {
        newState.accumulatedSuccesses >= 5 -> {
            if (nodeExists) LocateResult.Located(LocatedTarget.AccessNodeTarget(state.query), newState.accumulatedSuccesses)
            else LocateResult.NotFound
        }
        newState.accumulatedSuccesses >= 3 && !nodeExists -> LocateResult.NotFound
        else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
    }
    val newStates = when (locateResult) {
        is LocateResult.Ongoing -> interrogationStates + ("LOCATE_ACCESS_NODE@HOST" to newState)
        else -> interrogationStates - "LOCATE_ACCESS_NODE@HOST"
    }
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses).copy(interrogationStates = newStates)
    val opResult = if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome) else OperationResult.Failure(updatedDecker, outcome)
    return Pair(opResult, locateResult)
}

fun Decker.locateAccessNode(grid: Grid, query: String = "", precision: QueryPrecision, diceRoller: DiceRoller): Pair<OperationResult, LocateResult> {
    val contextTag = when (grid) { is LTG -> "LTG"; is RTG -> "RTG"; is PLTG -> "PLTG" }
    val stateKey = "LOCATE_ACCESS_NODE@$contextTag"
    val existingState = interrogationStates[stateKey]
    require(existingState != null || query.isNotBlank()) { "Query must not be blank for a new locate operation" }
    val state = existingState ?: InterrogationState(SystemOperation.LOCATE_ACCESS_NODE, query)
    logger.info { "[$name] locateAccessNode on ${grid.name} (accumulated=${state.accumulatedSuccesses})" }
    requireJackedIn()
    val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_ACCESS_NODE, grid, state, precision, diceRoller)
    val accessibleHosts = when (grid) {
        is LTG  -> grid.hosts
        is RTG  -> grid.ltgs.flatMap { it.hosts }
        is PLTG -> grid.hosts
    }
    val nodeExists = accessibleHosts.any { it.name.contains(state.query, ignoreCase = true) }
    val locateResult = when {
        newState.accumulatedSuccesses >= 5 -> {
            if (nodeExists) LocateResult.Located(LocatedTarget.AccessNodeTarget(state.query), newState.accumulatedSuccesses)
            else LocateResult.NotFound
        }
        newState.accumulatedSuccesses >= 3 && !nodeExists -> LocateResult.NotFound
        else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
    }
    val newStates = when (locateResult) {
        is LocateResult.Ongoing -> interrogationStates + (stateKey to newState)
        else -> interrogationStates - stateKey
    }
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses).copy(interrogationStates = newStates)
    val opResult = if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome) else OperationResult.Failure(updatedDecker, outcome)
    return Pair(opResult, locateResult)
}

fun Decker.analyzeSecurity(grid: Grid, diceRoller: DiceRoller): AnalyzeSecurityResult {
    logger.info { "[$name] analyzeSecurity → ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SECURITY, grid.subsystemRatings.control, grid.securityRating.value, diceRoller)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    val newTally = tallyFor(grid) + outcome.hostSuccesses
    return AnalyzeSecurityResult(updatedDecker, outcome, grid.securityRating, newTally, grid.alertStatus).also {
        logger.info { "[$name] analyzeSecurity: tally=$newTally alert=${grid.alertStatus}" }
    }
}

fun Decker.locateIc(grid: Grid, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] locateIc on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_IC, grid.subsystemRatings.index, grid.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

/** PRD: SO-10 through SO-12 */
fun Decker.downloadData(file: DataFile, host: Host, diceRoller: DiceRoller): Pair<OperationResult, DownloadHandle?> {
    logger.info { "[$name] downloadData → ${file.name} (${file.sizeMp} Mp)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DOWNLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) {
        val ioSpeed = cyberdeck.ioSpeedMpPerTurn
        if (ioSpeed <= 0) {
            logger.warn { "[$name] downloadData: ioSpeedMpPerTurn is 0 — cyberdeck cannot transfer data" }
            return Pair(OperationResult.Failure(updated, outcome), null)
        }
        val turns = ceil(file.sizeMp.toDouble() / ioSpeed).toInt().coerceAtLeast(1)
        val handle = DownloadHandle(file, file.sizeMp, ioSpeed, turns)
        logger.info { "[$name] downloadData started: ${handle.turnsRemaining} turns at $ioSpeed Mp/turn" }
        Pair(OperationResult.Success(updated, outcome), handle)
    } else {
        logger.warn { "[$name] downloadData failed" }
        Pair(OperationResult.Failure(updated, outcome), null)
    }
}

fun Decker.recordCompletedDownload(file: DataFile): Decker {
    logger.info { "[$name] recordCompletedDownload: ${file.name}" }
    return copy(runDownloadedFiles = runDownloadedFiles + file)
}

fun Decker.editFile(
    file: DataFile,
    host: Host,
    newContent: ByteArray?,
    diceRoller: DiceRoller,
    attemptAuthentication: Boolean = false
): EditFileResult {
    logger.info { "[$name] editFile → ${file.name} (delete=${newContent == null})" }
    requireJackedIn()
    require(newContent == null || newContent.size <= 4096) { "File content too large (max 4096 bytes)" }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.EDIT_FILE, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    val authSuccesses: Int? = if (outcome.deckerWins && attemptAuthentication) {
        val readWrite = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.READ_WRITE }
        val authTn = maxOf(2, host.subsystemRatings.control - (readWrite?.currentRating ?: 0))
        val authResult = diceRoller.roll(computerSkill, authTn)
        logger.info { "[$name] editFile authentication: TN=$authTn → ${authResult.successes} successes" }
        authResult.successes
    } else null
    return EditFileResult(updated, outcome, authSuccesses)
}

/** PRD: SO-10 through SO-12 */
fun Decker.uploadData(host: Host, dataSizeMp: Int, diceRoller: DiceRoller): Pair<OperationResult, UploadHandle?> {
    logger.info { "[$name] uploadData → ${host.name} (${dataSizeMp} Mp)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.UPLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) {
        val ioSpeed = cyberdeck.ioSpeedMpPerTurn
        if (ioSpeed <= 0) {
            logger.warn { "[$name] uploadData: ioSpeedMpPerTurn is 0 — cyberdeck cannot transfer data" }
            return Pair(OperationResult.Failure(updated, outcome), null)
        }
        val turns = ceil(dataSizeMp.toDouble() / ioSpeed).toInt().coerceAtLeast(1)
        val handle = UploadHandle(file = DataFile(name = "upload to ${host.name}", sizeMp = dataSizeMp), totalMp = dataSizeMp, ioSpeedMpPerTurn = ioSpeed, turnsRemaining = turns)
        logger.info { "[$name] uploadData started: ${handle.turnsRemaining} turns at $ioSpeed Mp/turn" }
        Pair(OperationResult.Success(updated, outcome), handle)
    } else {
        logger.warn { "[$name] uploadData failed" }
        Pair(OperationResult.Failure(updated, outcome), null)
    }
}

// ── Slave operations ───────────────────────────────────────────────────────────

fun Decker.controlSlave(
    device: RemoteDevice,
    host: Host,
    diceRoller: DiceRoller,
    effectiveSkill: Int? = null
): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] controlSlave → ${device.name}" }
    requireJackedIn()
    val skill = effectiveSkill ?: computerSkill
    require(skill in 1..20) { "effectiveSkill must be between 1 and 20 (got $skill)" }
    val deckerForTest = if (effectiveSkill != null) copy(computerSkill = skill) else this
    val outcome = SystemTestResolver.resolve(deckerForTest, SystemOperation.CONTROL_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
    logger.info { "[$name] controlSlave: skill=$skill → ${outcome.deckerSuccesses}; host=${outcome.hostSuccesses}" }
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins)
        Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.CONTROL_SLAVE, MonitoredTarget.SlaveDevice(device)))
    else
        Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.editSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] editSlave → ${device.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.EDIT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.EDIT_SLAVE, MonitoredTarget.SlaveDevice(device)))
    else Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.monitorSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] monitorSlave → ${device.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.MONITOR_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.MONITOR_SLAVE, MonitoredTarget.SlaveDevice(device)))
    else Pair(OperationResult.Failure(updated, outcome), null)
}

/** PRD: SO-13, SO-14 */
fun Decker.maintainMonitoredOperation(handle: MonitoredOperationHandle): MonitoredOperationHandle {
    if (!handle.active) {
        logger.warn { "[$name] maintainMonitoredOperation: operation already aborted — ignoring" }
        return handle
    }
    logger.info { "[$name] maintainMonitoredOperation: ${handle.operation.name} maintained" }
    return handle.copy(needsMaintenance = false)
}

/** Called by the game engine at the start of each initiative pass to arm the maintenance check. */
fun MonitoredOperationHandle.beginInitiativePass(): MonitoredOperationHandle =
    if (active) copy(needsMaintenance = true) else this

/** Called by the game engine at the end of each initiative pass; aborts if not maintained. PRD: SO-13. */
fun Decker.checkMaintenance(handle: MonitoredOperationHandle): MonitoredOperationHandle {
    if (!handle.active || !handle.needsMaintenance) return handle
    logger.warn { "[$name] checkMaintenance: ${handle.operation.name} missed free action — aborting" }
    return handle.copy(active = false, needsMaintenance = false)
}

/** PRD: SO-14 */
fun Decker.abortMonitoredOperation(handle: MonitoredOperationHandle): MonitoredOperationHandle =
    handle.copy(active = false).also {
        logger.warn { "[$name] abortMonitoredOperation: ${handle.operation.name} aborted" }
    }

// ── Null Operation ─────────────────────────────────────────────────────────────

fun Decker.nullOperation(host: Host, inactivitySeconds: Int, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] nullOperation: inactivity=${inactivitySeconds}s on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolveNullOperation(this, host, inactivitySeconds, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.nullOperation(grid: Grid, inactivitySeconds: Int, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] nullOperation: inactivity=${inactivitySeconds}s on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolveNullOperation(this, grid, inactivitySeconds, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

// ── Medic Utility ──────────────────────────────────────────────────────────────

/** PRD: CD-20 */
fun Decker.invokeMedic(diceRoller: DiceRoller): MedicResult {
    logger.info { "[$name] invokeMedic: invoking Medic utility" }
    check(persona != null) { "invokeMedic requires a jacked-in persona" }
    val p = persona
    val medic = checkNotNull(cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.MEDIC }) {
        "Medic utility is not loaded"
    }
    val filled = p.conditionMonitor.damage
    if (filled >= 10) {
        logger.warn { "[$name] invokeMedic: CM at Deadly — cannot repair" }
        return MedicResult(this, 0, medic.currentRating)
    }
    val tn = when {
        filled <= 3 -> 4
        filled <= 6 -> 5
        else        -> 6
    }
    val successes = diceRoller.roll(medic.currentRating, tn).successes
    val repaired = successes.coerceAtMost(filled)
    val newCm = p.conditionMonitor.copy(damage = filled - repaired)
    val newMedicRating = medic.currentRating - 1

    val newActive = if (newMedicRating <= 0) {
        cyberdeck.activeUtilities.filterNot { it.type == UtilityType.MEDIC }
    } else {
        cyberdeck.activeUtilities.map { if (it.type == UtilityType.MEDIC) Utility(it.type, it.rating, currentRating = newMedicRating) else it }
    }
    // storedUtilities is immutable at runtime (CD-21): only remove the stored entry when depleted (CD-22)
    val newStored = if (newMedicRating <= 0) {
        cyberdeck.storedUtilities.filterNot { it.type == UtilityType.MEDIC }
    } else {
        cyberdeck.storedUtilities
    }
    val updatedDecker = copy(
        persona = p.copy(conditionMonitor = newCm),
        cyberdeck = cyberdeck.copy(activeUtilities = newActive, storedUtilities = newStored)
    )
    logger.info { "[$name] invokeMedic: filled=$filled TN=$tn successes=$successes repaired=$repaired newMedicRating=$newMedicRating" }
    return MedicResult(updatedDecker, repaired, newMedicRating)
}

// ── Distributed Databases ──────────────────────────────────────────────────────

/** PRD: SO-03, SO-04 */
fun Decker.resolvePointerChain(file: DataFile, diceRoller: DiceRoller): PointerChain {
    require(file.isPointer) { "resolvePointerChain called on a non-pointer DataFile" }
    val chainLength = diceRoller.flat(1, 6) // 1D6, non-exploding (a flat length, not a success test)
    val links = buildList {
        var current = requireNotNull(file.pointerToHost) { "resolvePointerChain: DataFile has null pointerToHost" }
        repeat(chainLength - 1) {
            add(current)
            current = current.connectedHosts.firstOrNull() ?: current
        }
        add(current)
    }
    val finalFile = links.last().dataFiles.firstOrNull { !it.isPointer } ?: file
    logger.info { "[$name] resolvePointerChain: ${chainLength} hops to ${finalFile.name}" }
    return PointerChain(links, finalFile)
}

// ── Locate Decker / Locate IC ──────────────────────────────────────────────────

/** PRD: MP-10, SO individual table */
fun Decker.locateDecker(
    host: Host,
    targetPersona: Persona,
    diceRoller: DiceRoller
): LocateDeckerResult {
    logger.info { "[$name] locateDecker on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_DECKER, host.subsystemRatings.index, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    if (!outcome.deckerWins) {
        logger.warn { "[$name] locateDecker: Index Test failed" }
        return LocateDeckerResult(updated, outcome, located = false, targetNotified = false)
    }
    val sensorTn = maxOf(2, targetPersona.masking + targetPersona.sleazeRating)
    val sensorResult = diceRoller.roll(requireNotNull(persona) { "locateDecker: decker has no persona" }.sensor, sensorTn)
    val located = sensorResult.successes >= 1
    logger.info { "[$name] locateDecker: sensor vs TN=$sensorTn (masking=${targetPersona.masking} sleaze=${targetPersona.sleazeRating}) → ${sensorResult.successes} successes, located=$located" }
    return LocateDeckerResult(updated, outcome, located, targetNotified = located)
}

fun Decker.locateIc(host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] locateIc on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_IC, host.subsystemRatings.index, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

// ── Comcall operations ─────────────────────────────────────────────────────────

fun Decker.makeComcall(host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] makeComcall on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.MAKE_COMCALL, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.MAKE_COMCALL, MonitoredTarget.ComcallHost(host)))
    else Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.tapComcall(host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
    // Server-side source of truth: the target's own dataline scanners (PRD: use the highest rating).
    val scannerDeviceRating = host.datalineScannerRatings.maxOrNull() ?: 0
    logger.info { "[$name] tapComcall on ${host.name} (scannerRating=$scannerDeviceRating)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.TAP_COMCALL, host.subsystemRatings.files, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    if (!outcome.deckerWins) {
        logger.warn { "[$name] tapComcall: System Test failed" }
        return Pair(OperationResult.Failure(updated, outcome), null)
    }
    // Dataline scanner check: scanner test does NOT affect RTG tally (PRD: Tap Comcall)
    if (scannerDeviceRating > 0) {
        val commlink = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.COMMLINK }
        val scannerTn = maxOf(2, scannerDeviceRating - (commlink?.currentRating ?: 0))
        val scannerResult = diceRoller.roll(computerSkill, scannerTn)
        logger.info { "[$name] tapComcall: scanner test TN=$scannerTn → ${scannerResult.successes} successes" }
        if (scannerResult.successes == 0) {
            logger.warn { "[$name] tapComcall: scanner detected the tap" }
            return Pair(OperationResult.Failure(updated, outcome), null)
        }
    }
    return Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.TAP_COMCALL, MonitoredTarget.ComcallHost(host)))
}

// ── Relocate Icon ──────────────────────────────────────────────────────────────

/** PRD: SO individual table, CD-16 */
fun Decker.relocateIcon(host: Host, diceRoller: DiceRoller): OperationResult {
    logger.info { "[$name] relocateIcon on ${host.name}" }
    requireJackedIn()
    // PRD: TN = opponent's Sensor − Relocate rating. Use opponentSensorRating from TrackState when
    // available (non-zero); fall back to Control subsystem when not currently being tracked.
    val tn = trackState?.opponentSensorRating?.takeIf { it > 0 } ?: host.subsystemRatings.control
    val outcome = SystemTestResolver.resolve(this, SystemOperation.RELOCATE_ICON,
        tn, host.securityRating.value, diceRoller)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

// ── Scramble IC destruct ───────────────────────────────────────────────────────

fun Decker.resolveScrambleDestructTest(ic: Scramble, file: DataFile, diceRoller: DiceRoller): ScrambleDestructResult {
    logger.info { "[$name] resolveScrambleDestructTest: IC rating=${ic.rating} vs computerSkill=$computerSkill" }
    val successes = diceRoller.roll(ic.rating, maxOf(2, computerSkill)).successes
    val destroyed = successes >= 1
    logger.info { "[$name] resolveScrambleDestructTest: successes=$successes destroyed=$destroyed" }
    return ScrambleDestructResult(dataDestroyed = destroyed, icRating = ic.rating)
}

// ── Buffered Message ───────────────────────────────────────────────────────────

fun Decker.bufferMessage(text: String, recipient: LinkedObserver): BufferedMessage {
    check(persona != null) { "bufferMessage requires a jacked-in persona" }
    require(text.split(WORD_SPLIT_REGEX).size <= 100) { "Buffered message exceeds 100 words" }
    logger.info { "[$name] bufferMessage → ${recipient.name}: \"${text.take(40)}${if (text.length > 40) "..." else ""}\"" }
    return BufferedMessage(text, recipient)
}

// ── Private helpers ────────────────────────────────────────────────────────────

private fun Decker.tallyFor(host: Host): Int =
    (currentLocation as? MatrixLocation.OnHost)?.takeIf { it.host == host }?.host?.securityTally ?: 0

private fun Decker.tallyFor(grid: Grid): Int = when (val loc = currentLocation) {
    is MatrixLocation.OnLTG  -> if (loc.ltg === grid) loc.ltg.securityTally else 0
    is MatrixLocation.OnRTG  -> if (loc.rtg === grid) loc.rtg.securityTally else 0
    is MatrixLocation.OnPLTG -> if (loc.pltg === grid) loc.pltg.securityTally else 0
    else -> 0
}
