package com.shadowrun.matrix.server.dto

import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.operations.MatrixObject
import kotlinx.serialization.Serializable

@Serializable
data class DeckerStateDto(
    val name: String,
    val location: String,
    val jackedIn: Boolean,
    val locationIndex: Int? = null,
    val isPinnedByBlackIc: Boolean,
    val physicalDamage: Int,
    val physicalMaxBoxes: Int,
    val mentalDamage: Int,
    val mentalMaxBoxes: Int,
    val hackingPool: Int,
    val remainingHackingPool: Int,
    val mcpRating: Int,
    val activeUtilities: List<UtilityDto>
)

@Serializable
data class UtilityDto(val type: String, val rating: Int)

fun Decker.toDto() = DeckerStateDto(
    name = name,
    location = currentLocation?.label() ?: "not jacked in",
    jackedIn = currentLocation != null,
    locationIndex = currentLocation?.let { loc ->
        visibleObjects().indexOf(loc.toMatrixObject()).takeIf { it >= 0 }
    },
    isPinnedByBlackIc = isPinnedByBlackIc,
    physicalDamage = physicalConditionMonitor.damage,
    physicalMaxBoxes = physicalConditionMonitor.maxBoxes,
    mentalDamage = mentalConditionMonitor.damage,
    mentalMaxBoxes = mentalConditionMonitor.maxBoxes,
    hackingPool = hackingPool,
    remainingHackingPool = remainingHackingPool,
    mcpRating = cyberdeck.mcpRating,
    activeUtilities = cyberdeck.activeUtilities.map { UtilityDto(it.type.name, it.currentRating) }
)

private fun MatrixLocation.label(): String = when (this) {
    is MatrixLocation.OnRTG  -> "RTG: ${rtg.name}"
    is MatrixLocation.OnLTG  -> "LTG: ${ltg.name}"
    is MatrixLocation.OnPLTG -> "PLTG: ${pltg.name}"
    is MatrixLocation.OnHost -> "Host: ${host.name}"
}

private fun MatrixLocation.toMatrixObject(): MatrixObject = when (this) {
    is MatrixLocation.OnRTG  -> MatrixObject.GridNode(rtg)
    is MatrixLocation.OnLTG  -> MatrixObject.LocalGrid(ltg)
    is MatrixLocation.OnPLTG -> MatrixObject.PrivateGrid(pltg)
    is MatrixLocation.OnHost -> MatrixObject.HostNode(host)
}
