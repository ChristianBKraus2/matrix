package com.shadowrun.matrix.network

data class DataFile(
    val name: String,
    val isScrambleProtected: Boolean = false,
    val pointerToHost: Host? = null
)
