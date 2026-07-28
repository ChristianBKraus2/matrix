package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.config.GridInitializer
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint

object HostMock {

    fun build(name: String) : Host {
       return Host(
                name = name,
                securityRating = com.shadowrun.matrix.common.SecurityRating(
                    com.shadowrun.matrix.common.SecurityCode.GREEN, 3
                ),
                subsystemRatings = com.shadowrun.matrix.common.SubsystemRatings(3, 3, 3, 3, 3),
                intrusionDifficulty = com.shadowrun.matrix.common.IntrusionDifficulty.AVERAGE,
                topologyType = com.shadowrun.matrix.common.TopologyType.TIERED
            ) 
    }
}