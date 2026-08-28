package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Application.matrixModule(registry: SessionRegistry) {
    install(WebSockets)

    routing {
        staticResources("/", "static")
        webSocket("/decker/ws") {
            registry.register(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                    try {
                        val json = frame.readText()
                        val msgType = Json.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
                        when (msgType) {
                            "join"   -> registry.receiveJoin(this, Json.decodeFromString<JoinMessage>(json))
                            "action" -> registry.receiveAction(this, Json.decodeFromString<ActionCommand>(json))
                            else     -> this.send(Frame.Text(MatrixJson.encodeToString(
                                ErrorMessage(message = ErrorCode.UNKNOWN_MESSAGE_TYPE, details = msgType)
                            )))
                        }
                    } catch (e: Exception) {
                        runCatching {
                            this.send(Frame.Text(MatrixJson.encodeToString(
                                ErrorMessage(message = ErrorCode.BAD_REQUEST, details = e.message?.take(120))
                            )))
                        }
                    }
                    }
                }
            } finally {
                registry.deregister(this)
            }
        }
    }
}

fun startMatrixServer(
    registry: SessionRegistry,
    port: Int = 8080
) = embeddedServer(Netty, port = port) { matrixModule(registry) }.start(wait = false)
