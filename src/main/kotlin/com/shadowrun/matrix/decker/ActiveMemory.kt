package com.shadowrun.matrix.decker

import com.shadowrun.matrix.programs.Utility

data class PendingUpload(
    val utility: Utility,
    val turnsRemaining: Int
)

sealed class LoadUtilityResult {
    /** Utility accepted; now in pendingUploads with upload countdown running. */
    data class Success(val decker: Decker) : LoadUtilityResult()

    /** Insufficient active memory; decker state unchanged; no action economy spent. */
    data class InsufficientMemory(
        val decker: Decker,
        val requiredMp: Int,
        val availableMp: Int
    ) : LoadUtilityResult()
}
