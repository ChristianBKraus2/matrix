package com.shadowrun.matrix.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val MatrixJson = Json { encodeDefaults = true }

// Inbound decoder for client frames. Lenient about unknown keys so a newer client that adds a
// field does not break an older server (forward compatibility, S-9). Type mismatches and missing
// required fields still fail — and are reported as a generic bad_request (S-4).
val MatrixJsonIn = Json { ignoreUnknownKeys = true }

@Serializable
enum class SessionRole {
    @SerialName("observer") OBSERVER,
    @SerialName("registered_decker") REGISTERED_DECKER,
    @SerialName("active_controller") ACTIVE_CONTROLLER,
}

@Serializable
enum class ErrorCode {
    @SerialName("not_your_turn")       NOT_YOUR_TURN,
    @SerialName("no_action_pending")   NO_ACTION_PENDING,
    @SerialName("already_registered")  ALREADY_REGISTERED,
    @SerialName("name_already_taken")  NAME_ALREADY_TAKEN,
    @SerialName("name_too_long")       NAME_TOO_LONG,
    @SerialName("unknown_message_type") UNKNOWN_MESSAGE_TYPE,
    @SerialName("bad_request")         BAD_REQUEST,
    @SerialName("server_full")         SERVER_FULL,
    @SerialName("insufficient_hacking_pool") INSUFFICIENT_HACKING_POOL,
}

@Serializable
data class JoinMessage(
    val type: String = "join",
    val deckerName: String,
    val reconnectToken: String? = null
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
    val query: String? = null,
    val dataSize: Int? = null,
    val hackingPoolDice: Int? = null
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
    val deckerName: String? = null,
    val reconnectToken: String? = null
)

@Serializable
data class ErrorMessage(
    val type: String = "error",
    val message: ErrorCode,
    val details: String? = null
)

