package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.JackpointType

data class Jackpoint(
    val type: JackpointType,
    val connectsToLtg: LTG? = null,
    val connectsToHost: Host? = null
) {
    init {
        require((connectsToLtg == null) != (connectsToHost == null)) {
            "Jackpoint must connect to exactly one target: either an LTG or a Host"
        }
    }
}
