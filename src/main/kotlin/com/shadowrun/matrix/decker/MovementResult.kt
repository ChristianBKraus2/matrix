package com.shadowrun.matrix.decker

import com.shadowrun.matrix.network.MatrixLocation

sealed class LogonResult {
    /** Decker won the System Test; persona is now at [location]. */
    data class Success(val decker: Decker, val location: MatrixLocation) : LogonResult()

    /** Decker lost the System Test; still at [location] (null when attempting initial jack-in). */
    data class Failure(val decker: Decker, val location: MatrixLocation?) : LogonResult()
}

sealed class LogoffResult {
    /** Graceful Logoff succeeded; traces cleared; no dump shock. */
    data class GracefulSuccess(val decker: Decker) : LogoffResult()

    /** Decker jacked out (voluntarily or graceful logoff failed); dump shock applies. */
    data class JackOut(val decker: Decker, val dumpShock: Boolean) : LogoffResult()
}
