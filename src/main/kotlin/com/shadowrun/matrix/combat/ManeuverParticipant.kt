package com.shadowrun.matrix.combat

data class ManeuverParticipant(
    val evasion: Int,
    val sensor: Int,
    val cloakRating: Int = 0,
    val lockOnRating: Int = 0,
    val hackingPool: Int = 0
) {
    init {
        require(evasion >= 0) { "evasion must be >= 0, was $evasion" }
        require(sensor >= 0) { "sensor must be >= 0, was $sensor" }
        require(cloakRating >= 0) { "cloakRating must be >= 0, was $cloakRating" }
        require(lockOnRating >= 0) { "lockOnRating must be >= 0, was $lockOnRating" }
        require(hackingPool >= 0) { "hackingPool must be >= 0, was $hackingPool" }
    }
}
