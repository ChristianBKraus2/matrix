package com.shadowrun.matrix.combat

import com.shadowrun.matrix.decker.Decker

data class IcDamageResult(
    val updatedDecker: Decker,
    val iconDamage: AttackResult,
    val simsenseOverload: SimsenseOverloadResult?,
    val dumpShockTriggered: Boolean
)
