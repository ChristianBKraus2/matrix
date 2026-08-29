package com.shadowrun.matrix.operations

import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.RemoteDevice

/** Typed target for a monitored operation. */
sealed class MonitoredTarget {
    /** A remote device controlled or observed via a Slave subsystem. */
    data class SlaveDevice(val device: RemoteDevice) : MonitoredTarget()
    /** A host node targeted by a comcall operation. */
    data class ComcallHost(val host: Host) : MonitoredTarget()
}

/**
 * Handle for an active monitored operation (Control Slave, Edit Slave, Monitor Slave,
 * Make Comcall, Tap Comcall). The caller must supply a Free Action every Initiative Pass
 * by calling [Decker.maintainMonitoredOperation]; missing one sets [active] to false and
 * aborts the operation. PRD: SO-13, SO-14
 */
data class MonitoredOperationHandle(
    val operation: SystemOperation,
    /** The target resource. */
    val target: MonitoredTarget,
    val active: Boolean = true
)
