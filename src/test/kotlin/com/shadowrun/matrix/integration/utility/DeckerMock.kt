package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.config.DeckCatalogLoader
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.programs.PersonaProgram
import java.io.InputStream

object DeckerMock {
    fun build(jackpoint: Jackpoint): Decker {
        val programs = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 6),
            PersonaProgram(PersonaAttributeType.EVASION, 6),
            PersonaProgram(PersonaAttributeType.MASKING, 6),
            PersonaProgram(PersonaAttributeType.SENSORS, 6)
        )
        val deck = Cyberdeck(
            name = "Fairlight Excalibur",
            mcpRating = 10,
            activeMemoryMp = 2000,
            storageMemoryMp = 5000,
            ioSpeedMpPerTurn = 300,
            costNuyen = 1_200_000,
            personaPrograms = programs
        )
        return Decker(
            name = "Quicksilver",
            intelligence = 7,
            body = 4,
            willpower = 5,
            reaction = 6,
            computerSkill = 8,
            cyberdeck = deck,
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor(),
            jackpoint = jackpoint
        )
    }
    fun load(decker: InputStream, decks: InputStream) : Decker {
        val decks = DeckCatalogLoader.load(decks)
        return com.shadowrun.matrix.config.DeckerLoader.load(decker,decks)
    }
}