package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridLoadTest {

    // ── Setup ─────────────────────────────────────────────────────────────────────

    private val matrix = GridInitializer.initialize()

    /** Roller where decker always wins (rolls 6s) and host always loses (rolls 1s). */
    private fun winRoller() = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 6) 5 else 0  // decker: face=6 (success), host: face=1 (no success)
        }
    })

    private fun buildDecker(jackpoint: Jackpoint): Decker {
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

    // ── Grid loading ──────────────────────────────────────────────────────────────

    @Test
    fun `grid loads all north american RTGs`() {
        assertTrue(matrix.rtgs.size >= 19, "Expected at least 19 RTGs, got ${matrix.rtgs.size}")
    }

    @Test
    fun `UCAS RTG has correct security rating`() {
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        assertEquals(SecurityCode.GREEN, ucas.securityRating.code)
        assertEquals(4, ucas.securityRating.value)
    }

    @Test
    fun `UCAS has Seattle LTG`() {
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        val seattle = ucas.ltgs.firstOrNull { it.name == "UCAS-SEA" }
        assertNotNull(seattle, "UCAS-SEA LTG not found")
        assertEquals("Seattle", seattle.region)
    }

    @Test
    fun `Seattle LTG has Mitsuhama Pagoda host`() {
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        val seattle = ucas.ltgs.first { it.name == "UCAS-SEA" }
        val pagoda = seattle.hosts.firstOrNull { it.name == "Mitsuhama Pagoda" }
        assertNotNull(pagoda, "Mitsuhama Pagoda not found in UCAS-SEA")
        assertEquals(SecurityCode.ORANGE, pagoda.securityRating.code)
        assertEquals(6, pagoda.securityRating.value)
    }

    @Test
    fun `LTG ratings inherit from parent RTG when not overridden`() {
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        val seattle = ucas.ltgs.first { it.name == "UCAS-SEA" }
        assertEquals(ucas.securityRating, seattle.securityRating)
        assertEquals(ucas.subsystemRatings.access, seattle.subsystemRatings.access)
    }

    @Test
    fun `Aztlan RTG has correct security rating`() {
        val azt = matrix.rtgs.first { it.name == "AZT" }
        assertEquals(SecurityCode.ORANGE, azt.securityRating.code)
        assertEquals(3, azt.securityRating.value)
    }

    @Test
    fun `Aztlan RTG has Aztechnology PLTG`() {
        val azt = matrix.rtgs.first { it.name == "AZT" }
        val allPltgs = azt.ltgs.flatMap { it.pltgs }
        val aztPltg = allPltgs.firstOrNull { it.owner == "Aztechnology" }
        assertNotNull(aztPltg, "Aztechnology PLTG not found under AZT RTG")
    }
}
