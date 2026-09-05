package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.*
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.HostMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.applyAlertTransition
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertAndTallyTest : IntegrationTestBase() {

    // ── Passive Alert +2 subsystem rating effect ──────────────────────────────

    @Test
    fun `passive alert permanently raises all subsystem ratings by 2`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host
        val before = host.subsystemRatings

        val updatedHost = applyAlertTransition(host, AlertStatus.PASSIVE_ALERT)

        assertEquals(before.access  + 2, updatedHost.subsystemRatings.access,  "ACCESS should increase by 2 on Passive Alert")
        assertEquals(before.control + 2, updatedHost.subsystemRatings.control, "CONTROL should increase by 2 on Passive Alert")
        assertEquals(before.index   + 2, updatedHost.subsystemRatings.index,   "INDEX should increase by 2 on Passive Alert")
        assertEquals(before.files   + 2, updatedHost.subsystemRatings.files,   "FILES should increase by 2 on Passive Alert")
        assertEquals(before.slave   + 2, updatedHost.subsystemRatings.slave,   "SLAVE should increase by 2 on Passive Alert")
        assertEquals(AlertStatus.PASSIVE_ALERT, updatedHost.alertStatus)
    }

    @Test
    fun `active alert does not change subsystem ratings, only flips alert status`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host
        val before = host.subsystemRatings

        val updatedHost = applyAlertTransition(host, AlertStatus.ACTIVE_ALERT)

        assertEquals(before.access,  updatedHost.subsystemRatings.access,  "Active Alert should not change ACCESS")
        assertEquals(before.control, updatedHost.subsystemRatings.control, "Active Alert should not change CONTROL")
        assertEquals(before.index,   updatedHost.subsystemRatings.index,   "Active Alert should not change INDEX")
        assertEquals(before.files,   updatedHost.subsystemRatings.files,   "Active Alert should not change FILES")
        assertEquals(before.slave,   updatedHost.subsystemRatings.slave,   "Active Alert should not change SLAVE")
        assertEquals(AlertStatus.ACTIVE_ALERT, updatedHost.alertStatus)
    }

    @Test
    fun `failed operations push tally to passive alert threshold and raise subsystem ratings`() {
        // Two failed analyzeSubsystem calls cross threshold 10 → PASSIVE_ALERT.
        // After alert transition, all subsystem ratings on the context host should be +2 from base.
        val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = setupRoller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeSubsystem(SubsystemType.ACCESS, succeed = false)
        }

        icon.assertAlertStatus(AlertStatus.PASSIVE_ALERT)

        // The host on the context should have had its subsystem ratings raised via applyAlertTransition
        val host = icon.context.host
        assertTrue(host.subsystemRatings.access > 3, "ACCESS rating should be above base 3 after Passive Alert")
        assertTrue(host.subsystemRatings.files  > 3, "FILES rating should be above base 3 after Passive Alert")
    }

    // ── Security tally reset on RTG switch ────────────────────────────────────

    @Test
    fun `security tally resets when switching to a different RTG`() {
        // Jack into UCAS-SEA LTG → move to parent UCAS RTG (accumulating some tally) → hop to AZT RTG.
        // Tally on the AZT RTG should only reflect the cross-RTG logon successes (host wins = 0 on winRoller).
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg()
            logonToRtg("AZT")
        }

        val loc = icon.currentDecker().currentLocation as MatrixLocation.OnRTG
        // winRoller: host rolls 0 successes so host tally delta = 0; RTG tally should be 0.
        assertEquals(0, loc.rtg.securityTally, "Tally should reset to 0 on a fresh RTG (host scored 0 successes)")
    }

    @Test
    fun `security tally on source RTG is independent from tally on destination RTG`() {
        // Accumulate tally on UCAS RTG via a failed logon, then hop to AZT.
        // The UCAS tally should not carry over to the AZT RTG.
        // Step 1: jack in, move to UCAS RTG (win).
        // Step 2 onward: failRoller on the cross-RTG hop means the host wins → tally on AZT = hostSuccesses only.
        val icon = scenario(diceRoller = winRoller()) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg()
        }

        // Now cross to AZT with failRoller so host builds its own tally
        val ucasRtg = (icon.currentDecker().currentLocation as MatrixLocation.OnRTG).rtg
        val aztRtg = ucasRtg.connectedRtgs.first { it.name == "AZT" }
        val result = icon.currentDecker().logonToRtg(aztRtg, failRoller())

        val ucasTally = ucasRtg.securityTally
        // For Failure: use attemptedLocation (the AZT RTG with updated tally), not decker.currentLocation
        // (which would be the UCAS RTG the decker stayed on). For Success: decker moved to AZT.
        val aztTally = when (result) {
            is com.shadowrun.matrix.decker.LogonResult.Success -> (result.decker.currentLocation as? MatrixLocation.OnRTG)?.rtg?.securityTally ?: 0
            is com.shadowrun.matrix.decker.LogonResult.Failure -> (result.attemptedLocation as? MatrixLocation.OnRTG)?.rtg?.securityTally ?: 0
        }

        // ucasTally = 0 because winRoller gave the host 0 successes on all UCAS steps.
        // aztTally > 0 because failRoller gives host successes: face=3 >= DF=3, so all securityValue dice hit.
        assertEquals(0, ucasTally, "UCAS RTG tally should be 0 — winRoller gave host 0 successes")
        assertTrue(aztTally > 0, "AZT RTG tally should reflect host successes from failRoller (face=3 >= DF=3)")
    }

    @Test
    fun `security tally accumulates across multiple operations on same host`() {
        // Each failed operation adds host successes to the tally. Verify that the tally is cumulative.
        val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = setupRoller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
        }

        val tally = (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host.securityTally
        assertTrue(tally > 0, "Tally should be > 0 after a failed operation")
    }

    // ── RTG tally multi-decker sync ──────────────────────────────────────────────

    @Test
    fun `updateRtgTally propagates new tally to all deckers on LTGs under that RTG`() {
        val r = RTG("UCAS", "NA", SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4))
        val ltgA = LTG("Seattle", r, SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4))
        val ltgB = LTG("Portland", r, SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4))
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val jpA = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = ltgA)
        val jpB = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = ltgB)
        val deckerA = DeckerMock.build(jpA).copy(currentLocation = MatrixLocation.OnLTG(ltgA), persona = persona)
        val deckerB = DeckerMock.build(jpB, DeckerMock.STANDARD).copy(currentLocation = MatrixLocation.OnLTG(ltgB), persona = persona)
        val context = GameContext(
            host = HostMock.build("Placeholder"),
            securityCode = SecurityCode.GREEN,
            deckers = listOf(deckerA, deckerB)
        )

        context.updateRtgTally("UCAS", 7)

        val updatedA = context.deckerByName(deckerA.name)!!
        val updatedB = context.deckerByName(deckerB.name)!!
        val locA = updatedA.currentLocation as MatrixLocation.OnLTG
        val locB = updatedB.currentLocation as MatrixLocation.OnLTG
        assertEquals(7, locA.ltg.securityTally, "Decker-A's LTG tally should be updated to 7")
        assertEquals(7, locA.ltg.parentRtg.securityTally, "Decker-A's embedded parentRtg tally should be updated to 7")
        assertEquals(7, locB.ltg.securityTally, "Decker-B's LTG tally should be updated to 7 (shared RTG tally, M-09)")
    }

    @Test
    fun `updateRtgTally does not propagate to deckers on a PLTG`() {
        val r = RTG("UCAS", "NA", SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4))
        val ltg = LTG("Seattle", r, SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4))
        val pltg = PLTG("Corp", "MegaCorp", ltg, SecurityRating(SecurityCode.GREEN, 6), SubsystemRatings(4, 4, 4, 4, 4), securityTally = 3)
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val jp = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = ltg)
        val deckerOnPltg = DeckerMock.build(jp).copy(currentLocation = MatrixLocation.OnPLTG(pltg), persona = persona)
        val context = GameContext(
            host = HostMock.build("Placeholder"),
            securityCode = SecurityCode.GREEN,
            deckers = listOf(deckerOnPltg)
        )

        context.updateRtgTally("UCAS", 9)

        val updated = context.deckerByName(deckerOnPltg.name)!!
        assertEquals(3, (updated.currentLocation as MatrixLocation.OnPLTG).pltg.securityTally,
            "PLTG tally must not be affected by RTG tally update — PLTG tally is independent after logon (SR3 p.211)")
    }
}
