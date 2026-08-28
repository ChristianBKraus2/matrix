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

private val logger = KotlinLogging.logger {}

fun main() {
    val classLoader = Thread.currentThread().contextClassLoader

    val catalog = classLoader.getResourceAsStream("decks.yaml")!!
        .use { DeckCatalogLoader.load(it) }

    val decker = classLoader.getResourceAsStream("headcrash.yaml")!!
        .use { DeckerLoader.load(it, catalog) }

    val host = classLoader.getResourceAsStream("hosts/MitsuhamaPagoda.yaml")!!
        .use { HostLoader.load(it) }

    GridInitializer.initialize()

    val registry = SessionRegistry()
    startMatrixServer(registry)
    logger.info { "Matrix server running on http://localhost:8080" }

    val context = GameContext(
        host = host,
        securityCode = SecurityCode.GREEN,
        deckers = listOf(decker)
    )
    val controller = WebSocketDeckerController(registry, decker)

    while (true) {
        try {
            runBlocking { controller.action(context, DiceRoller()) }
        } catch (e: Exception) {
            logger.warn { "Game loop: ${e.message}" }
            Thread.sleep(500)
        }
    }
}
