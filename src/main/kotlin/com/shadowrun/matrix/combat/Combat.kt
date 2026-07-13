package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.CombatManeuverType
import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating

data class CombatTurn(val number: Int = 1)

data class CombatManeuver(val type: CombatManeuverType)

data class DumpShock(val securityRating: SecurityRating) {
    val power: Int get() = securityRating.value
    val level: DamageLevel get() = when (securityRating.code) {
        SecurityCode.BLUE   -> DamageLevel.LIGHT
        SecurityCode.GREEN  -> DamageLevel.MODERATE
        SecurityCode.ORANGE -> DamageLevel.SERIOUS
        SecurityCode.RED    -> DamageLevel.DEADLY
    }
}

data class SimsenseOverload(val damageLevel: DamageLevel)
