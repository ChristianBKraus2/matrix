package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.MatrixIcon
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import com.shadowrun.matrix.combat.BlackIcPinState
import com.shadowrun.matrix.combat.CombatInitiative
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.combat.IcSuppressionState
import com.shadowrun.matrix.combat.TrackState
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.GameContext
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
    val currentLocation: MatrixLocation? = null,
    val blackIcPin: BlackIcPinState? = null,
    val trackState: TrackState? = null,
    val meatworldComm: Boolean = false,
    val suppressedIc: List<IcSuppressionState> = emptyList(),
    val runDownloadedFiles: List<DataFile> = emptyList(),
    val interrogationStates: Map<SystemOperation, InterrogationState> = emptyMap(),
    val detectedIcons: Set<MatrixIcon> = emptySet()
) : ActiveIcon {
    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult = ActionResult.DeckerAction

    override fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative =
        CombatResolver.rollDeckerInitiative(this, meatworldComm = meatworldComm, diceRoller)

    val hackingPool: Int get() = (intelligence + cyberdeck.mcpRating) / 3
    val isPinnedByBlackIc: Boolean get() = blackIcPin != null

    /** Each suppressed IC reduces Detection Factor by 1 (CC-22). */
    val suppressionDfPenalty: Int get() = suppressedIc.size

    /** Detection Factor used by the host in all System Tests = base DF minus suppression penalty. */
    val effectiveDetectionFactor: Int get() = detectionFactor - suppressionDfPenalty

    /** Detection Factor = ceil((Masking + Sleaze.currentRating) / 2); or ceil(Masking / 2) if no Sleaze active.
     *  Recalculated dynamically — Sleaze in pendingUploads does not count. PRD: CD-17, CD-18 */
    val detectionFactor: Int get() {
        val masking = cyberdeck.personaPrograms
            .firstOrNull { it.attributeType == PersonaAttributeType.MASKING }
            ?.rating ?: 0
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
    fun visibleObjects(): List<MatrixObject> {
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
                loc.host.icPrograms.forEach { add(MatrixObject.IcProgram(it)) }
                loc.host.dataFiles.forEach { add(MatrixObject.File(it)) }
                loc.host.remoteDevices.forEach { add(MatrixObject.Device(it)) }
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

            when (val loc = currentLocation) {
                null -> Unit

                is MatrixLocation.OnRTG -> {
                    loc.rtg.connectedRtgs.forEach { add(AvailableAction.LogonToRtg(it)) }
                    loc.rtg.ltgs.forEach { add(AvailableAction.LogonToLtg(it)) }
                    addGridSystemActions()
                }

                is MatrixLocation.OnLTG -> {
                    add(AvailableAction.LogonToRtg(loc.ltg.parentRtg))
                    loc.ltg.pltgs.forEach { add(AvailableAction.LogonToPltg(it)) }
                    loc.ltg.hosts.forEach { add(AvailableAction.LogonToHost(it)) }
                    addGridSystemActions()
                }

                is MatrixLocation.OnPLTG -> {
                    add(AvailableAction.LogonToLtg(loc.pltg.parentLtg))
                    loc.pltg.hosts.forEach { add(AvailableAction.LogonToHost(it)) }
                    addGridSystemActions()
                }

                is MatrixLocation.OnHost -> {
                    loc.host.connectedHosts.forEach { add(AvailableAction.LogonToHost(it)) }
                    addHostSystemActions(loc.host)
                }
            }
        }
    }

    private fun MutableList<AvailableAction>.addGridSystemActions() {
        add(AvailableAction.Operation(SystemOperation.NULL_OPERATION))
        add(AvailableAction.Operation(SystemOperation.LOCATE_ACCESS_NODE))
        add(AvailableAction.Operation(SystemOperation.ANALYZE_SECURITY))
        add(AvailableAction.Operation(SystemOperation.LOCATE_IC))
        add(AvailableAction.Operation(SystemOperation.ANALYZE_IC))
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
        add(AvailableAction.Operation(SystemOperation.DECRYPT_ACCESS))
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
        host.dataFiles.forEach {
            val obj = MatrixObject.File(it)
            add(AvailableAction.Operation(SystemOperation.ANALYZE_ICON, obj))
            if (!it.isScrambleProtected) {
                add(AvailableAction.Operation(SystemOperation.DOWNLOAD_DATA, obj))
                add(AvailableAction.Operation(SystemOperation.EDIT_FILE, obj))
            }
            if (it.isScrambleProtected) add(AvailableAction.Operation(SystemOperation.DECRYPT_FILE, obj))
        }
        host.remoteDevices.forEach {
            val obj = MatrixObject.Device(it)
            add(AvailableAction.Operation(SystemOperation.ANALYZE_ICON, obj))
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
        return when (val loc = currentLocation) {
            is MatrixLocation.OnHost  -> copy(currentLocation = MatrixLocation.OnHost(loc.host.copy(securityTally = loc.host.securityTally + hostSuccesses)))
            is MatrixLocation.OnLTG   -> copy(currentLocation = MatrixLocation.OnLTG(loc.ltg.copy(securityTally = loc.ltg.securityTally + hostSuccesses)))
            is MatrixLocation.OnRTG   -> copy(currentLocation = MatrixLocation.OnRTG(loc.rtg.copy(securityTally = loc.rtg.securityTally + hostSuccesses)))
            is MatrixLocation.OnPLTG  -> copy(currentLocation = MatrixLocation.OnPLTG(loc.pltg.copy(securityTally = loc.pltg.securityTally + hostSuccesses)))
            null                      -> this
        }
    }

}
