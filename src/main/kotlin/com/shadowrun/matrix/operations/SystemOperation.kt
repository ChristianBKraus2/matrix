package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.ActionType
import com.shadowrun.matrix.common.ActionType.COMPLEX
import com.shadowrun.matrix.common.ActionType.FREE
import com.shadowrun.matrix.common.ActionType.SIMPLE
import com.shadowrun.matrix.common.OperationCategory
import com.shadowrun.matrix.common.OperationCategory.INTERROGATION
import com.shadowrun.matrix.common.OperationCategory.MONITORED
import com.shadowrun.matrix.common.OperationCategory.ONGOING
import com.shadowrun.matrix.common.OperationCategory.STANDARD
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.SubsystemType.ACCESS
import com.shadowrun.matrix.common.SubsystemType.CONTROL
import com.shadowrun.matrix.common.SubsystemType.FILES
import com.shadowrun.matrix.common.SubsystemType.INDEX
import com.shadowrun.matrix.common.SubsystemType.SLAVE
import com.shadowrun.matrix.programs.UtilityType

enum class SystemOperation(
    val testType: SubsystemType?,
    val utility: UtilityType?,
    val actionType: ActionType,
    val category: OperationCategory
) {
    ANALYZE_HOST(CONTROL, UtilityType.ANALYZE, COMPLEX, STANDARD),
    ANALYZE_IC(CONTROL, UtilityType.ANALYZE, FREE, STANDARD),
    ANALYZE_ICON(CONTROL, UtilityType.ANALYZE, FREE, STANDARD),
    ANALYZE_SECURITY(CONTROL, UtilityType.ANALYZE, SIMPLE, STANDARD),
    ANALYZE_SUBSYSTEM(null, UtilityType.ANALYZE, SIMPLE, STANDARD),
    CONTROL_SLAVE(SLAVE, UtilityType.SPOOF, COMPLEX, MONITORED),
    DECRYPT_ACCESS(ACCESS, UtilityType.DECRYPT, SIMPLE, STANDARD),
    DECRYPT_FILE(FILES, UtilityType.DECRYPT, SIMPLE, STANDARD),
    DECRYPT_SLAVE(SLAVE, UtilityType.DECRYPT, SIMPLE, STANDARD),
    DOWNLOAD_DATA(FILES, UtilityType.READ_WRITE, SIMPLE, ONGOING),
    EDIT_FILE(FILES, UtilityType.READ_WRITE, SIMPLE, STANDARD),
    EDIT_SLAVE(SLAVE, UtilityType.SPOOF, COMPLEX, MONITORED),
    GRACEFUL_LOGOFF(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD),
    INVOKE_MEDIC(CONTROL, null, COMPLEX, STANDARD),
    LOCATE_ACCESS_NODE(INDEX, UtilityType.BROWSE, COMPLEX, INTERROGATION),
    LOCATE_DECKER(INDEX, UtilityType.SCANNER, COMPLEX, STANDARD),
    LOCATE_FILE(INDEX, UtilityType.BROWSE, COMPLEX, INTERROGATION),
    LOCATE_IC(INDEX, UtilityType.ANALYZE, COMPLEX, STANDARD),
    LOCATE_SLAVE(INDEX, UtilityType.BROWSE, COMPLEX, INTERROGATION),
    LOGON_TO_HOST(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD),
    LOGON_TO_LTG(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD),
    LOGON_TO_RTG(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD),
    MAKE_COMCALL(FILES, UtilityType.COMMLINK, COMPLEX, MONITORED),
    MONITOR_SLAVE(SLAVE, UtilityType.SPOOF, SIMPLE, MONITORED),
    NULL_OPERATION(CONTROL, UtilityType.DECEPTION, COMPLEX, STANDARD),
    RELOCATE_ICON(CONTROL, UtilityType.RELOCATE, SIMPLE, STANDARD),
    /** Deferred operation (not yet implemented); excluded from availableActions(). */
    SWAP_MEMORY(null, null, SIMPLE, STANDARD),
    TAP_COMCALL(FILES, UtilityType.COMMLINK, COMPLEX, MONITORED),
    UPLOAD_DATA(FILES, UtilityType.READ_WRITE, SIMPLE, ONGOING)
}
