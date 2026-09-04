package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory

/** Where a completed download is written. PRD: ACC-01 */
sealed class DownloadDestination {
    data object ActiveMemory : DownloadDestination()
    data object StorageMemory : DownloadDestination()
    data class OfflineStorage(val accessory: Accessory.OfflineStorage) : DownloadDestination()
}
