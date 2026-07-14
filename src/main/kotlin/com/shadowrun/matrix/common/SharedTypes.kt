package com.shadowrun.matrix.common

data class SecurityRating(val code: SecurityCode, val value: Int)

data class SubsystemRatings(
    val access: Int,
    val control: Int,
    val index: Int,
    val files: Int,
    val slave: Int
) {
    fun get(type: SubsystemType): Int = when (type) {
        SubsystemType.ACCESS  -> access
        SubsystemType.CONTROL -> control
        SubsystemType.INDEX   -> index
        SubsystemType.FILES   -> files
        SubsystemType.SLAVE   -> slave
    }
}

data class ConditionMonitor(val maxBoxes: Int = 10, val damage: Int = 0) {
    val remaining: Int get() = maxBoxes - damage
    val isDestroyed: Boolean get() = damage >= maxBoxes
    val isCrashed: Boolean get() = isDestroyed

    fun applyDamage(boxes: Int): ConditionMonitor = copy(damage = (damage + boxes).coerceAtMost(maxBoxes))
    fun applyDamage(level: DamageLevel): ConditionMonitor = applyDamage(level.boxes)
}
