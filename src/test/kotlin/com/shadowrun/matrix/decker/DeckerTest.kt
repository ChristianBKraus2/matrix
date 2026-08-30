package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory
import com.shadowrun.matrix.accessories.HitcherJackType
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.combat.IcSuppressionState
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckerTest {

    private fun deck(
        mcpRating: Int = 6,
        responseIncrease: Int = 0,
        personaPrograms: List<PersonaProgram> = emptyList(),
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck",
        mcpRating = mcpRating,
        activeMemoryMp = 500,
        storageMemoryMp = 1000,
        ioSpeedMpPerTurn = 50,
        responseIncrease = responseIncrease,
        costNuyen = 10000,
        personaPrograms = personaPrograms,
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    // ── Decker ─────────────────────────────────────────────────────────────────

    @Test
    fun `hackingPool is (intelligence + mcpRating) div 3`() {
        val decker = Decker(
            name = "Ghost",
            intelligence = 6,
            body = 4,
            willpower = 5,
            reaction = 5,
            computerSkill = 6,
            cyberdeck = deck(mcpRating = 6),
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor()
        )
        assertEquals(4, decker.hackingPool)
    }

    @Test
    fun `Decker persona is null when not jacked in`() {
        val decker = Decker(
            name = "Ghost", intelligence = 6, body = 4, willpower = 5,
            reaction = 5, computerSkill = 6, cyberdeck = deck()
        )
        assertNull(decker.persona)
        assertNull(decker.jackpoint)
    }

    @Test
    fun `Decker carries persona and jackpoint during a run`() {
        val node = Node(SubsystemType.ACCESS)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6, currentNode = node)
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = com.shadowrun.matrix.network.LTG(
            "Seattle",
            com.shadowrun.matrix.network.RTG("UCAS", "North America",
                com.shadowrun.matrix.common.SecurityRating(com.shadowrun.matrix.common.SecurityCode.GREEN, 6),
                com.shadowrun.matrix.common.SubsystemRatings(6,6,6,6,6)),
            com.shadowrun.matrix.common.SecurityRating(com.shadowrun.matrix.common.SecurityCode.GREEN, 6),
            com.shadowrun.matrix.common.SubsystemRatings(6,6,6,6,6)))
        val decker = Decker(
            name = "Ghost", intelligence = 6, body = 4, willpower = 5,
            reaction = 5, computerSkill = 6, cyberdeck = deck(), persona = persona, jackpoint = jp
        )
        assertEquals(persona, decker.persona)
        assertEquals(node, decker.persona?.currentNode)
        assertEquals(jp, decker.jackpoint)
    }

    // ── Cyberdeck ──────────────────────────────────────────────────────────────

    @Test
    fun `Cyberdeck detectionFactor with sleaze`() {
        assertEquals(5, deck().detectionFactor(maskingRating = 6, sleazeRating = 4))
    }

    @Test
    fun `Cyberdeck detectionFactor without sleaze uses ceil`() {
        assertEquals(3, deck().detectionFactor(maskingRating = 5))
    }

    @Test
    fun `Cyberdeck responseIncrease cannot exceed maxResponseIncrease`() {
        assertFailsWith<IllegalArgumentException> {
            deck(mcpRating = 6, responseIncrease = 2)  // max = 6/4 = 1
        }
    }

    @Test
    fun `Cyberdeck persona programs fit within MPCP constraints`() {
        val pp = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 6),
            PersonaProgram(PersonaAttributeType.EVASION, 6),
            PersonaProgram(PersonaAttributeType.MASKING, 6),
            PersonaProgram(PersonaAttributeType.SENSORS, 6)
        )
        // MPCP 8, sum = 24 = 8×3 — exactly at limit, should pass
        val d = deck(mcpRating = 8, personaPrograms = pp)
        assertEquals(4, d.personaPrograms.size)
    }

    @Test
    fun `Cyberdeck rejects persona program rating exceeding MPCP`() {
        assertFailsWith<IllegalArgumentException> {
            deck(mcpRating = 6, personaPrograms = listOf(
                PersonaProgram(PersonaAttributeType.BOD, 7)  // 7 > MPCP 6
            ))
        }
    }

    @Test
    fun `Cyberdeck rejects total persona ratings exceeding MPCP times 3`() {
        assertFailsWith<IllegalArgumentException> {
            deck(mcpRating = 6, personaPrograms = listOf(
                PersonaProgram(PersonaAttributeType.BOD, 6),
                PersonaProgram(PersonaAttributeType.EVASION, 6),
                PersonaProgram(PersonaAttributeType.MASKING, 6),
                PersonaProgram(PersonaAttributeType.SENSORS, 6)  // sum = 24 > 6×3=18
            ))
        }
    }

    @Test
    fun `Cyberdeck rejects active utilities exceeding active memory`() {
        // ANALYZE rating 10 → mpSize = 100 × 3 = 300; active memory = 100
        val analyze = Utility(UtilityType.ANALYZE, rating = 10)
        assertFailsWith<IllegalArgumentException> {
            Cyberdeck(
                name = "Tight", mcpRating = 10, activeMemoryMp = 100, storageMemoryMp = 2000,
                ioSpeedMpPerTurn = 50, costNuyen = 1000,
                activeUtilities = listOf(analyze), storedUtilities = listOf(analyze)
            )
        }
    }

    @Test
    fun `Cyberdeck accepts active utilities within active memory`() {
        val browse = Utility(UtilityType.BROWSE, rating = 3)  // mpSize = 9 × 1 = 9
        val d = deck(activeUtilities = listOf(browse), storedUtilities = listOf(browse))
        assertEquals(1, d.activeUtilities.size)
    }

    @Test
    fun `Cyberdeck carries accessories`() {
        val hitcher = Accessory.HitcherJack(HitcherJackType.DATAJACK_FEED)
        val d = deck().copy(accessories = listOf(hitcher))
        assertEquals(1, d.accessories.size)
        assertIs<Accessory.HitcherJack>(d.accessories[0])
    }

    // ── Persona ────────────────────────────────────────────────────────────────

    @Test
    fun `Persona defaults to no current node when created off-line`() {
        val p = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4)
        assertNull(p.currentNode)
    }

    @Test
    fun `Persona carries current node location`() {
        val accessNode = Node(SubsystemType.ACCESS, "Main door")
        val p = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4, currentNode = accessNode)
        assertEquals(SubsystemType.ACCESS, p.currentNode?.subsystemType)
    }

    @Test
    fun `Persona default status is LEGITIMATE`() {
        val p = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4)
        assertEquals(PersonaStatus.LEGITIMATE, p.status)
    }

    // ── Cyberterminal ──────────────────────────────────────────────────────────

    @Test
    fun `Cyberterminal MPCP cannot exceed 4`() {
        assertFailsWith<IllegalArgumentException> {
            Cyberterminal(
                name = "Tortoise", mcpRating = 5,
                activeMemoryMp = 200, storageMemoryMp = 500, ioSpeedMpPerTurn = 100,
                costNuyen = 500
            )
        }
    }

    @Test
    fun `Cyberterminal immuneToDumpShock is true`() {
        val terminal = Cyberterminal(
            name = "Tortoise", mcpRating = 4,
            activeMemoryMp = 200, storageMemoryMp = 500, ioSpeedMpPerTurn = 100,
            costNuyen = 400
        )
        assertEquals(true, terminal.immuneToDumpShock)
    }

    // ── detectionFactor ───────────────────────────────────────────────────────────

    private fun secRating(v: Int = 4) = SecurityRating(SecurityCode.GREEN, v)
    private fun subsystems(v: Int = 4) = SubsystemRatings(v, v, v, v, v)
    private fun ltg() = LTG("Seattle", RTG("UCAS", "NA", secRating(), subsystems()), secRating(), subsystems())

    private fun deckerWithMasking(masking: Int, sleaze: Utility? = null): Decker {
        val programs = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 4),
            PersonaProgram(PersonaAttributeType.EVASION, 4),
            PersonaProgram(PersonaAttributeType.MASKING, masking),
            PersonaProgram(PersonaAttributeType.SENSORS, 4)
        )
        val active = if (sleaze != null) listOf(sleaze) else emptyList()
        return Decker(
            name = "Ghost", intelligence = 6, body = 4, willpower = 5, reaction = 5, computerSkill = 6,
            cyberdeck = deck(personaPrograms = programs, activeUtilities = active),
            persona = Persona(bod = 4, evasion = 4, masking = masking, sensor = 4),
            currentLocation = MatrixLocation.OnLTG(ltg())
        )
    }

    @Test
    fun `detectionFactor without Sleaze uses ceil(masking div 2)`() {
        assertEquals(3, deckerWithMasking(6).detectionFactor)
        assertEquals(3, deckerWithMasking(5).detectionFactor)
        assertEquals(2, deckerWithMasking(4).detectionFactor)
    }

    @Test
    fun `detectionFactor with active Sleaze uses ceil((masking + sleaze) div 2)`() {
        val sleaze = Utility(UtilityType.SLEAZE, rating = 4)
        val d = deckerWithMasking(6, sleaze = sleaze)
        // ceil((6 + 4) / 2) = 5
        assertEquals(5, d.detectionFactor)
    }

    @Test
    fun `effectiveDetectionFactor is reduced by suppressed IC count`() {
        val sleaze = Utility(UtilityType.SLEAZE, rating = 4)
        val ic = com.shadowrun.matrix.ic.Probe(rating = 3)
        val d = deckerWithMasking(6, sleaze = sleaze).copy(suppressedIc = listOf(IcSuppressionState(ic, ic.rating)))
        // base DF = 5, penalty = 1 → effective DF = 4
        assertEquals(4, d.effectiveDetectionFactor)
    }

    // ── withUpdatedTally ──────────────────────────────────────────────────────────

    @Test
    fun `withUpdatedTally on OnLTG accumulates tally`() {
        val l = ltg().copy(securityTally = 2)
        val d = deckerWithMasking(6).copy(currentLocation = MatrixLocation.OnLTG(l))
        val updated = d.withUpdatedTally(3)
        val newLtg = (updated.currentLocation as MatrixLocation.OnLTG).ltg
        assertEquals(5, newLtg.securityTally)
    }

    @Test
    fun `withUpdatedTally on OnPLTG accumulates tally`() {
        val l = ltg()
        val p = PLTG("Corp-PLTG", "Renraku", l, secRating(), subsystems()).copy(securityTally = 1)
        val d = deckerWithMasking(6).copy(currentLocation = MatrixLocation.OnPLTG(p))
        val updated = d.withUpdatedTally(4)
        val newPltg = (updated.currentLocation as MatrixLocation.OnPLTG).pltg
        assertEquals(5, newPltg.securityTally)
    }

    @Test
    fun `withUpdatedTally with 0 successes returns same instance`() {
        val l = ltg().copy(securityTally = 2)
        val d = deckerWithMasking(6).copy(currentLocation = MatrixLocation.OnLTG(l))
        val updated = d.withUpdatedTally(0)
        assertTrue(updated === d)
    }
}

