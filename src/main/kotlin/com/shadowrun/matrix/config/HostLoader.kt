package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.ic.Blaster
import com.shadowrun.matrix.ic.Crippler
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.ic.NonLethalBlackIC
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.ic.Ripper
import com.shadowrun.matrix.ic.Scramble
import com.shadowrun.matrix.ic.Sparky
import com.shadowrun.matrix.ic.TarBaby
import com.shadowrun.matrix.ic.TarPit
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.network.SAN
import com.shadowrun.matrix.network.SecuritySheaf
import com.shadowrun.matrix.network.TriggerStep
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

private val logger = KotlinLogging.logger {}

object HostLoader {

    fun load(input: InputStream): Host {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        @Suppress("UNCHECKED_CAST")
        val data = yaml.load<Map<String, Any>>(input)
        return buildFromMap(data)
    }

    internal fun buildFromMap(data: Map<String, Any>): Host {
        val secRating = parseSecurityRating(data["security"] as String)
        val ratings = parseSubsystemRatings(data["ratings"])
        val difficulty = IntrusionDifficulty.valueOf(
            (data["intrusion_difficulty"] as? String ?: "AVERAGE").uppercase()
        )
        val topology = TopologyType.valueOf(
            (data["topology"] as? String ?: "OPEN_ACCESS").uppercase()
        )
        val offline = (data["offline"] as? Boolean) ?: false
        val resetTime = (data["reset_time_minutes"] as? Int)

        val nodes = buildNodes(data["nodes"])
        val nodesByType = nodes.groupBy { it.subsystemType }.also { grouped ->
            val dupes = grouped.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) logger.warn { "Host YAML has duplicate subsystem types — using first for each: $dupes" }
        }.mapValues { (_, v) -> v.first() }

        @Suppress("UNCHECKED_CAST")
        val sans = (data["sans"] as? List<Map<String, Any>> ?: emptyList()).map { buildSan(it) }

        @Suppress("UNCHECKED_CAST")
        val icPrograms = (data["ic_programs"] as? List<Map<String, Any>> ?: emptyList())
            .map { buildIc(it, nodesByType) }

        @Suppress("UNCHECKED_CAST")
        val dataFiles = (data["data_files"] as? List<Map<String, Any>> ?: emptyList())
            .map { buildDataFile(it) }

        @Suppress("UNCHECKED_CAST")
        val remoteDevices = (data["remote_devices"] as? List<Map<String, Any>> ?: emptyList())
            .map { buildRemoteDevice(it) }

        @Suppress("UNCHECKED_CAST")
        val securitySheaf = (data["security_sheaf"] as? Map<String, Any>)
            ?.let { buildSecuritySheaf(it, nodesByType) }
            ?: SecuritySheaf()

        return Host(
            name = data["name"] as String,
            securityRating = secRating,
            subsystemRatings = ratings,
            intrusionDifficulty = difficulty,
            topologyType = topology,
            offline = offline,
            resetTimeMinutes = resetTime,
            nodes = nodes,
            sans = sans,
            icPrograms = icPrograms,
            dataFiles = dataFiles,
            remoteDevices = remoteDevices,
            securitySheaf = securitySheaf
        )
    }

    private fun buildNodes(value: Any?): List<Node> {
        if (value == null) return SubsystemType.entries.map { Node(it) }
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is List<*> -> (value as List<Map<String, Any>>).map { entry ->
                Node(
                    subsystemType = SubsystemType.valueOf((entry["type"] as String).uppercase()),
                    description = (entry["description"] as? String) ?: ""
                )
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, String>
                SubsystemType.entries.map { type -> Node(type, map[type.name] ?: "") }
            }
            else -> error("Unsupported nodes format: ${value::class.simpleName}")
        }
    }

    private fun buildSan(data: Map<String, Any>): SAN =
        SAN(
            name = data["name"] as String,
            isScrambleProtected = (data["scramble_protected"] as? Boolean) ?: false
        )

    private fun buildDataFile(data: Map<String, Any>): DataFile =
        DataFile(
            name = data["name"] as String,
            isScrambleProtected = (data["scramble_protected"] as? Boolean) ?: false,
            sizeMp = (data["size_mp"] as? Int) ?: 0
        )

    private fun buildRemoteDevice(data: Map<String, Any>): RemoteDevice =
        RemoteDevice(
            name = data["name"] as String,
            systemAddress = data["system_address"] as String
        )

    private fun buildSecuritySheaf(data: Map<String, Any>, nodesByType: Map<SubsystemType, Node>): SecuritySheaf {
        @Suppress("UNCHECKED_CAST")
        val steps = (data["trigger_steps"] as? List<Map<String, Any>> ?: emptyList())
            .map { buildTriggerStep(it, nodesByType) }
        return SecuritySheaf(steps)
    }

    private fun buildTriggerStep(data: Map<String, Any>, nodesByType: Map<SubsystemType, Node>): TriggerStep {
        @Suppress("UNCHECKED_CAST")
        val activatedIc = (data["activated_ic"] as? List<Map<String, Any>> ?: emptyList())
            .map { buildIc(it, nodesByType) }
        val alertTransition = (data["alert_transition"] as? String)
            ?.let { AlertStatus.valueOf(it.uppercase()) }
        return TriggerStep(
            tallyThreshold = data["tally_threshold"] as Int,
            description = data["description"] as String,
            activatedIc = activatedIc,
            alertTransition = alertTransition,
            securityDeckerCount = (data["security_decker_count"] as? Int) ?: 0
        )
    }

    private fun buildIc(data: Map<String, Any>, nodesByType: Map<SubsystemType, Node>): IC {
        val rating = data["rating"] as Int
        val guardedNode = (data["guarded_node"] as? String)
            ?.let { nodesByType[SubsystemType.valueOf(it.uppercase())] }
        return when ((data["type"] as String).lowercase()) {
            "killer"           -> Killer(rating, guardedNode)
            "probe"            -> Probe(rating, guardedNode)
            "scramble"         -> Scramble(rating, guardedNode)
            "tarbaby"          -> TarBaby(
                rating = rating,
                targetCategory = (data["target_category"] as? String)
                    ?.let { com.shadowrun.matrix.common.UtilityCategory.valueOf(it.uppercase()) }
                    ?: com.shadowrun.matrix.common.UtilityCategory.OPERATIONAL,
                guardedNode = guardedNode
            )
            "blaster"          -> Blaster(rating, guardedNode)
            "sparky"           -> Sparky(rating, guardedNode)
            "tarpit"           -> TarPit(
                rating = rating,
                targetCategory = (data["target_category"] as? String)
                    ?.let { com.shadowrun.matrix.common.UtilityCategory.valueOf(it.uppercase()) }
                    ?: com.shadowrun.matrix.common.UtilityCategory.OPERATIONAL,
                guardedNode = guardedNode
            )
            "lethalblackic"    -> LethalBlackIC(rating, guardedNode)
            "nonlethalblackic" -> NonLethalBlackIC(rating, guardedNode)
            "crippler"         -> Crippler(
                rating,
                PersonaAttributeType.valueOf((data["target_attribute"] as String).uppercase()),
                guardedNode
            )
            "ripper"           -> Ripper(
                rating,
                PersonaAttributeType.valueOf((data["target_attribute"] as String).uppercase()),
                guardedNode
            )
            else -> error("Unknown IC type: ${data["type"]}")
        }
    }

    private fun parseSecurityRating(value: String): SecurityRating {
        val parts = value.split("-")
        require(parts.size == 2) { "Invalid security rating: $value" }
        val code = SecurityCode.valueOf(parts[0].uppercase())
        val sv = parts[1].toInt()
        return SecurityRating(code, sv)
    }

    private fun parseSubsystemRatings(value: Any?): SubsystemRatings {
        val map = ConfigUtils.parseSubsystemRatings(value)
        return SubsystemRatings(
            access  = map["access"]  ?: error("missing access rating"),
            control = map["control"] ?: error("missing control rating"),
            index   = map["index"]   ?: error("missing index rating"),
            files   = map["files"]   ?: error("missing files rating"),
            slave   = map["slave"]   ?: error("missing slave rating")
        )
    }
}
