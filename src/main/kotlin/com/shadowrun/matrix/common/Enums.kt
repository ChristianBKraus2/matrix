package com.shadowrun.matrix.common

enum class SecurityCode { BLUE, GREEN, ORANGE, RED }

enum class AlertStatus { NO_ALERT, PASSIVE_ALERT, ACTIVE_ALERT }

enum class SubsystemType { ACCESS, CONTROL, INDEX, FILES, SLAVE }

enum class DamageLevel { LIGHT, MODERATE, SERIOUS, DEADLY }

enum class ActionType { FREE, SIMPLE, COMPLEX }

enum class OperationCategory { STANDARD, INTERROGATION, ONGOING, MONITORED }

enum class PersonaAttributeType { BOD, EVASION, MASKING, SENSORS }

enum class UtilityCategory { OPERATIONAL, SPECIAL, OFFENSIVE, DEFENSIVE }

enum class IcBehavior { PROACTIVE, REACTIVE }

enum class PersonaStatus { LEGITIMATE, INTRUDING }

enum class IntrusionDifficulty { EASY, AVERAGE, HARD }

enum class TopologyType { OPEN_ACCESS, TIERED, HOST_HOST, PRIVATE_GRID }

enum class JackpointType {
    LEGAL_ACCESS, ILLEGAL_ACCESS, WORKSTATION, CONSOLE,
    REMOTE_DEVICE, TELECOM, ILLEGAL_JUNCTION_BOX
}

enum class AccessoryType { OFFLINE_STORAGE, VID_SCREEN, HITCHER_JACK }

enum class CombatManeuverType { EVADE_DETECTION, PARRY_ATTACK, POSITION_ATTACK }

val DamageLevel.boxes: Int get() = when (this) {
    DamageLevel.LIGHT    -> 1
    DamageLevel.MODERATE -> 3
    DamageLevel.SERIOUS  -> 6
    DamageLevel.DEADLY   -> 10
}
