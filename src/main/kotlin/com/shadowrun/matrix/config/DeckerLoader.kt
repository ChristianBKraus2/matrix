package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Cyberterminal
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

object DeckerLoader {

    private val logger = KotlinLogging.logger {}

    fun load(input: InputStream, catalog: List<DeckCatalogEntry> = emptyList()): Decker {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        @Suppress("UNCHECKED_CAST")
        val data = yaml.load<Map<String, Any>>(input)
        return buildDecker(data, catalog)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDecker(data: Map<String, Any>, catalog: List<DeckCatalogEntry>): Decker {
        val deckData = data["cyberdeck"] as Map<String, Any>
        val cyberdeck = buildCyberdeck(deckData, catalog)
        return Decker(
            name           = data["name"] as String,
            intelligence   = (data["intelligence"] as Int),
            body           = (data["body"] as Int),
            willpower      = (data["willpower"] as Int),
            reaction       = (data["reaction"] as Int),
            computerSkill  = (data["computer_skill"] as Int),
            cyberdeck      = cyberdeck
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildCyberdeck(data: Map<String, Any>, catalog: List<DeckCatalogEntry>): Cyberdeck {
        val modelName = data["model"] as? String
        val entry = modelName?.let { name ->
            catalog.firstOrNull { it.model == name }.also { found ->
                if (found == null && name.isNotBlank()) {
                    logger.warn { "DeckerLoader: model '$name' not found in catalog — using inline values" }
                }
            }
        }

        val ppData   = data["persona_programs"] as Map<String, Int>
        val utilData = (data["utilities"] as? List<Map<String, Any>>) ?: emptyList()

        val personaPrograms = buildPersonaPrograms(ppData)
        val utilities       = utilData.map { buildUtility(it) }

        // Build Map<UtilityType, Boolean> once — O(n) lookup, detects duplicate types
        val activeByType = mutableMapOf<UtilityType, Boolean>()
        for (m in utilData) {
            val typeName = (m["type"] as? String)?.uppercase()?.replace(' ', '_')?.replace('/', '_') ?: continue
            val type = UtilityType.valueOf(typeName)
            require(type !in activeByType) { "Duplicate utility type '$type' in decker YAML" }
            activeByType[type] = (m["active"] as? Boolean) ?: false
        }

        // Partition utilities by active flag; pre-loaded ones go into activeUtilities.
        val activeUtils = utilities.filter { u ->
            activeByType[u.type] ?: false
        }

        val resolvedName        = modelName ?: "Unknown"
        val resolvedMcp         = (data["mpcp"] as? Int) ?: entry?.mpcp ?: error("mpcp required")
        val resolvedHardening   = (data["hardening"] as? Int) ?: entry?.hardening ?: 0
        val resolvedActiveMem   = (data["active_memory"] as? Int) ?: entry?.activeMemoryMp ?: error("active_memory required")
        val resolvedStorageMem  = (data["storage_memory"] as? Int) ?: entry?.storageMemoryMp ?: error("storage_memory required")
        val resolvedIoSpeed     = (data["io_speed"] as? Int) ?: entry?.ioSpeedMpPerTurn ?: error("io_speed required")
        val resolvedCost        = (data["cost_nuyen"] as? Int) ?: entry?.costNuyen ?: 0

        // CD-01 load-time validation (creation.md). Note: the responseIncrease cap (CD-02) is
        // enforced separately by Cyberdeck.init.
        personaPrograms.forEach { pp ->
            require(pp.rating <= resolvedMcp) {
                "Persona program ${pp.name} rating ${pp.rating} exceeds MPCP $resolvedMcp (CD-01)"
            }
        }
        val personaRatingSum = personaPrograms.sumOf { it.rating }
        require(personaRatingSum <= resolvedMcp * 3) {
            "Sum of persona program ratings ($personaRatingSum) exceeds MPCP × 3 (${resolvedMcp * 3}) (CD-01)"
        }
        val totalUtilityMp = utilities.sumOf { it.mpSize }
        require(totalUtilityMp <= resolvedStorageMem) {
            "Total utility Mp ($totalUtilityMp) exceeds storage memory $resolvedStorageMem (CD-01)"
        }

        // type: cyberterminal → Cyberterminal factory (CT-01..CT-04); else a standard Cyberdeck.
        val type = (data["type"] as? String)?.trim()?.lowercase()
        if (type == "cyberterminal") {
            return Cyberterminal(
                name             = resolvedName,
                mcpRating        = resolvedMcp,
                hardening        = resolvedHardening,
                activeMemoryMp   = resolvedActiveMem,
                storageMemoryMp  = resolvedStorageMem,
                ioSpeedMpPerTurn = resolvedIoSpeed,
                costNuyen        = resolvedCost,
                personaPrograms  = personaPrograms,
                activeUtilities  = activeUtils,
                storedUtilities  = utilities  // all utilities live in storage
            )
        }

        return Cyberdeck(
            name             = resolvedName,
            mcpRating        = resolvedMcp,
            hardening        = resolvedHardening,
            activeMemoryMp   = resolvedActiveMem,
            storageMemoryMp  = resolvedStorageMem,
            ioSpeedMpPerTurn = resolvedIoSpeed,
            responseIncrease = (data["response_increase"] as? Int) ?: 0,
            costNuyen        = resolvedCost,
            personaPrograms  = personaPrograms,
            activeUtilities  = activeUtils,
            storedUtilities  = utilities  // all utilities live in storage
        )
    }

    private fun buildPersonaPrograms(data: Map<String, Int>): List<PersonaProgram> = listOf(
        PersonaProgram(PersonaAttributeType.BOD,     data["bod"]     ?: error("missing bod")),
        PersonaProgram(PersonaAttributeType.EVASION, data["evasion"] ?: error("missing evasion")),
        PersonaProgram(PersonaAttributeType.MASKING, data["masking"] ?: error("missing masking")),
        PersonaProgram(PersonaAttributeType.SENSORS, data["sensor"]  ?: error("missing sensor"))
    )

    @Suppress("UNCHECKED_CAST")
    private fun buildUtility(data: Map<String, Any>): Utility {
        val typeName = (data["type"] as String).uppercase().replace(' ', '_').replace('/', '_')
        val type     = UtilityType.valueOf(typeName)
        val rating   = data["rating"] as Int
        val dmgLevel = (data["damage_level"] as? String)?.let {
            DamageLevel.valueOf(it.uppercase())
        }
        val sourceCode = (data["source_code"] as? Boolean) ?: false
        return Utility(type, rating, dmgLevel, sourceCode = sourceCode)
    }
}
