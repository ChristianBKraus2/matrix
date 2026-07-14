package com.shadowrun.matrix.combat

import com.shadowrun.matrix.decker.Decker

data class TarBabyResult(
    val updatedDecker: Decker,
    val bothCrashed: Boolean,
    val deckerNoticed: Boolean
)
