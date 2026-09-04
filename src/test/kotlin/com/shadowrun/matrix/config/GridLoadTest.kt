package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.SecurityCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridLoadTest {

    // ── Setup ─────────────────────────────────────────────────────────────────────

    private val matrix = GridInitializer.initialize()

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
