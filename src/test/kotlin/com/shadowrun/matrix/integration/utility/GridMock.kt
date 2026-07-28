package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.config.GridInitializer
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint

object GridMock {

    val matrix = GridInitializer.initialize()

    fun jackpoint(rtg: String, ltg: String) : Jackpoint {
        val jackpoint = Jackpoint(
            JackpointType.ILLEGAL_ACCESS,
            connectsToLtg = matrix.rtgs.first { it.name == rtg }.ltgs.first { it.name == ltg }
        )
        return jackpoint
    }
    
    fun getDefaultJackpoint() : Jackpoint {
        return jackpoint("UCAS", "UCAS-SEA")
    }
    fun getDefaultLTG() : String {
        return "UCAS-SEA"
    }
    fun getDefaultHost(atzlan: Boolean = false) : Host {
        return if (atzlan) {
            matrix.getHost("AZT", "AZT-MEX", "Aztlan Ministry of Information")!!
        } else {
            matrix.getHost("UCAS", "UCAS-SEA", "Renraku Public Relations")!!
        }
    }
    
}