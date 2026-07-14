package com.shadowrun.matrix.programs

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.common.UtilityCategory.DEFENSIVE
import com.shadowrun.matrix.common.UtilityCategory.OFFENSIVE
import com.shadowrun.matrix.common.UtilityCategory.OPERATIONAL
import com.shadowrun.matrix.common.UtilityCategory.SPECIAL

enum class UtilityType(val multiplier: Int, val category: UtilityCategory) {
    ANALYZE(3, OPERATIONAL),
    BROWSE(1, OPERATIONAL),
    COMMLINK(1, OPERATIONAL),
    DECEPTION(2, OPERATIONAL),
    DECRYPT(1, OPERATIONAL),
    READ_WRITE(2, OPERATIONAL),
    RELOCATE(2, OPERATIONAL),
    SCANNER(3, OPERATIONAL),
    SPOOF(3, OPERATIONAL),

    SLEAZE(3, SPECIAL),
    TRACK(8, SPECIAL),

    // ATTACK multiplier varies by damage level; base is LIGHT (×2); use Utility(ATTACK, rating, damageLevel) for others
    ATTACK(2, OFFENSIVE),
    BLACK_HAMMER(20, OFFENSIVE),
    KILLJOY(10, OFFENSIVE),
    SLOW(4, OFFENSIVE),

    ARMOR(3, DEFENSIVE),
    CLOAK(3, DEFENSIVE),
    LOCK_ON(3, DEFENSIVE),
    MEDIC(4, DEFENSIVE)
}

class Utility(
    val type: UtilityType,
    rating: Int,
    val attackDamageLevel: DamageLevel? = null,
    val currentRating: Int = rating,
    val sourceCode: Boolean = false
) : Program(
    name = type.name,
    rating = rating,
    multiplier = if (type == UtilityType.ATTACK && attackDamageLevel != null)
        attackDamageLevel.ordinal + 2
    else
        type.multiplier
)
