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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

fun main() {
    val classLoader = Thread.currentThread().contextClassLoader

    val catalog = requireNotNull(classLoader.getResourceAsStream("decks.yaml")) { "Resource not found: decks.yaml" }
        .use { DeckCatalogLoader.load(it) }

    val decker = requireNotNull(classLoader.getResourceAsStream("headcrash.yaml")) { "Resource not found: headcrash.yaml" }
        .use { DeckerLoader.load(it, catalog) }

    val host = requireNotNull(classLoader.getResourceAsStream("hosts/MitsuhamaPagoda.yaml")) { "Resource not found: hosts/MitsuhamaPagoda.yaml" }
        .use { HostLoader.load(it) }

    val matrix = GridInitializer.initialize()

    val registry = SessionRegistry()
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

    while (true) {
        try {
            runBlocking { controller.conductTurn(context, diceRoller) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Game loop error" }
            Thread.sleep(500)
        }
    }
}
