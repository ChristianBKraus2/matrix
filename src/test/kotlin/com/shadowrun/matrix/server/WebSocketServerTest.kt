package com.shadowrun.matrix.server

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.ResultMessage
import com.shadowrun.matrix.utility.DiceRoller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WebSocketServerTest {

    private fun makeDecker(): Decker = DeckerMock.build(
        Jackpoint(
            JackpointType.ILLEGAL_ACCESS,
            connectsToLtg = GridMock.matrix.rtgs
                .first { it.name == "UCAS" }.ltgs.first { it.name == "UCAS-SEA" }
        )
    )

    private fun winRoller() = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    })

    private fun makeContext(decker: Decker) = GameContext(
        host = GridMock.getDefaultHost(),
        securityCode = SecurityCode.GREEN,
        deckers = mutableListOf(decker),
        activeIc = mutableListOf()
    )

    @Test
    fun `connection receives ControlMessage with observer role`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        val obj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("control", obj["type"]?.jsonPrimitive?.content)
        assertEquals("observer", obj["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `all connections receive ControlMessage with observer role`() = runBlocking {
        val registry = SessionRegistry()
        val session1 = FakeWebSocketSession()
        val session2 = FakeWebSocketSession()
        registry.register(session1)
        registry.register(session2)
        assertEquals("observer", Json.parseToJsonElement(session1.nextText()).jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("observer", Json.parseToJsonElement(session2.nextText()).jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `JoinMessage registers decker and sends registered_decker ControlMessage`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        val obj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("control", obj["type"]?.jsonPrimitive?.content)
        assertEquals("registered_decker", obj["role"]?.jsonPrimitive?.content)
        assertEquals("Kylie", obj["deckerName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `JoinMessage with already-taken name returns name_already_taken error`() = runBlocking {
        val registry = SessionRegistry()
        val session1 = FakeWebSocketSession()
        val session2 = FakeWebSocketSession()
        registry.register(session1)
        registry.register(session2)
        session1.nextText() // observer
        session2.nextText() // observer
        registry.receiveJoin(session1, JoinMessage(deckerName = "Kylie"))
        session1.nextText() // registered_decker
        registry.receiveJoin(session2, JoinMessage(deckerName = "Kylie"))
        val error = Json.decodeFromString<ErrorMessage>(session2.nextText())
        assertEquals(ErrorCode.NAME_ALREADY_TAKEN, error.message)
    }

    @Test
    fun `JoinMessage sent twice by same session returns already_registered error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie"))
        session.nextText() // registered_decker
        registry.receiveJoin(session, JoinMessage(deckerName = "Shadowcat"))
        val error = Json.decodeFromString<ErrorMessage>(session.nextText())
        assertEquals(ErrorCode.ALREADY_REGISTERED, error.message)
    }

    @Test
    fun `non-controller session sending ActionCommand receives not_your_turn error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveAction(session, ActionCommand(actionIndex = 0))
        val error = Json.decodeFromString<ErrorMessage>(session.nextText())
        assertEquals(ErrorCode.NOT_YOUR_TURN, error.message)
    }

    @Test
    fun `action with no registered session broadcasts turn-skipped ResultMessage`() = runBlocking {
        val registry = SessionRegistry()
        val decker = makeDecker()
        val wsController = WebSocketDeckerController(registry, decker, actionTimeoutSeconds = 5)
        val context = makeContext(decker)
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer

        val thread = Thread { runBlocking { wsController.conductTurn(context, winRoller()) } }
        thread.start()

        val result = Json.decodeFromString<ResultMessage>(session.nextText())
        assertFalse(result.success)
        assertTrue(result.details.contains("turn skipped"))
        thread.join(3000)
    }

    @Test
    fun `registered decker receives promotion and StateMessage on turn start`() = runBlocking {
        val registry = SessionRegistry()
        val decker = makeDecker()
        val wsController = WebSocketDeckerController(registry, decker, actionTimeoutSeconds = 5)
        val context = makeContext(decker)
        val deckerName = wsController.decker.name
        val session = FakeWebSocketSession()

        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = deckerName))
        session.nextText() // registered_decker

        val thread = Thread { runBlocking { wsController.conductTurn(context, winRoller()) } }
        thread.start()

        val promotionText = session.nextText()
        assertEquals("active_controller", Json.parseToJsonElement(promotionText).jsonObject["role"]?.jsonPrimitive?.content)

        val stateObj = Json.parseToJsonElement(session.nextText()).jsonObject
        assertEquals("state", stateObj["type"]?.jsonPrimitive?.content)
        assertEquals("active_controller", stateObj["role"]?.jsonPrimitive?.content)

        registry.receiveAction(session, ActionCommand(actionIndex = 0))

        val result = Json.decodeFromString<ResultMessage>(session.nextText())
        assertFalse(result.success)
        assertTrue(result.details.contains("Invalid action index"))

        session.nextText() // demotion ControlMessage(registered_decker)
        thread.join(3000)
    }

    // ── Reconnect and name validation ────────────────────────────────────────────

    @Test
    fun `JoinMessage with valid reconnect token succeeds after disconnect`() = runBlocking {
        val registry = SessionRegistry()
        val session1 = FakeWebSocketSession()
        registry.register(session1)
        session1.nextText() // observer
        registry.receiveJoin(session1, JoinMessage(deckerName = "Kylie"))
        val registered = Json.parseToJsonElement(session1.nextText()).jsonObject
        val token = registered["reconnectToken"]?.jsonPrimitive?.content
        assertNotNull(token)
        registry.deregister(session1)

        val session2 = FakeWebSocketSession()
        registry.register(session2)
        session2.nextText() // observer
        registry.receiveJoin(session2, JoinMessage(deckerName = "Kylie", reconnectToken = token))
        val obj = Json.parseToJsonElement(session2.nextText()).jsonObject
        assertEquals("registered_decker", obj["role"]?.jsonPrimitive?.content)
        assertTrue(obj["reconnect"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)
    }

    @Test
    fun `JoinMessage with wrong reconnect token returns name_already_taken`() = runBlocking {
        val registry = SessionRegistry()
        val session1 = FakeWebSocketSession()
        registry.register(session1)
        session1.nextText() // observer
        registry.receiveJoin(session1, JoinMessage(deckerName = "Kylie"))
        session1.nextText() // registered_decker
        registry.deregister(session1)

        val session2 = FakeWebSocketSession()
        registry.register(session2)
        session2.nextText() // observer
        registry.receiveJoin(session2, JoinMessage(deckerName = "Kylie", reconnectToken = "wrong-token"))
        val error = Json.decodeFromString<ErrorMessage>(session2.nextText())
        assertEquals(ErrorCode.NAME_ALREADY_TAKEN, error.message)
    }

    @Test
    fun `JoinMessage with no token for a disconnected name returns name_already_taken`() = runBlocking {
        val registry = SessionRegistry()
        val session1 = FakeWebSocketSession()
        registry.register(session1)
        session1.nextText() // observer
        registry.receiveJoin(session1, JoinMessage(deckerName = "Kylie"))
        session1.nextText() // registered_decker
        registry.deregister(session1)

        val session2 = FakeWebSocketSession()
        registry.register(session2)
        session2.nextText() // observer
        registry.receiveJoin(session2, JoinMessage(deckerName = "Kylie")) // no token
        val error = Json.decodeFromString<ErrorMessage>(session2.nextText())
        assertEquals(ErrorCode.NAME_ALREADY_TAKEN, error.message)
    }

    @Test
    fun `JoinMessage with invalid characters in name returns bad_request error`() = runBlocking {
        val registry = SessionRegistry()
        val session = FakeWebSocketSession()
        registry.register(session)
        session.nextText() // observer
        registry.receiveJoin(session, JoinMessage(deckerName = "Kylie@!#"))
        val error = Json.decodeFromString<ErrorMessage>(session.nextText())
        assertEquals(ErrorCode.BAD_REQUEST, error.message)
    }

    @Test
    fun `active controller disconnect mid-turn broadcasts forfeit ResultMessage`() = runBlocking {
        val registry = SessionRegistry()
        val decker = makeDecker()
        val wsController = WebSocketDeckerController(registry, decker, actionTimeoutSeconds = 5)
        val context = makeContext(decker)
        val deckerName = wsController.decker.name

        val deckerSession = FakeWebSocketSession()
        val observerSession = FakeWebSocketSession()
        registry.register(deckerSession)
        registry.register(observerSession)
        deckerSession.nextText()  // observer
        observerSession.nextText() // observer

        registry.receiveJoin(deckerSession, JoinMessage(deckerName = deckerName))
        deckerSession.nextText() // registered_decker

        val thread = Thread { runBlocking { wsController.conductTurn(context, winRoller()) } }
        thread.start()

        deckerSession.nextText()  // ControlMessage(active_controller)
        deckerSession.nextText()  // StateMessage(active_controller)
        observerSession.nextText() // StateMessage(observer)

        registry.deregister(deckerSession) // triggers forfeit

        val result = Json.decodeFromString<ResultMessage>(observerSession.nextText())
        assertFalse(result.success)
        assertTrue(result.details.contains("forfeit"))
        thread.join(6000)
    }
}
