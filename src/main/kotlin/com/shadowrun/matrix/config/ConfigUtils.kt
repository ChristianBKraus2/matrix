package com.shadowrun.matrix.config

internal object ConfigUtils {
    @Suppress("UNCHECKED_CAST")
    fun parseSubsystemRatings(value: Any?): Map<String, Int> {
        requireNotNull(value) { "subsystem ratings map is required but was null" }
        return value as Map<String, Int>
    }
}
