package com.shadowrun.matrix.combat

data class CombatModifiers(
    val parryAttackBonus: Int = 0,
    val positionAttackTnBonus: Int = 0,
    val positionAttackPowerBonus: Int = 0
) {
    init {
        require(positionAttackTnBonus == 0 || positionAttackPowerBonus == 0) {
            "Position Attack grants TN bonus OR Power bonus, not both"
        }
    }
}
