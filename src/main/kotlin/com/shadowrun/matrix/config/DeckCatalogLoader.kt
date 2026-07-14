package com.shadowrun.matrix.config

import org.yaml.snakeyaml.Yaml
import java.io.InputStream

object DeckCatalogLoader {

    @Suppress("UNCHECKED_CAST")
    fun load(input: InputStream): List<DeckCatalogEntry> {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(input)
        val entries = data["decks"] as List<Map<String, Any>>
        return entries.map { buildEntry(it) }
    }

    private fun buildEntry(data: Map<String, Any>) = DeckCatalogEntry(
        model            = data["model"] as String,
        mpcp             = data["mpcp"] as Int,
        hardening        = (data["hardening"] as? Int) ?: 0,
        activeMemoryMp   = data["active_memory"] as Int,
        storageMemoryMp  = data["storage_memory"] as Int,
        ioSpeedMpPerTurn = data["io_speed"] as Int,
        costNuyen        = (data["cost_nuyen"] as? Int) ?: 0
    )
}
