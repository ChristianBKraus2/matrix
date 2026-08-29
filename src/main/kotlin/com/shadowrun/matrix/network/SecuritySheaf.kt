package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.ic.IC

data class TriggerStep(
    val tallyThreshold: Int,
    val description: String,
    // IC programs activated when this threshold is reached
    val activatedIc: List<IC> = emptyList(),
    // If non-null, the host transitions to this alert level at this step
    val alertTransition: AlertStatus? = null,
    // AL-02: number of security decker NPCs to spawn on Active Alert
    val securityDeckerCount: Int = 0
)

data class SecuritySheaf(val triggerSteps: List<TriggerStep> = emptyList()) {
    init {
        val thresholds = triggerSteps.map { it.tallyThreshold }
        require(thresholds.size == thresholds.distinct().size) {
            "SecuritySheaf triggerSteps must have unique tallyThreshold values, but found duplicates: $thresholds"
        }
        require(thresholds == thresholds.sorted()) {
            "SecuritySheaf triggerSteps must be sorted ascending by tallyThreshold, but got: $thresholds"
        }
    }
}
