package com.shadowrun.matrix

import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.config.DeckCatalogLoader
import com.shadowrun.matrix.config.DeckerLoader
import com.shadowrun.matrix.config.GridInitializer
import com.shadowrun.matrix.config.HostLoader
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.server.SessionRegistry
import com.shadowrun.matrix.server.WebSocketDeckerController
import com.shadowrun.matrix.server.startMatrixServer
import com.shadowrun.matrix.utility.DiceRoller
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

// Demo game loop: after this many consecutive failed turns, give up rather than busy-loop the log.
private const val MAX_CONSECUTIVE_ERRORS = 10
private const val ERROR_BACKOFF_MS = 500L

fun main() {
    val classLoader = Thread.currentThread().contextClassLoader

    val catalog = requireNotNull(classLoader.getResourceAsStream("decks.yaml")) { "Resource not found: decks.yaml" }
        .use { DeckCatalogLoader.load(it) }

    val decker = requireNotNull(classLoader.getResourceAsStream("headcrash.yaml")) { "Resource not found: headcrash.yaml" }
        .use { DeckerLoader.load(it, catalog) }

    val host = requireNotNull(classLoader.getResourceAsStream("hosts/MitsuhamaPagoda.yaml")) { "Resource not found: hosts/MitsuhamaPagoda.yaml" }
        .use { HostLoader.load(it) }

    val matrix = GridInitializer.initialize()

    val joinSecret = System.getenv("MATRIX_JOIN_SECRET")
    if (joinSecret == null) logger.warn { "MATRIX_JOIN_SECRET not set — join authentication disabled" }
    val registry = SessionRegistry(joinSecret = joinSecret)
    startMatrixServer(registry)
    logger.info { "Matrix server running on http://localhost:8080" }

    val context = GameContext(
        host = host,
        securityCode = host.securityRating.code,
        deckers = listOf(decker),
        matrix = matrix
    )
    val controller = WebSocketDeckerController(registry, decker)
    val diceRoller = DiceRoller()

    // Single event loop for the whole demo (one runBlocking, not one per turn). A run of
    // consecutive failures backs off and eventually aborts instead of spinning forever.
    runBlocking {
        var consecutiveErrors = 0
        while (true) {
            try {
                controller.conductTurn(context, diceRoller)
                consecutiveErrors = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                logger.error(e) { "Game loop error ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)" }
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    logger.error { "Aborting game loop after $MAX_CONSECUTIVE_ERRORS consecutive errors" }
                    break
                }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }
}
