package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test
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

    /**
     * The decker logs on to Mitsuhama Pagoda successfully, then fails an analyzeSubsystem on FILES.
     * The host rolls all successes (6 dice, value 3 ≥ DF 3), pushing the tally to 6 — crossing the
     * threshold of 5 that deploys the Probe IC.
     */
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
}

