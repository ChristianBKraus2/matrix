package com.shadowrun.matrix.server

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.*
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.AnalyzeHostResult
import com.shadowrun.matrix.operations.AnalyzeSecurityResult
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.EditFileResult
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.LocateDeckerResult
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.MatrixIcon
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.MatrixJson
import com.shadowrun.matrix.server.dto.ResultMessage
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import com.shadowrun.matrix.server.dto.toDto
import com.shadowrun.matrix.utility.DiceRoller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

class WebSocketDeckerController(
    private val registry: SessionRegistry,
    initialDecker: Decker,
    private val actionTimeoutSeconds: Long = 120
) : ActiveIcon {

    var decker: Decker = initialDecker
        private set

    override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val visibleObjects = decker.visibleObjects()
        val availableActions = decker.availableActions()
            .filterNot { it is AvailableAction.Operation &&
                         it.operation in setOf(SystemOperation.SWAP_MEMORY, SystemOperation.LOCATE_DECKER) }

        // Set pendingAction BEFORE promoteForTurn so receiveAction never sees a null future
        // after the client learns it is the active controller (fixes TOCTOU race).
        val deferred = CompletableDeferred<ActionCommand>()
        registry.setPendingAction(deferred)
        val hasController = registry.promoteForTurn(decker.name)
        if (!hasController) {
            registry.setPendingAction(null)
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(
                success = false, deckerSuccesses = 0, hostSuccesses = 0,
                details = "No controller registered for decker ${decker.name} — turn skipped"
            )))
            return ActionResult.DeckerAction
        }

        val stateBase = StateMessage(
            role = SessionRole.OBSERVER,
            decker = decker.toDto(),
            visibleObjects = visibleObjects.toDto(),
            availableActions = availableActions.toDto()
        )
        registry.broadcastWithRoles(stateBase)

        val cmd = try {
            withTimeoutOrNull(actionTimeoutSeconds * 1000L) { deferred.await() }
        } catch (_: DeckerDisconnectedException) {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Decker disconnected — turn forfeit")))
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        } catch (e: Exception) {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Unexpected error — turn aborted")))
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        } finally {
            registry.setPendingAction(null)
        }

        if (cmd == null) {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Action timed out")))
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        }

        val chosen = availableActions.getOrNull(cmd.actionIndex)
        if (chosen == null) {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Invalid action index ${cmd.actionIndex}")))
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        }

        val oldDecker = decker
        try {
            val result = dispatch(chosen, cmd, diceRoller)
            decker = result.decker
            context.applyDeckerOperationResult(oldDecker, decker)
            // Re-read from context: applyDeckerOperationResult may have replaced the decker
            // reference (e.g. alert transition updates the embedded host object).
            decker = context.deckers.firstOrNull { it.name == decker.name } ?: decker
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(
                success = result.success,
                deckerSuccesses = result.deckerSuccesses,
                hostSuccesses = result.hostSuccesses,
                details = result.details
            )))
            registry.demoteAfterTurn(decker.name)
        } catch (e: Exception) {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(
                success = false, deckerSuccesses = 0, hostSuccesses = 0,
                details = "Internal error — turn aborted"
            )))
            registry.demoteAfterTurn(decker.name)
        }

        return ActionResult.DeckerAction
    }

    private fun dispatch(action: AvailableAction, cmd: ActionCommand, diceRoller: DiceRoller): DispatchResult {
        val host = (decker.currentLocation as? MatrixLocation.OnHost)?.host
        return when (action) {
            is AvailableAction.LogonToRtg     -> decker.logonToRtg(action.rtg, diceRoller).toDispatch()
            is AvailableAction.LogonToLtg     -> decker.logonToLtg(action.ltg, diceRoller).toDispatch()
            is AvailableAction.LogonToPltg    -> decker.logonToPltg(action.pltg, diceRoller).toDispatch()
            is AvailableAction.LogonToHost    -> decker.logonToHost(action.host, diceRoller).toDispatch()
            is AvailableAction.GracefulLogoff -> decker.gracefulLogoff(diceRoller).toDispatch()
            is AvailableAction.JackOut        -> {
                if (decker.isPinnedByBlackIc)
                    DispatchResult(decker, false, 0, 0, "Pinned by Black IC — cannot jack out")
                else
                    decker.jackOut().toDispatch()
            }
            is AvailableAction.Operation -> {
                if (host == null) dispatchGridOperation(action, cmd, diceRoller)
                else dispatchHostOperation(action, cmd, host, diceRoller)
            }
        }
    }

    private fun dispatchGridOperation(
        action: AvailableAction.Operation,
        @Suppress("UNUSED_PARAMETER") cmd: ActionCommand,
        diceRoller: DiceRoller
    ): DispatchResult = when (action.operation) {
        SystemOperation.RELOCATE_ICON -> {
            val trackState = decker.trackState
            decker.relocateIcon(
                opponentSensor = trackState?.trackingIcRating ?: 0,
                trackerMcpRating = trackState?.trackingIcRating ?: 0,
                diceRoller
            ).toDispatch()
        }
        else -> DispatchResult(decker, false, 0, 0, "${action.operation} requires host context")
    }

    private fun dispatchHostOperation(
        action: AvailableAction.Operation,
        cmd: ActionCommand,
        host: Host,
        diceRoller: DiceRoller
    ): DispatchResult = when (action.operation) {
        SystemOperation.ANALYZE_HOST,
        SystemOperation.ANALYZE_IC,
        SystemOperation.ANALYZE_ICON,
        SystemOperation.ANALYZE_SECURITY,
        SystemOperation.ANALYZE_SUBSYSTEM   -> dispatchAnalyzeOp(action, host, diceRoller)
        SystemOperation.LOCATE_FILE,
        SystemOperation.LOCATE_SLAVE,
        SystemOperation.LOCATE_ACCESS_NODE,
        SystemOperation.LOCATE_DECKER,
        SystemOperation.LOCATE_IC           -> dispatchLocateOp(action, cmd, host, diceRoller)
        SystemOperation.DOWNLOAD_DATA,
        SystemOperation.EDIT_FILE,
        SystemOperation.UPLOAD_DATA,
        SystemOperation.DECRYPT_ACCESS,
        SystemOperation.DECRYPT_FILE,
        SystemOperation.DECRYPT_SLAVE       -> dispatchDataOp(action, cmd, host, diceRoller)
        SystemOperation.CONTROL_SLAVE,
        SystemOperation.EDIT_SLAVE,
        SystemOperation.MONITOR_SLAVE       -> dispatchSlaveOp(action, host, diceRoller)
        SystemOperation.MAKE_COMCALL,
        SystemOperation.TAP_COMCALL         -> dispatchCommsOp(action, cmd, host, diceRoller)
        SystemOperation.NULL_OPERATION,
        SystemOperation.RELOCATE_ICON,
        SystemOperation.SWAP_MEMORY         -> dispatchMiscOp(action, cmd, host, diceRoller)
        else -> DispatchResult(decker, false, 0, 0, "Unsupported: ${action.operation}")
    }

    private fun dispatchAnalyzeOp(action: AvailableAction.Operation, host: Host, diceRoller: DiceRoller): DispatchResult =
        when (action.operation) {
            SystemOperation.ANALYZE_HOST -> {
                val items: List<HostInfoItem> =
                    listOf(HostInfoItem.SecurityRating) + SubsystemType.entries.map { HostInfoItem.Subsystem(it) }
                decker.analyzeHost(host, items, diceRoller).toDispatch()
            }
            SystemOperation.ANALYZE_IC -> {
                val ic = (action.target as MatrixObject.IcProgram).ic
                decker.analyzeIc(ic, host, diceRoller).toDispatch()
            }
            SystemOperation.ANALYZE_ICON -> {
                val ic = (action.target as MatrixObject.IcProgram).ic
                decker.analyzeIcon(MatrixIcon.IcIcon(ic), host, diceRoller).toDispatch()
            }
            SystemOperation.ANALYZE_SECURITY  -> decker.analyzeSecurity(host, diceRoller).toDispatch()
            SystemOperation.ANALYZE_SUBSYSTEM -> {
                val node = (action.target as MatrixObject.HostSubsystem).node
                decker.analyzeSubsystem(host, node.subsystemType, diceRoller).toDispatch()
            }
            else -> DispatchResult(decker, false, 0, 0, "Unsupported analyze op: ${action.operation}")
        }

    private fun dispatchLocateOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.LOCATE_FILE -> {
                val (opResult, locateResult) = locateWithState(p) { prec -> decker.locateFile(host, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_SLAVE -> {
                val (opResult, locateResult) = locateWithState(p) { prec -> decker.locateSlave(host, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_ACCESS_NODE -> {
                val (opResult, locateResult) = locateWithState(p) { prec -> decker.locateAccessNode(host, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_DECKER -> DispatchResult(decker, false, 0, 0, "LOCATE_DECKER requires a target Persona — not supported via WebSocket")
            SystemOperation.LOCATE_IC     -> decker.locateIc(host, diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported locate op: ${action.operation}")
        }
    }

    private fun dispatchDataOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.DOWNLOAD_DATA -> {
                val file = (action.target as MatrixObject.File).file
                decker.downloadData(file, host, diceRoller).first.toDispatch()
            }
            SystemOperation.EDIT_FILE -> {
                val file = (action.target as MatrixObject.File).file
                val content = p?.newContent
                if (content != null && content.length > 4096) {
                    return DispatchResult(decker, false, 0, 0, "File content exceeds maximum allowed size")
                }
                decker.editFile(file, host, content?.toByteArray(), diceRoller).toDispatch()
            }
            SystemOperation.UPLOAD_DATA    -> decker.uploadData(host, diceRoller).toDispatch()
            SystemOperation.DECRYPT_ACCESS -> decker.decryptAccess(host, diceRoller).toDispatch()
            SystemOperation.DECRYPT_FILE -> {
                val file = (action.target as MatrixObject.File).file
                decker.decryptFile(file, host, diceRoller).toDispatch()
            }
            SystemOperation.DECRYPT_SLAVE -> decker.decryptSlave(host, diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported data op: ${action.operation}")
        }
    }

    private fun dispatchSlaveOp(action: AvailableAction.Operation, host: Host, diceRoller: DiceRoller): DispatchResult =
        when (action.operation) {
            SystemOperation.CONTROL_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.controlSlave(device, host, diceRoller).first.toDispatch()
            }
            SystemOperation.EDIT_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.editSlave(device, host, diceRoller).first.toDispatch()
            }
            SystemOperation.MONITOR_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.monitorSlave(device, host, diceRoller).first.toDispatch()
            }
            else -> DispatchResult(decker, false, 0, 0, "Unsupported slave op: ${action.operation}")
        }

    private fun dispatchCommsOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.MAKE_COMCALL -> decker.makeComcall(host, diceRoller, p?.hasValidPasscode ?: false).first.toDispatch()
            SystemOperation.TAP_COMCALL  -> decker.tapComcall(host, p?.scannerDeviceRating ?: 0, diceRoller).first.toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported comms op: ${action.operation}")
        }
    }

    private fun dispatchMiscOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.NULL_OPERATION -> decker.nullOperation(host, p?.inactivitySeconds ?: 0, diceRoller).toDispatch()
            SystemOperation.RELOCATE_ICON  -> {
                val trackState = decker.trackState
                decker.relocateIcon(
                    opponentSensor = trackState?.trackingIcRating ?: 0,
                    trackerMcpRating = trackState?.trackingIcRating ?: 0,
                    diceRoller
                ).toDispatch()
            }
            SystemOperation.SWAP_MEMORY -> DispatchResult(decker, false, 0, 0, "SWAP_MEMORY requires utility selection — not supported via WebSocket")
            else -> DispatchResult(decker, false, 0, 0, "Unsupported misc op: ${action.operation}")
        }
    }

    private fun locateWithState(
        params: com.shadowrun.matrix.server.dto.ActionParams?,
        call: (QueryPrecision) -> Pair<OperationResult, LocateResult>
    ): Pair<OperationResult, LocateResult> {
        val precision = params?.precision?.let { runCatching { QueryPrecision.valueOf(it) }.getOrNull() } ?: QueryPrecision.NORMAL
        return call(precision)
    }

    // ── Result converters ──────────────────────────────────────────────────────

    private data class DispatchResult(
        val decker: Decker,
        val success: Boolean,
        val deckerSuccesses: Int,
        val hostSuccesses: Int,
        val details: String
    )

    private fun LogonResult.toDispatch() = when (this) {
        is LogonResult.Success -> DispatchResult(decker, true,  0, 0, "Logged on to $location")
        is LogonResult.Failure -> DispatchResult(decker, false, 0, 0, "Logon failed")
    }

    private fun LogoffResult.toDispatch() = when (this) {
        is LogoffResult.GracefulSuccess -> DispatchResult(decker, true, 0, 0, "Graceful logoff")
        is LogoffResult.JackOut         -> DispatchResult(decker, true, 0, 0, if (dumpShock) "Jacked out (dump shock!)" else "Jacked out")
    }

    private fun OperationResult.toDispatch(extra: String = ""): DispatchResult {
        val base = "${outcome.deckerSuccesses} decker vs ${outcome.hostSuccesses} host"
        val details = if (extra.isBlank()) base else "$base — $extra"
        return DispatchResult(decker, this is OperationResult.Success, outcome.deckerSuccesses, outcome.hostSuccesses, details)
    }

    private fun AnalyzeHostResult.toDispatch(): DispatchResult {
        val details = buildString {
            append("${outcome.deckerSuccesses} vs ${outcome.hostSuccesses}")
            revealedSecurityRating?.let { append("; security ${it.code}(${it.value})") }
            if (revealedSubsystemRatings.isNotEmpty())
                append("; subsystems: ${revealedSubsystemRatings.entries.joinToString { "${it.key}=${it.value}" }}")
        }
        return DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, details)
    }

    private fun AnalyzeSecurityResult.toDispatch(): DispatchResult {
        val details = "Security ${securityRating.code}(${securityRating.value}), tally=$currentTally, alert=$alertStatus"
        return DispatchResult(decker, true, outcome.deckerSuccesses, outcome.hostSuccesses, details)
    }

    private fun EditFileResult.toDispatch(): DispatchResult {
        val extra = authenticationSuccesses?.let { " (auth=$it)" } ?: ""
        return DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, "Edit file$extra")
    }

    private fun LocateDeckerResult.toDispatch(): DispatchResult {
        val details = if (located) "Target located" + if (targetNotified) " (target notified)" else "" else "Not located"
        return DispatchResult(decker, located, outcome.deckerSuccesses, outcome.hostSuccesses, details)
    }

    private fun LocateResult.label() = when (this) {
        is LocateResult.Ongoing  -> "ongoing (${accumulatedSuccesses} accumulated)"
        is LocateResult.Located  -> "located!"
        LocateResult.NotFound    -> "not found"
    }
}
