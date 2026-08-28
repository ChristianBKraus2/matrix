package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ControlMessage
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString

class SessionRegistry {
    private val lock = Any()
    private val sessions = LinkedHashSet<DefaultWebSocketServerSession>()
    private val deckerSessions = LinkedHashMap<String, DefaultWebSocketServerSession>()
    private val sessionDecker = HashMap<DefaultWebSocketServerSession, String>()
    private var activeController: DefaultWebSocketServerSession? = null

    private var pendingAction: CompletableDeferred<ActionCommand>? = null
    private val disconnectedDeckerNames = HashSet<String>()

    fun setPendingAction(deferred: CompletableDeferred<ActionCommand>?) {
        synchronized(lock) { pendingAction = deferred }
    }

    suspend fun register(session: DefaultWebSocketServerSession) {
        synchronized(lock) { sessions.add(session) }
        session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(role = SessionRole.OBSERVER))))
    }

    suspend fun receiveJoin(session: DefaultWebSocketServerSession, msg: JoinMessage) {
        val name = msg.deckerName
        if (name.length > 32) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = ErrorCode.NAME_TOO_LONG))))
            return
        }
        val (error, isReconnect) = synchronized(lock) {
            when {
                sessionDecker.containsKey(session) -> ErrorCode.ALREADY_REGISTERED to false
                deckerSessions.containsKey(name)   -> ErrorCode.NAME_ALREADY_TAKEN to false
                else -> {
                    deckerSessions[name] = session
                    sessionDecker[session] = name
                    null to disconnectedDeckerNames.remove(name)
                }
            }
        }
        if (error != null) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = error))))
        } else {
            session.send(Frame.Text(MatrixJson.encodeToString(
                ControlMessage(role = SessionRole.REGISTERED_DECKER, deckerName = name, reconnect = isReconnect)
            )))
        }
    }

    suspend fun deregister(session: DefaultWebSocketServerSession) {
        // Read and null pendingAction inside the lock so nothing else can observe the
        // intermediate state between "activeController = null" and completing the future.
        val futureToCancel: CompletableDeferred<ActionCommand>?
        synchronized(lock) {
            sessions.remove(session)
            val name = sessionDecker.remove(session)
            if (name != null) {
                deckerSessions.remove(name)
                disconnectedDeckerNames.add(name)
            }
            val wasController = activeController == session
            if (wasController) {
                activeController = null
                futureToCancel = pendingAction
                pendingAction = null
            } else {
                futureToCancel = null
            }
        }
        futureToCancel?.completeExceptionally(DeckerDisconnectedException())
    }

    suspend fun promoteForTurn(deckerName: String): Boolean {
        val session = synchronized(lock) {
            deckerSessions[deckerName]?.also { activeController = it }
        } ?: return false
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = SessionRole.ACTIVE_CONTROLLER, deckerName = deckerName)
        )))
        return true
    }

    suspend fun demoteAfterTurn(deckerName: String) {
        val session = synchronized(lock) {
            activeController = null
            deckerSessions[deckerName]
        } ?: return
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = SessionRole.REGISTERED_DECKER, deckerName = deckerName)
        )))
    }

    suspend fun broadcast(text: String) {
        val snapshot = synchronized(lock) { sessions.toList() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }

    suspend fun broadcastWithRoles(base: StateMessage) {
        val frames: List<Pair<DefaultWebSocketServerSession, String>> = synchronized(lock) {
            sessions.map { s ->
                val role = when {
                    s == activeController          -> SessionRole.ACTIVE_CONTROLLER
                    sessionDecker.containsKey(s)   -> SessionRole.REGISTERED_DECKER
                    else                           -> SessionRole.OBSERVER
                }
                s to MatrixJson.encodeToString(base.copy(role = role))
            }
        }
        for ((session, text) in frames) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }

    suspend fun receiveAction(session: DefaultWebSocketServerSession, cmd: ActionCommand) {
        // Capture both activeController and pendingAction atomically to avoid TOCTOU races.
        val (future, error) = synchronized(lock) {
            if (session != activeController) return@synchronized null to ErrorCode.NOT_YOUR_TURN
            val f = pendingAction
            if (f == null || f.isCompleted) return@synchronized null to ErrorCode.NO_ACTION_PENDING
            f to null
        }
        if (error != null) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = error))))
            return
        }
        future!!.complete(cmd)
    }
}
