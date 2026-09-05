package com.shadowrun.matrix.server

import com.shadowrun.matrix.server.dto.ActionCommand
import com.shadowrun.matrix.server.dto.ErrorCode
import com.shadowrun.matrix.server.dto.ErrorMessage
import com.shadowrun.matrix.server.dto.JoinMessage
import com.shadowrun.matrix.server.dto.MatrixJson
import com.shadowrun.matrix.server.dto.MatrixJsonIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private const val MAX_FRAME_SIZE = 65_536L
private const val MAX_CONNECTIONS = 32

// Heartbeat so half-open / dead TCP connections are detected: Ktor pings every PING_PERIOD and
// closes the session if no pong arrives within PING_TIMEOUT. The close drives the finally-block
// deregister → cancelIfActive, unblocking a turn that was waiting on the dropped controller.
private val PING_PERIOD = 15.seconds
private val PING_TIMEOUT = 30.seconds

// Generic client-facing detail for dispatch failures; the real exception is logged server-side.
private const val GENERIC_BAD_REQUEST = "malformed request"

// Same-origin allow-list for the WebSocket upgrade (CSWSH mitigation). The UI is served
// same-origin from "/", so the local/production origins suffice. A missing Origin header
// (non-browser clients, tests) is allowed — browsers always send Origin on a WS handshake.
// Note: the Vite dev server runs on a different port; add its origin here to use it.
private val ALLOWED_ORIGINS = setOf("http://localhost:8080", "http://127.0.0.1:8080")

fun Application.matrixModule(registry: SessionRegistry) {
    install(WebSockets) {
        maxFrameSize = MAX_FRAME_SIZE
        pingPeriod = PING_PERIOD
        timeout = PING_TIMEOUT
    }

    routing {
        staticResources("/", "static") { default("index.html") }
        webSocket("/decker/ws") {
            val origin = call.request.headers["Origin"]
            if (origin != null && origin !in ALLOWED_ORIGINS) {
                logger.warn { "Connection refused: disallowed Origin '$origin'" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
                return@webSocket
            }
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
                            val msgType = MatrixJsonIn.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
                            when (msgType) {
                                "join"   -> registry.receiveJoin(this, MatrixJsonIn.decodeFromString<JoinMessage>(json))
                                "action" -> registry.receiveAction(this, MatrixJsonIn.decodeFromString<ActionCommand>(json))
                                else     -> this.send(Frame.Text(MatrixJson.encodeToString(
                                    ErrorMessage(message = ErrorCode.UNKNOWN_MESSAGE_TYPE, details = msgType?.take(64))
                                )))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Frame dispatch error" }
                            runCatching {
                                this.send(Frame.Text(MatrixJson.encodeToString(
                                    ErrorMessage(message = ErrorCode.BAD_REQUEST, details = GENERIC_BAD_REQUEST)
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
