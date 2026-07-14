package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.decker.Decker

data class CripplerResult(
    val updatedDecker: Decker,
    val targetAttribute: PersonaAttributeType,
    val reduction: Int
)
