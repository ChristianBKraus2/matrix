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
import kotlin.test.Test
import kotlin.test.assertEquals
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
        deckers = deckers.toMutableList(),
        activeIc = mutableListOf()
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
}
