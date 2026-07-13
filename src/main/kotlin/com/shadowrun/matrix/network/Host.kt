package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.ic.IC

data class Host(
    val name: String,
    val securityRating: SecurityRating,
    val subsystemRatings: SubsystemRatings,
    val intrusionDifficulty: IntrusionDifficulty,
    val topologyType: TopologyType,
    val securitySheaf: SecuritySheaf = SecuritySheaf(),
    val alertStatus: AlertStatus = AlertStatus.NO_ALERT,
    val securityTally: Int = 0,
    val resetTimeMinutes: Int? = null,
    // One SAN per connection point (to a grid or to another host)
    val sans: List<SAN> = emptyList(),
    // Exactly one Node per subsystem type; default to five empty nodes
    val nodes: List<Node> = SubsystemType.entries.map { Node(it) },
    val icPrograms: List<IC> = emptyList(),
    val dataFiles: List<DataFile> = emptyList(),
    val remoteDevices: List<RemoteDevice> = emptyList(),
    // Hosts directly linked to this host (tiered / host-host topologies)
    val connectedHosts: List<Host> = emptyList()
) {
    init {
        require(nodes.map { it.subsystemType }.toSet().size == 5) {
            "Host must have exactly one node per subsystem type"
        }
    }
}
