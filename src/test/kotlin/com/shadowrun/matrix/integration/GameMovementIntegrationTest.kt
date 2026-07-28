package com.shadowrun.matrix.integration

import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.MatrixLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameMovementIntegrationTest : IntegrationTestBase() {

    @Test
    fun `jack into UCAS-SEA and logon to Renraku Public Relations`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
        }

        assertEquals(2, icon.stepResults.size)
        assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
        assertIs<MatrixLocation.OnHost>(icon.context.deckers.first().currentLocation)
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

        assertEquals(6, icon.stepResults.size)
        assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
        assertNull(icon.context.deckers.first().persona,         "Persona should be null after logoff")
        assertNull(icon.context.deckers.first().currentLocation, "Location should be null after logoff")
    }
}
