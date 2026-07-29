package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
