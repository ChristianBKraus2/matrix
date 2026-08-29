package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnCoordinatorTest {

    @Test
    fun `setActive and currentController round-trip`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        assertEquals(session, coord.currentController())
    }

    @Test
    fun `setActive null clears controller`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        coord.setActive(null)
        assertNull(coord.currentController())
    }

    @Test
    fun `setPendingAction stores deferred`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        val deferred = CompletableDeferred<ActionCommand>()
        coord.setPendingAction(deferred)
        val (future, error) = coord.claimAction(session)
        assertNull(error)
        assertEquals(deferred, future)
    }

    @Test
    fun `cancelIfActive returns deferred when session matches active controller`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        val deferred = CompletableDeferred<ActionCommand>()
        coord.setPendingAction(deferred)
        val returned = coord.cancelIfActive(session)
        assertEquals(deferred, returned)
        assertNull(coord.currentController())
    }

    @Test
    fun `cancelIfActive returns null when session does not match`() = runBlocking {
        val coord = TurnCoordinator()
        val session1 = FakeWebSocketSession()
        val session2 = FakeWebSocketSession()
        coord.setActive(session1)
        val returned = coord.cancelIfActive(session2)
        assertNull(returned)
        assertEquals(session1, coord.currentController())
    }

    @Test
    fun `claimAction returns NOT_YOUR_TURN when session is not active controller`() = runBlocking {
        val coord = TurnCoordinator()
        val session1 = FakeWebSocketSession()
        val session2 = FakeWebSocketSession()
        coord.setActive(session1)
        val (future, error) = coord.claimAction(session2)
        assertNull(future)
        assertEquals("NOT_YOUR_TURN", error)
    }

    @Test
    fun `claimAction returns NO_ACTION_PENDING when pendingAction is null`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        val (future, error) = coord.claimAction(session)
        assertNull(future)
        assertEquals("NO_ACTION_PENDING", error)
    }

    @Test
    fun `claimAction returns NO_ACTION_PENDING when pendingAction is already completed`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        coord.setActive(session)
        val deferred = CompletableDeferred<ActionCommand>().also { it.complete(ActionCommand(actionIndex = 0)) }
        coord.setPendingAction(deferred)
        val (future, error) = coord.claimAction(session)
        assertNull(future)
        assertEquals("NO_ACTION_PENDING", error)
    }

    @Test
    fun `currentControllerUnsafe reflects setActive without locking`() = runBlocking {
        val coord = TurnCoordinator()
        val session = FakeWebSocketSession()
        assertNull(coord.currentControllerUnsafe())
        coord.setActive(session)
        assertEquals(session, coord.currentControllerUnsafe())
        coord.setActive(null)
        assertNull(coord.currentControllerUnsafe())
    }
}
