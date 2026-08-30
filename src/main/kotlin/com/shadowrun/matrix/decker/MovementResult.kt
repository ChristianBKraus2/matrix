package com.shadowrun.matrix.decker

import com.shadowrun.matrix.network.MatrixLocation

sealed class LogonResult {
    /** Decker won the System Test; persona is now at [location]. */
    data class Success(val decker: Decker, val location: MatrixLocation,
        val deckerSuccesses: Int = 0, val hostSuccesses: Int = 0) : LogonResult()

    /** Decker lost the System Test. [decker] remains at its previous location; [location] is the
     *  attempted destination with its security tally already incremented by the host's successes.
     *  Callers should read [decker.currentLocation] for the decker's actual position and use
     *  [location] to propagate the tally update to the target grid/host. */
    data class Failure(val decker: Decker, val location: MatrixLocation?,
        val deckerSuccesses: Int = 0, val hostSuccesses: Int = 0) : LogonResult()
}

sealed class LogoffResult {
    /** Graceful Logoff succeeded; traces cleared; no dump shock. */
    data class GracefulSuccess(val decker: Decker) : LogoffResult()

    /** Decker jacked out (voluntarily or graceful logoff failed); dump shock applies. */
    data class JackOut(val decker: Decker, val dumpShock: Boolean) : LogoffResult()
}
