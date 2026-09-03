package com.shadowrun.matrix.server

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.ic.NonLethalBlackIC
import com.shadowrun.matrix.decker.*import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.GameContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.operations.AnalyzeHostResult
import com.shadowrun.matrix.operations.AnalyzeSecurityResult
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.EditFileResult
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.LocateDeckerResult
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.Icon
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.MonitoredOperationHandle
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
) {

    private val logger = KotlinLogging.logger {}

    @Volatile
    var decker: Decker = initialDecker
        private set

    private suspend fun broadcastFail(details: String) =
        registry.broadcast(MatrixJson.encodeToString(ResultMessage(
            success = false, deckerSuccesses = 0, hostSuccesses = 0, details = details
        )))

    suspend fun conductTurn(context: GameContext, diceRoller: DiceRoller): ActionResult {
        decker = context.deckers.firstOrNull { it.name == decker.name } ?: decker
        val visibleObjects = decker.visibleObjects()
        val availableActions = decker.availableActions()

        // Set pendingAction BEFORE promoteForTurn so receiveAction never sees a null future
        // after the client learns it is the active controller (fixes TOCTOU race).
        val deferred = CompletableDeferred<ActionCommand>()
        registry.setPendingAction(deferred)
        val hasController = registry.promoteForTurn(decker.name)
        if (!hasController) {
            registry.setPendingAction(null)
            broadcastFail("No controller registered for decker ${decker.name} — turn skipped")
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
            broadcastFail("Decker disconnected — turn forfeit")
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            broadcastFail("Unexpected error — turn aborted")
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        } finally {
            registry.setPendingAction(null)
        }

        if (cmd == null) {
            broadcastFail("Action timed out")
            registry.demoteAfterTurn(decker.name)
            return ActionResult.DeckerAction
        }

        val chosen = availableActions.getOrNull(cmd.actionIndex)
        if (chosen == null) {
            broadcastFail("Invalid action index ${cmd.actionIndex}")
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
            val postVisible = decker.visibleObjects()
            val postActions = decker.availableActions()
            registry.broadcastWithRoles(StateMessage(
                role = SessionRole.OBSERVER,
                decker = decker.toDto(),
                visibleObjects = postVisible.toDto(),
                availableActions = postActions.toDto()
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "dispatch failed for decker ${decker.name}, action $chosen" }
            broadcastFail("Internal error — turn aborted")
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
            is AvailableAction.GracefulLogoff -> {
                val preLogoffSecRating = decker.currentLocation?.securityRating()
                decker.gracefulLogoff(diceRoller).toDispatch(preLogoffSecRating, diceRoller)
            }
            is AvailableAction.JackOut        -> {
                val preLogoffSecRating = decker.currentLocation?.securityRating()
                if (decker.isPinnedByBlackIc) {
                    val pin = requireNotNull(decker.blackIcPin)
                    val pinResult = CombatResolver.resolveJackOutWithPin(decker, diceRoller)
                    if (!pinResult.succeeded) {
                        DispatchResult(decker, false, 0, 0, "Willpower test failed — Black IC maintains the pin")
                    } else {
                        val securityCode = preLogoffSecRating?.code ?: SecurityCode.GREEN
                        val postAttackDecker = if (pinResult.finalIcAttackTriggered) {
                            when (val ic = pin.pinningIc) {
                                is LethalBlackIC    -> CombatResolver.resolveLethalBlackIc(decker, ic, securityCode, diceRoller).updatedDecker
                                is NonLethalBlackIC -> CombatResolver.resolveNonLethalBlackIc(decker, ic, securityCode, diceRoller).updatedDecker
                            }
                        } else decker
                        postAttackDecker.copy(blackIcPin = null).jackOut().toDispatch(preLogoffSecRating, diceRoller)
                    }
                } else {
                    decker.jackOut().toDispatch(preLogoffSecRating, diceRoller)
                }
            }
            is AvailableAction.Operation -> {
                if (host == null) dispatchGridOperation(action, cmd, diceRoller)
                else dispatchHostOperation(action, cmd, host, diceRoller)
            }
        }
    }

    private fun dispatchGridOperation(
        action: AvailableAction.Operation,
        cmd: ActionCommand,
        diceRoller: DiceRoller
    ): DispatchResult {
        val grid = when (val loc = decker.currentLocation) {
            is MatrixLocation.OnLTG  -> loc.ltg
            is MatrixLocation.OnRTG  -> loc.rtg
            is MatrixLocation.OnPLTG -> loc.pltg
            else -> return DispatchResult(decker, false, 0, 0, "Not on a grid node")
        }
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.NULL_OPERATION -> decker.nullOperation(grid, p?.inactivitySeconds?.coerceAtLeast(0) ?: 0, diceRoller).toDispatch()
            SystemOperation.RELOCATE_ICON  -> DispatchResult(decker, false, 0, 0, "RELOCATE_ICON requires a host context")
            SystemOperation.LOCATE_ACCESS_NODE -> {
                val (opResult, locateResult) = locateWithState(p) { prec, q -> decker.locateAccessNode(grid, q, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.ANALYZE_SECURITY -> decker.analyzeSecurity(grid, diceRoller).toDispatch()
            SystemOperation.LOCATE_IC        -> decker.locateIc(grid, diceRoller).toDispatch()
            SystemOperation.DECRYPT_ACCESS   -> decker.decryptAccess(grid, diceRoller).toDispatch()
            SystemOperation.INVOKE_MEDIC -> decker.invokeMedic(diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "${action.operation} not supported on grid")
        }
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
        SystemOperation.INVOKE_MEDIC       -> dispatchMiscOp(action, cmd, host, diceRoller)
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
                val ic = (action.target as? MatrixObject.IcProgram)?.ic
                    ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_IC requires an IcProgram target")
                decker.analyzeIc(ic, host, diceRoller).toDispatch()
            }
            SystemOperation.ANALYZE_ICON -> {
                val ic = (action.target as? MatrixObject.IcProgram)?.ic
                    ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_ICON requires an IcProgram target")
                decker.analyzeIcon(Icon.IcIcon(ic), host, diceRoller).toDispatch()
            }
            SystemOperation.ANALYZE_SECURITY  -> decker.analyzeSecurity(host, diceRoller).toDispatch()
            SystemOperation.ANALYZE_SUBSYSTEM -> {
                val node = (action.target as? MatrixObject.HostSubsystem)?.node
                    ?: return DispatchResult(decker, false, 0, 0, "ANALYZE_SUBSYSTEM requires a HostSubsystem target")
                decker.analyzeSubsystem(host, node.subsystemType, diceRoller).toDispatch()
            }
            else -> DispatchResult(decker, false, 0, 0, "Unsupported analyze op: ${action.operation}")
        }

    private fun dispatchLocateOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        val query = p?.query?.trim() ?: ""
        return when (action.operation) {
            SystemOperation.LOCATE_FILE -> {
                if (query.isBlank() && decker.interrogationStates["LOCATE_FILE@HOST"] == null)
                    return DispatchResult(decker, false, 0, 0, "LOCATE_FILE requires a search term on the first call")
                val (opResult, locateResult) = locateWithState(p) { prec, q -> decker.locateFile(host, q, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_SLAVE -> {
                if (query.isBlank() && decker.interrogationStates["LOCATE_SLAVE@HOST"] == null)
                    return DispatchResult(decker, false, 0, 0, "LOCATE_SLAVE requires a search term on the first call")
                val (opResult, locateResult) = locateWithState(p) { prec, q -> decker.locateSlave(host, q, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_ACCESS_NODE -> {
                if (query.isBlank() && decker.interrogationStates["LOCATE_ACCESS_NODE@HOST"] == null)
                    return DispatchResult(decker, false, 0, 0, "LOCATE_ACCESS_NODE requires a search term on the first call")
                val (opResult, locateResult) = locateWithState(p) { prec, q -> decker.locateAccessNode(host, q, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_IC     -> decker.locateIc(host, diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported locate op: ${action.operation}")
        }
    }

    private fun dispatchDataOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.DOWNLOAD_DATA -> {
                val file = (action.target as? MatrixObject.File)?.file
                    ?: return DispatchResult(decker, false, 0, 0, "DOWNLOAD_DATA requires a File target")
                val (opResult, handle) = decker.downloadData(file, host, diceRoller)
                val extra = handle?.let { "${it.turnsRemaining} turn(s) at ${it.ioSpeedMpPerTurn} Mp/turn" } ?: ""
                val dispatch = opResult.toDispatch(extra)
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeDownloads = dispatch.decker.activeDownloads + handle))
                else dispatch
            }
            SystemOperation.EDIT_FILE -> {
                val file = (action.target as? MatrixObject.File)?.file
                    ?: return DispatchResult(decker, false, 0, 0, "EDIT_FILE requires a File target")
                val content = p?.newContent
                if (content != null && content.toByteArray(Charsets.UTF_8).size > 4096) {
                    return DispatchResult(decker, false, 0, 0, "File content exceeds maximum allowed size")
                }
                decker.editFile(file, host, content?.toByteArray(), diceRoller).toDispatch()
            }
            SystemOperation.UPLOAD_DATA    -> {
                val dataSizeMp = (p?.dataSize ?: 100).coerceAtLeast(1)
                val (result, handle) = decker.uploadData(host, dataSizeMp, diceRoller)
                val dispatch = result.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeUploads = dispatch.decker.activeUploads + handle))
                else dispatch
            }
            SystemOperation.DECRYPT_ACCESS -> decker.decryptAccess(host, diceRoller).toDispatch()
            SystemOperation.DECRYPT_FILE -> {
                val file = (action.target as? MatrixObject.File)?.file
                    ?: return DispatchResult(decker, false, 0, 0, "DECRYPT_FILE requires a File target")
                val (opResult, scramble) = decker.decryptFile(file, host, diceRoller)
                val extra = when {
                    scramble == null -> ""
                    scramble.dataDestroyed -> "Scramble IC (rating ${scramble.icRating}) destroyed the file"
                    else -> "Scramble IC (rating ${scramble.icRating}) failed to destruct"
                }
                opResult.toDispatch(extra)
            }
            SystemOperation.DECRYPT_SLAVE -> decker.decryptSlave(host, diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported data op: ${action.operation}")
        }
    }

    private fun dispatchSlaveOp(action: AvailableAction.Operation, host: Host, diceRoller: DiceRoller): DispatchResult =
        when (action.operation) {
            SystemOperation.CONTROL_SLAVE -> {
                val device = (action.target as? MatrixObject.Device)?.device
                    ?: return DispatchResult(decker, false, 0, 0, "CONTROL_SLAVE requires a Device target")
                val (opResult, handle) = decker.controlSlave(device, host, diceRoller)
                val dispatch = opResult.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeMonitoredOperations = dispatch.decker.activeMonitoredOperations + handle))
                else dispatch
            }
            SystemOperation.EDIT_SLAVE -> {
                val device = (action.target as? MatrixObject.Device)?.device
                    ?: return DispatchResult(decker, false, 0, 0, "EDIT_SLAVE requires a Device target")
                val (opResult, handle) = decker.editSlave(device, host, diceRoller)
                val dispatch = opResult.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeMonitoredOperations = dispatch.decker.activeMonitoredOperations + handle))
                else dispatch
            }
            SystemOperation.MONITOR_SLAVE -> {
                val device = (action.target as? MatrixObject.Device)?.device
                    ?: return DispatchResult(decker, false, 0, 0, "MONITOR_SLAVE requires a Device target")
                val (opResult, handle) = decker.monitorSlave(device, host, diceRoller)
                val dispatch = opResult.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeMonitoredOperations = dispatch.decker.activeMonitoredOperations + handle))
                else dispatch
            }
            else -> DispatchResult(decker, false, 0, 0, "Unsupported slave op: ${action.operation}")
        }

    private fun dispatchCommsOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        return when (action.operation) {
            SystemOperation.MAKE_COMCALL -> {
                val (opResult, handle) = decker.makeComcall(host, diceRoller, cmd.params?.hasValidPasscode ?: false)
                val dispatch = opResult.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeMonitoredOperations = dispatch.decker.activeMonitoredOperations + handle))
                else dispatch
            }
            SystemOperation.TAP_COMCALL -> {
                val (opResult, handle) = decker.tapComcall(host, (cmd.params?.scannerDeviceRating ?: 0).coerceIn(0..10), diceRoller)
                val dispatch = opResult.toDispatch()
                if (handle != null && dispatch.success)
                    dispatch.copy(decker = dispatch.decker.copy(activeMonitoredOperations = dispatch.decker.activeMonitoredOperations + handle))
                else dispatch
            }
            else -> DispatchResult(decker, false, 0, 0, "Unsupported comms op: ${action.operation}")
        }
    }

    private fun dispatchMiscOp(action: AvailableAction.Operation, cmd: ActionCommand, host: Host, diceRoller: DiceRoller): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
            SystemOperation.NULL_OPERATION -> decker.nullOperation(host, p?.inactivitySeconds?.coerceAtLeast(0) ?: 0, diceRoller).toDispatch()
            SystemOperation.RELOCATE_ICON  -> dispatchRelocateIcon(host, diceRoller)
            SystemOperation.INVOKE_MEDIC   -> decker.invokeMedic(diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported misc op: ${action.operation}")
        }
    }

    private fun dispatchRelocateIcon(host: Host, diceRoller: DiceRoller): DispatchResult {
        return decker.relocateIcon(host, diceRoller).toDispatch()
    }

    private fun locateWithState(
        params: com.shadowrun.matrix.server.dto.ActionParams?,
        call: (QueryPrecision, String) -> Pair<OperationResult, LocateResult>
    ): Pair<OperationResult, LocateResult> {
        val precision = params?.precision?.let { runCatching { QueryPrecision.valueOf(it) }.getOrNull() } ?: QueryPrecision.NORMAL
        val query = params?.query?.trim() ?: ""
        return call(precision, query)
    }

    private fun MatrixLocation.securityRating(): SecurityRating = when (this) {
        is MatrixLocation.OnHost -> host.securityRating
        is MatrixLocation.OnRTG  -> rtg.securityRating
        is MatrixLocation.OnLTG  -> ltg.securityRating
        is MatrixLocation.OnPLTG -> pltg.securityRating
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
        is LogonResult.Success -> DispatchResult(decker, true,  deckerSuccesses, hostSuccesses, "Logged on to $location")
        is LogonResult.Failure -> DispatchResult(decker, false, deckerSuccesses, hostSuccesses, "Logon failed")
    }

    private fun LogoffResult.toDispatch(preLogoffSecRating: SecurityRating?, diceRoller: DiceRoller) = when (this) {
        is LogoffResult.GracefulSuccess -> DispatchResult(decker, true, 0, 0, "Graceful logoff")
        is LogoffResult.JackOut -> {
            val finalDecker = if (dumpShock && preLogoffSecRating != null) {
                CombatResolver.resolveDumpShock(decker, preLogoffSecRating, diceRoller)
            } else decker
            DispatchResult(finalDecker, true, 0, 0, if (dumpShock) "Jacked out (dump shock!)" else "Jacked out")
        }
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
        return DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, details)
    }

    private fun EditFileResult.toDispatch(): DispatchResult {
        val extra = authenticationSuccesses?.let { " (auth=$it)" } ?: ""
        return DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, "Edit file$extra")
    }


    private fun LocateResult.label() = when (this) {
        is LocateResult.Ongoing  -> "ongoing (${accumulatedSuccesses} accumulated)"
        is LocateResult.Located  -> "located!"
        LocateResult.NotFound    -> "not found"
    }

    private fun MedicResult.toDispatch() = DispatchResult(
        updatedDecker, true, 0, 0,
        "Medic repaired $boxesRepaired box(es); remaining rating: $medicRating"
    )
}
