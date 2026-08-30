package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.DamageLevel

data class AttackParticipant(
    val attackDicePool: Int,
    val weaponPower: Int = attackDicePool,
    val hackingPool: Int = 0,
    val rawDamageLevel: DamageLevel,
    val modifiers: CombatModifiers = CombatModifiers()
)
