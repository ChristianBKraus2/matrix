package com.shadowrun.matrix.operations

/**
 * Tracks an in-progress Upload Data operation.
 * [turnsRemaining] is decremented by the game engine each Combat Turn.
 * When it reaches 0 the transfer is complete; aborting early produces a corrupted upload.
 * PRD: SO-10 through SO-12
 */
data class UploadHandle(
    val description: String,
    val totalMp: Int,
    val ioSpeedMpPerTurn: Int,
    val turnsRemaining: Int,
    val active: Boolean = true
)
