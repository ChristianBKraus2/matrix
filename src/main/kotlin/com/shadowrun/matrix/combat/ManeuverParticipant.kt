package com.shadowrun.matrix.combat

data class ManeuverParticipant(
    val evasion: Int,
    val sensor: Int,
    val cloakRating: Int = 0,
    val lockOnRating: Int = 0,
    val hackingPool: Int = 0
)
