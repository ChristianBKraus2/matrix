package com.shadowrun.matrix.decker

import com.shadowrun.matrix.accessories.Accessory
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility

/**
 * Creates a cyberdeck configured as a cyberterminal.
 *
 * Enforced constraints (PRD: CT-01 through CT-04):
 *  - MPCP may not exceed 4 (CT-01).
 *  - Response Increase is always 0 (CT-02).
 *  - immuneToDumpShock = true — user cannot be harmed by Black IC or dump shock (CT-04).
 *
 * The –1 program-rating modifier (CT-03) is applied at test resolution time by
 * SystemTestResolver.effectiveRating(), not stored here.
 *
 * Cost is typically 10% of an equivalent cyberdeck (CT-05) — a data concern, not enforced in code.
 */
fun Cyberterminal(
    name: String,
    mcpRating: Int,
    hardening: Int = 0,
    activeMemoryMp: Int,
    storageMemoryMp: Int,
    ioSpeedMpPerTurn: Int,
    costNuyen: Int,
    personaPrograms: List<PersonaProgram> = emptyList(),
    activeUtilities: List<Utility> = emptyList(),
    storedUtilities: List<Utility> = emptyList(),
    accessories: List<Accessory> = emptyList()
): Cyberdeck {
    require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01), got $mcpRating" }
    return Cyberdeck(
        name = name,
        mcpRating = mcpRating,
        hardening = hardening,
        activeMemoryMp = activeMemoryMp,
        storageMemoryMp = storageMemoryMp,
        ioSpeedMpPerTurn = ioSpeedMpPerTurn,
        responseIncrease = 0,
        costNuyen = costNuyen,
        personaPrograms = personaPrograms,
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities,
        accessories = accessories,
        immuneToDumpShock = true
    )
}
