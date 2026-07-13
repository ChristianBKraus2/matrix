package com.shadowrun.matrix.config

import com.shadowrun.matrix.network.Matrix

object GridInitializer {

    /** Load grid.yaml from the classpath and return a fully-populated Matrix. */
    fun initialize(): Matrix {
        val input = GridInitializer::class.java.classLoader
            .getResourceAsStream("grid.yaml")
            ?: error("grid.yaml not found on classpath")
        return input.use { GridLoader.load(it) }
    }
}
