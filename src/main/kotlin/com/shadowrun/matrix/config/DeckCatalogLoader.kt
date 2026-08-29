package com.shadowrun.matrix.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

object DeckCatalogLoader {

    @Suppress("UNCHECKED_CAST")
    fun load(input: InputStream): List<DeckCatalogEntry> {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val data = yaml.load<Map<String, Any>>(input)
        @Suppress("UNCHECKED_CAST")
        val entries = (data["decks"] as? List<Map<String, Any>>) ?: error("missing 'decks' key in deck catalog YAML")
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
