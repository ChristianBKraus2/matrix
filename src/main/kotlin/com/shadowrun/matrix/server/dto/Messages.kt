package com.shadowrun.matrix.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val MatrixJson = Json { encodeDefaults = true }

@Serializable
enum class SessionRole {
    @SerialName("observer") OBSERVER,
    @SerialName("registered_decker") REGISTERED_DECKER,
    @SerialName("active_controller") ACTIVE_CONTROLLER,
}

@Serializable
data class JoinMessage(
    val type: String = "join",
    val deckerName: String
)

@Serializable
data class StateMessage(
    val type: String = "state",
    val role: SessionRole,
    val decker: DeckerStateDto,
    val visibleObjects: List<MatrixObjectDto>,
    val availableActions: List<AvailableActionDto>
)

@Serializable
data class ActionCommand(
    val type: String = "action",
    val actionIndex: Int,
    val params: ActionParams? = null
)

@Serializable
data class ActionParams(
    val newContent: String? = null,
    val inactivitySeconds: Int? = null,
    val precision: String? = null,
    val hasValidPasscode: Boolean? = null,
    val scannerDeviceRating: Int? = null
)

@Serializable
data class ResultMessage(
    val type: String = "result",
    val success: Boolean,
    val deckerSuccesses: Int,
    val hostSuccesses: Int,
    val details: String
)

@Serializable
data class ControlMessage(
    val type: String = "control",
    val role: SessionRole,
    val deckerName: String? = null
)

@Serializable
data class ErrorMessage(
    val type: String = "error",
    val message: String
)

