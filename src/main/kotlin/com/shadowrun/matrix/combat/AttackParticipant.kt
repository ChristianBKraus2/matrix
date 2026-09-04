package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.DamageLevel

data class AttackParticipant(
    val attackDicePool: Int,
    // Weapon Power is distinct from the attack dice pool; the two happen to coincide for some
    // attackers but must be supplied explicitly (E-9) rather than conflated via a default.
    val weaponPower: Int,
    val hackingPool: Int = 0,
    val rawDamageLevel: DamageLevel,
    val modifiers: CombatModifiers = CombatModifiers()
) {
    init {
        require(attackDicePool >= 0) { "attackDicePool must be >= 0, was $attackDicePool" }
        require(weaponPower >= 0) { "weaponPower must be >= 0, was $weaponPower" }
        require(hackingPool >= 0) { "hackingPool must be >= 0, was $hackingPool" }
    }
}
