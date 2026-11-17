package com.bod3.app.android

import android.content.Context
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec

/** Loads the data packs bundled in `assets/datapacks`. A broken pack is skipped and reported, never fatal. */
object AssetDataPacks {

    private const val DIRECTORY = "datapacks"

    data class Loaded(val packs: List<DataPack>, val errors: List<String>)

    fun load(context: Context): Loaded {
        val packs = mutableListOf<DataPack>()
        val errors = mutableListOf<String>()
        val names = runCatching { context.assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".json") }.sorted() }
            .getOrElse { errors += "Bundled data packs: ${it.message}"; emptyList() }
        for (name in names) {
            val json = runCatching { context.assets.open("$DIRECTORY/$name").bufferedReader().use { it.readText() } }
                .getOrElse { errors += "$name: ${it.message}"; continue }
            val result = DataPackCodec.decode(json)
            val pack = result.pack
            if (pack != null) packs += pack else errors += result.errors.map { "$name: $it" }
        }
        return Loaded(packs, errors)
    }
}
