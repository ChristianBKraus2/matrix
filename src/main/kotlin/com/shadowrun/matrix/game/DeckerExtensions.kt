package com.shadowrun.matrix.game

import com.shadowrun.matrix.combat.DefenderParticipant
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.programs.UtilityType

fun Decker.asDefenderParticipant(): DefenderParticipant {
    val p = requireNotNull(persona) { "asDefenderParticipant: decker has no persona" }
    val loc = requireNotNull(currentLocation as? MatrixLocation.OnHost) { "asDefenderParticipant: decker not OnHost" }
    val armorRating = cyberdeck.activeUtilities
        .firstOrNull { it.type == UtilityType.ARMOR }?.currentRating ?: 0
    return DefenderParticipant(
        bod = p.bod,
        armorCurrentRating = armorRating,
        personaStatus = p.status,
        securityCode = loc.host.securityRating.code
    )
}
