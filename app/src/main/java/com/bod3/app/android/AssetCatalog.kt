package com.bod3.app.android

import android.content.Context
import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogMigration
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.catalog.CatalogTree
import com.bod3.app.coding.CodingPack
import com.bod3.app.datapack.DataPack

/** Names/years-only starter tree plus exact existing diagnostic definitions, under their original scope. */
object AssetCatalog {
    data class Loaded(val pack: CatalogPack, val legacyLinks: Map<String, String>, val errors: List<String>)
    fun load(context: Context, data: List<DataPack>, actuators: List<ActuatorPack>, coding: List<CodingPack>): Loaded {
        val errors = mutableListOf<String>()
        val starter = runCatching {
            context.assets.open("catalog/starter.json").bufferedReader().use { CatalogCodec.decodeOrThrow(it.readText()) }
        }.getOrElse { errors += "Starter catalog: ${it.message}"; CatalogPack("starter-catalog", "Manual vehicle catalog") }
        val converted = CatalogMigration.convert(starter, data, actuators, coding)
        errors += CatalogTree(converted.pack).problems()
        return Loaded(converted.pack, converted.legacyLinks, errors)
    }
}
