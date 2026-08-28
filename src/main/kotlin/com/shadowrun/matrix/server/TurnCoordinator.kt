package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TurnCoordinator {
    private val mutex = Mutex()
    private var activeController: DefaultWebSocketServerSession? = null
    private var pendingAction: CompletableDeferred<ActionCommand>? = null

    suspend fun setPendingAction(deferred: CompletableDeferred<ActionCommand>?) = mutex.withLock {
        pendingAction = deferred
    }

    suspend fun setActive(session: DefaultWebSocketServerSession?) = mutex.withLock {
        activeController = session
    }

    suspend fun currentController(): DefaultWebSocketServerSession? = mutex.withLock { activeController }

    /**
     * If [session] is the active controller, clears state and returns any pending action so the
     * caller can cancel it. Returns null if [session] was not the active controller.
     */
    suspend fun cancelIfActive(session: DefaultWebSocketServerSession): CompletableDeferred<ActionCommand>? =
        mutex.withLock {
            if (activeController != session) return@withLock null
            activeController = null
            pendingAction.also { pendingAction = null }
        }

    /**
     * Atomically validates that [session] is the active controller with a pending incomplete action.
     * Returns Pair(future, null) on success, or Pair(null, errorKey) on failure.
     */
    suspend fun claimAction(
        session: DefaultWebSocketServerSession
    ): Pair<CompletableDeferred<ActionCommand>?, String?> = mutex.withLock {
        if (session != activeController) return@withLock null to "NOT_YOUR_TURN"
        val f = pendingAction
        if (f == null || f.isCompleted) return@withLock null to "NO_ACTION_PENDING"
        f to null
    }
}
