package com.shadowrun.matrix.operations

import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host

/**
 * Represents the result of resolving a pointer-chain DataFile.
 * [links] is the ordered list of intermediate hosts to traverse (length = 1D6).
 * The decker must Logon and Locate File on each link before reaching [finalFile].
 * PRD: SO-03, SO-04
 */
data class PointerChain(
    val links: List<Host>,
    val finalFile: DataFile
)
