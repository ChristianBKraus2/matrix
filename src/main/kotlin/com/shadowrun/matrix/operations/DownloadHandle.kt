package com.shadowrun.matrix.operations

import com.shadowrun.matrix.decker.DownloadDestination
import com.shadowrun.matrix.network.DataFile

/**
 * Tracks an in-progress Download Data operation.
 * [turnsRemaining] is decremented by the game engine each Combat Turn.
 * When it reaches 0 the transfer is complete; aborting early produces a corrupted copy.
 * PRD: SO-10 through SO-12
 */
data class DownloadHandle(
    val file: DataFile,
    val totalMp: Int,
    val ioSpeedMpPerTurn: Int,
    val turnsRemaining: Int,
    val active: Boolean = true,
    val destination: DownloadDestination = DownloadDestination.StorageMemory
)
