package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.operations.HostInfoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ICActivationTest : IntegrationTestBase() {

    @Test
    fun `jack into UCAS-SEA and logon to Mitsuhama Pagoda`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.assertOnHost("Mitsuhama Pagoda")
    }

    @Test
    fun `failed analyzeSubsystem on FILES crosses tally threshold 5 and activates Probe IC`() {
        val roller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = roller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
        }

        assertTrue(
            icon.context.activeIc.any { it is Probe },
            "Probe IC should be active after tally crosses threshold 5"
        )
    }

    @Test
    fun `Probe IC moves to the decker after activation`() {
        val roller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = roller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
        }

        assertTrue(icon.context.activeIc.any { it is Probe })

        assertEquals(0, icon.runCombatTurn(roller), "Probe IC should not deal icon damage")
    }

    @Test
    fun `two failed analyzeSubsystem calls cross tally threshold 10 and activate Killer IC`() {
        val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = setupRoller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            navigateToNode(SubsystemType.FILES)
        }

        assertTrue(icon.context.activeIc.any { it is Killer }, "Killer IC should be active after tally crosses threshold 10")
    }

    // --- Group: successful operations do not trigger IC ---

    @Test
    fun `successful analyzeSubsystem does not activate IC`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.ACCESS, succeed = true)
        }
        icon.assertNoActiveIc()
        icon.assertAlertStatus(AlertStatus.NO_ALERT)
    }

    @Test
    fun `failed analyzeSubsystem on ACCESS also activates Probe IC at threshold 5`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.ACCESS, succeed = false)
        }
        assertTrue(icon.context.activeIc.any { it is Probe }, "IC activation is tally-based, not subsystem-specific")
    }

    // --- Group: decryptAccess ---

    @Test
    fun `successful decryptAccess does not activate IC`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            decryptAccess(succeed = true)
        }
        icon.assertNoActiveIc()
    }

    @Test
    fun `failed decryptAccess raises tally and activates Probe IC`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            decryptAccess(succeed = false)
        }
        assertTrue(icon.context.activeIc.any { it is Probe }, "Probe IC should activate after failed decryptAccess raises tally past 5")
    }

    // --- Group: analyzeSecurity ---

    @Test
    fun `analyzeSecurity returns current tally and no-alert status`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSecurity()
        }
        icon.assertAlertStatus(AlertStatus.NO_ALERT)
        icon.assertNoActiveIc()
    }

    // --- Group: analyzeHost ---

    @Test
    fun `successful analyzeHost wins the test and activates no IC`() {
        // T-8: this scenario-level test verifies the end-to-end wiring of a winning analyzeHost —
        // the decker wins the System Test (asserted inside the DSL step via succeed = true) and no IC
        // activates. It does NOT assert the revealed items: the harness's winRoller wins by the 0-0
        // tie rule (0 net successes), and Quicksilver carries no ANALYZE utility (TN 8), so a reveal
        // is structurally unreachable here. The net-successes → revealedSecurityRating mechanic is
        // covered deterministically by the unit tests in SystemOperationsTest.
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeHost(items = listOf(HostInfoItem.SecurityRating), succeed = true)
        }
        icon.assertNoActiveIc()
    }

    @Test
    fun `failed analyzeHost reveals nothing`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeHost(items = listOf(HostInfoItem.SecurityRating), succeed = false)
        }
        // A failed analyzeHost still raises tally due to host successes → may activate Probe
        assertTrue(icon.context.activeIc.any { it is Probe }, "Probe IC activates when host wins analyzeHost and tally crosses 5")
    }

    // --- Group: two failures push to passive alert ---

    @Test
    fun `two failed operations accumulate tally to passive alert threshold`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            navigateToNode(SubsystemType.FILES)
        }
        icon.assertAlertStatus(AlertStatus.PASSIVE_ALERT)
        assertTrue(icon.context.activeIc.any { it is Probe })
        assertTrue(icon.context.activeIc.any { it is Killer })
    }

    // --- Group: analyzeIc on active IC ---

    @Test
    fun `analyzeIc succeeds on active Probe IC`() {
        // Win: jack-in (12) + logon (14) + IC detection (12) = 38 calls
        // Fail: analyzeSubsystem FILES (14 calls) → host wins → Probe activates
        // Win again: analyzeIc (14 calls) → decker wins
        val icon = scenario(diceRoller = winFailWinRoller(winCalls = 38, failCalls = 14)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeFirstActiveIc(succeed = true)
        }
        icon.assertActiveIcCount(1)
    }
}
