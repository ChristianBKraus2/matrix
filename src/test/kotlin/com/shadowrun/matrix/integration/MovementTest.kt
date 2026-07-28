package com.shadowrun.matrix.integration

import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test

class MovementTest : IntegrationTestBase() {

    @Test
    fun `jack into UCAS-SEA and logon to Renraku Public Relations`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
        }
        icon.assertOnHost("Renraku Public Relations")
    }

    @Test
    fun `low end decker - jack into UCAS-SEA and logon to Renraku Public Relations`() {
        val icon = scenario(deckerTier = DeckerMock.LOW_END) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
        }
        icon.assertOnHost("Renraku Public Relations")
    }

    @Test
    fun `jack into UCAS-SEA and fail to logon to Renraku Public Relations`() {
        val icon = scenario(diceRoller = winThenFailRoller()) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations", succeed = false)
        }
        icon.assertOnLtg("UCAS-SEA")
    }

    @Test
    fun `integration - jack in to LTG, traverse RTGs, enter host, logoff via game layer`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg()
            logonToRtg("AZT")
            logonToLtg("AZT/AZT-MEX")
            logonToHost("AZT/AZT-MEX/Aztlan Ministry of Information")
            gracefulLogoff()
        }
        icon.assertLoggedOff()
    }
}
