package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus

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
