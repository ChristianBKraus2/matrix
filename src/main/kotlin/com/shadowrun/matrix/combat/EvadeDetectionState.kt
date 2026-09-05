package com.shadowrun.matrix.combat

/**
 * Tracks an IC program that the decker has successfully evaded (CC-14, SR3 p. 224–225).
 * The IC cannot detect the decker until [turnsRemaining] Combat Turns have elapsed.
 * [turnsRemaining] is shortened by 1 for each security tally point added during the evasion
 * period; when it reaches 0 the IC is dropped from detectedIcons — the decker must use
 * Locate IC to re-detect it (CC-18).
 */
data class EvadeDetectionState(
    val icName: String,
    val turnsRemaining: Int
)
