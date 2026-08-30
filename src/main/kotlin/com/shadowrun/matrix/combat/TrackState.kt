package com.shadowrun.matrix.combat

data class TrackState(
    val trackingIcRating: Int,
    val locationCycleTurnsRemaining: Int,
    val opponentSensorRating: Int = 0,
    val trackerMcpRating: Int = 0
)
