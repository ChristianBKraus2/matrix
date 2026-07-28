package com.shadowrun.matrix.integration

import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.MatrixLocation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class GameMovementIntegrationTest : IntegrationTestBase() {

    @Test
    fun `jack into UCAS-SEA and logon to Renraku Public Relations`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
        }

        assertIs<MatrixLocation.OnHost>(icon.currentDecker().currentLocation)
    }

    @Test
    fun `jack into UCAS-SEA and fail to logon to Renraku Public Relations`() {
        val icon = scenario(diceRoller = winThenFailRoller()) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations", succeed = false)
        }
        assertIs<MatrixLocation.OnLTG>(icon.currentDecker().currentLocation)
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

        assertNull(icon.currentDecker().persona,         "Persona should be null after logoff")
        assertNull(icon.currentDecker().currentLocation, "Location should be null after logoff")
    }
}
