package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.ActionType
import com.shadowrun.matrix.common.ActionType.COMPLEX
import com.shadowrun.matrix.common.ActionType.FREE
import com.shadowrun.matrix.common.ActionType.SIMPLE
import com.shadowrun.matrix.network.Grid
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.RTG

sealed class AvailableAction {
    abstract val actionType: ActionType

    data class LogonToRtg(val rtg: RTG, override val actionType: ActionType = COMPLEX) : AvailableAction()

    /**
     * Access an LTG or PLTG the decker has stored an address for (ticket 06). Collapses the former
     * per-target LogonToLtg/LogonToPltg into a single dropdown of reachable, known targets.
     */
    data class AccessLtg(val targets: List<Grid>, override val actionType: ActionType = COMPLEX) : AvailableAction()

    /** Access a host the decker has stored an address for (ticket 06). One action, dropdown of targets. */
    data class AccessHost(val targets: List<Host>, override val actionType: ActionType = COMPLEX) : AvailableAction()

    /** Defeat scramble IC on a SAN; shows hosts that are scramble-protected and not yet decrypted (ticket 17). */
    data class DecryptAccess(val targets: List<Host>, override val actionType: ActionType = SIMPLE) : AvailableAction()

    /** Choose one candidate name revealed by a successful Locate; stores it on the decker (ticket 06). */
    data class SelectLocateTarget(
        val operation: SystemOperation,
        val candidates: List<String>,
        override val actionType: ActionType = FREE
    ) : AvailableAction()

    data class GracefulLogoff(override val actionType: ActionType = COMPLEX) : AvailableAction()
    data class JackOut(override val actionType: ActionType = FREE) : AvailableAction()

    /** A [SystemOperation] the decker can attempt, with an optional target object it applies to. */
    data class Operation(
        val operation: SystemOperation,
        val target: MatrixObject? = null,
        override val actionType: ActionType = operation.actionType
    ) : AvailableAction()
}
