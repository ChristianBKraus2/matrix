package com.shadowrun.matrix.operations

/**
 * Handle for an active monitored operation (Control Slave, Edit Slave, Monitor Slave,
 * Make Comcall, Tap Comcall). The caller must supply a Free Action every Initiative Pass
 * by calling [Decker.maintainMonitoredOperation]; missing one sets [active] to false and
 * aborts the operation. PRD: SO-13, SO-14
 */
data class MonitoredOperationHandle(
    val operation: SystemOperation,
    /** The target resource (RemoteDevice, commcode string, etc.). */
    val target: Any,
    val active: Boolean = true
)
