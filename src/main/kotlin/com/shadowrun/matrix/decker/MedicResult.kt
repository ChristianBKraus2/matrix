package com.shadowrun.matrix.decker

/**
 * Result of invoking the Medic utility (CC-22 / CD-26).
 * [medicRating] is the Medic's currentRating *after* the mandatory decrement.
 * When [medicRating] reaches 0 the utility has been auto-unloaded.
 */
data class MedicResult(
    val updatedDecker: Decker,
    val boxesRepaired: Int,
    val medicRating: Int
)
