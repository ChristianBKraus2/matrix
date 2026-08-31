package com.shadowrun.matrix.network

data class DataFile(
    val name: String,
    val isScrambleProtected: Boolean = false,
    /** Non-null when this file is a pointer to data on another host (distributed database). */
    val pointerToHost: Host? = null,
    /** The specific file on the target host; may be another pointer, forming a chain. */
    val pointerTargetFile: DataFile? = null,
    /** Size in megapulses; used to compute download time via I/O speed. */
    val sizeMp: Int = 0
) {
    val isPointer: Boolean get() = pointerToHost != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataFile) return false
        return name == other.name && isScrambleProtected == other.isScrambleProtected && sizeMp == other.sizeMp
    }
    override fun hashCode(): Int = 31 * (31 * name.hashCode() + isScrambleProtected.hashCode()) + sizeMp
    override fun toString() = "DataFile(name=$name, scramble=$isScrambleProtected, size=${sizeMp}Mp" + (if (isPointer) ", ->pointer" else "") + ")"
}
