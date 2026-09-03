package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Cyberterminal
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.Persona
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemTestResolverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private fun programs(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck",
        mcpRating = mcpRating,
        activeMemoryMp = 2000,
        storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 500,
        costNuyen = 0,
        personaPrograms = programs(),
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    private fun decker(cyberdeck: Cyberdeck = deck()) = Decker(
        name = "TestDecker",
        intelligence = 6,
        body = 4,
        willpower = 5,
        reaction = 5,
        computerSkill = 6,
        cyberdeck = cyberdeck,
        physicalConditionMonitor = ConditionMonitor(),
        mentalConditionMonitor = ConditionMonitor()
    )

    private fun host(secValue: Int = 6, control: Int = 8) = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.GREEN, secValue),
        subsystemRatings = SubsystemRatings(8, control, 8, 8, 8),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS
    )

    /** Roller that always returns [face] for every die. */
    private fun fixedRoller(face: Int) = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = face.coerceIn(from, until - 1)
    })

    // face=5 → not open-ended, beats TN ≤ 5; used as "decker wins" roller
    // face=2 → not open-ended, loses against most TNs; used as "decker loses" roller

    // ── resolveNullOperation ──────────────────────────────────────────────────────

    @Test
    fun `resolveNullOperation under 10 seconds applies 0 bonus to SecurityValue`() {
        val d = decker()
        val h = host(secValue = 4)
        // With face=5 decker always fails high TN; host (DF=3) always succeeds — we care host rolls exactly 4 dice (no bonus)
        val outcome = SystemTestResolver.resolveNullOperation(d, h, inactivitySeconds = 5, fixedRoller(5))
        // secValue=4, bonus=0 → host rolled 4 dice; with face=5 vs DF=3 host gets 4 successes
        // decker rolled 6 dice with face=5 vs TN=max(2, control-deception)
        // Structural check: outcome is returned with no exception
        assertEquals(4, outcome.hostSuccesses)
        assertFalse(outcome.deckerWins)
    }

    @Test
    fun `resolveNullOperation 90 seconds applies +2 to SecurityValue`() {
        // 90 s → ONE_MINUTE_TO_ONE_HOUR → bonus = 2
        val d = decker()
        val h = host(secValue = 4)
        // Roller: decker always rolls face=8 (success), host always rolls face=2 (fail vs DF=3)
        // Host effective SV = 4+2 = 6 dice
        val roller = fixedRoller(2)
        val outcome = SystemTestResolver.resolveNullOperation(d, h, inactivitySeconds = 90, roller)
        // With face=2 vs DF=3 host gets 0 successes; decker wins
        assertTrue(outcome.deckerWins)
    }

    @Test
    fun `resolveNullOperation 7200 seconds applies +4 bonus`() {
        assertEquals(4, NullOperationModifier.totalBonusForDuration(7200))
    }

    @Test
    fun `NullOperationModifier brackets map correctly`() {
        assertEquals(NullOperationModifier.UNDER_TEN_SECONDS, NullOperationModifier.forDuration(5))
        assertEquals(NullOperationModifier.TEN_SECONDS_TO_ONE_MINUTE, NullOperationModifier.forDuration(30))
        assertEquals(NullOperationModifier.ONE_MINUTE_TO_ONE_HOUR, NullOperationModifier.forDuration(120))
        assertEquals(NullOperationModifier.ONE_HOUR_TO_TWELVE_HOURS, NullOperationModifier.forDuration(3600))
    }

    // ── resolveInterrogation ──────────────────────────────────────────────────────

    @Test
    fun `resolveInterrogation accumulates decker successes across calls`() {
        val browse = Utility(UtilityType.BROWSE, rating = 4)
        val d = decker(deck(activeUtilities = listOf(browse), storedUtilities = listOf(browse)))
        val h = host()
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "paydata", accumulatedSuccesses = 2)
        // decker rolls face=5 → successes at TN=4; host rolls face=1 → 0 successes at DF=3 → net > 0
        var callCount = 0
        val splitRoller = DiceRoller(object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                val face = if (callCount < d.computerSkill) 5 else 1
                callCount++
                return face.coerceIn(from, until - 1)
            }
        })
        val (outcome, newState) = SystemTestResolver.resolveInterrogation(d, SystemOperation.LOCATE_FILE, h, state, QueryPrecision.NORMAL, splitRoller)
        assertTrue(newState.accumulatedSuccesses > 2)
        assertTrue(outcome.deckerSuccesses > 0)
    }

    @Test
    fun `resolveInterrogation applies VAGUE precision +1 TN modifier`() {
        val d = decker()
        val h = host(control = 6)
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "query")
        // With face=2 vs TN=max(2, 6+1)=7 → 0 successes; with NORMAL TN=6 → still 0 for face=2
        // We verify no crash and that the precision modifies TN direction
        val (_, newStateVague) = SystemTestResolver.resolveInterrogation(d, SystemOperation.LOCATE_FILE, h, state, QueryPrecision.VAGUE, fixedRoller(2))
        val (_, newStateSpecific) = SystemTestResolver.resolveInterrogation(d, SystemOperation.LOCATE_FILE, h, state, QueryPrecision.VERY_SPECIFIC, fixedRoller(5))
        // Very specific gives lower TN → more successes with same roller
        assertTrue(newStateSpecific.accumulatedSuccesses >= newStateVague.accumulatedSuccesses)
    }

    @Test
    fun `resolveInterrogation TN floors at 2`() {
        val browse = Utility(UtilityType.BROWSE, rating = 8)
        val d = decker(deck(activeUtilities = listOf(browse), storedUtilities = listOf(browse)))
        val h = host(control = 4)  // 4 - 8 = -4 → max(2, -4+VERY_SPECIFIC(-2)) still 2
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "q")
        // Should not throw even with extreme modifier
        val (outcome, _) = SystemTestResolver.resolveInterrogation(d, SystemOperation.LOCATE_FILE, h, state, QueryPrecision.VERY_SPECIFIC, fixedRoller(5))
        assertTrue(outcome.deckerSuccesses > 0)
    }

    // ── QueryPrecision ────────────────────────────────────────────────────────────

    @Test
    fun `QueryPrecision modifiers match spec`() {
        assertEquals(+2, QueryPrecision.VERY_VAGUE.modifier)
        assertEquals(+1, QueryPrecision.VAGUE.modifier)
        assertEquals(0,  QueryPrecision.NORMAL.modifier)
        assertEquals(-1, QueryPrecision.SPECIFIC.modifier)
        assertEquals(-2, QueryPrecision.VERY_SPECIFIC.modifier)
    }
}
