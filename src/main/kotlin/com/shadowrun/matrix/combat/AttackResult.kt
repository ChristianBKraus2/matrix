package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.DamageLevel

sealed class AttackResult {
    data class Hit(
        /**
         * Net successes on the attack test. For Black IC / Black Hammer / Killjoy this is a
         * **sentinel `1`** ("a hit occurred") — those attacks don't use the standard success count.
         * Guarded by [attackerSuccessesMeaningful]: callers that cycle on this value (e.g. TrackLock)
         * must not consume a hit whose flag is false.
         */
        val attackerSuccesses: Int,
        val rawDamageLevel: DamageLevel,
        val stagedDamageLevel: DamageLevel,
        val rawWeaponPower: Int,
        val effectivePower: Int,
        /** False when [attackerSuccesses] is a Black-IC sentinel rather than a real success count. */
        val attackerSuccessesMeaningful: Boolean = true
    ) : AttackResult()

    data object Miss : AttackResult()
}


