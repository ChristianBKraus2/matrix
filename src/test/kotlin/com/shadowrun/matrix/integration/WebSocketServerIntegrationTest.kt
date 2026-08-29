package com.shadowrun.matrix.integration

import com.shadowrun.matrix.server.SessionRegistry
import com.shadowrun.matrix.server.WebSocketDeckerController
import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.AvailableActionDto
import com.shadowrun.matrix.server.dto.DeckerStateDto
import com.shadowrun.matrix.server.dto.SessionRole
import com.shadowrun.matrix.server.dto.StateMessage
import com.shadowrun.matrix.server.matrixModule
import com.shadowrun.matrix.decker.*
import kotlinx.coroutines.runBlocking
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WebSocketServerIntegrationTest : IntegrationTestBase() {

    @Test
    fun `connecting receives observer ControlMessage`() = webSocketTest {
        val obj = receiveJson()
        assertEquals("control", obj["type"]?.jsonPrimitive?.content)
        assertEquals("observer", obj["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sending JoinMessage receives registered_decker ControlMessage`() = webSocketTest {
        consumeObserver()
        send(Frame.Text("""{"type":"join","deckerName":"Kylie"}"""))
        val obj = receiveJson()
        assertEquals("control", obj["type"]?.jsonPrimitive?.content)
        assertEquals("registered_decker", obj["role"]?.jsonPrimitive?.content)
        assertEquals("Kylie", obj["deckerName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `receiving StateMessage shows decker info`() = webSocketTest { registry ->
        joinAsDecker("Kylie")
        registry.broadcastWithRoles(StateMessage(
            role = SessionRole.OBSERVER,
            decker = deckerState("Kylie"),
            visibleObjects = emptyList(),
            availableActions = emptyList()
        ))
        val obj = receiveJson()
        assertEquals("state", obj["type"]?.jsonPrimitive?.content)
        assertEquals("Kylie", obj["decker"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `receiving StateMessage shows available actions`() = webSocketTest { registry ->
        joinAsDecker("Kylie")
        registry.broadcastWithRoles(StateMessage(
            role = SessionRole.OBSERVER,
            decker = deckerState("Kylie"),
            visibleObjects = emptyList(),
            availableActions = listOf(
                AvailableActionDto.LogonToLtg(index = 0, actionType = "COMPLEX", ltgName = "UCAS-SEA")
            )
        ))
        val obj = receiveJson()
        val actionsEl = assertNotNull(obj["availableActions"], "availableActions must be present in state message")
        val actions = actionsEl.jsonArray
        assertEquals("LogonToLtg", actions[0].jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("UCAS-SEA", actions[0].jsonObject["ltgName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sending LogonToLtg action completes pending action`() = webSocketTest { registry ->
        joinAsDecker("Kylie")
        registry.promoteForTurn("Kylie")
        incoming.receive() // consume active_controller
        val pending = CompletableDeferred<ActionCommand>()
        registry.setPendingAction(pending)
        send(Frame.Text("""{"type":"action","actionIndex":0}"""))
        val cmd = withTimeout(1000) { pending.await() }
        assertEquals(0, cmd.actionIndex)
    }

    @Test
    fun `decker navigating to UCAS RTG sees all four LTGs as available actions`() = webSocketTest { registry ->
        val jackpoint = GridMock.getDefaultJackpoint()
        val decker = DeckerMock.build(jackpoint, DeckerMock.HIGH_END)
        val ltg = assertNotNull(jackpoint.connectsToLtg, "jackpoint must connect to an LTG")
        val jackedInDecker = (decker.jackInToLtg(ltg, winRoller()) as LogonResult.Success).decker
        val context = buildDefaultContext(jackedInDecker)
        val controller = WebSocketDeckerController(registry, jackedInDecker, actionTimeoutSeconds = 5)

        joinAsDecker(jackedInDecker.name)

        // Turn 1: on UCAS-SEA LTG — logon to parent RTG (UCAS)
        val turn1 = Thread { runBlocking { controller.conductTurn(context, winRoller()) } }.also { it.start() }
        incoming.receive() // active_controller
        val state1 = receiveJson()
        val availableActions1 = assertNotNull(state1["availableActions"], "state1 must contain availableActions")
        val rtgIndex = availableActions1.jsonArray
            .indexOfFirst { it.jsonObject["kind"]?.jsonPrimitive?.content == "LogonToRtg" }
        send(Frame.Text("""{"type":"action","actionIndex":$rtgIndex}"""))
        incoming.receive() // result
        incoming.receive() // registered_decker
        incoming.receive() // post-action StateMessage broadcast
        turn1.join(5000)
        assertFalse(turn1.isAlive, "turn1 did not terminate in time")

        // Turn 2: now on UCAS RTG — all 4 LTGs must appear as available actions
        val turn2 = Thread { runBlocking { controller.conductTurn(context, winRoller()) } }.also { it.start() }
        incoming.receive() // active_controller
        val state2 = receiveJson()
        val availableActions2 = assertNotNull(state2["availableActions"], "state2 must contain availableActions")
        val ltgNames = availableActions2.jsonArray
            .filter { it.jsonObject["kind"]?.jsonPrimitive?.content == "LogonToLtg" }
            .map { assertNotNull(it.jsonObject["ltgName"], "ltgName must be present in LogonToLtg action").jsonPrimitive.content }
        assertEquals(setOf("UCAS-SEA", "UCAS-CHI", "UCAS-NYC", "UCAS-BOS"), ltgNames.toSet())

        val jackOutIndex = availableActions2.jsonArray
            .indexOfFirst { it.jsonObject["kind"]?.jsonPrimitive?.content == "JackOut" }
        send(Frame.Text("""{"type":"action","actionIndex":$jackOutIndex}"""))
        incoming.receive() // result
        incoming.receive() // registered_decker
        turn2.join(5000)
        assertFalse(turn2.isAlive, "turn2 did not terminate in time")
    }

    private fun webSocketTest(block: suspend DefaultClientWebSocketSession.(SessionRegistry) -> Unit) =
        testApplication {
            val registry = SessionRegistry()
            application { matrixModule(registry) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/decker/ws") { block(registry) }
        }

    private suspend fun DefaultClientWebSocketSession.receiveJson(): JsonObject =
        Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private suspend fun DefaultClientWebSocketSession.consumeObserver() {
        incoming.receive()
    }

    private suspend fun DefaultClientWebSocketSession.joinAsDecker(name: String) {
        consumeObserver()
        send(Frame.Text("""{"type":"join","deckerName":"$name"}"""))
        incoming.receive() // consume registered_decker
    }

    private fun deckerState(name: String) =
        DeckerStateDto(
            name = name,
            location = "not jacked in",
            locationIndex = null,
            isPinnedByBlackIc = false,
            physicalDamage = 0,
            physicalMaxBoxes = 10,
            mentalDamage = 0,
            mentalMaxBoxes = 10,
            hackingPool = 6,
            mcpRating = 4,
            activeUtilities = emptyList()
        )
}
