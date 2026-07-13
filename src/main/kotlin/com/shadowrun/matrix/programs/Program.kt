package com.shadowrun.matrix.programs

abstract class Program(
    val name: String,
    val rating: Int,
    val multiplier: Int
) {
    val mpSize: Int get() = rating * rating * multiplier
}
