package com.shadowrun.matrix.combat

data class TrackState(
    val trackingIcRating: Int,
    val locationCycleTurnsRemaining: Int,
    val opponentSensorRating: Int,
    val trackerMcpRating: Int
) {
    init {
        require(trackingIcRating >= 0) { "trackingIcRating must be >= 0, was $trackingIcRating" }
        require(locationCycleTurnsRemaining >= 0) { "locationCycleTurnsRemaining must be >= 0, was $locationCycleTurnsRemaining" }
        require(opponentSensorRating >= 0) { "opponentSensorRating must be >= 0, was $opponentSensorRating" }
        require(trackerMcpRating >= 0) { "trackerMcpRating must be >= 0, was $trackerMcpRating" }
    }
}
