package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.DamageLevel

sealed class AttackResult {
    data class Hit(
        val attackerSuccesses: Int,
        val rawDamageLevel: DamageLevel,
        val stagedDamageLevel: DamageLevel,
        val rawWeaponPower: Int,
        val effectivePower: Int
    ) : AttackResult()

    data object Miss : AttackResult()
}


