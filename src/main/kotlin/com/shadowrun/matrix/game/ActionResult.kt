package com.shadowrun.matrix.game

sealed class ActionResult {
    data class IcAttack(val message: String) : ActionResult()
    data class IcMoved(val message: String) : ActionResult()
    data object NoTarget : ActionResult()
    data object DeckerAction : ActionResult()
}
