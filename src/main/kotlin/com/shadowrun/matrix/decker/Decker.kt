package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.DownloadHandle
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.Icon
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.MonitoredOperationHandle
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.operations.UploadHandle
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import com.shadowrun.matrix.combat.BlackIcPinState
import com.shadowrun.matrix.combat.CombatInitiative
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.combat.EvadeDetectionState
import com.shadowrun.matrix.combat.IcSuppressionState
import com.shadowrun.matrix.combat.TrackState
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.ic.IC
import kotlin.math.ceil

data class Decker(
    val name: String,
    val intelligence: Int,
    val body: Int,
    val willpower: Int,
    val reaction: Int,
    val computerSkill: Int,
    val cyberdeck: Cyberdeck,
    val physicalConditionMonitor: ConditionMonitor = ConditionMonitor(),
    val mentalConditionMonitor: ConditionMonitor = ConditionMonitor(),
    val persona: Persona? = null,
    val jackpoint: Jackpoint? = null,
    val currentLocation: MatrixLocation? = null,
    val blackIcPin: BlackIcPinState? = null,
    val trackState: TrackState? = null,
    val meatworldComm: Boolean = false,
    val suppressedIc: List<IcSuppressionState> = emptyList(),
    val runDownloadedFiles: List<DataFile> = emptyList(),
    val offlineStorageFiles: List<DataFile> = emptyList(),
    val activeDownloads: List<DownloadHandle> = emptyList(),
    val activeUploads: List<UploadHandle> = emptyList(),
    val activeMonitoredOperations: List<MonitoredOperationHandle> = emptyList(),
    val interrogationStates: Map<String, InterrogationState> = emptyMap(),
    val detectedIcons: Set<Icon> = emptySet(),
    val analyzedIcNames: Set<String> = emptySet(),
    val analyzeSecuritySystems: Set<String> = emptySet(),
    val knownPasscodes: Set<String> = emptySet(),
    /** LTG/PLTG/host names the decker has stored an address for and may Access (ticket 06, persisted). */
    val knownAddresses: Set<String> = emptySet(),
    /** Host-qualified keys `"<hostName>::<fileName>"` of files revealed by Locate File (run-scoped). */
    val locatedFiles: Set<String> = emptySet(),
    /** Host-qualified keys `"<hostName>::<deviceName>"` of slaves revealed by Locate Slave (run-scoped). */
    val locatedSlaves: Set<String> = emptySet(),
    /** Names of hosts whose scramble-protected SAN has been successfully defeated via Decrypt Access (ticket 17, run-scoped). */
    val decryptedSans: Set<String> = emptySet(),
    /** Candidates from the most recent successful Locate, awaiting the decker's selection (ticket 06). */
    val pendingLocate: com.shadowrun.matrix.operations.PendingLocate? = null,
    val hackingPoolUsed: Int = 0,
    val evadeDetectionStates: List<EvadeDetectionState> = emptyList()
) : ActiveIcon {
    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult = ActionResult.DeckerAction

    override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
        CombatResolver.rollDeckerInitiative(this, meatworldComm = meatworldComm, diceRoller)

    val hackingPool: Int get() = (intelligence + cyberdeck.mcpRating) / 3
    val remainingHackingPool: Int get() = hackingPool - hackingPoolUsed
    val isPinnedByBlackIc: Boolean get() = blackIcPin != null

    /** Each suppressed IC reduces Detection Factor by 1 (CC-22). */
    val suppressionDfPenalty: Int get() = suppressedIc.size

    val detectedIcNames: Set<String>
        get() = detectedIcons.filterIsInstance<Icon.IcIcon>().mapTo(mutableSetOf()) { it.ic.name }

    /** Detection Factor used by the host in all System Tests = base DF minus suppression penalty, floored at 2. */
    val effectiveDetectionFactor: Int get() = maxOf(2, detectionFactor - suppressionDfPenalty)

    /** Detection Factor = ceil((Masking + Sleaze.currentRating) / 2); or ceil(Masking / 2) if no Sleaze active.
     *  Recalculated dynamically — Sleaze in pendingUploads does not count. PRD: CD-17, CD-18 */
    val detectionFactor: Int get() {
        val masking = persona?.masking
            ?: cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.MASKING }?.rating
            ?: 0
        val sleaze = cyberdeck.activeUtilities
            .firstOrNull { it.type == UtilityType.SLEAZE }?.currentRating
        return cyberdeck.detectionFactor(masking, sleaze)
    }

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

    /**
     * Returns all Matrix objects visible to the decker from their current location.
     * Returns empty if not jacked in.
     */
    fun visibleObjects(activeIc: List<IC> = emptyList()): List<MatrixObject> {
        if (persona == null) return emptyList()
        return when (val loc = currentLocation) {
            null -> emptyList()
            is MatrixLocation.OnRTG -> buildList {
                add(MatrixObject.GridNode(loc.rtg))
                loc.rtg.connectedRtgs.forEach { add(MatrixObject.GridNode(it)) }
                loc.rtg.ltgs.forEach { add(MatrixObject.LocalGrid(it)) }
            }
            is MatrixLocation.OnLTG -> buildList {
                add(MatrixObject.LocalGrid(loc.ltg))
                add(MatrixObject.GridNode(loc.ltg.parentRtg))
                loc.ltg.pltgs.forEach { add(MatrixObject.PrivateGrid(it)) }
                loc.ltg.hosts.forEach { add(MatrixObject.HostNode(it)) }
            }
            is MatrixLocation.OnPLTG -> buildList {
                add(MatrixObject.PrivateGrid(loc.pltg))
                add(MatrixObject.LocalGrid(loc.pltg.parentLtg))
                loc.pltg.hosts.forEach { add(MatrixObject.HostNode(it)) }
            }
            is MatrixLocation.OnHost -> buildList {
                add(MatrixObject.HostNode(loc.host))
                loc.host.nodes.forEach { add(MatrixObject.HostSubsystem(it)) }
                // IC visibility is identity-based (not name-based): only IC whose specific instance has
                // been located/detected is shown. Resident host IC and triggered active IC are unioned
                // and de-duplicated by identity so an IC that appears in both pools renders once (ticket 15).
                val detectedIcs = detectedIcons.filterIsInstance<Icon.IcIcon>().map { it.ic }
                (loc.host.icPrograms + activeIc)
                    .filter { candidate -> detectedIcs.any { it.matchesIdentity(candidate) } }
                    .distinctBy { listOf(it::class, it.name, it.rating, it.guardedNode) }
                    .forEach { add(MatrixObject.IcProgram(it, analyzed = it.name in analyzedIcNames)) }
                loc.host.dataFiles
                    .filter { "${loc.host.name}::${it.name}" in locatedFiles }
                    .forEach { add(MatrixObject.File(it)) }
                loc.host.remoteDevices
                    .filter { "${loc.host.name}::${it.name}" in locatedSlaves }
                    .forEach { add(MatrixObject.Device(it)) }
                loc.host.connectedHosts.forEach { add(MatrixObject.HostNode(it)) }
            }
        }
    }

    /**
     * Returns all actions the decker can attempt from their current location.
     * Returns empty if not jacked in.
     * Availability is positional only — whether the required utility is loaded is left to the caller.
     */
    fun availableActions(): List<AvailableAction> {
        if (persona == null) return emptyList()
        return buildList {
            add(AvailableAction.GracefulLogoff())
            add(AvailableAction.JackOut())

            pendingLocate?.let { add(AvailableAction.SelectLocateTarget(it.operation, it.candidates)) }

            when (val loc = currentLocation) {
                null -> Unit

                is MatrixLocation.OnRTG -> {
                    loc.rtg.connectedRtgs.forEach { add(AvailableAction.LogonToRtg(it)) }
                    // Access is gated to LTGs whose address the decker has stored (ticket 06).
                    val ltgs = loc.rtg.ltgs.filter { it.name in knownAddresses }
                    if (ltgs.isNotEmpty()) add(AvailableAction.AccessLtg(ltgs))
                    addGridSystemActions()
                }

                is MatrixLocation.OnLTG -> {
                    add(AvailableAction.LogonToRtg(loc.ltg.parentRtg))
                    val ltgTargets = loc.ltg.pltgs.filter { it.name in knownAddresses }
                    if (ltgTargets.isNotEmpty()) add(AvailableAction.AccessLtg(ltgTargets))
                    val knownHosts = loc.ltg.hosts.filter { it.name in knownAddresses }
                    addHostNavigationActions(knownHosts)
                    addGridSystemActions()
                }

                is MatrixLocation.OnPLTG -> {
                    // Navigating back up to the parent LTG is still address-gated (ticket 06).
                    val ltgTargets = listOf(loc.pltg.parentLtg).filter { it.name in knownAddresses }
                    if (ltgTargets.isNotEmpty()) add(AvailableAction.AccessLtg(ltgTargets))
                    val knownHosts = loc.pltg.hosts.filter { it.name in knownAddresses }
                    addHostNavigationActions(knownHosts)
                    addGridSystemActions()
                }

                is MatrixLocation.OnHost -> {
                    val knownHosts = loc.host.connectedHosts.filter { it.name in knownAddresses }
                    addHostNavigationActions(knownHosts)
                    addHostSystemActions(loc.host)
                }
            }
        }
    }

    /** Splits hosts into accessible (no scramble or already decrypted) vs. needs-decrypt, adding the appropriate actions (ticket 17). */
    private fun MutableList<AvailableAction>.addHostNavigationActions(knownHosts: List<Host>) {
        val accessible = knownHosts.filter { host -> host.sans.none { it.isScrambleProtected } || host.name in decryptedSans }
        val needsDecrypt = knownHosts.filter { host -> host.sans.any { it.isScrambleProtected } && host.name !in decryptedSans }
        if (accessible.isNotEmpty()) add(AvailableAction.AccessHost(accessible))
        if (needsDecrypt.isNotEmpty()) add(AvailableAction.DecryptAccess(needsDecrypt))
    }

    private fun MutableList<AvailableAction>.addGridSystemActions() {
        add(AvailableAction.Operation(SystemOperation.NULL_OPERATION))
        add(AvailableAction.Operation(SystemOperation.LOCATE_ACCESS_NODE))
        add(AvailableAction.Operation(SystemOperation.ANALYZE_SECURITY))
    }

    private fun MutableList<AvailableAction>.addHostSystemActions(host: Host) {
        add(AvailableAction.Operation(SystemOperation.ANALYZE_HOST))
        add(AvailableAction.Operation(SystemOperation.ANALYZE_SECURITY))
        add(AvailableAction.Operation(SystemOperation.NULL_OPERATION))
        add(AvailableAction.Operation(SystemOperation.RELOCATE_ICON))
        add(AvailableAction.Operation(SystemOperation.LOCATE_FILE))
        add(AvailableAction.Operation(SystemOperation.LOCATE_SLAVE))
        add(AvailableAction.Operation(SystemOperation.LOCATE_ACCESS_NODE))
        add(AvailableAction.Operation(SystemOperation.LOCATE_IC))
        add(AvailableAction.Operation(SystemOperation.DECRYPT_SLAVE))
        add(AvailableAction.Operation(SystemOperation.UPLOAD_DATA))
        add(AvailableAction.Operation(SystemOperation.MAKE_COMCALL))
        add(AvailableAction.Operation(SystemOperation.TAP_COMCALL))
        if (cyberdeck.activeUtilities.any { it.type == UtilityType.MEDIC }) {
            add(AvailableAction.Operation(SystemOperation.INVOKE_MEDIC))
        }
        host.nodes.forEach {
            add(AvailableAction.Operation(SystemOperation.ANALYZE_SUBSYSTEM, MatrixObject.HostSubsystem(it)))
        }
        host.icPrograms.forEach {
            val obj = MatrixObject.IcProgram(it)
            add(AvailableAction.Operation(SystemOperation.ANALYZE_IC, obj))
            add(AvailableAction.Operation(SystemOperation.ANALYZE_ICON, obj))
        }
        // File/slave operations gate to targets revealed via Locate File / Locate Slave (ticket 06).
        host.dataFiles.forEach {
            if ("${host.name}::${it.name}" !in locatedFiles) return@forEach
            val obj = MatrixObject.File(it)
            if (!it.isScrambleProtected) {
                add(AvailableAction.Operation(SystemOperation.DOWNLOAD_DATA, obj))
                add(AvailableAction.Operation(SystemOperation.EDIT_FILE, obj))
            }
            if (it.isScrambleProtected) add(AvailableAction.Operation(SystemOperation.DECRYPT_FILE, obj))
        }
        host.remoteDevices.forEach {
            if ("${host.name}::${it.name}" !in locatedSlaves) return@forEach
            val obj = MatrixObject.Device(it)
            add(AvailableAction.Operation(SystemOperation.CONTROL_SLAVE, obj))
            add(AvailableAction.Operation(SystemOperation.EDIT_SLAVE, obj))
            add(AvailableAction.Operation(SystemOperation.MONITOR_SLAVE, obj))
        }
    }

    // ── Internal helpers (used by extension files) ────────────────────────────

    internal fun requireJackedIn() =
        check(currentLocation != null) { "Decker is not jacked in" }

    internal fun withUpdatedTally(hostSuccesses: Int): Decker {
        if (hostSuccesses == 0) return this
        val afterTally = when (val loc = currentLocation) {
            is MatrixLocation.OnHost  -> copy(currentLocation = MatrixLocation.OnHost(loc.host.copy(securityTally = loc.host.securityTally + hostSuccesses)))
            is MatrixLocation.OnLTG   -> copy(currentLocation = MatrixLocation.OnLTG(loc.ltg.copy(securityTally = loc.ltg.securityTally + hostSuccesses)))
            is MatrixLocation.OnRTG   -> copy(currentLocation = MatrixLocation.OnRTG(loc.rtg.copy(securityTally = loc.rtg.securityTally + hostSuccesses)))
            is MatrixLocation.OnPLTG  -> copy(currentLocation = MatrixLocation.OnPLTG(loc.pltg.copy(securityTally = loc.pltg.securityTally + hostSuccesses)))
            null                      -> return this
        }
        // Each tally point added shortens the IC re-detection countdown by 1 (SR3 p. 224–225).
        val (expired, surviving) = afterTally.evadeDetectionStates
            .map { it.copy(turnsRemaining = it.turnsRemaining - hostSuccesses) }
            .partition { it.turnsRemaining <= 0 }
        val expiredNames = expired.mapTo(mutableSetOf()) { it.icName }
        return afterTally.copy(
            evadeDetectionStates = surviving,
            detectedIcons = if (expiredNames.isEmpty()) afterTally.detectedIcons
                else afterTally.detectedIcons.filterTo(mutableSetOf()) { it !is Icon.IcIcon || it.ic.name !in expiredNames }
        )
    }

    /**
     * Decrements every IC re-detection countdown by 1 Combat Turn (SR3 p. 224–225).
     * Call once per Combat Turn from the game loop. Expired entries are removed and
     * their ICs are dropped from detectedIcons — requiring Locate IC to re-detect (CC-18).
     */
    fun tickEvadeCountdowns(): Decker {
        if (evadeDetectionStates.isEmpty()) return this
        val (expired, surviving) = evadeDetectionStates
            .map { it.copy(turnsRemaining = it.turnsRemaining - 1) }
            .partition { it.turnsRemaining <= 0 }
        val expiredNames = expired.mapTo(mutableSetOf()) { it.icName }
        return copy(
            evadeDetectionStates = surviving,
            detectedIcons = detectedIcons.filterTo(mutableSetOf()) { it !is Icon.IcIcon || it.ic.name !in expiredNames }
        )
    }

}
