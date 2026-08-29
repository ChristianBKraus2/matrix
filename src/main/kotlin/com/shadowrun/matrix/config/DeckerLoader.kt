package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.decker.Cyberdeck
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
            deckingSpecialization = (data["decking_specialization"] as? Boolean) ?: false,
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
        val (activeUtils, storedOnly) = utilities.partition { u ->
            activeByType[u.type] ?: false
        }

        return Cyberdeck(
            name             = modelName ?: "Unknown",
            mcpRating        = (data["mpcp"] as? Int) ?: entry?.mpcp ?: error("mpcp required"),
            hardening        = (data["hardening"] as? Int) ?: entry?.hardening ?: 0,
            activeMemoryMp   = (data["active_memory"] as? Int) ?: entry?.activeMemoryMp ?: error("active_memory required"),
            storageMemoryMp  = (data["storage_memory"] as? Int) ?: entry?.storageMemoryMp ?: error("storage_memory required"),
            ioSpeedMpPerTurn = (data["io_speed"] as? Int) ?: entry?.ioSpeedMpPerTurn ?: error("io_speed required"),
            responseIncrease = (data["response_increase"] as? Int) ?: 0,
            costNuyen        = (data["cost_nuyen"] as? Int) ?: entry?.costNuyen ?: 0,
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
