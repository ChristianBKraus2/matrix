package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode

data class DefenderParticipant(
    val bod: Int,
    val armorCurrentRating: Int = 0,
    val personaStatus: PersonaStatus,
    val securityCode: SecurityCode
) {
    init {
        require(bod >= 0) { "bod must be >= 0, was $bod" }
        require(armorCurrentRating >= 0) { "armorCurrentRating must be >= 0, was $armorCurrentRating" }
    }
}
