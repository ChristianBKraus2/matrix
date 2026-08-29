package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import io.github.oshai.kotlinlogging.KotlinLogging
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

private val logger = KotlinLogging.logger {}

private const val MAX_FRAME_SIZE = 65_536L
private const val MAX_CONNECTIONS = 32

fun Application.matrixModule(registry: SessionRegistry) {
    install(WebSockets) {
        maxFrameSize = MAX_FRAME_SIZE
    }

    routing {
        staticResources("/", "static")
        webSocket("/decker/ws") {
            if (!registry.register(this, maxConnections = MAX_CONNECTIONS)) {
                logger.warn { "Connection refused: server at capacity ($MAX_CONNECTIONS)" }
                this.send(Frame.Text(MatrixJson.encodeToString(
                    ErrorMessage(message = ErrorCode.SERVER_FULL)
                )))
                return@webSocket
            }
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
                                ErrorMessage(message = ErrorCode.UNKNOWN_MESSAGE_TYPE, details = msgType?.take(64))
                            )))
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Frame dispatch error" }
                        runCatching {
                            this.send(Frame.Text(MatrixJson.encodeToString(
                                ErrorMessage(message = ErrorCode.BAD_REQUEST, details = null)
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
