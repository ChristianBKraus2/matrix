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
    const val HIGH_END = "Quicksilver"
    const val STANDARD = "Static"
    const val LOW_END   = "Glitch"

    fun build(jackpoint: Jackpoint, tier: String = HIGH_END): Decker = when (tier) {
        HIGH_END -> highEnd(jackpoint)
        STANDARD -> standard(jackpoint)
        LOW_END  -> lowEnd(jackpoint)
        else     -> throw IllegalArgumentException("Unknown decker tier: $tier")
    }

    fun highEnd(jackpoint: Jackpoint): Decker {
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
            name = HIGH_END,
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

    fun standard(jackpoint: Jackpoint): Decker {
        val programs = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 4),
            PersonaProgram(PersonaAttributeType.EVASION, 4),
            PersonaProgram(PersonaAttributeType.MASKING, 4),
            PersonaProgram(PersonaAttributeType.SENSORS, 4)
        )
        val deck = Cyberdeck(
            name = "Renraku Kraftwerk",
            mcpRating = 6,
            activeMemoryMp = 750,
            storageMemoryMp = 2000,
            ioSpeedMpPerTurn = 150,
            costNuyen = 120_000,
            personaPrograms = programs
        )
        return Decker(
            name = STANDARD,
            intelligence = 5,
            body = 3,
            willpower = 4,
            reaction = 4,
            computerSkill = 5,
            cyberdeck = deck,
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor(),
            jackpoint = jackpoint
        )
    }

    fun lowEnd(jackpoint: Jackpoint): Decker {
        val programs = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 2),
            PersonaProgram(PersonaAttributeType.EVASION, 2),
            PersonaProgram(PersonaAttributeType.MASKING, 3),
            PersonaProgram(PersonaAttributeType.SENSORS, 2)
        )
        val deck = Cyberdeck(
            name = "Allegiance Alpha",
            mcpRating = 3,
            activeMemoryMp = 200,
            storageMemoryMp = 500,
            ioSpeedMpPerTurn = 50,
            costNuyen = 15_000,
            personaPrograms = programs
        )
        return Decker(
            name = LOW_END,
            intelligence = 3,
            body = 2,
            willpower = 3,
            reaction = 3,
            computerSkill = 3,
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