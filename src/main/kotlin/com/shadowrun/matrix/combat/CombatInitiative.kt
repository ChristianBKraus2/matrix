package com.shadowrun.matrix.combat

data class CombatInitiative(
    val score: Int,
    val initiativePasses: Int
) {
    init {
        require(score >= 0) { "score must be >= 0, was $score" }
        require(initiativePasses >= 0) { "initiativePasses must be >= 0, was $initiativePasses" }
    }
}
