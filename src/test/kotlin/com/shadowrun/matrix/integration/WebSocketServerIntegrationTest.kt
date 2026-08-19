package com.shadowrun.matrix.integration

import com.shadowrun.matrix.server.SessionRegistry
import com.shadowrun.matrix.server.matrixModule
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WebSocketServerIntegrationTest {

    @Test
    fun `connecting receives observer ControlMessage`() = testApplication {
        application { matrixModule(SessionRegistry()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/decker/ws") {
            val text = (incoming.receive() as Frame.Text).readText()
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("control", obj["type"]?.jsonPrimitive?.content)
            assertEquals("observer", obj["role"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `sending JoinMessage receives registered_decker ControlMessage`() = testApplication {
        application { matrixModule(SessionRegistry()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/decker/ws") {
            incoming.receive() // consume observer message
            send(Frame.Text("""{"type":"join","deckerName":"Kylie"}"""))
            val text = (incoming.receive() as Frame.Text).readText()
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("control", obj["type"]?.jsonPrimitive?.content)
            assertEquals("registered_decker", obj["role"]?.jsonPrimitive?.content)
            assertEquals("Kylie", obj["deckerName"]?.jsonPrimitive?.content)
        }
    }
}
