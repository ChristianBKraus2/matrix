package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.decker.*
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlaveOperationsTest : IntegrationTestBase() {

    private fun host(icon: ScriptedDeckerIcon) =
        (icon.currentDecker().currentLocation as MatrixLocation.OnHost).host

    // ── locateSlave ───────────────────────────────────────────────────────────

    @Test
    fun `locateSlave accumulates successes and locates Security Camera Network`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        assertTrue(host.remoteDevices.any { it.name.contains("Security Camera", ignoreCase = true) },
            "Mitsuhama Pagoda must have a Security Camera Network device for this test")

        // BROWSE rating 4, VERY_SPECIFIC (-2): TN = max(2, index(8) - 2 - 4) = 2.
        // hitRoller face=5 ≥ 2 → 8 successes → ≥ 3 threshold → Located.
        val browse = com.shadowrun.matrix.programs.Utility(com.shadowrun.matrix.programs.UtilityType.BROWSE, rating = 4)
        icon.equipUtility(browse)

        val state = InterrogationState(SystemOperation.LOCATE_SLAVE, "Security Camera")
        val result = icon.currentDecker().locateSlave(host, QueryPrecision.VERY_SPECIFIC, hitRoller())
        icon.context.updateDecker(icon.currentDecker(), result.first.decker)

        assertIs<LocateResult.Located>(result.second, "Should accumulate 3+ successes and locate the device")
    }

    @Test
    fun `locateSlave fails when host wins`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val state = InterrogationState(SystemOperation.LOCATE_SLAVE, "Camera")
        val result = icon.currentDecker().locateSlave(host, QueryPrecision.NORMAL, failRoller())

        assertIs<OperationResult.Failure>(result.first, "failRoller should make host win")
        assertTrue(result.second !is LocateResult.Located, "Should not locate device when host wins")
    }

    // ── controlSlave ─────────────────────────────────────────────────────────

    @Test
    fun `controlSlave succeeds and returns a MonitoredOperationHandle`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val device = host.remoteDevices.first()

        val (opResult, handle) = icon.currentDecker().controlSlave(device, host, winRoller())

        assertIs<OperationResult.Success>(opResult, "controlSlave should succeed with winRoller")
        assertNotNull(handle, "Should receive a MonitoredOperationHandle on success")
        assertTrue(handle.active, "Handle should be active immediately after creation")
    }

    @Test
    fun `controlSlave fails when host wins`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val device = host.remoteDevices.first()

        val (opResult, handle) = icon.currentDecker().controlSlave(device, host, failRoller())

        assertIs<OperationResult.Failure>(opResult, "controlSlave should fail with failRoller")
        assertTrue(handle == null, "No handle should be returned on failure")
    }

    // ── maintainMonitoredOperation / abortMonitoredOperation ─────────────────

    @Test
    fun `maintainMonitoredOperation keeps handle active`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val device = host.remoteDevices.first()
        val (_, handle) = icon.currentDecker().controlSlave(device, host, winRoller())
        assertNotNull(handle)

        val maintained = icon.currentDecker().maintainMonitoredOperation(handle)

        assertTrue(maintained.active, "Handle should remain active after maintain")
    }

    @Test
    fun `abortMonitoredOperation deactivates the handle`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val device = host.remoteDevices.first()
        val (_, handle) = icon.currentDecker().controlSlave(device, host, winRoller())
        assertNotNull(handle)

        val aborted = icon.currentDecker().abortMonitoredOperation(handle)

        assertTrue(!aborted.active, "Handle should be inactive after abort")
    }

    // ── monitorSlave ─────────────────────────────────────────────────────────

    @Test
    fun `monitorSlave succeeds with winRoller`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val host = host(icon)
        val device = host.remoteDevices.first()

        val (opResult, handle) = icon.currentDecker().monitorSlave(device, host, winRoller())

        assertIs<OperationResult.Success>(opResult, "monitorSlave should succeed with winRoller")
        assertNotNull(handle)
    }
}
