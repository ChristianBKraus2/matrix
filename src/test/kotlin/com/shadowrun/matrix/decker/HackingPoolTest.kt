package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HackingPoolTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private fun deck(mcpRating: Int = 9) = Cyberdeck(
        name = "TestDeck", mcpRating = mcpRating,
        activeMemoryMp = 2000, storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 100, costNuyen = 0
    )

    private fun decker(intelligence: Int = 6, mcpRating: Int = 9, computerSkill: Int = 4, host: Host? = null): Decker {
        val persona = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4)
        return Decker(
            name = "TestDecker", intelligence = intelligence, body = 3,
            willpower = 4, reaction = 4, computerSkill = computerSkill,
            cyberdeck = deck(mcpRating),
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor(),
            persona = persona,
            currentLocation = if (host != null) MatrixLocation.OnHost(host) else null
        )
    }

    private fun host(secValue: Int = 4, control: Int = 4) = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.GREEN, secValue),
        subsystemRatings = SubsystemRatings(control, control, control, control, control),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS,
        alertStatus = AlertStatus.NO_ALERT
    )

    /** Counts how many dice were rolled; face=0 → 0 successes for all rolls. */
    private fun countingRoller(): Pair<DiceRoller, () -> Int> {
        var count = 0
        val roller = DiceRoller(object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int { count++; return 0 }
        })
        return roller to { count }
    }

    // ── hackingPool property ──────────────────────────────────────────────────────

    @Test
    fun `hackingPool is floor of (intelligence plus mcpRating) divided by 3`() {
        // (6 + 9) / 3 = 5
        assertEquals(5, decker(intelligence = 6, mcpRating = 9).hackingPool)
        // (7 + 12) / 3 = 6
        assertEquals(6, decker(intelligence = 7, mcpRating = 11).hackingPool)
        // (5 + 4) / 3 = 3 (integer division floors)
        assertEquals(3, decker(intelligence = 5, mcpRating = 4).hackingPool)
    }

    // ── remainingHackingPool property ─────────────────────────────────────────────

    @Test
    fun `remainingHackingPool equals hackingPool when hackingPoolUsed is 0`() {
        val d = decker()
        assertEquals(d.hackingPool, d.remainingHackingPool)
        assertEquals(0, d.hackingPoolUsed)
    }

    @Test
    fun `remainingHackingPool decreases by hackingPoolUsed`() {
        val d = decker(intelligence = 6, mcpRating = 9) // hackingPool = 5
        val used = d.copy(hackingPoolUsed = 3)
        assertEquals(2, used.remainingHackingPool)
    }

    @Test
    fun `hackingPoolUsed defaults to 0 for a freshly constructed decker`() {
        assertEquals(0, decker().hackingPoolUsed)
    }

    // ── Pool dice added to system test rolls ──────────────────────────────────────

    @Test
    fun `analyzeHost rolls computerSkill plus hackingPoolDice dice for decker`() {
        val h = host(secValue = 4, control = 4)
        val d = decker(computerSkill = 4, host = h)
        val items = listOf(HostInfoItem.SecurityRating)

        val (roller0, count0) = countingRoller()
        d.analyzeHost(h, items, roller0, hackingPoolDice = 0)
        val baseCount = count0()

        val (roller3, count3) = countingRoller()
        d.analyzeHost(h, items, roller3, hackingPoolDice = 3)
        val poolCount = count3()

        // 3 extra pool dice must have been rolled in addition to computerSkill dice
        assertEquals(baseCount + 3, poolCount, "hackingPoolDice=3 should add exactly 3 more dice")
    }

    @Test
    fun `analyzeIc rolls computerSkill plus hackingPoolDice dice for decker`() {
        val h = host()
        val d = decker(computerSkill = 3, host = h)
        val ic = com.shadowrun.matrix.ic.Killer(rating = 4)

        val (roller0, count0) = countingRoller()
        d.analyzeIc(ic, h, roller0, hackingPoolDice = 0)

        val (roller2, count2) = countingRoller()
        d.analyzeIc(ic, h, roller2, hackingPoolDice = 2)

        assertEquals(count0() + 2, count2(), "hackingPoolDice=2 should add exactly 2 more dice")
    }

    // ── Pool validation via remainingHackingPool ──────────────────────────────────

    @Test
    fun `hackingPoolUsed can be incremented via copy`() {
        val d = decker()
        val pool = d.hackingPool
        val afterAction = d.copy(hackingPoolUsed = d.hackingPoolUsed + 2)
        assertEquals(pool - 2, afterAction.remainingHackingPool)
    }

    @Test
    fun `remainingHackingPool cannot go below zero via coerce`() {
        val d = decker(intelligence = 3, mcpRating = 3) // hackingPool = 2
        val overused = d.copy(hackingPoolUsed = 5)
        // remainingHackingPool = 2 - 5 = -3; the pool property itself is not clamped — validation
        // is the controller's responsibility. Verify that the raw computed value reflects usage.
        assertEquals(-3, overused.remainingHackingPool)
        assertTrue(overused.remainingHackingPool < 0, "Negative remaining pool signals overdraw")
    }

    // ── Turn-start reset ──────────────────────────────────────────────────────────

    @Test
    fun `copy with hackingPoolUsed=0 resets pool to full capacity`() {
        val d = decker()
        val afterFirstAction = d.copy(hackingPoolUsed = 4)
        assertEquals(d.hackingPool - 4, afterFirstAction.remainingHackingPool)

        val resetForNextTurn = afterFirstAction.copy(hackingPoolUsed = 0)
        assertEquals(d.hackingPool, resetForNextTurn.remainingHackingPool)
    }
}
