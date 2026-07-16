package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import kotlin.test.Test
import kotlin.test.assertEquals

class AlertTransitionsTest {

    private fun host(
        access: Int = 4, control: Int = 5, index: Int = 6, files: Int = 7, slave: Int = 8,
        alertStatus: AlertStatus = AlertStatus.NO_ALERT
    ) = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.GREEN, 6),
        subsystemRatings = SubsystemRatings(access, control, index, files, slave),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS,
        alertStatus = alertStatus
    )

    // ── AL-01: Passive Alert ──────────────────────────────────────────────────────

    @Test
    fun `PASSIVE_ALERT raises all five subsystem ratings by 2`() {
        val h = host(access = 4, control = 5, index = 6, files = 7, slave = 8)
        val updated = applyAlertTransition(h, AlertStatus.PASSIVE_ALERT)
        assertEquals(6,  updated.subsystemRatings.access)
        assertEquals(7,  updated.subsystemRatings.control)
        assertEquals(8,  updated.subsystemRatings.index)
        assertEquals(9,  updated.subsystemRatings.files)
        assertEquals(10, updated.subsystemRatings.slave)
    }

    @Test
    fun `PASSIVE_ALERT sets alertStatus to PASSIVE_ALERT`() {
        val h = host()
        val updated = applyAlertTransition(h, AlertStatus.PASSIVE_ALERT)
        assertEquals(AlertStatus.PASSIVE_ALERT, updated.alertStatus)
    }

    @Test
    fun `PASSIVE_ALERT applied twice stacks the +2 bonus`() {
        val h = host(access = 4)
        val once = applyAlertTransition(h, AlertStatus.PASSIVE_ALERT)
        val twice = applyAlertTransition(once, AlertStatus.PASSIVE_ALERT)
        assertEquals(8, twice.subsystemRatings.access)
    }

    // ── AL-02: Active Alert ───────────────────────────────────────────────────────

    @Test
    fun `ACTIVE_ALERT sets alertStatus to ACTIVE_ALERT`() {
        val h = host()
        val updated = applyAlertTransition(h, AlertStatus.ACTIVE_ALERT)
        assertEquals(AlertStatus.ACTIVE_ALERT, updated.alertStatus)
    }

    @Test
    fun `ACTIVE_ALERT does not change subsystem ratings`() {
        val h = host(access = 4, control = 5, index = 6, files = 7, slave = 8)
        val updated = applyAlertTransition(h, AlertStatus.ACTIVE_ALERT)
        assertEquals(4, updated.subsystemRatings.access)
        assertEquals(5, updated.subsystemRatings.control)
        assertEquals(6, updated.subsystemRatings.index)
        assertEquals(7, updated.subsystemRatings.files)
        assertEquals(8, updated.subsystemRatings.slave)
    }

    // ── NO_ALERT: no-op guard ─────────────────────────────────────────────────────

    @Test
    fun `NO_ALERT transition is a no-op`() {
        val h = host(access = 4, alertStatus = AlertStatus.PASSIVE_ALERT)
        val updated = applyAlertTransition(h, AlertStatus.NO_ALERT)
        assertEquals(h, updated)
    }
}
