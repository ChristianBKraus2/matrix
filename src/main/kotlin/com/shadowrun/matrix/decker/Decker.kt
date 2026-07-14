package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.operations.AnalyzeHostResult
import com.shadowrun.matrix.operations.AnalyzeSecurityResult
import com.shadowrun.matrix.operations.DownloadHandle
import com.shadowrun.matrix.operations.EditFileResult
import com.shadowrun.matrix.operations.IcDetectionResult
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.MatrixIcon
import com.shadowrun.matrix.operations.MonitoredOperationHandle
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.PointerChain
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.SensorTestResult
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.operations.SystemTestOutcome
import com.shadowrun.matrix.operations.SystemTestResolver
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.ceil

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

    /** Detection Factor = ceil((Masking + Sleaze.currentRating) / 2); or ceil(Masking / 2) if no Sleaze active.
     *  Recalculated dynamically — Sleaze in pendingUploads does not count. PRD: CD-17, CD-18 */
    val detectionFactor: Int get() {
        val masking = cyberdeck.personaPrograms
            .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.MASKING }
            ?.rating ?: 0
        val sleaze = cyberdeck.activeUtilities
            .firstOrNull { it.type == UtilityType.SLEAZE }?.currentRating
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
            operation = SystemOperation.LOGON_TO_HOST,
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
            operation = SystemOperation.LOGON_TO_RTG,
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
        val outcome = SystemTestResolver.resolve(this, SystemOperation.GRACEFUL_LOGOFF, accessRating, securityValue, diceRoller)
        return if (outcome.deckerWins) {
            LogoffResult.GracefulSuccess(copy(persona = null, currentLocation = null)).also {
                logger.info { "[$name] gracefulLogoff succeeded: traces cleared, no dump shock" }
            }
        } else {
            val shock = !cyberdeck.immuneToDumpShock
            LogoffResult.JackOut(copy(persona = null, currentLocation = null), dumpShock = shock).also {
                logger.warn { "[$name] gracefulLogoff failed: falling back to jack-out (dumpShock=$shock)" }
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
        val shock = !cyberdeck.immuneToDumpShock
        return LogoffResult.JackOut(copy(persona = null, currentLocation = null), dumpShock = shock).also {
            logger.info { "[$name] jackOut complete: dumpShock=$shock" }
        }
    }

    // ── Active memory management ──────────────────────────────────────────────────

    /**
     * Load a utility from storage into active memory (Simple Action, no test).
     * The utility enters a pending-upload state; it becomes effective only after the upload
     * countdown completes via [advanceCombatTurn]. PRD: CD-07, CD-08, CD-10, CD-12
     */
    fun loadUtility(utility: com.shadowrun.matrix.programs.Utility): LoadUtilityResult {
        logger.info { "[$name] loadUtility → ${utility.type} (rating=${utility.rating}, ${utility.mpSize} Mp)" }
        check(persona != null) { "Decker is not jacked in" }
        require(cyberdeck.storedUtilities.any { it.type == utility.type && it.rating == utility.rating }) {
            "Utility ${utility.type} is not in storage"
        }
        require(cyberdeck.activeUtilities.none { it.type == utility.type } &&
                cyberdeck.pendingUploads.none { it.utility.type == utility.type }) {
            "Utility ${utility.type} is already loaded or uploading"
        }
        if (cyberdeck.freeActiveMemoryMp < utility.mpSize) {
            logger.warn { "[$name] loadUtility ${utility.type}: insufficient memory (need=${utility.mpSize}, free=${cyberdeck.freeActiveMemoryMp})" }
            return LoadUtilityResult.InsufficientMemory(this, utility.mpSize, cyberdeck.freeActiveMemoryMp)
        }
        val turnsRequired = Math.ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn).toInt()
        val updatedDeck = if (turnsRequired == 0) {
            cyberdeck.copy(activeUtilities = cyberdeck.activeUtilities + utility)
        } else {
            cyberdeck.copy(pendingUploads = cyberdeck.pendingUploads + PendingUpload(utility, turnsRequired))
        }
        val result = LoadUtilityResult.Success(copy(cyberdeck = updatedDeck))
        logger.info { "[$name] loadUtility ${utility.type}: accepted (uploadTurns=$turnsRequired)" }
        return result
    }

    /**
     * Unload a utility from active memory or cancel a pending upload (Free Action, no test).
     * Memory is freed immediately; the stored copy retains its currentRating. PRD: CD-09
     */
    fun unloadUtility(utility: com.shadowrun.matrix.programs.Utility): Decker {
        logger.info { "[$name] unloadUtility → ${utility.type}" }
        check(persona != null) { "Decker is not jacked in" }
        val newActive = cyberdeck.activeUtilities.filterNot { it.type == utility.type }
        val newPending = cyberdeck.pendingUploads.filterNot { it.utility.type == utility.type }
        require(newActive.size < cyberdeck.activeUtilities.size || newPending.size < cyberdeck.pendingUploads.size) {
            "Utility ${utility.type} is not loaded or uploading"
        }
        return copy(cyberdeck = cyberdeck.copy(activeUtilities = newActive, pendingUploads = newPending)).also {
            logger.info { "[$name] unloadUtility ${utility.type}: removed" }
        }
    }

    /**
     * Swap [toUnload] out and [toLoad] in (Simple Action total; the unload is absorbed).
     * Frees [toUnload]'s memory before checking capacity for [toLoad]. PRD: CD-13
     */
    fun swapUtility(
        toUnload: com.shadowrun.matrix.programs.Utility,
        toLoad: com.shadowrun.matrix.programs.Utility
    ): LoadUtilityResult {
        logger.info { "[$name] swapUtility: unload ${toUnload.type} → load ${toLoad.type}" }
        val afterUnload = unloadUtility(toUnload)
        return afterUnload.loadUtility(toLoad)
    }

    /**
     * Advance the game clock by one Combat Turn: decrement all upload countdowns and promote
     * completed uploads to active memory. Auto-unloads depleted utilities. PRD: CD-11, CD-22
     * This is not a player action; it is called by the game engine at the start of each turn.
     */
    fun advanceCombatTurn(): Decker {
        logger.info { "[$name] advanceCombatTurn" }
        val decremented = cyberdeck.pendingUploads.map { it.copy(turnsRemaining = it.turnsRemaining - 1) }
        val nowActive = decremented.filter { it.turnsRemaining <= 0 }.map { it.utility }
        val stillPending = decremented.filter { it.turnsRemaining > 0 }

        val allActive = cyberdeck.activeUtilities + nowActive
        val (live, depleted) = allActive.partition { it.currentRating > 0 }
        depleted.forEach { logger.warn { "[$name] advanceCombatTurn: utility ${it.type} depleted and auto-unloaded" } }
        val newStored = cyberdeck.storedUtilities.filterNot { su -> depleted.any { it.type == su.type } }

        val updatedDeck = cyberdeck.copy(
            activeUtilities = live,
            pendingUploads = stillPending,
            storedUtilities = newStored
        )
        nowActive.forEach { logger.info { "[$name] advanceCombatTurn: ${it.type} upload complete, now active" } }
        return copy(cyberdeck = updatedDeck)
    }

    // ── Action economy ────────────────────────────────────────────────────────────

    /**
     * Number of actions the decker may perform per 3-second turn (non-combat).
     * = ceil(persona.reaction / 10) + 1 per Response Increase die beyond the base 1D6.
     * PRD: SO-01, SO-02
     */
    val actionsPerTurn: Int
        get() {
            val p = checkNotNull(persona) { "actionsPerTurn requires a jacked-in persona" }
            return ceil(p.reaction / 10.0).toInt() + cyberdeck.responseIncrease
        }

    // ── Matrix Perception ─────────────────────────────────────────────────────────

    /**
     * Free Sensor Test to notice a newly-entered icon. No utility modifier allowed.
     * TN = target Masking + Sleaze (for personas) or icon rating (for IC/programs).
     * PRD: MP-01 through MP-05
     */
    fun noticeIcon(icon: MatrixIcon, diceRoller: DiceRoller): SensorTestResult {
        logger.info { "[$name] noticeIcon: $icon" }
        check(persona != null) { "noticeIcon requires a jacked-in persona" }
        val tn = when (icon) {
            is MatrixIcon.PersonaIcon -> icon.persona.masking + icon.sleazeRating
            is MatrixIcon.IcIcon     -> icon.ic.rating
        }
        val result = diceRoller.roll(persona!!.sensor, maxOf(2, tn))
        logger.info { "[$name] noticeIcon: sensor=${persona!!.sensor} dice vs TN=$tn → ${result.successes} successes" }
        return if (result.successes == 0) {
            SensorTestResult.Undetected
        } else {
            SensorTestResult.Detected(icon, result.successes)
        }
    }

    /**
     * GM-triggered Sensor Test when a decker triggers reactive IC.
     * Made once only; reveals presence (1), type (2), or full info (3+).
     * PRD: MP-07, MP-08
     */
    fun noticeTriggeredIc(ic: IC, diceRoller: DiceRoller): IcDetectionResult {
        logger.info { "[$name] noticeTriggeredIc: IC=${ic.name} rating=${ic.rating}" }
        check(persona != null) { "noticeTriggeredIc requires a jacked-in persona" }
        val result = diceRoller.roll(persona!!.sensor, maxOf(2, ic.rating))
        logger.info { "[$name] noticeTriggeredIc: sensor=${persona!!.sensor} dice vs TN=${ic.rating} → ${result.successes} successes" }
        return when {
            result.successes == 0 -> IcDetectionResult.Undetected
            result.successes == 1 -> IcDetectionResult.PresenceOnly(result.successes)
            result.successes == 2 -> IcDetectionResult.TypeKnown(ic, result.successes)
            else                  -> IcDetectionResult.FullyLocated(ic, result.successes)
        }
    }

    // ── Analyze operations ────────────────────────────────────────────────────────

    /**
     * Analyze the ratings of [host]. Each net success reveals one piece of info;
     * 7+ net successes reveals all. Decker must be on the host. PRD: SO individual table
     */
    fun analyzeHost(host: Host, diceRoller: DiceRoller): AnalyzeHostResult {
        logger.info { "[$name] analyzeHost → ${host.name}" }
        requireJackedIn()
        require(currentLocation is MatrixLocation.OnHost && (currentLocation as MatrixLocation.OnHost).host == host) {
            "analyzeHost requires the decker to be on the target host"
        }
        val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_HOST, host.subsystemRatings.control, host.securityRating.value, diceRoller)
        val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        val net = outcome.deckerSuccesses - outcome.hostSuccesses
        val secRating = if (net >= 1) host.securityRating else null
        val subsystems = if (net >= 7) {
            SubsystemType.entries.associateWith { host.subsystemRatings.get(it) }
        } else {
            SubsystemType.entries.take(maxOf(0, net - 1))
                .associateWith { host.subsystemRatings.get(it) }
        }
        return AnalyzeHostResult(updatedDecker, outcome, secRating, subsystems).also {
            logger.info { "[$name] analyzeHost: net=$net successes, revealed security=${secRating != null}, subsystems=${subsystems.keys}" }
        }
    }

    /**
     * Scan any icon to identify its general type (Free Action).
     * Special floor: TN cannot drop below 2 even with combined Sensor + Analyze.
     * PRD: SO individual table
     */
    fun analyzeIcon(icon: MatrixIcon, host: Host, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] analyzeIcon" }
        requireJackedIn()
        val analyze = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ANALYZE }
        val tn = maxOf(2, host.subsystemRatings.control - (persona?.sensor ?: 0) - (analyze?.currentRating ?: 0))
        val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_ICON, tn, host.securityRating.value, diceRoller)
        val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome)
        else OperationResult.Failure(updatedDecker, outcome)
    }

    /**
     * Returns current Security Rating, security tally (including this test's points), and alert status.
     * PRD: SO individual table
     */
    fun analyzeSecurity(host: Host, diceRoller: DiceRoller): AnalyzeSecurityResult {
        logger.info { "[$name] analyzeSecurity → ${host.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SECURITY, host.subsystemRatings.control, host.securityRating.value, diceRoller)
        val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        val newTally = tallyFor(host) + outcome.hostSuccesses
        return AnalyzeSecurityResult(updatedDecker, outcome, host.securityRating, newTally, host.alertStatus).also {
            logger.info { "[$name] analyzeSecurity: tally=$newTally alert=${host.alertStatus}" }
        }
    }

    /**
     * Identifies anomalies in a subsystem (scramble IC, defenses, etc.).
     * The test type is the targeted subsystem's rating. PRD: SO individual table
     */
    fun analyzeSubsystem(host: Host, subsystem: SubsystemType, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] analyzeSubsystem → $subsystem on ${host.name}" }
        requireJackedIn()
        val tn = host.subsystemRatings.get(subsystem)
        val outcome = SystemTestResolver.resolve(this, SystemOperation.ANALYZE_SUBSYSTEM, tn, host.securityRating.value, diceRoller)
        val updatedDecker = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updatedDecker, outcome)
        else OperationResult.Failure(updatedDecker, outcome)
    }

    // ── Decrypt operations ────────────────────────────────────────────────────────

    /** Defeats scramble IC on a SAN before Logon to Host. PRD: SO individual table */
    fun decryptAccess(host: Host, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] decryptAccess → ${host.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_ACCESS, host.subsystemRatings.access, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    }

    /** Defeats scramble IC on a file before download or edit. PRD: SO individual table */
    fun decryptFile(file: DataFile, host: Host, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] decryptFile → ${file.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_FILE, host.subsystemRatings.files, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    }

    /** Defeats scramble IC on a Slave subsystem. PRD: SO individual table */
    fun decryptSlave(host: Host, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] decryptSlave → ${host.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.DECRYPT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    }

    // ── Interrogation operations ──────────────────────────────────────────────────

    /**
     * One interrogation step toward locating a file on [host].
     * Accumulate 5+ successes to locate. PRD: SO-05 through SO-09
     */
    fun locateFile(
        host: Host,
        state: InterrogationState,
        precision: QueryPrecision,
        diceRoller: DiceRoller
    ): Pair<OperationResult, LocateResult> {
        logger.info { "[$name] locateFile on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
        requireJackedIn()
        val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_FILE, host, state, precision, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        val locateResult = when {
            newState.accumulatedSuccesses >= 5 -> {
                val file = host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
                if (file != null) LocateResult.Located(file, newState.accumulatedSuccesses)
                else LocateResult.NotFound
            }
            newState.accumulatedSuccesses >= 3 && host.dataFiles.none { it.name.contains(state.query, ignoreCase = true) } ->
                LocateResult.NotFound
            else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
        }
        logger.info { "[$name] locateFile result: $locateResult" }
        val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
        return Pair(opResult, locateResult)
    }

    /**
     * One interrogation step toward locating a slave device on [host].
     * Requires only 3 accumulated successes (vs 5 for file/node). PRD: SO individual table
     */
    fun locateSlave(
        host: Host,
        state: InterrogationState,
        precision: QueryPrecision,
        diceRoller: DiceRoller
    ): Pair<OperationResult, LocateResult> {
        logger.info { "[$name] locateSlave on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
        requireJackedIn()
        val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_SLAVE, host, state, precision, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        val locateResult = when {
            newState.accumulatedSuccesses >= 3 -> {
                val device = host.remoteDevices.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
                if (device != null) LocateResult.Located(device, newState.accumulatedSuccesses)
                else LocateResult.NotFound
            }
            else -> LocateResult.Ongoing(newState.accumulatedSuccesses)
        }
        logger.info { "[$name] locateSlave result: $locateResult" }
        val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
        return Pair(opResult, locateResult)
    }

    /**
     * One interrogation step toward finding an LTG code or host address.
     * Requires 5 accumulated successes. PRD: SO individual table
     */
    fun locateAccessNode(
        host: Host,
        state: InterrogationState,
        precision: QueryPrecision,
        diceRoller: DiceRoller
    ): Pair<OperationResult, LocateResult> {
        logger.info { "[$name] locateAccessNode on ${host.name} (accumulated=${state.accumulatedSuccesses})" }
        requireJackedIn()
        val (outcome, newState) = SystemTestResolver.resolveInterrogation(this, SystemOperation.LOCATE_ACCESS_NODE, host, state, precision, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        val locateResult = if (newState.accumulatedSuccesses >= 5) LocateResult.Located(state.query, newState.accumulatedSuccesses)
        else LocateResult.Ongoing(newState.accumulatedSuccesses)
        val opResult = if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
        return Pair(opResult, locateResult)
    }

    // ── File operations ───────────────────────────────────────────────────────────

    /**
     * Starts a Download Data operation. Returns a [DownloadHandle] tracking the transfer.
     * Transfer time = ceil(file.sizeMp / ioSpeed) turns. PRD: SO-10 through SO-12
     */
    fun downloadData(file: DataFile, host: Host, diceRoller: DiceRoller): Pair<OperationResult, DownloadHandle?> {
        logger.info { "[$name] downloadData → ${file.name} (${file.sizeMp} Mp)" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.DOWNLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) {
            val turns = ceil(file.sizeMp.toDouble() / cyberdeck.ioSpeedMpPerTurn).toInt().coerceAtLeast(1)
            val handle = DownloadHandle(file, file.sizeMp, cyberdeck.ioSpeedMpPerTurn, turns)
            logger.info { "[$name] downloadData started: ${handle.turnsRemaining} turns at ${cyberdeck.ioSpeedMpPerTurn} Mp/turn" }
            Pair(OperationResult.Success(updated, outcome), handle)
        } else {
            logger.warn { "[$name] downloadData failed" }
            Pair(OperationResult.Failure(updated, outcome), null)
        }
    }

    /**
     * Edit (create, modify, or delete) a file on the host.
     * [newContent] = null means deletion. Optionally attempts header authentication.
     * PRD: SO individual table
     */
    fun editFile(
        file: DataFile,
        host: Host,
        newContent: ByteArray?,
        diceRoller: DiceRoller,
        attemptAuthentication: Boolean = false
    ): EditFileResult {
        logger.info { "[$name] editFile → ${file.name} (delete=${newContent == null})" }
        requireJackedIn()
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

    /**
     * Upload data from deck storage to the Matrix. For modifying an existing file,
     * follow with [editFile]. PRD: SO individual table
     */
    fun uploadData(host: Host, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] uploadData → ${host.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.UPLOAD_DATA, host.subsystemRatings.files, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    }

    // ── Slave operations (Monitored) ──────────────────────────────────────────────

    /**
     * Take control of a remote device. Pass [effectiveSkill] for manufacturing/scientific
     * processes (average of Computer + relevant B/R skill); null uses the decker's Computer Skill.
     * PRD: SO individual table
     */
    fun controlSlave(
        device: RemoteDevice,
        host: Host,
        diceRoller: DiceRoller,
        effectiveSkill: Int? = null
    ): Pair<OperationResult, MonitoredOperationHandle?> {
        logger.info { "[$name] controlSlave → ${device.name}" }
        requireJackedIn()
        val skill = effectiveSkill ?: computerSkill
        val spoof = cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.SPOOF }
        val tn = maxOf(2, host.subsystemRatings.slave - (spoof?.let { SystemTestResolver.effectiveRating(it, cyberdeck) } ?: 0))
        val deckerResult = diceRoller.roll(skill, tn)
        val hostResult = diceRoller.roll(host.securityRating.value, detectionFactor)
        val outcome = SystemTestOutcome(deckerResult.successes, hostResult.successes, deckerResult.successes >= hostResult.successes)
        logger.info { "[$name] controlSlave: decker=$skill dice TN=$tn → ${deckerResult.successes}; host=${hostResult.successes}" }
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) {
            Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.CONTROL_SLAVE, device))
        } else {
            Pair(OperationResult.Failure(updated, outcome), null)
        }
    }

    /**
     * Modify data sent to/from a remote device (fake camera feeds, alter sensor readings).
     * Monitored operation. PRD: SO individual table
     */
    fun editSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
        logger.info { "[$name] editSlave → ${device.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.EDIT_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.EDIT_SLAVE, device))
        else Pair(OperationResult.Failure(updated, outcome), null)
    }

    /**
     * Read data from a remote device (audio, video, sensor readouts).
     * Monitored operation. PRD: SO individual table
     */
    fun monitorSlave(device: RemoteDevice, host: Host, diceRoller: DiceRoller): Pair<OperationResult, MonitoredOperationHandle?> {
        logger.info { "[$name] monitorSlave → ${device.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolve(this, SystemOperation.MONITOR_SLAVE, host.subsystemRatings.slave, host.securityRating.value, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) Pair(OperationResult.Success(updated, outcome), MonitoredOperationHandle(SystemOperation.MONITOR_SLAVE, device))
        else Pair(OperationResult.Failure(updated, outcome), null)
    }

    /**
     * Supply the required Free Action to keep a monitored operation running.
     * Missing this call aborts the operation (handle.active = false). PRD: SO-13, SO-14
     */
    fun maintainMonitoredOperation(handle: MonitoredOperationHandle): MonitoredOperationHandle {
        return if (handle.active) handle else handle.also {
            logger.warn { "[$name] maintainMonitoredOperation: handle already inactive" }
        }
    }

    /**
     * Abort a monitored operation by not maintaining it (sets active = false).
     * PRD: SO-14
     */
    fun abortMonitoredOperation(handle: MonitoredOperationHandle): MonitoredOperationHandle =
        handle.copy(active = false).also {
            logger.warn { "[$name] abortMonitoredOperation: ${handle.operation.name} aborted" }
        }

    // ── Null Operation ────────────────────────────────────────────────────────────

    /**
     * GM-called when the decker is inactive. Applies inactivity bonus to host Security Value.
     * PRD: SO individual table
     */
    fun nullOperation(host: Host, inactivitySeconds: Int, diceRoller: DiceRoller): OperationResult {
        logger.info { "[$name] nullOperation: inactivity=${inactivitySeconds}s on ${host.name}" }
        requireJackedIn()
        val outcome = SystemTestResolver.resolveNullOperation(this, host, inactivitySeconds, diceRoller)
        val updated = withUpdatedTally(outcome.hostSuccesses)
        return if (outcome.deckerWins) OperationResult.Success(updated, outcome) else OperationResult.Failure(updated, outcome)
    }

    // ── Distributed Databases ─────────────────────────────────────────────────────

    /**
     * When a located file is a pointer, resolve the chain of intermediate hosts.
     * Chain length = 1D6 from the first available die. PRD: SO-03, SO-04
     */
    fun resolvePointerChain(file: DataFile, diceRoller: DiceRoller): PointerChain {
        require(file.isPointer) { "resolvePointerChain called on a non-pointer DataFile" }
        val chainLength = diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 } // 1D6
        val links = buildList {
            var current = file.pointerToHost!!
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

    // ── Private helpers ───────────────────────────────────────────────────────────

    /** Returns the current security tally for [host] from currentLocation, or 0 if not on that host. */
    private fun tallyFor(host: Host): Int =
        (currentLocation as? MatrixLocation.OnHost)?.takeIf { it.host == host }?.host?.securityTally ?: 0

    /**
     * Returns a copy of this decker with the security tally on the current host/grid
     * incremented by [hostSuccesses]. If not on a host/grid, returns this unchanged.
     */
    private fun withUpdatedTally(hostSuccesses: Int): Decker {
        if (hostSuccesses == 0) return this
        return when (val loc = currentLocation) {
            is MatrixLocation.OnHost  -> copy(currentLocation = MatrixLocation.OnHost(loc.host.copy(securityTally = loc.host.securityTally + hostSuccesses)))
            is MatrixLocation.OnLTG   -> copy(currentLocation = MatrixLocation.OnLTG(loc.ltg.copy(securityTally = loc.ltg.securityTally + hostSuccesses)))
            is MatrixLocation.OnRTG   -> copy(currentLocation = MatrixLocation.OnRTG(loc.rtg.copy(securityTally = loc.rtg.securityTally + hostSuccesses)))
            is MatrixLocation.OnPLTG  -> copy(currentLocation = MatrixLocation.OnPLTG(loc.pltg.copy(securityTally = loc.pltg.securityTally + hostSuccesses)))
            null                      -> this
        }
    }
    private fun requireJackpoint(): Jackpoint =
        checkNotNull(jackpoint) { "Decker has no jackpoint set" }

    private fun requireNotJackedIn() =
        check(persona == null && currentLocation == null) { "Decker is already jacked in" }

    private fun requireJackedIn() =
        check(currentLocation != null) { "Decker is not jacked in" }

    /** Run the System Test and return a LogonResult; [buildLocation] maps host tally delta → new MatrixLocation. */
    private fun performLogon(
        operation: SystemOperation,
        accessRating: Int,
        securityValue: Int,
        diceRoller: DiceRoller,
        buildLocation: (Int) -> MatrixLocation
    ): LogonResult {
        val outcome = SystemTestResolver.resolve(this, operation, accessRating, securityValue, diceRoller)
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
                    .firstOrNull { it.attributeType == com.shadowrun.matrix.common.PersonaAttributeType.SENSORS }?.rating ?: 0,
                reaction = reaction + cyberdeck.responseIncrease * 2
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
