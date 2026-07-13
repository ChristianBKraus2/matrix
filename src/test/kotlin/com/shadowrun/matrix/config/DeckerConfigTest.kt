package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeckerConfigTest {

    private val decker by lazy {
        val input = DeckerConfigTest::class.java.classLoader
            .getResourceAsStream("headcrash.yaml")
            ?: error("headcrash.yaml not found on classpath")
        input.use { DeckerLoader.load(it) }
    }

    @Test
    fun `decker name loads correctly`() {
        assertEquals("HeadCrash", decker.name)
    }

    @Test
    fun `decker stats load correctly`() {
        assertEquals(6, decker.intelligence)
        assertEquals(4, decker.body)
        assertEquals(5, decker.willpower)
        assertEquals(5, decker.reaction)
        assertEquals(6, decker.computerSkill)
    }

    @Test
    fun `cyberdeck hardware values load correctly`() {
        val deck = decker.cyberdeck
        assertEquals(8,    deck.mcpRating)
        assertEquals(4,    deck.hardening)
        assertEquals(1000, deck.activeMemoryMp)
        assertEquals(2000, deck.storageMemoryMp)
        assertEquals(360,  deck.ioSpeedMpPerTurn)
        assertEquals(2,    deck.responseIncrease)
    }

    @Test
    fun `persona programs load correctly`() {
        val programs = decker.cyberdeck.personaPrograms
        assertEquals(4, programs.size)

        fun rating(type: PersonaAttributeType) =
            programs.first { it.attributeType == type }.rating

        assertEquals(6, rating(PersonaAttributeType.BOD))
        assertEquals(6, rating(PersonaAttributeType.EVASION))
        assertEquals(6, rating(PersonaAttributeType.MASKING))
        assertEquals(6, rating(PersonaAttributeType.SENSORS))
    }

    @Test
    fun `utilities load correctly`() {
        val utils = decker.cyberdeck.storedUtilities
        assertEquals(5, utils.size)

        assertTrue(utils.any { it.type == UtilityType.DECEPTION && it.rating == 4 })
        assertTrue(utils.any { it.type == UtilityType.SLEAZE     && it.rating == 5 })
        assertTrue(utils.any { it.type == UtilityType.ANALYZE    && it.rating == 4 })
        assertTrue(utils.any { it.type == UtilityType.ATTACK     && it.rating == 6 })
        assertTrue(utils.any { it.type == UtilityType.ARMOR      && it.rating == 5 })
    }

    @Test
    fun `utility storage fits within storage memory`() {
        val totalMp = decker.cyberdeck.storedUtilities.sumOf { it.mpSize }
        assertTrue(totalMp <= decker.cyberdeck.storageMemoryMp,
            "Total utility Mp ($totalMp) exceeds storage memory (${decker.cyberdeck.storageMemoryMp})")
    }

    @Test
    fun `hacking pool is calculated correctly`() {
        // (intelligence=6 + mpcp=8) / 3 = 4
        assertEquals(4, decker.hackingPool)
    }

    @Test
    fun `detection factor is calculated correctly with sleaze active`() {
        // Sleaze is in storedUtilities; to be active it must also be in activeUtilities.
        // Verify the formula directly via cyberdeck helper.
        val masking = decker.cyberdeck.personaPrograms
            .first { it.attributeType == PersonaAttributeType.MASKING }.rating
        val sleaze = decker.cyberdeck.storedUtilities
            .first { it.type == UtilityType.SLEAZE }.rating

        // ceil((6 + 5) / 2) = 6
        assertEquals(6, decker.cyberdeck.detectionFactor(masking, sleaze))
    }

    @Test
    fun `response increase satisfies mpcp cap`() {
        val deck = decker.cyberdeck
        assertTrue(deck.responseIncrease <= deck.maxResponseIncrease,
            "Response Increase ${deck.responseIncrease} exceeds cap ${deck.maxResponseIncrease}")
    }

    @Test
    fun `persona programs satisfy mpcp constraints`() {
        val deck = decker.cyberdeck
        deck.personaPrograms.forEach { pp ->
            assertTrue(pp.rating <= deck.mcpRating,
                "${pp.name} rating ${pp.rating} exceeds MPCP ${deck.mcpRating}")
        }
        val total = deck.personaPrograms.sumOf { it.rating }
        assertTrue(total <= deck.mcpRating * 3,
            "Total persona program ratings $total exceed MPCP*3 = ${deck.mcpRating * 3}")
    }

    // ── Offline-host integration ────────────────────────────────────────────────

    @Test
    fun `Saeder-Krupp Research Vault is marked offline`() {
        val matrix = GridInitializer.initialize()
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        val seattle = ucas.ltgs.first { it.name == "UCAS-SEA" }
        val vault = seattle.hosts.firstOrNull { it.name == "Saeder-Krupp Research Vault" }
        assertNotNull(vault, "Saeder-Krupp Research Vault not found in UCAS-SEA")
        assertTrue(vault.offline, "Saeder-Krupp Research Vault should be offline")
    }

    @Test
    fun `online hosts are not marked offline`() {
        val matrix = GridInitializer.initialize()
        val ucas = matrix.rtgs.first { it.name == "UCAS" }
        val seattle = ucas.ltgs.first { it.name == "UCAS-SEA" }
        val pagoda = seattle.hosts.first { it.name == "Mitsuhama Pagoda" }
        assertTrue(!pagoda.offline, "Mitsuhama Pagoda should not be offline")
    }
}
