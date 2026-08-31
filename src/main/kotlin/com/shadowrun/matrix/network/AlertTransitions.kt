package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.ic.IC

/**
 * Applies an alert transition to [host], returning an updated copy.
 *
 * AL-01 — Passive Alert: all five subsystem ratings raised by +2; increase is permanent for the
 * session (not reversed if tally later drops below the trigger step).
 * **Stacking is intentional:** calling this function a second time on a host already in
 * `PASSIVE_ALERT` adds another +2 to each rating. The game rules impose no cap on passive-alert
 * stacking, and the test suite (`AlertTransitionsTest`) verifies this behaviour.
 *
 * AL-02 — Active Alert: sets alertStatus to ACTIVE_ALERT. The triggering TriggerStep may carry
 * [securityDeckerCount]; the caller is responsible for actually spawning those NPC deckers on
 * the host.
 *
 * PRD: AL-01, AL-02
 */
fun applyAlertTransition(host: Host, newAlertStatus: AlertStatus): Host = when (newAlertStatus) {
    AlertStatus.PASSIVE_ALERT -> host.copy(
        subsystemRatings = host.subsystemRatings.copy(
            access  = host.subsystemRatings.access  + 2,
            control = host.subsystemRatings.control + 2,
            index   = host.subsystemRatings.index   + 2,
            files   = host.subsystemRatings.files   + 2,
            slave   = host.subsystemRatings.slave   + 2
        ),
        alertStatus = AlertStatus.PASSIVE_ALERT
    )
    AlertStatus.ACTIVE_ALERT -> host.copy(alertStatus = AlertStatus.ACTIVE_ALERT)
    AlertStatus.NO_ALERT -> host  // no-op; callers should never transition back to NO_ALERT this way
}

/**
 * Applies an alert transition to any [Grid] subtype (RTG, LTG, or PLTG), returning an updated copy.
 * Mirrors the host overload — see its KDoc for stacking and session-permanence notes.
 * PRD: AL-01, AL-02
 */
fun applyAlertTransition(grid: Grid, newAlertStatus: AlertStatus): Grid = when (newAlertStatus) {
    AlertStatus.PASSIVE_ALERT -> {
        val boosted = grid.subsystemRatings.copy(
            access  = grid.subsystemRatings.access  + 2,
            control = grid.subsystemRatings.control + 2,
            index   = grid.subsystemRatings.index   + 2,
            files   = grid.subsystemRatings.files   + 2,
            slave   = grid.subsystemRatings.slave   + 2
        )
        when (grid) {
            is RTG  -> grid.copy(subsystemRatings = boosted, alertStatus = AlertStatus.PASSIVE_ALERT)
            is LTG  -> grid.copy(subsystemRatings = boosted, alertStatus = AlertStatus.PASSIVE_ALERT)
            is PLTG -> grid.copy(subsystemRatings = boosted, alertStatus = AlertStatus.PASSIVE_ALERT)
        }
    }
    AlertStatus.ACTIVE_ALERT -> when (grid) {
        is RTG  -> grid.copy(alertStatus = AlertStatus.ACTIVE_ALERT)
        is LTG  -> grid.copy(alertStatus = AlertStatus.ACTIVE_ALERT)
        is PLTG -> grid.copy(alertStatus = AlertStatus.ACTIVE_ALERT)
    }
    AlertStatus.NO_ALERT -> grid
}

/**
 * Result of evaluating a grid's security sheaf against a tally change.
 * [activatedIc] lists every IC program whose trigger step was crossed.
 * [updatedGrid] carries any alert-status or subsystem-rating changes applied by the crossed steps.
 */
data class GridTriggerResult(val activatedIc: List<IC>, val updatedGrid: Grid)

/**
 * Checks which trigger steps in [grid]'s security sheaf are crossed by a tally increase from
 * [oldTally] to [newTally], activates their IC programs, and applies any alert transitions.
 * Mirrors [com.shadowrun.matrix.game.GameContext.checkTriggers] for host-level contexts.
 * PRD: AL-01, AL-02
 */
fun checkGridTriggers(grid: Grid, oldTally: Int, newTally: Int): GridTriggerResult {
    val crossed = grid.securitySheaf.triggerSteps
        .filter { it.tallyThreshold in (oldTally + 1)..newTally }
    val activatedIc = crossed.flatMap { it.activatedIc }
    var updated: Grid = grid
    for (step in crossed) {
        step.alertTransition?.let { transition ->
            if (transition != updated.alertStatus) {
                updated = applyAlertTransition(updated, transition)
            }
        }
    }
    return GridTriggerResult(activatedIc, updated)
}
