package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridIntegrationTest {

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

    // ── PRD Integration Test ──────────────────────────────────────────────────────
    //
    // Scenario (PRD Data Creation > Integration Tests):
    //   1. Decker logs on to an LTG
    //   2. Switches to the RTG
    //   3. Moves to a different RTG
    //   4. Enters one of its LTGs
    //   5. Logs on to a host in that LTG
    //   6. Logs off

    @Test
    fun `integration - jack in to LTG, traverse RTGs, enter host, logoff`() {
        // ── Resolve grid objects ──────────────────────────────────────────────────
        val ucasBase = matrix.rtgs.first { it.name == "UCAS" }
        val aztlan = matrix.rtgs.first { it.name == "AZT" }
        val mexicoCity = aztlan.ltgs.first { it.name == "AZT-MEX" }
        val targetHost = mexicoCity.hosts.first { it.name == "Aztlan Ministry of Information" }

        // Wire UCAS → Aztlan inter-RTG link
        val ucas = ucasBase.copy(connectedRtgs = listOf(aztlan))
        // Seattle's parentRtg must be the same ucas object used throughout
        val seattleBase = ucasBase.ltgs.first { it.name == "UCAS-SEA" }
        val seattle = seattleBase.copy(parentRtg = ucas)
        val ucasWithLtgs = ucas.copy(ltgs = listOf(seattle) + ucas.ltgs.drop(1))

        // ── Step 1: jack in to Seattle LTG ───────────────────────────────────────
        val jackpoint = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = seattle)
        var decker = buildDecker(jackpoint)

        val jackInResult = decker.jackInToLtg(seattle, winRoller())
        assertIs<LogonResult.Success>(jackInResult, "Step 1 - jack in to LTG failed")
        assertIs<MatrixLocation.OnLTG>(jackInResult.location)
        decker = jackInResult.decker

        // ── Step 2: move to UCAS RTG ──────────────────────────────────────────────
        // parentRtg of the LTG we're on is `ucas`; we pass that same object
        val currentLtg = (decker.currentLocation as MatrixLocation.OnLTG).ltg
        val toRtgResult = decker.logonToRtg(currentLtg.parentRtg, winRoller())
        assertIs<LogonResult.Success>(toRtgResult, "Step 2 - move to RTG failed")
        assertIs<MatrixLocation.OnRTG>(toRtgResult.location)
        decker = toRtgResult.decker

        // Ensure current RTG knows about connected RTGs (for step 3)
        decker = decker.copy(
            currentLocation = MatrixLocation.OnRTG(ucasWithLtgs)
        )

        // ── Step 3: move to Aztlan RTG (long-distance hop) ───────────────────────
        val toAztlanResult = decker.logonToRtg(aztlan, winRoller())
        assertIs<LogonResult.Success>(toAztlanResult, "Step 3 - move to Aztlan RTG failed")
        val aztlanLocation = toAztlanResult.location
        assertIs<MatrixLocation.OnRTG>(aztlanLocation)
        assertEquals("AZT", aztlanLocation.rtg.name)
        assertTrue(aztlanLocation.rtg.securityTally < 5, "Tally should reset on RTG hop")
        decker = toAztlanResult.decker

        // ── Step 4: enter Mexico City LTG ────────────────────────────────────────
        val aztlanWithLtgs = aztlanLocation.rtg.copy(ltgs = aztlan.ltgs)
        decker = decker.copy(currentLocation = MatrixLocation.OnRTG(aztlanWithLtgs))

        val toLtgResult = decker.logonToLtg(mexicoCity, winRoller())
        assertIs<LogonResult.Success>(toLtgResult, "Step 4 - enter Mexico City LTG failed")
        assertIs<MatrixLocation.OnLTG>(toLtgResult.location)
        decker = toLtgResult.decker

        // ── Step 5: log on to host ────────────────────────────────────────────────
        val mexCityWithHosts = (decker.currentLocation as MatrixLocation.OnLTG).ltg
            .copy(hosts = mexicoCity.hosts)
        decker = decker.copy(currentLocation = MatrixLocation.OnLTG(mexCityWithHosts))

        val toHostResult = decker.logonToHost(targetHost, winRoller())
        assertIs<LogonResult.Success>(toHostResult, "Step 5 - logon to host failed")
        val hostLocation = toHostResult.location
        assertIs<MatrixLocation.OnHost>(hostLocation)
        assertEquals("Aztlan Ministry of Information", hostLocation.host.name)
        decker = toHostResult.decker

        // ── Step 6: graceful logoff ───────────────────────────────────────────────
        val logoffResult = decker.gracefulLogoff(winRoller())
        assertIs<LogoffResult.GracefulSuccess>(logoffResult, "Step 6 - logoff failed")
        assertNull(logoffResult.decker.persona)
        assertNull(logoffResult.decker.currentLocation)
    }
}
