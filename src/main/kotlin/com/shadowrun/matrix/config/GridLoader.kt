package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.Matrix
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

object GridLoader {

    fun load(input: InputStream): Matrix {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Map<String, Any>>(input)

        @Suppress("UNCHECKED_CAST")
        val rtgData = root["rtgs"] as List<Map<String, Any>>

        val rtgs = rtgData.map { buildRtg(it) }

        // Second pass: wire connectedRtgs by id reference
        val rtgById = rtgs.associateBy { it.name }
        require(rtgs.size == rtgById.size) { "Duplicate RTG IDs in grid.yaml: ${rtgs.map { it.name }.groupBy { it }.filter { it.value.size > 1 }.keys}" }
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

        // Third pass: wire Host.connectedHosts by name (source host declares connected_hosts in YAML).
        val hostConnectionSpec = collectHostConnectionSpec(rtgData)
        val afterHostWiring = if (hostConnectionSpec.isEmpty()) Matrix(wiredRtgs)
            else wireHostConnections(Matrix(wiredRtgs), hostConnectionSpec)

        // Fourth pass: resolve DataFile pointer fields (pointer_to_host / pointer_target_file).
        val dataFilePointerSpec = collectDataFilePointerSpec(rtgData)
        return if (dataFilePointerSpec.isEmpty()) afterHostWiring
            else wireDataFilePointers(afterHostWiring, dataFilePointerSpec)
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

        // RTG-level PLTGs are attached to every LTG of that RTG so deckers on any LTG can reach them.
        val ltgsWithPltgs = if (pltgDataList.isNotEmpty() && ltgs.isNotEmpty()) {
            ltgs.map { ltg ->
                val pltgsForLtg = pltgDataList.map { buildPltg(it, ltg) }
                ltg.copy(pltgs = ltg.pltgs + pltgsForLtg)
            }
        } else {
            ltgs
        }

        val finalRtg = placeholder.copy(ltgs = ltgsWithPltgs)
        val fixedLtgs = ltgsWithPltgs.map { it.copy(parentRtg = finalRtg) }
        val trulyFinalRtg = finalRtg.copy(ltgs = fixedLtgs)
        val rewiredLtgs = fixedLtgs.map { it.copy(parentRtg = trulyFinalRtg) }
        return trulyFinalRtg.copy(ltgs = rewiredLtgs)
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
        val secRating = (data["security"] as? String)?.let { parseSecurityRating(it) } ?: parentLtg.securityRating
        val ratings = data["ratings"]?.let { parseSubsystemRatings(it) } ?: parentLtg.subsystemRatings

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
        val configPath = data["config"] as? String
        if (configPath != null) {
            val stream = GridLoader::class.java.classLoader.getResourceAsStream(configPath)
                ?: error("Host config not found on classpath: $configPath")
            return HostLoader.load(stream)
        }
        return HostLoader.buildFromMap(data)
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

    // ─── Host connection wiring (D7C-2) ──────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun collectHostConnectionSpec(rtgData: List<Map<String, Any>>): Map<String, List<String>> {
        val spec = mutableMapOf<String, List<String>>()
        fun scanHosts(hosts: Any?) {
            for (hd in ((hosts as? List<*>) ?: return).filterIsInstance<Map<String, Any>>()) {
                val name = hd["name"] as? String ?: continue
                val connected = (hd["connected_hosts"] as? List<*>)?.filterIsInstance<String>() ?: continue
                if (connected.isNotEmpty()) spec[name] = connected
            }
        }
        for (rtg in rtgData) {
            for (ltg in ((rtg["ltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                scanHosts(ltg["hosts"])
                for (pltg in ((ltg["pltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                    scanHosts(pltg["hosts"])
                }
            }
            for (pltg in ((rtg["pltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                scanHosts(pltg["hosts"])
            }
        }
        return spec
    }

    private fun wireHostConnections(matrix: Matrix, spec: Map<String, List<String>>): Matrix {
        val allHosts = matrix.rtgs.flatMap { rtg ->
            rtg.ltgs.flatMap { ltg -> ltg.hosts + ltg.pltgs.flatMap { it.hosts } }
        }.associateBy { it.name }

        fun Host.wired(): Host {
            val links = spec[name]?.mapNotNull { allHosts[it] }?.takeIf { it.isNotEmpty() } ?: return this
            return copy(connectedHosts = links)
        }

        val newRtgs = matrix.rtgs.map { rtg ->
            val ltgsWired = rtg.ltgs.map { ltg ->
                val newLtg = ltg.copy(
                    hosts  = ltg.hosts.map { it.wired() },
                    pltgs  = ltg.pltgs.map { pltg -> pltg.copy(hosts = pltg.hosts.map { it.wired() }) }
                )
                newLtg.copy(pltgs = newLtg.pltgs.map { it.copy(parentLtg = newLtg) })
            }
            val newRtg = rtg.copy(ltgs = ltgsWired)
            newRtg.copy(ltgs = newRtg.ltgs.map { it.copy(parentRtg = newRtg) })
        }
        return Matrix(newRtgs)
    }

    // ─── DataFile pointer wiring (D7C-6) ─────────────────────────────────────
    // Only inline hosts (no `config:` key) declare pointer_to_host in grid.yaml.
    // Config-file hosts that need pointer DataFiles should be extended via HostLoader separately.

    private data class DataFilePointerEntry(
        val hostName: String, val fileName: String,
        val targetHostName: String, val targetFileName: String?
    )

    @Suppress("UNCHECKED_CAST")
    private fun collectDataFilePointerSpec(rtgData: List<Map<String, Any>>): List<DataFilePointerEntry> {
        val spec = mutableListOf<DataFilePointerEntry>()
        fun scanHosts(hosts: Any?) {
            for (hd in ((hosts as? List<*>) ?: return).filterIsInstance<Map<String, Any>>()) {
                if (hd.containsKey("config")) continue  // config-file hosts: pointers live in the host YAML
                val hostName = hd["name"] as? String ?: continue
                for (fd in ((hd["data_files"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                    val fileName    = fd["name"] as? String ?: continue
                    val targetHost  = fd["pointer_to_host"] as? String ?: continue
                    val targetFile  = fd["pointer_target_file"] as? String
                    spec += DataFilePointerEntry(hostName, fileName, targetHost, targetFile)
                }
            }
        }
        for (rtg in rtgData) {
            for (ltg in ((rtg["ltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                scanHosts(ltg["hosts"])
                for (pltg in ((ltg["pltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                    scanHosts(pltg["hosts"])
                }
            }
            for (pltg in ((rtg["pltgs"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<Map<String, Any>>()) {
                scanHosts(pltg["hosts"])
            }
        }
        return spec
    }

    private fun wireDataFilePointers(matrix: Matrix, spec: List<DataFilePointerEntry>): Matrix {
        val allHosts = matrix.rtgs.flatMap { rtg ->
            rtg.ltgs.flatMap { ltg -> ltg.hosts + ltg.pltgs.flatMap { it.hosts } }
        }.associateBy { it.name }

        val updatedFiles = mutableMapOf<Pair<String, String>, DataFile>()
        for (entry in spec) {
            val targetHost = allHosts[entry.targetHostName] ?: continue
            val sourceHost = allHosts[entry.hostName] ?: continue
            val sourceFile = sourceHost.dataFiles.firstOrNull { it.name == entry.fileName } ?: continue
            val targetFile = entry.targetFileName?.let { n -> targetHost.dataFiles.firstOrNull { it.name == n } }
            updatedFiles[entry.hostName to entry.fileName] = sourceFile.copy(pointerToHost = targetHost, pointerTargetFile = targetFile)
        }

        fun Host.withWiredDataFiles(): Host {
            val updated = dataFiles.map { df -> updatedFiles[name to df.name] ?: df }
            return if (updated == dataFiles) this else copy(dataFiles = updated)
        }

        val newRtgs = matrix.rtgs.map { rtg ->
            val ltgsWired = rtg.ltgs.map { ltg ->
                val newLtg = ltg.copy(
                    hosts  = ltg.hosts.map { it.withWiredDataFiles() },
                    pltgs  = ltg.pltgs.map { pltg -> pltg.copy(hosts = pltg.hosts.map { it.withWiredDataFiles() }) }
                )
                newLtg.copy(pltgs = newLtg.pltgs.map { it.copy(parentLtg = newLtg) })
            }
            val newRtg = rtg.copy(ltgs = ltgsWired)
            newRtg.copy(ltgs = newRtg.ltgs.map { it.copy(parentRtg = newRtg) })
        }
        return Matrix(newRtgs)
    }
}
