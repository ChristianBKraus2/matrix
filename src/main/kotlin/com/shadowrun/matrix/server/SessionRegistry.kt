package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ControlMessage
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import com.shadowrun.matrix.server.dto.StateMessage
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.encodeToString
import java.util.concurrent.CompletableFuture

class SessionRegistry {
    private val lock = Any()
    private val sessions = LinkedHashSet<DefaultWebSocketServerSession>()
    private val deckerSessions = LinkedHashMap<String, DefaultWebSocketServerSession>()
    private val sessionDecker = HashMap<DefaultWebSocketServerSession, String>()
    private var activeController: DefaultWebSocketServerSession? = null

    @Volatile
    var pendingAction: CompletableFuture<ActionCommand>? = null

    suspend fun register(session: DefaultWebSocketServerSession) {
        synchronized(lock) { sessions.add(session) }
        session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(role = "observer"))))
    }

    suspend fun receiveJoin(session: DefaultWebSocketServerSession, msg: JoinMessage) {
        val name = msg.deckerName
        val error = synchronized(lock) {
            when {
                sessionDecker.containsKey(session) -> "already_registered"
                deckerSessions.containsKey(name)   -> "name_already_taken"
                else -> {
                    deckerSessions[name] = session
                    sessionDecker[session] = name
                    null
                }
            }
        }
        if (error != null) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = error))))
        } else {
            session.send(Frame.Text(MatrixJson.encodeToString(
                ControlMessage(role = "registered_decker", deckerName = name)
            )))
        }
    }

    suspend fun deregister(session: DefaultWebSocketServerSession) {
        val wasController: Boolean
        synchronized(lock) {
            sessions.remove(session)
            val name = sessionDecker.remove(session)
            if (name != null) deckerSessions.remove(name)
            wasController = activeController == session
            if (wasController) activeController = null
        }
        if (wasController) {
            pendingAction?.completeExceptionally(DeckerDisconnectedException())
        }
    }

    suspend fun promoteForTurn(deckerName: String): Boolean {
        val session = synchronized(lock) {
            deckerSessions[deckerName]?.also { activeController = it }
        } ?: return false
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = "active_controller", deckerName = deckerName)
        )))
        return true
    }

    suspend fun demoteAfterTurn(deckerName: String) {
        val session = synchronized(lock) {
            activeController = null
            deckerSessions[deckerName]
        } ?: return
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = "registered_decker", deckerName = deckerName)
        )))
    }

    suspend fun broadcast(text: String) {
        val snapshot = synchronized(lock) { sessions.toList() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }

    suspend fun broadcastWithRoles(base: StateMessage) {
        val sessionRoles: List<Pair<DefaultWebSocketServerSession, String>> = synchronized(lock) {
            sessions.map { s ->
                s to when {
                    s == activeController          -> "active_controller"
                    sessionDecker.containsKey(s)   -> "registered_decker"
                    else                           -> "observer"
                }
            }
        }
        for ((session, role) in sessionRoles) {
            runCatching { session.send(Frame.Text(MatrixJson.encodeToString(base.copy(role = role)))) }
        }
    }

    suspend fun receiveAction(session: DefaultWebSocketServerSession, cmd: ActionCommand) {
        val future = pendingAction
        if (session != synchronized(lock) { activeController }) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "not_your_turn"))))
            return
        }
        if (future == null || future.isDone) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "no_action_pending"))))
            return
        }
        future.complete(cmd)
    }
}
