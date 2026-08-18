package com.shadowrun.matrix.server

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.ResultMessage
import com.shadowrun.matrix.utility.DiceRoller
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `first connection receives ControlMessage granted true`() {
        val registry = SessionRegistry()
        testApplication {
            install(WebSockets)
            routing {
                serverWebSocket("/decker/ws") {
                    registry.register(this)
                    try { for (f in incoming) { } } finally { registry.deregister(this) }
                }
            }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/decker/ws") {
                val text = (incoming.receive() as Frame.Text).readText()
                val obj = Json.parseToJsonElement(text).jsonObject
                assertEquals("control", obj["type"]?.jsonPrimitive?.content)
                assertEquals("true", obj["granted"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `second connection receives ControlMessage granted false`() {
        val registry = SessionRegistry()
        testApplication {
            install(WebSockets)
            routing {
                serverWebSocket("/decker/ws") {
                    registry.register(this)
                    try { for (f in incoming) { } } finally { registry.deregister(this) }
                }
            }
            val client1 = createClient { install(ClientWebSockets) }
            val client2 = createClient { install(ClientWebSockets) }

            // Connect first client
            client1.webSocket("/decker/ws") {
                val first = (incoming.receive() as Frame.Text).readText()
                assertEquals("true", Json.parseToJsonElement(first).jsonObject["granted"]?.jsonPrimitive?.content)

                // Connect second client while first is still connected
                client2.webSocket("/decker/ws") {
                    val second = (incoming.receive() as Frame.Text).readText()
                    assertEquals("false", Json.parseToJsonElement(second).jsonObject["granted"]?.jsonPrimitive?.content)
                }
            }
        }
    }

    @Test
    fun `action broadcasts StateMessage then accepts ActionCommand`() {
        val registry = SessionRegistry()
        val decker = makeDecker()
        val controller = WebSocketDeckerController(registry, decker, actionTimeoutSeconds = 5)
        val context = makeContext(decker)

        testApplication {
            install(WebSockets)
            routing {
                serverWebSocket("/decker/ws") {
                    registry.register(this)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val cmd = Json.decodeFromString<ActionCommand>(frame.readText())
                                registry.receiveAction(this, cmd)
                            }
                        }
                    } finally {
                        registry.deregister(this)
                    }
                }
            }

            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/decker/ws") {
                // Consume ControlMessage
                incoming.receive()

                // Start action() on a background thread
                val thread = Thread { controller.action(context, winRoller()) }
                thread.start()

                // Receive StateMessage
                val stateText = (incoming.receive() as Frame.Text).readText()
                val stateType = Json.parseToJsonElement(stateText).jsonObject["type"]?.jsonPrimitive?.content
                assertEquals("state", stateType)

                // Send action command — decker not jacked in so availableActions is empty → invalid index
                send(Frame.Text(Json.encodeToString(ActionCommand(actionIndex = 0))))

                // Receive ResultMessage
                val resultText = (incoming.receive() as Frame.Text).readText()
                val result = Json.decodeFromString<ResultMessage>(resultText)
                assertFalse(result.success)
                assertTrue(result.details.contains("Invalid action index"))

                thread.join(3000)
            }
        }
    }

    @Test
    fun `observer sending ActionCommand receives error message`() {
        val registry = SessionRegistry()
        val decker = makeDecker()
        val controller = WebSocketDeckerController(registry, decker, actionTimeoutSeconds = 5)
        val context = makeContext(decker)

        testApplication {
            install(WebSockets)
            routing {
                serverWebSocket("/decker/ws") {
                    registry.register(this)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val cmd = Json.decodeFromString<ActionCommand>(frame.readText())
                                registry.receiveAction(this, cmd)
                            }
                        }
                    } finally {
                        registry.deregister(this)
                    }
                }
            }

            val controller1 = createClient { install(ClientWebSockets) }
            val observer = createClient { install(ClientWebSockets) }

            controller1.webSocket("/decker/ws") {
                incoming.receive() // controller: consume ControlMessage(granted=true)

                // Start action() — will block waiting for input
                val thread = Thread { controller.action(context, winRoller()) }
                thread.start()

                // Consume StateMessage broadcast to controller
                incoming.receive()

                observer.webSocket("/decker/ws") {
                    incoming.receive() // observer: ControlMessage(granted=false)
                    incoming.receive() // observer: StateMessage broadcast from action()

                    // Observer tries to send action → should receive error
                    send(Frame.Text(Json.encodeToString(ActionCommand(actionIndex = 0))))

                    val errorText = (incoming.receive() as Frame.Text).readText()
                    val error = Json.decodeFromString<ErrorMessage>(errorText)
                    assertEquals("not your turn", error.message)
                }

                thread.join(8000)
            }
        }
    }
}
