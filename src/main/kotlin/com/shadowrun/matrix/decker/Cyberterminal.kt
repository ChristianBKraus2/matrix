package com.shadowrun.matrix.decker

data class Cyberterminal(
    val name: String,
    val mcpRating: Int,
    val costNuyen: Int
) {
    val effectiveRatingModifier: Int = -1

    init {
        require(mcpRating <= 4) { "Cyberterminal MPCP cannot exceed 4, got $mcpRating" }
    }
}
