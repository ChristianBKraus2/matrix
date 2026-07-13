package com.shadowrun.matrix.operations

data class SystemTestOutcome(
    val deckerSuccesses: Int,
    val hostSuccesses: Int,
    /** true when deckerSuccesses >= hostSuccesses (decker wins ties) */
    val deckerWins: Boolean
)
