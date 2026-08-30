package com.shadowrun.matrix.accessories

sealed class Accessory {
    data class OfflineStorage(val capacityMp: Int) : Accessory()
    object VidScreen : Accessory()
    data class HitcherJack(val type: HitcherJackType) : Accessory()
}

enum class HitcherJackType { ELECTRODE_NET, DATAJACK_FEED }
