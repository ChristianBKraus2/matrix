package com.shadowrun.matrix.combat

import com.shadowrun.matrix.ic.IC

/**
 * Tracks a crashed IC program held under suppression by the decker (CC-22).
 * Suppressing prevents the tally increase from the crash; each suppressed IC reduces Detection Factor by 1.
 * [icRating] records the rating at crash time — needed if the decker later unsuppresses.
 */
data class IcSuppressionState(
    val ic: IC,
    val icRating: Int
)
