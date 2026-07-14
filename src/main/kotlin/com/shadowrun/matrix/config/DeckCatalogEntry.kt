package com.shadowrun.matrix.config

data class DeckCatalogEntry(
    val model: String,
    val mpcp: Int,
    val hardening: Int,
    val activeMemoryMp: Int,
    val storageMemoryMp: Int,
    val ioSpeedMpPerTurn: Int,
    val costNuyen: Int
)
