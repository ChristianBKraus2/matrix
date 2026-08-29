package com.shadowrun.matrix.operations

/**
 * Security Value bonus applied to the host during a Null Operation based on how long
 * the decker has been inactive. The modifier is added to the host's Security Value dice,
 * not to the decker's target number. PRD: SO individual table (Null Operation)
 */
enum class NullOperationModifier(val bonus: Int) {
    UNDER_TEN_SECONDS(0),
    TEN_SECONDS_TO_ONE_MINUTE(1),
    ONE_MINUTE_TO_ONE_HOUR(2),
    ONE_HOUR_TO_TWELVE_HOURS(4);

    companion object {
        fun forDuration(seconds: Int): NullOperationModifier = when {
            seconds < 10   -> UNDER_TEN_SECONDS
            seconds < 60   -> TEN_SECONDS_TO_ONE_MINUTE
            seconds < 3600 -> ONE_MINUTE_TO_ONE_HOUR
            else           -> ONE_HOUR_TO_TWELVE_HOURS
        }

        /**
         * Total bonus for [seconds] of inactivity, including +1 per additional 12-hour window
         * beyond the first (applicable when seconds ≥ 3600 × 12).
         */
        fun totalBonusForDuration(seconds: Int): Int {
            val base = forDuration(seconds).bonus
            if (seconds < 43200) return base          // < 12 hours: no extra increments
            val extraIncrements = (seconds - 43200) / 43200   // additional complete 12-hr blocks beyond the first 12 hours
            return base + extraIncrements
        }
    }
}
