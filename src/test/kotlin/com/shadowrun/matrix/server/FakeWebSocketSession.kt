package com.shadowrun.matrix.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

@OptIn(InternalAPI::class)
class FakeWebSocketSession : DefaultWebSocketServerSession {
    private val _outgoing = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = Job()
    override val incoming: ReceiveChannel<Frame> = Channel()
    override val outgoing: SendChannel<Frame> = _outgoing
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking = false
    override var maxFrameSize = Long.MAX_VALUE
    override var pingIntervalMillis = -1L
    override var timeoutMillis = 15_000L
    override val closeReason: Deferred<CloseReason?> = CompletableDeferred()
    override val call: ApplicationCall get() = error("not used in tests")

    override suspend fun send(frame: Frame) { _outgoing.send(frame) }
    override suspend fun flush() = Unit
    override fun terminate() = Unit
    override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit

    suspend fun nextText(): String = (withTimeout(5_000) { _outgoing.receive() } as Frame.Text).readText()
}
