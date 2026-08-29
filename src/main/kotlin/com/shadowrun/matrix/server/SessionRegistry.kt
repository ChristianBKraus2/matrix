package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ControlMessage
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.util.UUID

private val logger = KotlinLogging.logger {}
private data class JoinOutcome(val error: ErrorCode?, val isReconnect: Boolean, val token: String?)

class SessionRegistry {
    private val mutex = Mutex()
    private val sessions = LinkedHashSet<DefaultWebSocketServerSession>()
    private val deckerSessions = LinkedHashMap<String, DefaultWebSocketServerSession>()
    private val sessionDecker = HashMap<DefaultWebSocketServerSession, String>()
    private val disconnectedDeckerNames = HashSet<String>()
    private val reconnectTokens = HashMap<String, String>()

    val turns = TurnCoordinator()

    suspend fun setPendingAction(deferred: CompletableDeferred<ActionCommand>?) =
        turns.setPendingAction(deferred)

    suspend fun register(session: DefaultWebSocketServerSession, maxConnections: Int = Int.MAX_VALUE): Boolean {
        val allowed = mutex.withLock {
            if (sessions.size >= maxConnections) return@withLock false
            sessions.add(session)
            true
        }
        if (!allowed) return false
        session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(role = SessionRole.OBSERVER))))
        return true
    }

    suspend fun receiveJoin(session: DefaultWebSocketServerSession, msg: JoinMessage) {
        val name = msg.deckerName
        if (name.length > 32) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = ErrorCode.NAME_TOO_LONG))))
            return
        }
        if (!name.matches(Regex("[A-Za-z0-9 _\\-]{1,32}"))) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = ErrorCode.BAD_REQUEST, details = "Name contains invalid characters"))))
            return
        }

        val (error, isReconnect, token) = mutex.withLock {
            when {
                sessionDecker.containsKey(session) ->
                    JoinOutcome(ErrorCode.ALREADY_REGISTERED, false, null)
                deckerSessions.containsKey(name) ->
                    JoinOutcome(ErrorCode.NAME_ALREADY_TAKEN, false, null)
                disconnectedDeckerNames.contains(name) -> {
                    val stored = reconnectTokens[name]
                    if (stored == null || msg.reconnectToken != stored) {
                        JoinOutcome(ErrorCode.NAME_ALREADY_TAKEN, false, null)
                    } else {
                        deckerSessions[name] = session
                        sessionDecker[session] = name
                        disconnectedDeckerNames.remove(name)
                        reconnectTokens.remove(name)
                        JoinOutcome(null, true, null)
                    }
                }
                else -> {
                    val newToken = UUID.randomUUID().toString()
                    deckerSessions[name] = session
                    sessionDecker[session] = name
                    reconnectTokens[name] = newToken
                    JoinOutcome(null, false, newToken)
                }
            }
        }

        if (error != null) {
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = error))))
        } else {
            session.send(Frame.Text(MatrixJson.encodeToString(
                ControlMessage(
                    role = SessionRole.REGISTERED_DECKER,
                    deckerName = name,
                    reconnect = isReconnect,
                    reconnectToken = token
                )
            )))
        }
    }

    suspend fun deregister(session: DefaultWebSocketServerSession) {
        mutex.withLock {
            sessions.remove(session)
            val name = sessionDecker.remove(session)
            if (name != null) {
                deckerSessions.remove(name)
                disconnectedDeckerNames.add(name)
            }
        }
        val futureToCancel = turns.cancelIfActive(session)
        futureToCancel?.completeExceptionally(DeckerDisconnectedException())
    }

    suspend fun promoteForTurn(deckerName: String): Boolean {
        val session = mutex.withLock { deckerSessions[deckerName] } ?: return false
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = SessionRole.ACTIVE_CONTROLLER, deckerName = deckerName)
        )))
        turns.setActive(session)
        return true
    }

    suspend fun demoteAfterTurn(deckerName: String) {
        turns.setActive(null)
        val session = mutex.withLock { deckerSessions[deckerName] } ?: return
        session.send(Frame.Text(MatrixJson.encodeToString(
            ControlMessage(role = SessionRole.REGISTERED_DECKER, deckerName = deckerName)
        )))
    }

    suspend fun broadcast(text: String) {
        val snapshot = mutex.withLock { sessions.toList() }
        for (session in snapshot) {
            runCatching { session.send(Frame.Text(text)) }
                .onFailure { logger.warn(it) { "broadcast send failed" } }
        }
    }

    suspend fun broadcastWithRoles(base: StateMessage) {
        val snapshot = mutex.withLock {
            val controller = turns.currentControllerUnsafe()
            sessions.map { s ->
                val role = when {
                    s == controller              -> SessionRole.ACTIVE_CONTROLLER
                    sessionDecker.containsKey(s) -> SessionRole.REGISTERED_DECKER
                    else                         -> SessionRole.OBSERVER
                }
                s to role
            }
        }
        for ((session, role) in snapshot) {
            val text = MatrixJson.encodeToString(base.copy(role = role))
            runCatching { session.send(Frame.Text(text)) }
                .onFailure { logger.warn(it) { "broadcastWithRoles send failed" } }
        }
    }

    suspend fun receiveAction(session: DefaultWebSocketServerSession, cmd: ActionCommand) {
        val (future, errorKey) = turns.claimAction(session)
        if (errorKey != null) {
            val errorCode = when (errorKey) {
                "NOT_YOUR_TURN"     -> ErrorCode.NOT_YOUR_TURN
                "NO_ACTION_PENDING" -> ErrorCode.NO_ACTION_PENDING
                else                -> ErrorCode.BAD_REQUEST
            }
            session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = errorCode))))
            return
        }
        future!!.complete(cmd)
    }
}
