package com.shadowrun.matrix.combat

import com.shadowrun.matrix.decker.Decker

data class IcDamageResult(
    val updatedDecker: Decker,
    val iconDamage: AttackResult,
    val simsenseOverload: SimsenseOverloadResult?,
    val dumpShockTriggered: Boolean,
    /** Reduction to MPCP from the Black IC's final blaster shot (rules p. 230). Only non-zero on a kill blow. */
    val mpcpReductionOnKill: Int = 0
)
