package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.AvailableActionDto
import com.shadowrun.matrix.server.dto.ControlMessage
import com.shadowrun.matrix.server.dto.DeckerStateDto
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixObjectDto
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryTest {

    private fun makeStateBase() = StateMessage(
        role = SessionRole.OBSERVER,
        decker = DeckerStateDto(
            name = "Test", location = "not jacked in", jackedIn = false,
            isPinnedByBlackIc = false,
            physicalDamage = 0, physicalMaxBoxes = 10,
            mentalDamage = 0, mentalMaxBoxes = 10,
            hackingPool = 5, mcpRating = 4,
            activeUtilities = emptyList()
        ),
        visibleObjects = emptyList<MatrixObjectDto>(),
        availableActions = emptyList<AvailableActionDto>()
    )

    @Test
    fun `deregister non-controller does not signal pendingAction`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        val deferred = CompletableDeferred<ActionCommand>()
        registry.setPendingAction(deferred)
        registry.deregister(session)
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `deregister controller with null pendingAction does not throw`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText()
        registry.promoteForTurn("Kylie")
        session.nextText()
        // pendingAction is null — must not throw
        registry.deregister(session)
    }

    @Test
    fun `deregister controller with completed pendingAction does not throw`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText()
        registry.promoteForTurn("Kylie")
        session.nextText()
        registry.setPendingAction(CompletableDeferred<ActionCommand>().also { it.complete(ActionCommand(actionIndex = 0)) })
        // isDone=true — completeExceptionally is a no-op; must not throw
        registry.deregister(session)
    }

    @Test
    fun `receiveAction when pendingAction is null sends no_action_pending error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText()
        registry.promoteForTurn("Kylie")
        session.nextText()
        // pendingAction is still null at this point
        registry.receiveAction(session, ActionCommand(actionIndex = 0))
        assertEquals(ErrorCode.NO_ACTION_PENDING, Json.decodeFromString<ErrorMessage>(session.nextText()).message)
    }

    @Test
    fun `receiveAction when pendingAction is done sends no_action_pending error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText()
        registry.promoteForTurn("Kylie")
        session.nextText()
        registry.setPendingAction(CompletableDeferred<ActionCommand>().also { it.complete(ActionCommand(actionIndex = 0)) })
        registry.receiveAction(session, ActionCommand(actionIndex = 1))
        assertEquals(ErrorCode.NO_ACTION_PENDING, Json.decodeFromString<ErrorMessage>(session.nextText()).message)
    }

    @Test
    fun `broadcastWithRoles sends registered_decker role to joined session`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText()
        registry.broadcastWithRoles(makeStateBase())
        val obj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("state", obj["type"]?.jsonPrimitive?.content)
        assertEquals("registered_decker", obj["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `broadcastWithRoles sends observer role to unjoined session`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.broadcastWithRoles(makeStateBase())
        val obj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("state", obj["type"]?.jsonPrimitive?.content)
        assertEquals("observer", obj["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `demoteAfterTurn with unknown decker name does not send or throw`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText()
        registry.demoteAfterTurn("nonexistent")
        assertNull(withTimeoutOrNull(300) { session.nextText() })
    }

    @Test
    fun `promoteForTurn returns false when decker not registered`() = runBlocking {
        val registry = SessionRegistry()
        assertFalse(registry.promoteForTurn("nonexistent"))
    }

    @Test
    fun `broadcast delivers text to all registered sessions`() = runBlocking {
        val registry = SessionRegistry()
        val s1 = FakeWebSocketSession()
        val s2 = FakeWebSocketSession()
        registry.register(s1)
        registry.register(s2)
        s1.nextText()
        s2.nextText()
        registry.broadcast("""{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", s1.nextText())
        assertEquals("""{"hello":"world"}""", s2.nextText())
    }

    @Test
    fun `register returns false when maxConnections reached`() = runBlocking {
        val registry = SessionRegistry()
        val s1 = FakeWebSocketSession()
        val s2 = FakeWebSocketSession()
        assertTrue(registry.register(s1, maxConnections = 1))
        s1.nextText() // observer frame
        assertFalse(registry.register(s2, maxConnections = 1))
    }

    @Test
    fun `receiveJoin with 32-char name succeeds`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "A".repeat(32)))
        val response = Json.decodeFromString<ControlMessage>(session.nextText())
        assertEquals(SessionRole.REGISTERED_DECKER, response.role)
    }

    @Test
    fun `receiveJoin with 33-char name sends name_too_long error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "A".repeat(33)))
        assertEquals(ErrorCode.NAME_TOO_LONG, Json.decodeFromString<ErrorMessage>(session.nextText()).message)
    }

    @Test
    fun `broadcastWithRoles sends active_controller role to promoted session`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText() // registered_decker
        registry.promoteForTurn("Kylie")
        session.nextText() // active_controller control message
        registry.broadcastWithRoles(makeStateBase())
        val obj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("active_controller", obj["role"]?.jsonPrimitive?.content)
    }
}
