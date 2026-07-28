package com.shadowrun.matrix.integration

import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.network.MatrixLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameMovementIntegrationTest : IntegrationTestBase() {

    @Test
    fun `integration - jack in to LTG, traverse RTGs, enter host, logoff via game layer`() {
        val aztlan     = matrix.getRTG("AZT")!!
        val mexicoCity = matrix.getLTG("AZT", "AZT-MEX")!!
        val seattle    = matrix.getLTG("UCAS", "UCAS-SEA")!!
        val targetHost = matrix.getHost("AZT", "AZT-MEX", "Aztlan Ministry of Information")!!
        // Wire UCAS → Aztlan so the decker can hop between RTGs in step 3
        val ucas       = matrix.getRTG("UCAS")!!.copy(connectedRtgs = listOf(aztlan))

        val decker  = DeckerMock.build(GridMock.getDefaultJackpoint())
        val context = buildDefaultContext(decker)

        val steps: List<StepAction> = listOf(
            {                                                                    // step 1 – jack in to LTG
                val r = currentDecker().jackInToLtg(seattle, roller)
                assertIs<LogonResult.Success>(r, "Step 1 – jack in to LTG failed")
                updateCurrentDecker(r.decker)
            },
            {                                                                    // step 2 – move to UCAS RTG
                val currentLtg = (currentDecker().currentLocation as MatrixLocation.OnLTG).ltg
                val r = currentDecker().logonToRtg(currentLtg.parentRtg, roller)
                assertIs<LogonResult.Success>(r, "Step 2 – move to RTG failed")
                updateCurrentDecker(r.decker.copy(currentLocation = MatrixLocation.OnRTG(ucas)))
            },
            {                                                                    // step 3 – hop to Aztlan RTG
                val r = currentDecker().logonToRtg(aztlan, roller)
                assertIs<LogonResult.Success>(r, "Step 3 – move to Aztlan RTG failed")
                updateCurrentDecker(r.decker)
            },
            {                                                                    // step 4 – enter Mexico City LTG
                val aztlanWithLtgs = (currentDecker().currentLocation as MatrixLocation.OnRTG)
                    .rtg.copy(ltgs = aztlan.ltgs)
                updateCurrentDecker(currentDecker().copy(currentLocation = MatrixLocation.OnRTG(aztlanWithLtgs)))
                val r = currentDecker().logonToLtg(mexicoCity, roller)
                assertIs<LogonResult.Success>(r, "Step 4 – enter Mexico City LTG failed")
                updateCurrentDecker(r.decker)
            },
            {                                                                    // step 5 – logon to host
                val mexCityWithHosts = (currentDecker().currentLocation as MatrixLocation.OnLTG)
                    .ltg.copy(hosts = mexicoCity.hosts)
                updateCurrentDecker(currentDecker().copy(currentLocation = MatrixLocation.OnLTG(mexCityWithHosts)))
                val r = currentDecker().logonToHost(targetHost, roller)
                assertIs<LogonResult.Success>(r, "Step 5 – logon to host failed")
                updateCurrentDecker(r.decker)
            },
            {                                                                    // step 6 – graceful logoff
                val r = currentDecker().gracefulLogoff(roller)
                assertIs<LogoffResult.GracefulSuccess>(r, "Step 6 – logoff failed")
                updateCurrentDecker(r.decker)
            }
        )

        val icon = ScriptedDeckerIcon(decker, context, steps)
        runActions(icon, context, count = 6, diceRoller = winRoller())

        assertEquals(6, icon.stepResults.size)
        assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
        assertNull(context.deckers.first().persona,         "Persona should be null after logoff")
        assertNull(context.deckers.first().currentLocation, "Location should be null after logoff")
    }
}
