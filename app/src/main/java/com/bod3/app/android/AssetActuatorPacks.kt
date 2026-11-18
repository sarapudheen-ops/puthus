package com.bod3.app.android

import android.content.Context
import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.actuator.ActuatorRecipeCodec

/** Loads the actuator recipe packs bundled in `assets/actuators`. A broken pack is skipped and reported. */
object AssetActuatorPacks {

    private const val DIRECTORY = "actuators"

    data class Loaded(val packs: List<ActuatorPack>, val errors: List<String>)

    fun load(context: Context): Loaded {
        val packs = mutableListOf<ActuatorPack>()
        val errors = mutableListOf<String>()
        val names = runCatching { context.assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".json") }.sorted() }
            .getOrElse { errors += "Bundled actuator recipes: ${it.message}"; emptyList() }
        for (name in names) {
            val read = runCatching { context.assets.open("$DIRECTORY/$name").bufferedReader().use { it.readText() } }
            val json = read.getOrNull()
            if (json == null) {
                errors += "$name: ${read.exceptionOrNull()?.message}"
                continue
            }
            val result = ActuatorRecipeCodec.decode(json)
            val pack = result.pack
            if (pack != null) packs += pack else errors += result.errors.map { "$name: $it" }
        }
        return Loaded(packs, errors)
    }
}
