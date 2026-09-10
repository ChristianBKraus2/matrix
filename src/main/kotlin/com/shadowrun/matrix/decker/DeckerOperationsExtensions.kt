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
import com.shadowrun.matrix.operations.LinkedObserver
import com.shadowrun.matrix.operations.LocateDeckerResult
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.Icon
import com.shadowrun.matrix.operations.MonitoredOperationHandle
import com.shadowrun.matrix.operations.MonitoredTarget
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.PendingLocate
import com.shadowrun.matrix.operations.PointerChain
import com.shadowrun.matrix.operations.ScrambleDestructResult
import com.shadowrun.matrix.operations.queryPrecisionFromRegex
import com.shadowrun.matrix.operations.rankMatches
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
        is Icon.IcIcon      -> icon.ic.rating
        is Icon.FileIcon,
        is Icon.DeviceIcon  -> 2
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

fun Decker.analyzeHost(host: Host, requestedItems: List<HostInfoItem>, diceRoller: DiceRoller, hackingPoolDice: Int = 0): AnalyzeHostResult {
    logger.info { "[$name] analyzeHost → ${host.name}" }
    requireJackedIn()
    require(currentLocation is MatrixLocation.OnHost && currentLocation.host === host) {
        "analyzeHost requires the decker to be on the target host"
    }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_HOST, host.subsystemRatings.control, host.securityRating.value, diceRoller, hackingPoolDice)
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
    val finalDecker = if (secRating != null) updatedDecker.copy(analyzeSecuritySystems = updatedDecker.analyzeSecuritySystems + host.name) else updatedDecker
    return AnalyzeHostResult(finalDecker, outcome, secRating, subsystems).also {
        logger.info { "[$name] analyzeHost: net=$net successes, revealed security=${secRating != null}, subsystems=${subsystems.keys}" }
    }
}

fun Decker.analyzeIc(ic: IC, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] analyzeIc on ${host.name}: IC=${ic.name} rating=${ic.rating}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_IC, host.subsystemRatings.control, host.securityRating.value, diceRoller, hackingPoolDice)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updatedDecker.copy(analyzedIcNames = analyzedIcNames + ic.name), outcome)
    else OperationResult.Failure(updatedDecker, outcome).also {
        logger.info { "[$name] analyzeIc: ${if (outcome.deckerWins) "success" else "failure"}" }
    }
}

fun Decker.analyzeIcon(icon: Icon, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] analyzeIcon" }
    requireJackedIn()
    val sensorRating = persona?.sensor ?: 0
    val tn = maxOf(2, host.subsystemRatings.control - sensorRating)
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_ICON, tn, host.securityRating.value, diceRoller, hackingPoolDice)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) {
        val withAnalysis = when (icon) {
            is Icon.IcIcon     -> updatedDecker.copy(analyzedIcNames = analyzedIcNames + icon.ic.name)
            is Icon.PersonaIcon,
            is Icon.FileIcon,
            is Icon.DeviceIcon -> updatedDecker
        }
        OperationResult.Success(withAnalysis, outcome)
    } else OperationResult.Failure(updatedDecker, outcome)
}

fun Decker.analyzeSecurity(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): AnalyzeSecurityResult {
    logger.info { "[$name] analyzeSecurity → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SECURITY, host.subsystemRatings.control, host.securityRating.value, diceRoller, hackingPoolDice)
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        .let { if (outcome.deckerWins) it.copy(analyzeSecuritySystems = analyzeSecuritySystems + host.name) else it }
    val newTally = tallyFor(host) + outcome.hostSuccesses
    return AnalyzeSecurityResult(updatedDecker, outcome, host.securityRating, newTally, host.alertStatus).also {
        logger.info { "[$name] analyzeSecurity: tally=$newTally alert=${host.alertStatus}" }
    }
}

fun Decker.analyzeSubsystem(host: Host, subsystem: SubsystemType, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] analyzeSubsystem → $subsystem on ${host.name}" }
    requireJackedIn()
    val tn = host.subsystemRatings.get(subsystem)
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SUBSYSTEM, tn, host.securityRating.value, diceRoller, hackingPoolDice)
    var updatedDecker = withUpdatedTally(outcome.hostSuccesses)
    if (outcome.deckerWins) {
        val scrambleIc = host.icPrograms.filterIsInstance<Scramble>()
            .filter { it.guardedNode == null || it.guardedNode.subsystemType == subsystem }
        if (scrambleIc.isNotEmpty()) {
            val newDetected = scrambleIc.mapTo(mutableSetOf()) { Icon.IcIcon(it) }
            updatedDecker = updatedDecker.copy(detectedIcons = updatedDecker.detectedIcons + newDetected)
            logger.info { "[$name] analyzeSubsystem: detected ${scrambleIc.size} Scramble IC on $subsystem" }
        }
    }
    return if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome)
    else OperationResult.Failure(updatedDecker, outcome)
}

// ── Decrypt operations ─────────────────────────────────────────────────────────

fun Decker.decryptAccess(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] decryptAccess → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_ACCESS, host.subsystemRatings.access, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.decryptAccess(grid: Grid, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] decryptAccess on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_ACCESS, grid.subsystemRatings.access, grid.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.decryptFile(file: DataFile, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, ScrambleDestructResult?> {
    logger.info { "[$name] decryptFile → ${file.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_FILE, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
    var updated = withUpdatedTally(outcome.hostSuccesses)
    val scramble: ScrambleDestructResult? = if (!outcome.deckerWins) {
        host.icPrograms.filterIsInstance<Scramble>()
            .firstOrNull { it.guardedNode == null || it.guardedNode.subsystemType == SubsystemType.FILES }
            ?.let { ic ->
                val result = updated.resolveScrambleDestructTest(ic, file, diceRoller)
                if (result.fileScrambled) updated = updated.withFileScrambledOnHost(file)
                result
            }
    } else null
    val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    return Pair(opResult, scramble)
}

private fun Decker.withFileScrambledOnHost(file: DataFile): Decker {
    val loc = currentLocation as? MatrixLocation.OnHost ?: return this
    val updatedFiles = loc.host.dataFiles.map { if (it == file) it.copy(scrambled = true) else it }
    return copy(currentLocation = MatrixLocation.OnHost(loc.host.copy(dataFiles = updatedFiles)))
}

fun Decker.decryptSlave(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] decryptSlave → ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

// ── Locate operations ──────────────────────────────────────────────────────────
//
// Ticket 06: a single successful System Test reveals up to 5 candidate names matching the
// decker's regex query. Query vagueness is derived from the query shape (queryPrecisionFromRegex),
// not supplied by the decker. The candidates are parked in pendingLocate; the decker then picks
// one via selectLocateTarget, which stores it (knownAddresses / locatedFiles / locatedSlaves).

/**
 * Runs one Locate System Test against [operation]'s INDEX subsystem, ranks [candidatePool] against
 * the regex [query], and — on a decker win with ≥1 match — parks the candidates in pendingLocate.
 */
private fun Decker.runLocate(
    operation: SystemOperation,
    query: String,
    baseSubsystemRating: Int,
    securityValue: Int,
    candidatePool: List<String>,
    diceRoller: DiceRoller,
    hackingPoolDice: Int
): Pair<OperationResult, LocateResult> {
    require(query.isNotBlank()) { "Query must not be blank for a locate operation" }
    requireJackedIn()
    val precision = queryPrecisionFromRegex(query)
    logger.info { "[$name] ${operation.name}: query=\"$query\" derived precision=$precision" }
    val outcome = SystemTestResolver.resolveLocate(this, operation, baseSubsystemRating, securityValue, precision, diceRoller, hackingPoolDice)
    var updated = withUpdatedTally(outcome.hostSuccesses)
    val locateResult = if (outcome.deckerWins) {
        val matches = rankMatches(query, candidatePool)
        if (matches.isNotEmpty()) {
            updated = updated.copy(pendingLocate = PendingLocate(operation, matches))
            LocateResult.Candidates(matches)
        } else LocateResult.None
    } else LocateResult.None
    logger.info { "[$name] ${operation.name} result: $locateResult" }
    val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    return Pair(opResult, locateResult)
}

fun Decker.locateFile(host: Host, query: String = "", diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, LocateResult> =
    runLocate(
        SystemOperation.LOCATE_FILE, query,
        host.subsystemRatings.index, host.securityRating.value,
        host.dataFiles.map { it.name },
        diceRoller, hackingPoolDice
    )

fun Decker.locateSlave(host: Host, query: String = "", diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, LocateResult> =
    runLocate(
        SystemOperation.LOCATE_SLAVE, query,
        host.subsystemRatings.index, host.securityRating.value,
        host.remoteDevices.map { it.name },
        diceRoller, hackingPoolDice
    )

fun Decker.locateAccessNode(host: Host, query: String = "", diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, LocateResult> =
    runLocate(
        SystemOperation.LOCATE_ACCESS_NODE, query,
        host.subsystemRatings.index, host.securityRating.value,
        host.connectedHosts.map { it.name },
        diceRoller, hackingPoolDice
    )

fun Decker.locateAccessNode(grid: Grid, query: String = "", diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, LocateResult> {
    val candidatePool = when (grid) {
        is RTG  -> grid.ltgs.map { it.name }
        is LTG  -> grid.hosts.map { it.name } + grid.pltgs.map { it.name }
        is PLTG -> grid.hosts.map { it.name }
    }
    return runLocate(
        SystemOperation.LOCATE_ACCESS_NODE, query,
        grid.subsystemRatings.index, grid.securityRating.value,
        candidatePool,
        diceRoller, hackingPoolDice
    )
}

/**
 * Stores the chosen candidate [targetName] from a pending Locate and clears pendingLocate.
 * Access-node targets are added to [Decker.knownAddresses]; located files/slaves are stored under
 * the host-qualified key `"<hostName>::<name>"` in [Decker.locatedFiles] / [Decker.locatedSlaves].
 * Ticket 06.
 */
fun Decker.selectLocateTarget(targetName: String): Decker {
    val pending = requireNotNull(pendingLocate) { "selectLocateTarget called with no pending locate" }
    require(targetName in pending.candidates) { "\"$targetName\" is not among the located candidates" }
    logger.info { "[$name] selectLocateTarget: ${pending.operation.name} → $targetName" }
    return when (pending.operation) {
        SystemOperation.LOCATE_ACCESS_NODE ->
            copy(knownAddresses = knownAddresses + targetName, pendingLocate = null)
        SystemOperation.LOCATE_FILE -> {
            val hostName = (currentLocation as? MatrixLocation.OnHost)?.host?.name
                ?: error("selectLocateTarget for a file requires the decker to be on a host")
            copy(locatedFiles = locatedFiles + "$hostName::$targetName", pendingLocate = null)
        }
        SystemOperation.LOCATE_SLAVE -> {
            val hostName = (currentLocation as? MatrixLocation.OnHost)?.host?.name
                ?: error("selectLocateTarget for a slave requires the decker to be on a host")
            copy(locatedSlaves = locatedSlaves + "$hostName::$targetName", pendingLocate = null)
        }
        else -> error("selectLocateTarget: unsupported pending operation ${pending.operation}")
    }
}

/**
 * Dismisses a pending Locate without storing any address (ticket 06 — the decker can escape the
 * selection modal). Clears [Decker.pendingLocate]; a no-op if nothing is pending.
 */
fun Decker.cancelLocateSelection(): Decker {
    if (pendingLocate == null) return this
    logger.info { "[$name] cancelLocateSelection: discarding ${pendingLocate?.operation?.name} candidates" }
    return copy(pendingLocate = null)
}

fun Decker.analyzeSecurity(grid: Grid, diceRoller: DiceRoller, hackingPoolDice: Int = 0): AnalyzeSecurityResult {
    logger.info { "[$name] analyzeSecurity → ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SECURITY, grid.subsystemRatings.control, grid.securityRating.value, diceRoller, hackingPoolDice)
    val revealedNames = if (outcome.deckerWins) buildSet {
        add(grid.name)
        // LTG inherits its security code from the parent RTG — reveal the RTG too so its code is visible.
        if (grid is LTG) add(grid.parentRtg.name)
    } else emptySet()
    val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        .let { if (revealedNames.isNotEmpty()) it.copy(analyzeSecuritySystems = analyzeSecuritySystems + revealedNames) else it }
    val newTally = tallyFor(grid) + outcome.hostSuccesses
    return AnalyzeSecurityResult(updatedDecker, outcome, grid.securityRating, newTally, grid.alertStatus).also {
        logger.info { "[$name] analyzeSecurity: tally=$newTally alert=${grid.alertStatus}" }
    }
}

fun Decker.locateIc(grid: Grid, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] locateIc on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_IC, grid.subsystemRatings.index, grid.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

/** PRD: SO-10 through SO-12 */
fun Decker.downloadData(file: DataFile, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, DownloadHandle?> {
    logger.info { "[$name] downloadData → ${file.name} (${file.sizeMp} Mp)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.DOWNLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
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

fun Decker.recordOfflineDownload(file: DataFile): Decker {
    logger.info { "[$name] recordOfflineDownload: ${file.name}" }
    return copy(offlineStorageFiles = offlineStorageFiles + file)
}

fun Decker.editFile(
    file: DataFile,
    host: Host,
    newContent: ByteArray?,
    diceRoller: DiceRoller,
    attemptAuthentication: Boolean = false,
    hackingPoolDice: Int = 0
): EditFileResult {
    logger.info { "[$name] editFile → ${file.name} (delete=${newContent == null})" }
    requireJackedIn()
    require(newContent == null || newContent.size <= 4096) { "File content too large (max 4096 bytes)" }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.EDIT_FILE, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
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
fun Decker.uploadData(host: Host, dataSizeMp: Int, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, UploadHandle?> {
    logger.info { "[$name] uploadData → ${host.name} (${dataSizeMp} Mp)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.UPLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
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
    effectiveSkill: Int? = null,
    hackingPoolDice: Int = 0
): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] controlSlave → ${device.name}" }
    requireJackedIn()
    val skill = effectiveSkill ?: computerSkill
    require(skill in 1..20) { "effectiveSkill must be between 1 and 20 (got $skill)" }
    val outcome = SystemTestResolver.resolve(this, SystemOperation.CONTROL_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller, hackingPoolDice, effectiveSkill = effectiveSkill)
    logger.info { "[$name] controlSlave: skill=$skill → ${outcome.deckerSuccesses}; host=${outcome.hostSuccesses}" }
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins)
        Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.CONTROL_SLAVE, MonitoredTarget.SlaveDevice(device)))
    else
        Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.editSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] editSlave → ${device.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.EDIT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.EDIT_SLAVE, MonitoredTarget.SlaveDevice(device)))
    else Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.monitorSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] monitorSlave → ${device.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.MONITOR_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller, hackingPoolDice)
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

fun Decker.nullOperation(host: Host, inactivitySeconds: Int, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] nullOperation: inactivity=${inactivitySeconds}s on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolveNullOperation(this, host, inactivitySeconds, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
}

fun Decker.nullOperation(grid: Grid, inactivitySeconds: Int, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] nullOperation: inactivity=${inactivitySeconds}s on ${grid.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolveNullOperation(this, grid, inactivitySeconds, diceRoller, hackingPoolDice)
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
    diceRoller: DiceRoller,
    hackingPoolDice: Int = 0
): LocateDeckerResult {
    logger.info { "[$name] locateDecker on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_DECKER, host.subsystemRatings.index, host.securityRating.value, diceRoller, hackingPoolDice)
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

fun Decker.locateIc(host: Host, diceRoller: DiceRoller, activeIc: List<IC> = emptyList(), hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] locateIc on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.LOCATE_IC, host.subsystemRatings.index, host.securityRating.value, diceRoller, hackingPoolDice)
    var updated = withUpdatedTally(outcome.hostSuccesses)
    if (outcome.deckerWins) {
        // Locate IC auto-locates the IC program(s) present on the host — resident IC plus any triggered
        // active IC — with no Sensor Test (SR3 p.217). Located IC then become visible (ticket 15).
        val located = (host.icPrograms + activeIc).map { Icon.IcIcon(it) }
        updated = updated.copy(detectedIcons = updated.detectedIcons + located)
        logger.info { "[$name] locateIc: located ${located.size} IC on ${host.name}" }
    }
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

// ── Comcall operations ─────────────────────────────────────────────────────────

fun Decker.makeComcall(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, MonitoredOperationHandle?> {
    logger.info { "[$name] makeComcall on ${host.name}" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.MAKE_COMCALL, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.MAKE_COMCALL, MonitoredTarget.ComcallHost(host)))
    else Pair(OperationResult.Failure(updated, outcome), null)
}

fun Decker.tapComcall(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): Pair<OperationResult, MonitoredOperationHandle?> {
    // Server-side source of truth: the target's own dataline scanners (PRD: use the highest rating).
    val scannerDeviceRating = host.datalineScannerRatings.maxOrNull() ?: 0
    logger.info { "[$name] tapComcall on ${host.name} (scannerRating=$scannerDeviceRating)" }
    requireJackedIn()
    val outcome = SystemTestResolver.resolve(this, SystemOperation.TAP_COMCALL, host.subsystemRatings.files, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    if (!outcome.deckerWins) {
        logger.warn { "[$name] tapComcall: System Test failed" }
        return Pair(OperationResult.Failure(updated, outcome), null)
    }
    // Dataline scanner check: opposed test, does NOT affect RTG tally (SR3 p.219, PRD: Tap Comcall)
    if (scannerDeviceRating > 0) {
        val commlink = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.COMMLINK }
        val deckerTn = maxOf(2, scannerDeviceRating - (commlink?.currentRating ?: 0))
        val deckerResult = diceRoller.roll(computerSkill, deckerTn)
        val scannerRollResult = diceRoller.roll(scannerDeviceRating, computerSkill)
        val netSuccesses = deckerResult.successes - scannerRollResult.successes
        logger.info { "[$name] tapComcall: scanner opposed test deckerTN=$deckerTn decker=${deckerResult.successes} scanner=${scannerRollResult.successes} net=$netSuccesses" }
        if (netSuccesses <= 0) {
            logger.warn { "[$name] tapComcall: scanner detected the tap" }
            return Pair(OperationResult.Failure(updated, outcome), null)
        }
    }
    return Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.TAP_COMCALL, MonitoredTarget.ComcallHost(host)))
}

// ── Relocate Icon ──────────────────────────────────────────────────────────────

/** PRD: SO individual table, CD-16 */
fun Decker.relocateIcon(host: Host, diceRoller: DiceRoller, hackingPoolDice: Int = 0): OperationResult {
    logger.info { "[$name] relocateIcon on ${host.name}" }
    requireJackedIn()
    // PRD: TN = opponent's Sensor − Relocate rating. Use opponentSensorRating from TrackState when
    // available (non-zero); fall back to Control subsystem when not currently being tracked.
    val tn = trackState?.opponentSensorRating?.takeIf { it > 0 } ?: host.subsystemRatings.control
    val outcome = SystemTestResolver.resolve(this, SystemOperation.RELOCATE_ICON,
        tn, host.securityRating.value, diceRoller, hackingPoolDice)
    val updated = withUpdatedTally(outcome.hostSuccesses)
    return if (outcome.deckerWins) OperationResult.Success(updated, outcome)
    else OperationResult.Failure(updated, outcome)
}

// ── Scramble IC destruct ───────────────────────────────────────────────────────

fun Decker.resolveScrambleDestructTest(ic: Scramble, file: DataFile, diceRoller: DiceRoller): ScrambleDestructResult {
    logger.info { "[$name] resolveScrambleDestructTest: IC rating=${ic.rating} vs computerSkill=$computerSkill" }
    val successes = diceRoller.roll(ic.rating, maxOf(2, computerSkill)).successes
    val scrambled = successes >= 1
    logger.info { "[$name] resolveScrambleDestructTest: successes=$successes scrambled=$scrambled" }
    return ScrambleDestructResult(fileScrambled = scrambled, icRating = ic.rating)
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
