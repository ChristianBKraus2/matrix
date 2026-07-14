package com.shadowrun.matrix.operations

/**
 * Tracks accumulated successes across repeated interrogation-operation attempts
 * (Locate File, Locate Slave, Locate Access Node). Held by the caller between turns.
 * PRD: SO-05 through SO-09
 */
data class InterrogationState(
    val operation: SystemOperation,
    /** The decker's stated search goal — used to apply query-precision TN modifiers. */
    val query: String,
    val accumulatedSuccesses: Int = 0
)

/**
 * How precisely the decker phrased the interrogation query.
 * Applies a TN modifier to the base target number before utility reduction (SO-07).
 */
enum class QueryPrecision(val modifier: Int) {
    VERY_VAGUE(+2),
    VAGUE(+1),
    NORMAL(0),
    SPECIFIC(-1),
    VERY_SPECIFIC(-2)
}
