package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.ActionType
import com.shadowrun.matrix.common.OperationCategory
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemOperationTest {

    @Test
    fun `ANALYZE_HOST uses Control subsystem and Analyze utility`() {
        val op = SystemOperation.ANALYZE_HOST
        assertEquals(SubsystemType.CONTROL, op.testType)
        assertEquals(UtilityType.ANALYZE, op.utility)
        assertEquals(ActionType.COMPLEX, op.actionType)
    }

    @Test
    fun `DOWNLOAD_DATA is ongoing`() {
        assertEquals(OperationCategory.ONGOING, SystemOperation.DOWNLOAD_DATA.category)
    }

    @Test
    fun `CONTROL_SLAVE is monitored`() {
        assertEquals(OperationCategory.MONITORED, SystemOperation.CONTROL_SLAVE.category)
    }

    @Test
    fun `all 27 operations are defined`() {
        assertEquals(27, SystemOperation.entries.size)
    }
}
