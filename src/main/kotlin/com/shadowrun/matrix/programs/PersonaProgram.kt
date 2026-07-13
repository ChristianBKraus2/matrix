package com.shadowrun.matrix.programs

import com.shadowrun.matrix.common.PersonaAttributeType

class PersonaProgram(
    val attributeType: PersonaAttributeType,
    rating: Int
) : Program(name = attributeType.name, rating = rating, multiplier = 1)
