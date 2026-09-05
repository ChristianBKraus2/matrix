package com.shadowrun.matrix.network

data class DataFile(
    val name: String,
    val isScrambleProtected: Boolean = false,
    /** Non-null when this file is a pointer to data on another host (distributed database). */
    val pointerToHost: Host? = null,
    /** The specific file on the target host; may be another pointer, forming a chain. */
    val pointerTargetFile: DataFile? = null,
    /** Size in megapulses; used to compute download time via I/O speed. */
    val sizeMp: Int = 0,
    /** True when Scramble IC has fired and corrupted this file; the file remains present but is unusable. */
    val scrambled: Boolean = false
) {
    val isPointer: Boolean get() = pointerToHost != null

    // pointerToHost/pointerTargetFile are excluded from equals/hashCode to avoid cyclic-reference
    // stack overflows (Host embeds DataFile which would embed Host). Two pointer files with the same
    // name and size compare equal regardless of their target; callers relying on pointer identity
    // must compare by reference (===) rather than by value.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataFile) return false
        return name == other.name && isScrambleProtected == other.isScrambleProtected && sizeMp == other.sizeMp
    }
    override fun hashCode(): Int = 31 * (31 * name.hashCode() + isScrambleProtected.hashCode()) + sizeMp
    override fun toString() = "DataFile(name=$name, scramble=$isScrambleProtected, size=${sizeMp}Mp" + (if (scrambled) ", SCRAMBLED" else "") + (if (isPointer) ", ->pointer" else "") + ")"
}
