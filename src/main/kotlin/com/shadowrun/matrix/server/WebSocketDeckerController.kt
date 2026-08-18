package com.shadowrun.matrix.server

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
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
import com.shadowrun.matrix.operations.InterrogationState
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
import com.shadowrun.matrix.server.dto.StateMessage
import com.shadowrun.matrix.server.dto.toDto
import com.shadowrun.matrix.utility.DiceRoller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WebSocketDeckerController(
    private val registry: SessionRegistry,
    initialDecker: Decker,
    private val actionTimeoutSeconds: Long = 120
) : ActiveIcon {

    var decker: Decker = initialDecker
        private set

    private val interrogationStates = mutableMapOf<SystemOperation, InterrogationState>()

    override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val visibleObjects = decker.visibleObjects()
        val availableActions = decker.availableActions()

        val hasController = runBlocking { registry.promoteForTurn(decker.name) }
        if (!hasController) {
            runBlocking {
                registry.broadcast(MatrixJson.encodeToString(ResultMessage(
                    success = false, deckerSuccesses = 0, hostSuccesses = 0,
                    details = "No controller registered for decker ${decker.name} — turn skipped"
                )))
            }
            return ActionResult.DeckerAction
        }

        val stateBase = StateMessage(
            role = "observer",
            decker = decker.toDto(),
            visibleObjects = visibleObjects.toDto(),
            availableActions = availableActions.toDto()
        )
        val future = CompletableFuture<ActionCommand>()
        registry.pendingAction = future

        runBlocking { registry.broadcastWithRoles(stateBase) }

        val cmd = try {
            future.get(actionTimeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            runBlocking {
                registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Action timed out")))
                registry.demoteAfterTurn(decker.name)
            }
            return ActionResult.DeckerAction
        } catch (e: ExecutionException) {
            if (e.cause is DeckerDisconnectedException) {
                runBlocking {
                    registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Decker disconnected — turn forfeit")))
                }
                return ActionResult.DeckerAction
            }
            throw e
        } finally {
            registry.pendingAction = null
        }

        val chosen = availableActions.getOrNull(cmd.actionIndex)
        if (chosen == null) {
            runBlocking {
                registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "Invalid action index ${cmd.actionIndex}")))
                registry.demoteAfterTurn(decker.name)
            }
            return ActionResult.DeckerAction
        }

        val oldDecker = decker
        val result = dispatch(chosen, cmd, diceRoller)
        decker = result.decker
        context.applyDeckerOperationResult(oldDecker, decker)

        runBlocking {
            registry.broadcast(MatrixJson.encodeToString(ResultMessage(
                success = result.success,
                deckerSuccesses = result.deckerSuccesses,
                hostSuccesses = result.hostSuccesses,
                details = result.details
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
        SystemOperation.RELOCATE_ICON ->
            decker.relocateIcon(opponentSensor = 0, trackerMcpRating = 0, diceRoller).toDispatch()
        else ->
            DispatchResult(decker, false, 0, 0, "${action.operation} requires host context")
    }

    private fun dispatchHostOperation(
        action: AvailableAction.Operation,
        cmd: ActionCommand,
        host: Host,
        diceRoller: DiceRoller
    ): DispatchResult {
        val p = cmd.params
        return when (action.operation) {
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
            SystemOperation.CONTROL_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.controlSlave(device, host, diceRoller).first.toDispatch()
            }
            SystemOperation.DECRYPT_ACCESS -> decker.decryptAccess(host, diceRoller).toDispatch()
            SystemOperation.DECRYPT_FILE -> {
                val file = (action.target as MatrixObject.File).file
                decker.decryptFile(file, host, diceRoller).toDispatch()
            }
            SystemOperation.DECRYPT_SLAVE  -> decker.decryptSlave(host, diceRoller).toDispatch()
            SystemOperation.DOWNLOAD_DATA  -> {
                val file = (action.target as MatrixObject.File).file
                decker.downloadData(file, host, diceRoller).first.toDispatch()
            }
            SystemOperation.EDIT_FILE -> {
                val file = (action.target as MatrixObject.File).file
                decker.editFile(file, host, p?.newContent?.toByteArray(), diceRoller).toDispatch()
            }
            SystemOperation.EDIT_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.editSlave(device, host, diceRoller).first.toDispatch()
            }
            SystemOperation.LOCATE_FILE -> {
                val (opResult, locateResult) = locateWithState(
                    action.operation, p, host, diceRoller
                ) { s, prec -> decker.locateFile(host, s, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_SLAVE -> {
                val (opResult, locateResult) = locateWithState(
                    action.operation, p, host, diceRoller
                ) { s, prec -> decker.locateSlave(host, s, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_ACCESS_NODE -> {
                val (opResult, locateResult) = locateWithState(
                    action.operation, p, host, diceRoller
                ) { s, prec -> decker.locateAccessNode(host, s, prec, diceRoller) }
                opResult.toDispatch(locateResult.label())
            }
            SystemOperation.LOCATE_DECKER -> DispatchResult(decker, false, 0, 0, "LOCATE_DECKER requires a target Persona — not supported via WebSocket")
            SystemOperation.LOCATE_IC     -> decker.locateIc(host, diceRoller).toDispatch()
            SystemOperation.MAKE_COMCALL  -> decker.makeComcall(host, diceRoller, p?.hasValidPasscode ?: false).first.toDispatch()
            SystemOperation.MONITOR_SLAVE -> {
                val device = (action.target as MatrixObject.Device).device
                decker.monitorSlave(device, host, diceRoller).first.toDispatch()
            }
            SystemOperation.NULL_OPERATION -> decker.nullOperation(host, p?.inactivitySeconds ?: 0, diceRoller).toDispatch()
            SystemOperation.RELOCATE_ICON  -> decker.relocateIcon(0, 0, diceRoller).toDispatch()
            SystemOperation.SWAP_MEMORY    ->
                DispatchResult(decker, false, 0, 0, "SWAP_MEMORY requires utility selection — not supported via WebSocket")
            SystemOperation.TAP_COMCALL    -> decker.tapComcall(host, p?.scannerDeviceRating ?: 0, diceRoller).first.toDispatch()
            SystemOperation.UPLOAD_DATA    -> decker.uploadData(host, diceRoller).toDispatch()
            else -> DispatchResult(decker, false, 0, 0, "Unsupported: ${action.operation}")
        }
    }

    private fun locateWithState(
        op: SystemOperation,
        params: com.shadowrun.matrix.server.dto.ActionParams?,
        @Suppress("UNUSED_PARAMETER") host: Host,
        @Suppress("UNUSED_PARAMETER") diceRoller: DiceRoller,
        call: (InterrogationState, QueryPrecision) -> Pair<OperationResult, LocateResult>
    ): Pair<OperationResult, LocateResult> {
        val state = interrogationStates.getOrPut(op) { InterrogationState(op, "") }
        val precision = params?.precision?.let { QueryPrecision.valueOf(it) } ?: QueryPrecision.NORMAL
        val (opResult, locateResult) = call(state, precision)
        when (locateResult) {
            is LocateResult.Ongoing  -> interrogationStates[op] = state.copy(accumulatedSuccesses = locateResult.accumulatedSuccesses)
            is LocateResult.Located  -> interrogationStates.remove(op)
            LocateResult.NotFound    -> interrogationStates.remove(op)
        }
        return opResult to locateResult
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
