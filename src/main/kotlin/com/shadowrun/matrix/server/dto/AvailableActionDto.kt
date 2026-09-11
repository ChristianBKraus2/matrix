package com.shadowrun.matrix.server.dto

import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.SystemOperation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed class AvailableActionDto {
    abstract val index: Int
    abstract val actionType: String

    @Serializable
    @SerialName("LogonToRtg")
    data class LogonToRtg(override val index: Int,
        override val actionType: String, val rtgName: String) : AvailableActionDto()

    @Serializable
    @SerialName("AccessLtg")
    data class AccessLtg(override val index: Int,
        override val actionType: String, val ltgNames: List<String>) : AvailableActionDto()

    @Serializable
    @SerialName("AccessHost")
    data class AccessHost(override val index: Int,
        override val actionType: String, val hostNames: List<String>) : AvailableActionDto()

    @Serializable
    @SerialName("DecryptAccess")
    data class DecryptAccess(override val index: Int,
        override val actionType: String, val hostNames: List<String>) : AvailableActionDto()

    @Serializable
    @SerialName("SelectLocateTarget")
    data class SelectLocateTarget(override val index: Int,
        override val actionType: String, val operation: String, val candidates: List<String>) : AvailableActionDto()

    @Serializable
    @SerialName("GracefulLogoff")
    data class GracefulLogoff(override val index: Int,
        override val actionType: String) : AvailableActionDto()

    @Serializable
    @SerialName("JackOut")
    data class JackOut(override val index: Int,
        override val actionType: String) : AvailableActionDto()

    @Serializable
    @SerialName("Operation")
    data class Operation(override val index: Int,
        override val actionType: String, val operation: String,
        val targetKind: String?, val targetName: String?,
        val paramKind: String? = null) : AvailableActionDto()
}

fun List<AvailableAction>.toDto(): List<AvailableActionDto> =
    mapIndexed { i, action -> action.toDto(i) }

fun AvailableAction.toDto(index: Int): AvailableActionDto = when (this) {
    is AvailableAction.LogonToRtg    -> AvailableActionDto.LogonToRtg(index, actionType = actionType.name, rtgName = rtg.name)
    is AvailableAction.AccessLtg     -> AvailableActionDto.AccessLtg(index, actionType = actionType.name, ltgNames = targets.map { it.name })
    is AvailableAction.AccessHost    -> AvailableActionDto.AccessHost(index, actionType = actionType.name, hostNames = targets.map { it.name })
    is AvailableAction.DecryptAccess -> AvailableActionDto.DecryptAccess(index, actionType = actionType.name, hostNames = targets.map { it.name })
    is AvailableAction.SelectLocateTarget -> AvailableActionDto.SelectLocateTarget(index, actionType = actionType.name, operation = operation.name, candidates = candidates)
    is AvailableAction.GracefulLogoff -> AvailableActionDto.GracefulLogoff(index, actionType = actionType.name)
    is AvailableAction.JackOut       -> AvailableActionDto.JackOut(index, actionType = actionType.name)
    is AvailableAction.Operation     -> AvailableActionDto.Operation(index, actionType = actionType.name,
        operation = operation.name,
        targetKind = target?.let { it::class.simpleName },
        targetName = target?.targetName(),
        paramKind = when (operation) {
            SystemOperation.LOCATE_FILE,
            SystemOperation.LOCATE_SLAVE,
            SystemOperation.LOCATE_ACCESS_NODE -> "query"
            SystemOperation.EDIT_FILE          -> "newContent"
            SystemOperation.UPLOAD_DATA        -> "dataSize"
            else                               -> null
        })
}

private fun com.shadowrun.matrix.operations.MatrixObject.targetName(): String = when (this) {
    is MatrixObject.GridNode      -> rtg.name
    is MatrixObject.LocalGrid     -> ltg.name
    is MatrixObject.PrivateGrid   -> pltg.name
    is MatrixObject.HostNode      -> host.name
    is MatrixObject.HostSubsystem -> node.subsystemType.name
    is MatrixObject.IcProgram     -> ic.name
    is MatrixObject.File          -> file.name
    is MatrixObject.Device        -> device.name
}
