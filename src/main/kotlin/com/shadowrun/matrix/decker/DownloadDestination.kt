package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory

/**
 * Destination for a Download Data operation (ACC-01, operations.md).
 *
 * [OfflineStorage] routes the downloaded file to an external storage accessory rather than
 * consuming the deck's built-in Storage Memory. The accessory's [Accessory] instance must
 * be present in [Cyberdeck.accessories].
 */
sealed class DownloadDestination {
    object ActiveMemory : DownloadDestination()
    object StorageMemory : DownloadDestination()
    data class OfflineStorage(val accessory: Accessory) : DownloadDestination()
}
