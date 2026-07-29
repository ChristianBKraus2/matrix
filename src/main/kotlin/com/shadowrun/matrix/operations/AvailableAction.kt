package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.ActionType
import com.shadowrun.matrix.common.ActionType.COMPLEX
import com.shadowrun.matrix.common.ActionType.FREE
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG

sealed class AvailableAction {
    abstract val actionType: ActionType

    data class LogonToRtg(val rtg: RTG, override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class LogonToLtg(val ltg: LTG, override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class LogonToPltg(val pltg: PLTG, override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class LogonToHost(val host: Host, override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class GracefulLogoff(override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class JackOut(override val actionType: ActionType = FREE) : AvailableAction()

    /** A [SystemOperation] the decker can attempt, with an optional target object it applies to. */
    data class Operation(
        val operation: SystemOperation,
        val target: MatrixObject? = null,
        override val actionType: ActionType = operation.actionType
    ) : AvailableAction()
}
