package com.shadowrun.matrix.integration

import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test

class ICActivationTest : IntegrationTestBase() {

    @Test
    fun `jack into UCAS-SEA and logon to Mitsuhama Pagoda`() {
        val icon = scenario {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.assertOnHost("Mitsuhama Pagoda")
    }

}
