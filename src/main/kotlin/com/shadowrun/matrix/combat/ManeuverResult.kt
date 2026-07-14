package com.shadowrun.matrix.combat

sealed class ManeuverResult {
    data class Success(val netSuccesses: Int) : ManeuverResult()
    data object Failure : ManeuverResult()
}
