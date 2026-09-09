package com.shadowrun.matrix.server.dto

import com.shadowrun.matrix.operations.MatrixObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Five Kotlin enum types in this hierarchy are serialised as raw [Enum.name] strings (not @SerialName).
 * When changing any of these enums, update the corresponding union types in frontend/src/types/messages.ts:
 *   AlertStatus, SecurityCode, TopologyType, SubsystemType, IcBehavior (behavior field on IcProgram)
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed class MatrixObjectDto {
    abstract val index: Int

    @Serializable
    @SerialName("GridNode")
    data class GridNode(
        override val index: Int,
        val name: String,
        val region: String,
        val alertStatus: String,
        val securityCode: String?,
        val securityValue: Int?,
        val ltgCount: Int,
        val connectedRtgCount: Int
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("LocalGrid")
    data class LocalGrid(
        override val index: Int,
        val name: String,
        val parentRtgName: String,
        val alertStatus: String,
        val securityValue: Int?,
        val hostCount: Int,
        val pltgCount: Int
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("PrivateGrid")
    data class PrivateGrid(
        override val index: Int,
        val name: String,
        val owner: String,
        val parentLtgName: String,
        val alertStatus: String,
        val securityCode: String?,
        val securityValue: Int?,
        val hostCount: Int
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("HostNode")
    data class HostNode(
        override val index: Int,
        val name: String,
        val topologyType: String,
        val offline: Boolean,
        val alertStatus: String,
        val securityCode: String?,
        val securityValue: Int?
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("HostSubsystem")
    data class HostSubsystem(
        override val index: Int,
        val subsystemType: String,
        val description: String
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("IcProgram")
    data class IcProgram(
        override val index: Int,
        val name: String,
        val analyzed: Boolean,
        val rating: Int?,
        val behavior: String?,
        val guardedNodeType: String?
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("File")
    data class File(
        override val index: Int,
        val name: String,
        val isScrambleProtected: Boolean,
        val isPointer: Boolean,
        val sizeMp: Int,
        val scrambled: Boolean
    ) : MatrixObjectDto()

    @Serializable
    @SerialName("Device")
    data class Device(
        override val index: Int,
        val name: String,
        val systemAddress: String
    ) : MatrixObjectDto()
}

fun List<MatrixObject>.toDto(analyzeSecuritySystems: Set<String> = emptySet()): List<MatrixObjectDto> =
    mapIndexed { i, obj -> obj.toDto(i, analyzeSecuritySystems) }

fun MatrixObject.toDto(index: Int, analyzeSecuritySystems: Set<String> = emptySet()): MatrixObjectDto = when (this) {
    is MatrixObject.GridNode -> {
        val revealed = rtg.name in analyzeSecuritySystems
        MatrixObjectDto.GridNode(index, name = rtg.name, region = rtg.region,
            alertStatus = rtg.alertStatus.name,
            securityCode = if (revealed) rtg.securityRating.code.name else null,
            securityValue = if (revealed) rtg.securityRating.value else null,
            ltgCount = rtg.ltgs.size, connectedRtgCount = rtg.connectedRtgs.size)
    }
    is MatrixObject.LocalGrid -> {
        val revealed = ltg.name in analyzeSecuritySystems
        MatrixObjectDto.LocalGrid(index, name = ltg.name, parentRtgName = ltg.parentRtg.name,
            alertStatus = ltg.alertStatus.name,
            securityValue = if (revealed) ltg.securityRating.value else null,
            hostCount = ltg.hosts.size, pltgCount = ltg.pltgs.size)
    }
    is MatrixObject.PrivateGrid -> {
        val revealed = pltg.name in analyzeSecuritySystems
        MatrixObjectDto.PrivateGrid(index, name = pltg.name, owner = pltg.owner,
            parentLtgName = pltg.parentLtg.name, alertStatus = pltg.alertStatus.name,
            securityCode = if (revealed) pltg.securityRating.code.name else null,
            securityValue = if (revealed) pltg.securityRating.value else null,
            hostCount = pltg.hosts.size)
    }
    is MatrixObject.HostNode -> {
        val revealed = host.name in analyzeSecuritySystems
        MatrixObjectDto.HostNode(index, name = host.name, topologyType = host.topologyType.name,
            offline = host.offline, alertStatus = host.alertStatus.name,
            securityCode = if (revealed) host.securityRating.code.name else null,
            securityValue = if (revealed) host.securityRating.value else null)
    }
    is MatrixObject.HostSubsystem ->
        MatrixObjectDto.HostSubsystem(index, subsystemType = node.subsystemType.name, description = node.description)
    is MatrixObject.IcProgram ->
        MatrixObjectDto.IcProgram(index, name = ic.name, analyzed = analyzed,
            rating = if (analyzed) ic.rating else null,
            behavior = if (analyzed) ic.behavior.name else null,
            guardedNodeType = if (analyzed) ic.guardedNode?.subsystemType?.name else null)
    is MatrixObject.File ->
        MatrixObjectDto.File(index, name = file.name, isScrambleProtected = file.isScrambleProtected,
            isPointer = file.isPointer, sizeMp = file.sizeMp, scrambled = file.scrambled)
    is MatrixObject.Device ->
        MatrixObjectDto.Device(index, name = device.name, systemAddress = device.systemAddress)
}
