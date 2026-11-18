package com.bod3.app.android

import android.content.Context
import com.bod3.app.coding.CodingPack
import com.bod3.app.coding.CodingRecipeCodec

/** Loads the coding recipe packs bundled in `assets/coding`. A broken pack is skipped and reported. */
object AssetCodingPacks {

    private const val DIRECTORY = "coding"

    data class Loaded(val packs: List<CodingPack>, val errors: List<String>)

    fun load(context: Context): Loaded {
        val packs = mutableListOf<CodingPack>()
        val errors = mutableListOf<String>()
        val names = runCatching { context.assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".json") }.sorted() }
            .getOrElse { errors += "Bundled coding recipes: ${it.message}"; emptyList() }
        for (name in names) {
            val read = runCatching { context.assets.open("$DIRECTORY/$name").bufferedReader().use { it.readText() } }
            val json = read.getOrNull()
            if (json == null) {
                errors += "$name: ${read.exceptionOrNull()?.message}"
                continue
            }
            val result = CodingRecipeCodec.decode(json)
            val pack = result.pack
            if (pack != null) packs += pack else errors += result.errors.map { "$name: $it" }
        }
        return Loaded(packs, errors)
    }
}
