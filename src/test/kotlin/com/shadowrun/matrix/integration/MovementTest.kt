package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.network.Jackpoint
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
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 12, thenValue = 3)) {
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

    // --- Group A: direct host jack-in via workstation jackpoint ---

    @Test
    fun `jack in directly to host via workstation jackpoint`() {
        val host = matrix.getHost("UCAS", "UCAS-SEA", "Renraku Public Relations")!!
        val jackpoint = Jackpoint(JackpointType.WORKSTATION, connectsToHost = host)
        val icon = scenario(jackpoint = jackpoint) {
            jackInToHost(host)
        }
        icon.assertOnHost("Renraku Public Relations")
    }

    @Test
    fun `fail to jack in directly to host via workstation jackpoint`() {
        val host = matrix.getHost("UCAS", "UCAS-SEA", "Renraku Public Relations")!!
        val jackpoint = Jackpoint(JackpointType.WORKSTATION, connectsToHost = host)
        val icon = scenario(jackpoint = jackpoint, diceRoller = failRoller()) {
            jackInToHost(host, succeed = false)
        }
        icon.assertNotJackedIn()
    }

    // --- Group B: jack-in to LTG failure ---

    @Test
    fun `fail to jack in to LTG - decker never enters the Matrix`() {
        val icon = scenario(diceRoller = failRoller()) {
            jackInToLtg("UCAS/UCAS-SEA", succeed = false)
        }
        icon.assertNotJackedIn()
    }

    // --- Group C: RTG transition failures ---

    @Test
    fun `fail to logon to parent RTG - stays on LTG`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 12, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg(succeed = false)
        }
        icon.assertOnLtg("UCAS-SEA")
    }

    @Test
    fun `fail to cross-RTG hop - stays on source RTG`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 24, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg()
            logonToRtg("AZT", succeed = false)
        }
        icon.assertOnRtg("UCAS")
    }

    @Test
    fun `fail to logon to LTG from RTG - stays on RTG`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 24, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToParentRtg()
            logonToLtg("UCAS/UCAS-CHI", succeed = false)
        }
        icon.assertOnRtg("UCAS")
    }

    // --- Group D: PLTG layer ---

    @Test
    fun `traverse LTG to PLTG to Host`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToPltg("UCAS/UCAS-SEA")
            logonToHost(matrix.getLTG("UCAS", "UCAS-SEA")!!.pltgs.first().hosts.first())
        }
        icon.assertOnHost("Ares R&D Secure Archive")
    }

    @Test
    fun `fail to logon to PLTG - stays on LTG`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 12, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToPltg("UCAS/UCAS-SEA", succeed = false)
        }
        icon.assertOnLtg("UCAS-SEA")
    }

    // --- Group F: logoff failure paths ---

    @Test
    fun `graceful logoff failure falls back to jack-out with dump shock`() {
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 25, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
            gracefulLogoff(succeed = false)
        }
        icon.assertLoggedOff()
    }

    @Test
    fun `forced jack-out causes dump shock`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Renraku Public Relations")
            jackOut()
        }
        icon.assertLoggedOff()
    }
}
