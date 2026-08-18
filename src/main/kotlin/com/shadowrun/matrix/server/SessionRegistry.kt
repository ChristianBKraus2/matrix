package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ControlMessage
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.encodeToString
import java.util.concurrent.CompletableFuture

class SessionRegistry {
    private val lock = Any()
    private val sessions = LinkedHashSet<DefaultWebSocketServerSession>()
    private var activeController: DefaultWebSocketServerSession? = null

    @Volatile
    var pendingAction: CompletableFuture<ActionCommand>? = null

    suspend fun register(session: DefaultWebSocketServerSession) {
        val becameController = synchronized(lock) {
            sessions.add(session)
            if (activeController == null) {
                activeController = session
                true
            } else false
        }
        if (becameController) {
            session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(granted = true))))
        } else {
            session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(granted = false))))
        }
    }

    suspend fun deregister(session: DefaultWebSocketServerSession) {
        val promoted: DefaultWebSocketServerSession?
        synchronized(lock) {
            sessions.remove(session)
            promoted = if (activeController == session) {
                activeController = sessions.firstOrNull()
                activeController
            } else null
        }
        promoted?.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(granted = true))))
    }

    suspend fun broadcast(text: String) {
        val snapshot = synchronized(lock) { sessions.toList() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
        }
    }

    /** Sends different JSON to the active controller vs. all other sessions. */
    suspend fun broadcastPersonalized(forController: String, forObserver: String) {
        val (controller, others) = synchronized(lock) {
            activeController to sessions.filter { it != activeController }
        }
        controller?.let { runCatching { it.send(Frame.Text(forController)) } }
        for (session in others) { runCatching { session.send(Frame.Text(forObserver)) } }
    }

    suspend fun receiveAction(session: DefaultWebSocketServerSession, cmd: ActionCommand) {
        val future = pendingAction
        if (session != synchronized(lock) { activeController }) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "not your turn"))))
            return
        }
        if (future == null || future.isDone) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "no action pending"))))
            return
        }
        future.complete(cmd)
    }
}
