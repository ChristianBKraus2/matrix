package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory
import com.shadowrun.matrix.common.AccessoryType
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `Cyberdeck detectionFactor without sleaze rounds up`() {
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
        val hitcher = Accessory(AccessoryType.HITCHER_JACK, "Allows passive observers")
        val d = deck().copy(accessories = listOf(hitcher))
        assertEquals(1, d.accessories.size)
        assertEquals(AccessoryType.HITCHER_JACK, d.accessories[0].type)
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
}

