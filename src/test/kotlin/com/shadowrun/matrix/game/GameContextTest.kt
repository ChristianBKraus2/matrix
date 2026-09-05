package com.shadowrun.matrix.game

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.Persona
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.SecuritySheaf
import com.shadowrun.matrix.network.TriggerStep
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameContextTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private fun ratings(v: Int = 5) = SubsystemRatings(v, v, v, v, v)

    private fun host(
        securityTally: Int = 0,
        alertStatus: AlertStatus = AlertStatus.NO_ALERT,
        sheaf: SecuritySheaf = SecuritySheaf()
    ) = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.GREEN, 4),
        subsystemRatings = ratings(),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS,
        securityTally = securityTally,
        alertStatus = alertStatus,
        securitySheaf = sheaf
    )

    private fun ltg() = LTG(
        name = "TestLTG",
        parentRtg = RTG("UCAS", "North America", SecurityRating(SecurityCode.GREEN, 4), ratings()),
        securityRating = SecurityRating(SecurityCode.GREEN, 4),
        subsystemRatings = ratings()
    )

    private fun deck() = Cyberdeck(
        name = "TestDeck",
        mcpRating = 8,
        activeMemoryMp = 2000,
        storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 500,
        costNuyen = 0,
        personaPrograms = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 6),
            PersonaProgram(PersonaAttributeType.EVASION, 6),
            PersonaProgram(PersonaAttributeType.MASKING, 6),
            PersonaProgram(PersonaAttributeType.SENSORS, 6)
        )
    )

    private fun deckerOnHost(h: Host) = Decker(
        name = "Hacker",
        intelligence = 6, body = 4, willpower = 5, reaction = 5, computerSkill = 6,
        cyberdeck = deck(),
        persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6, status = PersonaStatus.INTRUDING),
        currentLocation = MatrixLocation.OnHost(h)
    )

    private fun deckerOnLtg() = Decker(
        name = "Hacker",
        intelligence = 6, body = 4, willpower = 5, reaction = 5, computerSkill = 6,
        cyberdeck = deck(),
        persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6),
        currentLocation = MatrixLocation.OnLTG(ltg())
    )

    private fun context(
        h: Host = host(),
        deckers: List<Decker> = emptyList()
    ) = GameContext(
        host = h,
        securityCode = SecurityCode.GREEN,
        deckers = deckers
    )

    // ── updateHost ────────────────────────────────────────────────────────────────

    @Test
    fun `updateHost replaces context host`() {
        val ctx = context()
        val newHost = host(securityTally = 3)
        ctx.updateHost(newHost)
        assertSame(newHost, ctx.host)
    }

    @Test
    fun `updateHost repairs decker currentLocation`() {
        val h = host()
        val decker = deckerOnHost(h)
        val ctx = context(h, listOf(decker))
        val newHost = host(securityTally = 3)
        ctx.updateHost(newHost)
        val loc = ctx.deckers.first().currentLocation as MatrixLocation.OnHost
        assertEquals(newHost, loc.host)
    }

    @Test
    fun `updateHost leaves decker on LTG untouched`() {
        val decker = deckerOnLtg()
        val originalLocation = decker.currentLocation
        val ctx = context(deckers = listOf(decker))
        ctx.updateHost(host(securityTally = 5))
        assertEquals(originalLocation, ctx.deckers.first().currentLocation)
    }

    // ── checkTriggers ─────────────────────────────────────────────────────────────

    @Test
    fun `checkTriggers spawns IC when threshold crossed`() {
        val probe = Probe(rating = 5)
        val sheaf = SecuritySheaf(listOf(TriggerStep(tallyThreshold = 3, description = "Probe", activatedIc = listOf(probe))))
        val ctx = context(host(sheaf = sheaf))
        ctx.checkTriggers(0, 3)
        assertTrue(ctx.activeIc.contains(probe))
    }

    @Test
    fun `checkTriggers fires all steps crossed in one increment`() {
        val probe = Probe(rating = 3)
        val killer = Killer(rating = 5)
        val sheaf = SecuritySheaf(listOf(
            TriggerStep(tallyThreshold = 3, description = "Probe", activatedIc = listOf(probe)),
            TriggerStep(tallyThreshold = 5, description = "Killer", activatedIc = listOf(killer))
        ))
        val ctx = context(host(sheaf = sheaf))
        ctx.checkTriggers(0, 5)
        assertTrue(ctx.activeIc.contains(probe))
        assertTrue(ctx.activeIc.contains(killer))
    }

    @Test
    fun `checkTriggers does not fire steps already passed`() {
        val probe = Probe(rating = 3)
        val sheaf = SecuritySheaf(listOf(TriggerStep(tallyThreshold = 3, description = "Probe", activatedIc = listOf(probe))))
        val ctx = context(host(sheaf = sheaf))
        ctx.checkTriggers(3, 5)
        assertTrue(ctx.activeIc.isEmpty())
    }

    @Test
    fun `checkTriggers applies passive alert transition`() {
        val sheaf = SecuritySheaf(listOf(
            TriggerStep(tallyThreshold = 4, description = "Alert", alertTransition = AlertStatus.PASSIVE_ALERT)
        ))
        val ctx = context(host(sheaf = sheaf))
        val baseAccess = ctx.host.subsystemRatings.access
        ctx.checkTriggers(0, 4)
        assertEquals(AlertStatus.PASSIVE_ALERT, ctx.host.alertStatus)
        assertEquals(baseAccess + 2, ctx.host.subsystemRatings.access)
    }

    @Test
    fun `checkTriggers does not regress alert`() {
        val sheaf = SecuritySheaf(listOf(
            TriggerStep(tallyThreshold = 5, description = "Alert again", alertTransition = AlertStatus.PASSIVE_ALERT)
        ))
        val alreadyAlerter = host(alertStatus = AlertStatus.PASSIVE_ALERT, sheaf = sheaf)
        val ctx = context(alreadyAlerter)
        val ratingsBefore = ctx.host.subsystemRatings.access
        ctx.checkTriggers(3, 5)
        assertEquals(ratingsBefore, ctx.host.subsystemRatings.access)
    }

    // ── applyDeckerOperationResult ────────────────────────────────────────────────

    @Test
    fun `applyDeckerOperationResult calls checkTriggers when tally increases`() {
        val probe = Probe(rating = 3)
        val sheaf = SecuritySheaf(listOf(TriggerStep(tallyThreshold = 2, description = "Probe", activatedIc = listOf(probe))))
        val h = host(securityTally = 0, sheaf = sheaf)
        val decker = deckerOnHost(h)
        val updatedDecker = decker.copy(currentLocation = MatrixLocation.OnHost(h.copy(securityTally = 2)))
        val ctx = context(h, listOf(decker))
        ctx.applyDeckerOperationResult(decker, updatedDecker)
        assertTrue(ctx.activeIc.contains(probe))
    }

    @Test
    fun `applyDeckerOperationResult skips checkTriggers when tally unchanged`() {
        val probe = Probe(rating = 3)
        val sheaf = SecuritySheaf(listOf(TriggerStep(tallyThreshold = 2, description = "Probe", activatedIc = listOf(probe))))
        val h = host(securityTally = 0, sheaf = sheaf)
        val decker = deckerOnHost(h)
        val ctx = context(h, listOf(decker))
        ctx.applyDeckerOperationResult(decker, decker)
        assertTrue(ctx.activeIc.isEmpty())
    }

    @Test
    fun `applyDeckerOperationResult updates context host tally`() {
        val h = host(securityTally = 0)
        val decker = deckerOnHost(h)
        val updatedDecker = decker.copy(currentLocation = MatrixLocation.OnHost(h.copy(securityTally = 4)))
        val ctx = context(h, listOf(decker))
        ctx.applyDeckerOperationResult(decker, updatedDecker)
        assertEquals(4, ctx.host.securityTally)
    }

    // ── resetToSingleDecker ───────────────────────────────────────────────────────

    @Test
    fun `resetToSingleDecker replaces all deckers with a single new decker`() {
        val d1 = deckerOnHost(host())
        val d2 = deckerOnHost(host()).copy(name = "Second")
        val ctx = context(deckers = listOf(d1, d2))
        val newDecker = deckerOnHost(host()).copy(name = "Rebuilt")
        ctx.resetToSingleDecker(newDecker)
        assertEquals(1, ctx.deckers.size)
        assertEquals("Rebuilt", ctx.deckers[0].name)
    }

    // ── deckerByName ──────────────────────────────────────────────────────────────

    @Test
    fun `deckerByName returns decker with matching name`() {
        val decker = deckerOnHost(host())
        val ctx = context(deckers = listOf(decker))
        assertEquals(decker, ctx.deckerByName("Hacker"))
    }

    @Test
    fun `deckerByName returns null when no decker matches`() {
        val decker = deckerOnHost(host())
        val ctx = context(deckers = listOf(decker))
        assertNull(ctx.deckerByName("Ghost"))
    }

    // ── addToSecurityTally ────────────────────────────────────────────────────────

    @Test
    fun `addToSecurityTally increases host tally and fires triggers`() {
        val probe = Probe(rating = 3)
        val sheaf = SecuritySheaf(listOf(TriggerStep(tallyThreshold = 5, description = "Probe", activatedIc = listOf(probe))))
        val ctx = context(host(sheaf = sheaf))
        ctx.addToSecurityTally(5)
        assertEquals(5, ctx.host.securityTally)
        assertTrue(ctx.activeIc.contains(probe))
    }

    @Test
    fun `addToSecurityTally rejects negative points`() {
        val ctx = context()
        assertFailsWith<IllegalArgumentException> { ctx.addToSecurityTally(-1) }
    }

    @Test
    fun `addToSecurityTally with 0 is a no-op`() {
        val ctx = context(host(securityTally = 3))
        ctx.addToSecurityTally(0)
        assertEquals(3, ctx.host.securityTally)
        assertTrue(ctx.activeIc.isEmpty())
    }

    // ── runSpawnDetection ─────────────────────────────────────────────────────────

    private fun fixedRoller(face: Int) = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = face.coerceIn(from, until - 1)
    })

    @Test
    fun `runSpawnDetection adds triggered IC to detectedIcons when sensor test passes`() {
        val probe = Probe(rating = 3)
        val decker = deckerOnHost(host())
        val ctx = context(host(), deckers = listOf(decker))
        ctx.addIc(probe)
        ctx.runSpawnDetection(fixedRoller(5))
        assertTrue(ctx.deckers.first().detectedIcNames.contains(probe.name))
    }

    @Test
    fun `runSpawnDetection does not add IC to detectedIcons when sensor test fails`() {
        val probe = Probe(rating = 3)
        val decker = deckerOnHost(host())
        val ctx = context(host(), deckers = listOf(decker))
        ctx.addIc(probe)
        ctx.runSpawnDetection(fixedRoller(1))
        assertTrue(ctx.deckers.first().detectedIcNames.isEmpty())
    }

    @Test
    fun `runSpawnDetection skips deckers not on a host`() {
        val probe = Probe(rating = 3)
        val decker = deckerOnLtg()
        val ctx = context(host(), deckers = listOf(decker))
        ctx.addIc(probe)
        ctx.runSpawnDetection(fixedRoller(5))
        assertTrue(ctx.deckers.first().detectedIcNames.isEmpty())
    }

    @Test
    fun `runSpawnDetection does not re-detect already detected IC`() {
        val probe = Probe(rating = 3)
        val icon = com.shadowrun.matrix.operations.Icon.IcIcon(probe)
        val decker = deckerOnHost(host()).copy(detectedIcons = setOf(icon))
        val ctx = context(host(), deckers = listOf(decker))
        ctx.addIc(probe)
        ctx.runSpawnDetection(fixedRoller(1))
        // icon was already in detectedIcNames before runSpawnDetection — should still be there
        assertTrue(ctx.deckers.first().detectedIcNames.contains(probe.name))
    }
}
