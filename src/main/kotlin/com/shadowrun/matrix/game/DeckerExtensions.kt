package com.shadowrun.matrix.game

import com.shadowrun.matrix.combat.DefenderParticipant
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.network.MatrixLocation

fun Decker.asDefenderParticipant(): DefenderParticipant = DefenderParticipant(
    bod = persona!!.bod,
    armorCurrentRating = 0,
    personaStatus = persona.status,
    securityCode = (currentLocation as MatrixLocation.OnHost).host.securityRating.code
)
