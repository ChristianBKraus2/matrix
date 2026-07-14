package com.shadowrun.matrix.network

data class DataFile(
    val name: String,
    val isScrambleProtected: Boolean = false,
    /** Non-null when this file is a pointer to data on another host (distributed database). */
    val pointerToHost: Host? = null,
    /** Size in megapulses; used to compute download time via I/O speed. */
    val sizeMp: Int = 0
) {
    val isPointer: Boolean get() = pointerToHost != null
}
