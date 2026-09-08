package com.shadowrun.matrix.operations

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ticket 06: query-shape vagueness derivation and candidate ranking. */
class LocateMatchingTest {

    // ── queryPrecisionFromRegex ────────────────────────────────────────────────

    @Test
    fun `a full literal name is VERY_SPECIFIC`() {
        assertEquals(QueryPrecision.VERY_SPECIFIC, queryPrecisionFromRegex("Mitsuhama Pagoda"))
    }

    @Test
    fun `a fragment wrapped in wildcards is VERY_VAGUE`() {
        assertEquals(QueryPrecision.VERY_VAGUE, queryPrecisionFromRegex("*pago*"))
        assertEquals(QueryPrecision.VERY_VAGUE, queryPrecisionFromRegex(".*pago.*"))
    }

    @Test
    fun `an empty query is VERY_VAGUE`() {
        assertEquals(QueryPrecision.VERY_VAGUE, queryPrecisionFromRegex(""))
        assertEquals(QueryPrecision.VERY_VAGUE, queryPrecisionFromRegex("   "))
    }

    @Test
    fun `a mostly-literal trailing wildcard is VAGUE`() {
        assertEquals(QueryPrecision.VAGUE, queryPrecisionFromRegex("Mitsuhama*"))
    }

    // ── rankMatches ─────────────────────────────────────────────────────────────

    @Test
    fun `rankMatches returns only matching candidates, exact-first then shortest`() {
        val candidates = listOf("Mitsuhama Pagoda", "Mitsuhama Data Vault", "Renraku Arcology", "Mitsuhama")
        val ranked = rankMatches("Mitsuhama", candidates)
        assertEquals(listOf("Mitsuhama", "Mitsuhama Pagoda", "Mitsuhama Data Vault"), ranked)
    }

    @Test
    fun `rankMatches caps at five results`() {
        val candidates = (1..10).map { "Host-$it" }
        assertEquals(5, rankMatches("Host", candidates).size)
    }

    @Test
    fun `rankMatches treats a glob fragment as a wildcard match`() {
        val candidates = listOf("Fuchi Data Vault", "Renraku Arcology")
        assertEquals(listOf("Fuchi Data Vault"), rankMatches("*vault*", candidates))
    }

    @Test
    fun `rankMatches returns empty when nothing matches`() {
        assertEquals(emptyList(), rankMatches("XYZ", listOf("Fuchi", "Renraku")))
    }
}
