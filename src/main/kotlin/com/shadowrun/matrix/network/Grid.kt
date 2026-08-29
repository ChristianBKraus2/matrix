package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings

sealed class Grid(
    open val name: String,
    open val securityRating: SecurityRating,
    open val subsystemRatings: SubsystemRatings,
    open val securitySheaf: SecuritySheaf = SecuritySheaf(),
    open val securityTally: Int = 0,
    open val alertStatus: AlertStatus = AlertStatus.NO_ALERT
)

data class RTG(
    override val name: String,
    val region: String,
    override val securityRating: SecurityRating,
    override val subsystemRatings: SubsystemRatings,
    override val securitySheaf: SecuritySheaf = SecuritySheaf(),
    override val securityTally: Int = 0,
    override val alertStatus: AlertStatus = AlertStatus.NO_ALERT,
    val ltgs: List<LTG> = emptyList(),
    val connectedRtgs: List<RTG> = emptyList()
) : Grid(name, securityRating, subsystemRatings, securitySheaf, securityTally, alertStatus) {
    override fun equals(other: Any?) = other is RTG && name == other.name
    override fun hashCode() = name.hashCode()
    override fun toString() = "RTG(name=$name, region=$region, security=${securityRating.code}(${securityRating.value}), ltgs=${ltgs.size}, connectedRtgs=${connectedRtgs.size})"
}

data class LTG(
    override val name: String,
    val parentRtg: RTG,
    override val securityRating: SecurityRating,
    override val subsystemRatings: SubsystemRatings,
    override val securitySheaf: SecuritySheaf = SecuritySheaf(),
    override val securityTally: Int = 0,
    override val alertStatus: AlertStatus = AlertStatus.NO_ALERT,
    val hosts: List<Host> = emptyList(),
    val pltgs: List<PLTG> = emptyList(),
    val region: String = ""
) : Grid(name, securityRating, subsystemRatings, securitySheaf, securityTally, alertStatus) {
    override fun equals(other: Any?) = other is LTG && name == other.name
    override fun hashCode() = name.hashCode()
    override fun toString() = "LTG(name=$name, parentRtg=${parentRtg.name}, security=${securityRating.code}(${securityRating.value}))"
}

data class PLTG(
    override val name: String,
    val owner: String,
    val parentLtg: LTG,
    override val securityRating: SecurityRating,
    override val subsystemRatings: SubsystemRatings,
    override val securitySheaf: SecuritySheaf = SecuritySheaf(),
    override val securityTally: Int = 0,
    override val alertStatus: AlertStatus = AlertStatus.NO_ALERT,
    val hosts: List<Host> = emptyList()
) : Grid(name, securityRating, subsystemRatings, securitySheaf, securityTally, alertStatus) {
    override fun equals(other: Any?) = other is PLTG && name == other.name
    override fun hashCode() = name.hashCode()
    override fun toString() = "PLTG(name=$name, owner=$owner, parentLtg=${parentLtg.name})"
}
