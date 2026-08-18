package com.shadowrun.matrix.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.JoinMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun startMatrixServer(
    registry: SessionRegistry,
    port: Int = 8080
) = embeddedServer(Netty, port = port) {
    install(WebSockets)

    routing {
        webSocket("/decker/ws") {
            registry.register(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        runCatching {
                            val json = frame.readText()
                            val msgType = Json.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
                            when (msgType) {
                                "join"   -> registry.receiveJoin(this, Json.decodeFromString<JoinMessage>(json))
                                "action" -> registry.receiveAction(this, Json.decodeFromString<ActionCommand>(json))
                            }
                        }
                    }
                }
            } finally {
                registry.deregister(this)
            }
        }
    }
}.start(wait = false)
