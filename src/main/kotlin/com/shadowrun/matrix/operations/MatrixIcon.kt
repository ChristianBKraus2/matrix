package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.Persona
import com.shadowrun.matrix.ic.IC

/**
 * A discriminated union covering any icon that can be noticed or analyzed in the Matrix.
 * Used as the parameter to [Decker.noticeIcon] so the method can determine the correct
 * target number without needing to know the concrete type at call sites.
 * PRD: MP-01 through MP-06
 */
sealed class MatrixIcon {
    /** Another decker's persona. TN for notice = masking + sleaze (if any). */
    data class PersonaIcon(val persona: Persona, val sleazeRating: Int = 0) : MatrixIcon()

    /** An IC program. TN for notice = ic.rating. */
    data class IcIcon(val ic: IC) : MatrixIcon()
}

/** Return type of [Decker.noticeIcon]. PRD: MP-01–MP-05 */
sealed class SensorTestResult {
    object Undetected : SensorTestResult()
    data class Detected(val icon: MatrixIcon, val successes: Int) : SensorTestResult()
}

/** Return type of [Decker.noticeTriggeredIc]. PRD: MP-07, MP-08 */
sealed class IcDetectionResult {
    object Undetected : IcDetectionResult()
    data class PresenceOnly(val successes: Int) : IcDetectionResult()
    data class TypeKnown(val ic: IC, val successes: Int) : IcDetectionResult()
    data class FullyLocated(val ic: IC, val successes: Int) : IcDetectionResult()
}
