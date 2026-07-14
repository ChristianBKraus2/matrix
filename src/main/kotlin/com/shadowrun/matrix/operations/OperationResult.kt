package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.Decker

/** Common return type for system operations that involve a System Test. PRD: operations.md */
sealed class OperationResult {
    abstract val decker: Decker
    abstract val outcome: SystemTestOutcome

    data class Success(
        override val decker: Decker,
        override val outcome: SystemTestOutcome
    ) : OperationResult()

    data class Failure(
        override val decker: Decker,
        override val outcome: SystemTestOutcome
    ) : OperationResult()
}

/** One item the decker may request per net success in an Analyze Host operation. */
sealed class HostInfoItem {
    object SecurityRating : HostInfoItem()
    data class Subsystem(val type: SubsystemType) : HostInfoItem()
}

/**
 * Result of Analyze Host. The decker supplies [requestedItems] in priority order;
 * the library reveals the first [net] distinct items from that list.
 * On 7+ net successes all six pieces of info are revealed regardless of [requestedItems].
 */
data class AnalyzeHostResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    /** Non-null when the decker requested (or net ≥ 7) and net > 0. */
    val revealedSecurityRating: SecurityRating?,
    /** One entry per net success spent on a subsystem (up to all 5). */
    val revealedSubsystemRatings: Map<SubsystemType, Int>
)

/** Result of Analyze Security: current rating, tally (including this test), and alert status. */
data class AnalyzeSecurityResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    val securityRating: SecurityRating,
    /** Security tally including points accrued during this test. */
    val currentTally: Int,
    val alertStatus: AlertStatus
)

/** Result of an Edit File operation, with optional header-authentication successes. */
data class EditFileResult(
    val decker: Decker,
    val outcome: SystemTestOutcome,
    /** null when authentication step was not attempted. */
    val authenticationSuccesses: Int?
)

/** Shared result type for interrogation operations (Locate File/Slave/Access Node). */
sealed class LocateResult {
    /** Accumulated successes below threshold; still searching. */
    data class Ongoing(val accumulatedSuccesses: Int) : LocateResult()
    /** Accumulated successes ≥ threshold; target located. */
    data class Located(val target: Any, val accumulatedSuccesses: Int) : LocateResult()
    /** Host confirmed the queried data does not exist (≥ 3 successes with no data present). */
    object NotFound : LocateResult()
}
