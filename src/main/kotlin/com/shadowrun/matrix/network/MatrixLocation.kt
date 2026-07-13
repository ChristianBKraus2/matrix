package com.shadowrun.matrix.network

sealed class MatrixLocation {
    data class OnLTG(val ltg: LTG) : MatrixLocation()
    data class OnRTG(val rtg: RTG) : MatrixLocation()
    data class OnPLTG(val pltg: PLTG) : MatrixLocation()
    data class OnHost(val host: Host) : MatrixLocation()
}
