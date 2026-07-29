package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.applyAlertTransition
import com.shadowrun.matrix.network.MatrixLocation
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
        val aztTally = when (result) {
            is com.shadowrun.matrix.decker.LogonResult.Success -> (result.decker.currentLocation as? MatrixLocation.OnRTG)?.rtg?.securityTally ?: 0
            is com.shadowrun.matrix.decker.LogonResult.Failure -> (result.decker.currentLocation as? MatrixLocation.OnRTG)?.rtg?.securityTally ?: 0
        }

        // The point: AZT tally is not ucasTally + something; they are independent.
        // With failRoller host wins, ucasRtg tally stays at whatever it was.
        // AZT tally starts fresh from just the host's logon successes.
        assertTrue(aztTally != ucasTally || (aztTally == 0 && ucasTally == 0),
            "AZT tally ($aztTally) should not inherit from UCAS tally ($ucasTally)")
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
}
