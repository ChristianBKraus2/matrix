package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.AvailableActionDto
import com.shadowrun.matrix.server.dto.DeckerStateDto
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixObjectDto
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionRegistryTest {

    private fun makeStateBase() = StateMessage(
        role = SessionRole.OBSERVER,
        decker = DeckerStateDto(
            name = "Test", location = "not jacked in",
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
        val future = CompletableFuture<ActionCommand>()
        registry.pendingAction = future
        registry.deregister(session)
        assertFalse(future.isDone)
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
        registry.pendingAction = CompletableFuture.completedFuture(ActionCommand(actionIndex = 0))
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
        assertEquals("no_action_pending", Json.decodeFromString<ErrorMessage>(session.nextText()).message)
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
        registry.pendingAction = CompletableFuture.completedFuture(ActionCommand(actionIndex = 0))
        registry.receiveAction(session, ActionCommand(actionIndex = 1))
        assertEquals("no_action_pending", Json.decodeFromString<ErrorMessage>(session.nextText()).message)
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
}
