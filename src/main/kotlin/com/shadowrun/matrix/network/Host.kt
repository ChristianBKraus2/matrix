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
    // Physically isolated: cannot be reached from the Matrix; requires on-site jackpoint access.
    val offline: Boolean = false,
    val securitySheaf: SecuritySheaf = SecuritySheaf(),
    val alertStatus: AlertStatus = AlertStatus.NO_ALERT,
    val securityTally: Int = 0,
    val resetTimeMinutes: Int? = null,
    // One SAN per connection point (to a grid or to another host)
    val sans: List<SAN> = emptyList(),
    // At least one Node per subsystem type; default to five empty nodes
    val nodes: List<Node> = SubsystemType.entries.map { Node(it) },
    val icPrograms: List<IC> = emptyList(),
    val dataFiles: List<DataFile> = emptyList(),
    val remoteDevices: List<RemoteDevice> = emptyList(),
    // Hosts directly linked to this host (tiered / host-host topologies)
    val connectedHosts: List<Host> = emptyList()
) {
    init {
        val coveredTypes = nodes.map { it.subsystemType }.toSet()
        require(coveredTypes == SubsystemType.entries.toSet()) {
            "Host must have at least one node per subsystem type"
        }
    }

    override fun equals(other: Any?) = other is Host && name == other.name
    override fun hashCode() = name.hashCode()
    override fun toString() = "Host(name=$name, security=${securityRating.code}(${securityRating.value}), alert=$alertStatus, tally=$securityTally)"
}
