package com.shadowrun.matrix.game

import com.shadowrun.matrix.combat.DefenderParticipant
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.MatrixLocation

fun Decker.asDefenderParticipant(): DefenderParticipant {
    val p = requireNotNull(persona) { "asDefenderParticipant: decker has no persona" }
    val loc = requireNotNull(currentLocation as? MatrixLocation.OnHost) { "asDefenderParticipant: decker not OnHost" }
    return DefenderParticipant(
        bod = p.bod,
        armorCurrentRating = 0,
        personaStatus = p.status,
        securityCode = loc.host.securityRating.code
    )
}
