package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.Matrix
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.SecuritySheaf
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

object GridLoader {

    fun load(input: InputStream): Matrix {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Map<String, Any>>(input)

        @Suppress("UNCHECKED_CAST")
        val rtgData = root["rtgs"] as List<Map<String, Any>>

        val rtgs = rtgData.map { buildRtg(it) }

        // Second pass: wire connectedRtgs by id reference
        val rtgById = rtgs.associateBy { it.name }
        @Suppress("UNCHECKED_CAST")
        val wiredRtgs = rtgs.zip(rtgData).map { (rtg, data) ->
            val ids = (data["connected_rtgs"] as? List<String>) ?: emptyList()
            val connected = ids.mapNotNull { rtgById[it] }
            if (connected.isEmpty()) rtg else {
                val wiredRtg = rtg.copy(connectedRtgs = connected)
                val wiredLtgs = wiredRtg.ltgs.map { it.copy(parentRtg = wiredRtg) }
                wiredRtg.copy(ltgs = wiredLtgs)
            }
        }
        return Matrix(wiredRtgs)
    }

    private fun buildRtg(data: Map<String, Any>): RTG {
        val secRating = parseSecurityRating(data["security"] as String)
        val ratings = parseSubsystemRatings(data["ratings"])

        // Build a placeholder RTG first so LTGs can reference it
        val placeholder = RTG(
            name = data["id"] as String,
            region = data["name"] as? String ?: data["id"] as String,
            securityRating = secRating,
            subsystemRatings = ratings
        )

        @Suppress("UNCHECKED_CAST")
        val ltgDataList = (data["ltgs"] as? List<Map<String, Any>>) ?: emptyList()
        val ltgs = ltgDataList.map { buildLtg(it, placeholder, secRating, ratings) }

        // PLTGs are attached to LTGs; build them separately, attached to their LTG
        @Suppress("UNCHECKED_CAST")
        val pltgDataList = (data["pltgs"] as? List<Map<String, Any>>) ?: emptyList()

        // If pltgs are at the RTG level, attach them to the first LTG (or create a synthetic one).
        // Per the model, PLTGs attach to LTGs. We attach RTG-level PLTGs to each LTG so deckers
        // on any of those LTGs can reach the PLTG.
        val ltgsWithPltgs = if (pltgDataList.isNotEmpty() && ltgs.isNotEmpty()) {
            val pltgsForFirstLtg = pltgDataList.map { buildPltg(it, ltgs.first()) }
            val updatedFirst = ltgs.first().copy(pltgs = pltgsForFirstLtg)
            listOf(updatedFirst) + ltgs.drop(1)
        } else {
            ltgs
        }

        val finalRtg = placeholder.copy(ltgs = ltgsWithPltgs)
        val fixedLtgs = ltgsWithPltgs.map { it.copy(parentRtg = finalRtg) }
        return finalRtg.copy(ltgs = fixedLtgs)
    }

    private fun buildLtg(
        data: Map<String, Any>,
        parentRtg: RTG,
        inheritedSecRating: SecurityRating,
        inheritedRatings: SubsystemRatings
    ): LTG {
        val secRating = (data["security"] as? String)?.let { parseSecurityRating(it) } ?: inheritedSecRating
        val ratings = data["ratings"]?.let { parseSubsystemRatings(it) } ?: inheritedRatings

        val placeholder = LTG(
            name = data["id"] as String,
            parentRtg = parentRtg,
            securityRating = secRating,
            subsystemRatings = ratings,
            region = data["region"] as? String ?: ""
        )

        @Suppress("UNCHECKED_CAST")
        val hostDataList = (data["hosts"] as? List<Map<String, Any>>) ?: emptyList()
        val hosts = hostDataList.map { buildHost(it) }

        @Suppress("UNCHECKED_CAST")
        val pltgDataList = (data["pltgs"] as? List<Map<String, Any>>) ?: emptyList()
        val pltgs = pltgDataList.map { buildPltg(it, placeholder) }

        val finalLtg = placeholder.copy(hosts = hosts, pltgs = pltgs)
        val fixedPltgs = pltgs.map { it.copy(parentLtg = finalLtg) }
        return finalLtg.copy(pltgs = fixedPltgs)
    }

    private fun buildPltg(data: Map<String, Any>, parentLtg: LTG): PLTG {
        val secRating = parseSecurityRating(data["security"] as String)
        val ratings = parseSubsystemRatings(data["ratings"])

        @Suppress("UNCHECKED_CAST")
        val hostDataList = (data["hosts"] as? List<Map<String, Any>>) ?: emptyList()
        val hosts = hostDataList.map { buildHost(it) }

        return PLTG(
            name = data["name"] as? String ?: data["id"] as String,
            owner = data["owner"] as String,
            parentLtg = parentLtg,
            securityRating = secRating,
            subsystemRatings = ratings,
            hosts = hosts
        )
    }

    private fun buildHost(data: Map<String, Any>): Host {
        val secRating = parseSecurityRating(data["security"] as String)
        val ratings = parseSubsystemRatings(data["ratings"])
        val difficulty = IntrusionDifficulty.valueOf(
            (data["intrusion_difficulty"] as? String ?: "AVERAGE").uppercase()
        )
        val topology = TopologyType.valueOf(
            (data["topology"] as? String ?: "OPEN_ACCESS").uppercase()
        )
        val offline = (data["offline"] as? Boolean) ?: false
        return Host(
            name = data["name"] as String,
            securityRating = secRating,
            subsystemRatings = ratings,
            intrusionDifficulty = difficulty,
            topologyType = topology,
            offline = offline,
            securitySheaf = SecuritySheaf()
        )
    }

    private fun parseSecurityRating(value: String): SecurityRating {
        val parts = value.split("-")
        require(parts.size == 2) { "Invalid security rating: $value" }
        val code = SecurityCode.valueOf(parts[0].uppercase())
        val sv = parts[1].toInt()
        return SecurityRating(code, sv)
    }

    private fun parseSubsystemRatings(value: Any?): SubsystemRatings {
        @Suppress("UNCHECKED_CAST")
        val map = value as Map<String, Int>
        return SubsystemRatings(
            access  = map["access"]  ?: error("missing access rating"),
            control = map["control"] ?: error("missing control rating"),
            index   = map["index"]   ?: error("missing index rating"),
            files   = map["files"]   ?: error("missing files rating"),
            slave   = map["slave"]   ?: error("missing slave rating")
        )
    }
}
