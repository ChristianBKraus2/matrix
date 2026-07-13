package com.shadowrun.matrix.config

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

object DeckerLoader {

    fun load(input: InputStream): Decker {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val data = yaml.load<Map<String, Any>>(input)
        return buildDecker(data)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDecker(data: Map<String, Any>): Decker {
        val deckData = data["cyberdeck"] as Map<String, Any>
        val cyberdeck = buildCyberdeck(deckData)
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
    private fun buildCyberdeck(data: Map<String, Any>): Cyberdeck {
        val ppData   = data["persona_programs"] as Map<String, Int>
        val utilData = (data["utilities"] as? List<Map<String, Any>>) ?: emptyList()

        val personaPrograms = buildPersonaPrograms(ppData)
        val utilities       = utilData.map { buildUtility(it) }

        return Cyberdeck(
            name              = (data["model"] as? String) ?: "Unknown",
            mcpRating         = data["mpcp"] as Int,
            hardening         = (data["hardening"] as? Int) ?: 0,
            activeMemoryMp    = data["active_memory"] as Int,
            storageMemoryMp   = data["storage_memory"] as Int,
            ioSpeedMpPerTurn  = data["io_speed"] as Int,
            responseIncrease  = (data["response_increase"] as? Int) ?: 0,
            costNuyen         = (data["cost_nuyen"] as? Int) ?: 0,
            personaPrograms   = personaPrograms,
            storedUtilities   = utilities
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
        return Utility(type, rating, dmgLevel)
    }
}
