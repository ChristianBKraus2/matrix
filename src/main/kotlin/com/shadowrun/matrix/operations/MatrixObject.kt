package com.shadowrun.matrix.operations

import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.RemoteDevice

sealed class MatrixObject {
    data class GridNode(val rtg: RTG) : MatrixObject()
    data class LocalGrid(val ltg: LTG) : MatrixObject()
    data class PrivateGrid(val pltg: PLTG) : MatrixObject()
    data class HostNode(val host: Host) : MatrixObject()
    data class HostSubsystem(val node: Node) : MatrixObject()
    data class IcProgram(val ic: IC) : MatrixObject()
    data class File(val file: DataFile) : MatrixObject()
    data class Device(val device: RemoteDevice) : MatrixObject()
}
