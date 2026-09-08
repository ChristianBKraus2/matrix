package com.shadowrun.matrix.integration

import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.decker.jackInToLtg
import com.shadowrun.matrix.decker.locateAccessNode
import com.shadowrun.matrix.decker.logonToHost
import com.shadowrun.matrix.decker.selectLocateTarget
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.OperationResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ticket 06 end-to-end: jack in to an LTG, discover a host's address via a regex Locate, select the
 * candidate to store the address, and only then find Access Host offered — proving strict address
 * gating and the discovery-then-selection flow against the real initialized grid.
 */
class LocateAndAccessIntegrationTest : IntegrationTestBase() {

    @Test
    fun `jack in to LTG then locate, select, and access a host`() {
        val jackpoint = GridMock.getDefaultJackpoint() // UCAS / UCAS-SEA
        val decker = DeckerMock.build(jackpoint, DeckerMock.HIGH_END)
        val ltg = assertNotNull(jackpoint.connectsToLtg, "jackpoint must connect to an LTG")

        // 1. Jack in to the LTG.
        val jackedIn = (decker.jackInToLtg(ltg, winRoller()) as LogonResult.Success).decker
        val onLtg = assertIs<MatrixLocation.OnLTG>(jackedIn.currentLocation)
        val targetHost = onLtg.ltg.hosts.first { it.name == "Renraku Public Relations" }

        // 2. Strict gating: Access Host must NOT be offered before the address is located.
        assertFalse(
            jackedIn.availableActions().any {
                it is AvailableAction.AccessHost && it.targets.any { h -> h.name == targetHost.name }
            },
            "Access Host must not be available before the host address is located"
        )

        // 3. Locate the host by a full-literal regex query → candidates.
        val (locateOp, locateResult) = jackedIn.locateAccessNode(onLtg.ltg, targetHost.name, winRoller())
        assertIs<OperationResult.Success>(locateOp)
        val candidates = assertIs<LocateResult.Candidates>(locateResult)
        assertTrue(targetHost.name in candidates.names, "located candidates must include the target host")
        val located = locateOp.decker

        // 4. Select the located target → address stored, pendingLocate cleared.
        val withAddress = located.selectLocateTarget(targetHost.name)
        assertTrue(targetHost.name in withAddress.knownAddresses)
        assertNull(withAddress.pendingLocate)

        // 5. Access Host now appears exactly once and lists only the located host.
        val accessActions = withAddress.availableActions().filterIsInstance<AvailableAction.AccessHost>()
        assertEquals(1, accessActions.size)
        val accessTargets = accessActions.single().targets.map { it.name }
        assertTrue(targetHost.name in accessTargets)
        val otherHost = onLtg.ltg.hosts.firstOrNull { it.name != targetHost.name }
        if (otherHost != null) {
            assertFalse(otherHost.name in accessTargets, "un-located hosts must stay gated out of Access Host")
        }

        // 6. Logon to the located host succeeds.
        val onHost = (withAddress.logonToHost(targetHost, winRoller()) as LogonResult.Success).decker
        assertEquals(targetHost.name, assertIs<MatrixLocation.OnHost>(onHost.currentLocation).host.name)
    }
}
