package com.bod3.app.vehiclepack

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.catalog.CatalogTree
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Only constructed from the small index shipped in the APK, never a user's import/downloaded index. */
class VehiclePackIndex private constructor(
    val container: String, val containerBytes: Long, val catalogRoot: PackRange?,
    val reasons: Map<String, String>, val summary: Map<*, *>,
) {
    companion object {
        const val SOURCE_SHA256 = "26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a"
        const val MAX_INDEX_BYTES = 256 * 1024
        internal fun fromBundledJson(json: String): VehiclePackIndex {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_BYTES) { "Oversized bundled vehicle-pack index" }
            val m = MiniJson.parse(json) as? Map<*, *> ?: error("Invalid bundled vehicle-pack index")
            require(m["format"] == "bod3-toyota-vehicle-index" && m["formatVersion"] == 1.0 && m["converterVersion"] == 1.0 &&
                m["sourceSha256"] == SOURCE_SHA256 && m["manualSelectionOnly"] == true) { "Unsupported or unpinned vehicle-pack index" }
            val digest = m["containerSha256"] as? String ?: error("Missing vehicle-pack container hash")
            require(PackRange.HASH.matches(digest) && m["container"] == "toyota-$digest.bin") { "Unpinned vehicle-pack container" }
            val length = whole(m["containerBytes"], PackRange.MAX_CONTAINER_BYTES)
            require(length >= PackRange.MAGIC_BYTES)
            PackRange.parse(m["root"], length)
            val root = m["catalogRoot"]?.let { PackRange.parse(it, length) }
            val summary = m["summary"] as? Map<*, *> ?: error("Missing conversion summary")
            val reasons = (summary["reasons"] as? Map<*, *>)?.entries?.associate {
                val key = it.key as? String ?: error("Invalid conversion reason key")
                val value = it.value as? String ?: error("Invalid conversion reason")
                require(key.isNotBlank() && value.isNotBlank()); key to value
            } ?: emptyMap()
            return VehiclePackIndex("toyota-$digest.bin", length, root, reasons, summary)
        }
    }
}

internal fun whole(value: Any?, max: Long = 10_000_000): Long = (value as? Number)?.toDouble()?.let {
    require(it.isFinite() && it % 1.0 == 0.0 && it in 0.0..max.toDouble()) { "Invalid vehicle-pack count" }; it.toLong()
} ?: error("Missing vehicle-pack count")
private fun Map<*, *>.text(key: String): String = (this[key] as? String)?.takeIf { it.isNotBlank() } ?: error("Missing vehicle-pack $key")
private fun Map<*, *>.strings(key: String): List<String> = (this[key] as? List<*>)?.map {
    (it as? String)?.takeIf(String::isNotBlank) ?: error("Invalid vehicle-pack $key")
} ?: error("Missing vehicle-pack $key")

data class PackChoice(val id: String, val name: String, val level: String, val range: PackRange)
data class PackLevel(val id: String, val name: String, val level: String, val children: List<PackChoice>)
data class FeatureCount(val converted: Int, val unresolved: Int?) {
    val label: String get() = "$converted converted / ${unresolved?.toString() ?: "unknown"} unresolved"
}
data class PackSystemChoice(val id: String, val name: String, val enabled: Boolean, val reasons: List<String>,
                            val counts: Map<String, FeatureCount>, val range: PackRange)
class PackVehicle internal constructor(
    val id: String, val path: CatalogPack, val sourceSelection: List<String>,
    val hierarchyResolved: Boolean, val reasons: List<String>, val systems: List<PackSystemChoice>,
) {
    val variantId: String get() = path.nodes.last().id
    val title: String get() = CatalogTree(path).resolve(variantId)!!.title
}

/** These definitions reached the app ONLY through its pinned hash tree; not from an imported flag. */
class PackSystem internal constructor(
    val id: String, val name: String, val enabled: Boolean, val reasons: List<String>,
    val counts: Map<String, FeatureCount>, val sourceRecords: List<String>, val data: CatalogData,
    val unresolvedPages: List<PackRange>,
)

/** Range-by-range loading. Navigation never chooses a vehicle or sends diagnostic requests. */
class VehiclePackCatalog(val index: VehiclePackIndex, private val objects: PackObjectStore) {
    fun reason(key: String): String = index.reasons[key] ?: key
    private fun load(ref: PackRange, checkCancelled: () -> Unit) = objects.load(index.container, index.containerBytes, ref, checkCancelled)
    private fun range(raw: Any?) = PackRange.parse(raw, index.containerBytes)
    private fun reasons(m: Map<*, *>) = m.strings("reasons").also { keys -> require(keys.all { it in index.reasons }) { "Unknown conversion reason" } }

    fun root(checkCancelled: () -> Unit = {}): PackLevel = level(load(index.catalogRoot ?: error("No normalized Toyota choices are available in this conversion"), checkCancelled))
    fun open(choice: PackChoice, checkCancelled: () -> Unit = {}): PackLevel {
        val m = load(choice.range, checkCancelled)
        require(m["id"] == choice.id && m["level"] == choice.level && choice.level != "variant") { "Vehicle-pack navigation mismatch" }
        return level(m)
    }
    private fun level(m: Map<*, *>): PackLevel {
        require(m["kind"] == "catalog-level") { "Expected a catalog level, not source-menu data" }
        val level = m.text("level")
        require(level in listOf("root", "make", "model", "generation"))
        val next = listOf("root", "make", "model", "generation", "variant").let { it[it.indexOf(level) + 1] }
        val children = (m["children"] as? List<*>)?.map { raw ->
            val c = raw as? Map<*, *> ?: error("Invalid catalog child")
            require(c["level"] == next) { "Vehicle-pack level skipped" }
            PackChoice(c.text("id"), c.text("name"), next, range(c["object"]))
        } ?: error("Missing catalog children")
        require(children.size <= 20_000 && children.distinctBy { it.id }.size == children.size)
        return PackLevel(m.text("id"), m.text("name"), level, children)
    }
    /** Rehydrate only the explicitly selected four-level path; a VIN is never accepted here. */
    fun selectedVehicle(pathIds: List<String>, checkCancelled: () -> Unit = {}): PackVehicle {
        require(pathIds.size == 4) { "Choose Make → Model → Generation → Variant first" }
        var page = root(checkCancelled)
        for ((i, id) in pathIds.withIndex()) {
            checkCancelled()
            val choice = page.children.singleOrNull { it.id == id } ?: error("This selected vehicle has no exact converted pack in the bundled index")
            if (i == 3) return vehicle(choice, checkCancelled)
            page = open(choice, checkCancelled)
        }
        error("Missing converted variant")
    }

    fun vehicle(choice: PackChoice, checkCancelled: () -> Unit = {}): PackVehicle {
        require(choice.level == "variant") { "Choose an exact variant manually" }
        val m = load(choice.range, checkCancelled)
        require(m["kind"] == "converted-vehicle" && m["id"] == choice.id && m["level"] == "variant")
        val path = CatalogCodec.decodeOrThrow(JsonWriter.write(m["catalog"])) // Hierarchy only, no trusted flags.
        require(path.nodes.size == 4 && path.nodes.map { it.level } == CatalogLevel.entries && path.library.count == 0 &&
            path.platforms.isEmpty() && path.nodes.all { it.data.count == 0 && it.data.exclude == com.bod3.app.catalog.CatalogExclusions() }) { "Vehicle pack must provide exactly four name-only hierarchy levels" }
        require(path.nodes.last().id == choice.id && CatalogTree(path).path(choice.id).size == 4)
        val resolved = m["hierarchyResolved"] as? Boolean ?: error("Missing hierarchy status")
        val why = reasons(m)
        require(!resolved || why.isEmpty()) { "Resolved vehicle still has hierarchy errors" }
        val systems = (m["systems"] as? List<*>)?.map { raw ->
            val s = raw as? Map<*, *> ?: error("Invalid vehicle system")
            val enabled = s["enabled"] as? Boolean ?: error("Missing system status")
            val disabled = reasons(s)
            require(!enabled || (resolved && disabled.isEmpty())) { "Unresolved system cannot be enabled" }
            PackSystemChoice(s.text("id"), s.text("name"), enabled, disabled, counts(s["counts"]), range(s["object"]))
        } ?: error("Missing vehicle systems")
        require(systems.distinctBy { it.id }.size == systems.size && systems.size <= 1024)
        return PackVehicle(choice.id, path, m.strings("sourceSelection"), resolved, why, systems)
    }

    fun system(vehicle: PackVehicle, choice: PackSystemChoice, checkCancelled: () -> Unit = {}): PackSystem {
        require(choice in vehicle.systems) { "System does not belong to the selected vehicle" }
        val m = load(choice.range, checkCancelled)
        require(m["kind"] == "vehicle-system" && m["id"] == choice.id && m["name"] == choice.name && m["enabled"] == choice.enabled &&
            m["sourceVerified"] == true) { "Vehicle system differs from its pinned descriptor" }
        val why = reasons(m)
        val counts = counts(m["counts"])
        require(why == choice.reasons && counts == choice.counts)
        val sources = m.strings("sourceRecords"); require(sources.isNotEmpty())
        val errors = mutableListOf<String>()
        val data = CatalogCodec.readData(m["data"], "vehicle-system.data", errors)
        errors += data.problems()
        require(errors.isEmpty()) { errors.joinToString("\n") }
        if (!choice.enabled) {
            require(data.count == 0 && counts.values.all { it.converted == 0 } && why.isNotEmpty()) { "Disabled system contains executable definitions" }
        } else {
            require(vehicle.hierarchyResolved && why.isEmpty() && data.ecus.size == 1) { "No exact vehicle/ECU mapping" }
            val ecu = data.ecus.single()
            require(ecu.sourceRecords.isNotEmpty() && data.pids.all { it.sourceRecords.isNotEmpty() && it.target == ecu.target } &&
                data.actuators.all { it.sourceRecords.isNotEmpty() && it.module.target == ecu.target } &&
                data.coding.all { it.sourceRecords.isNotEmpty() && it.module.target == ecu.target }) { "Definitions are missing provenance or cross ECU boundaries" }
            require(counts.getValue("pids").converted == data.pids.size && counts.getValue("dtcs").converted == data.dtcCount &&
                counts.getValue("tests").converted == data.actuators.size && counts.getValue("coding").converted == data.coding.size)
            // Arbitrary catalog imports still call unverified(). Only this pinned path may enable them.
        }
        val trusted = data.copy(actuators = data.actuators.map { it.copy(verified = true) },
            coding = data.coding.map { it.copy(verified = true) }, security = data.security.map { it.copy(verified = true) })
        val ecu = m["ecuSource"] as? Map<*, *>
        val function = m["functionSource"] as? Map<*, *>
        val entry = m["entrySource"] as? Map<*, *>
        val pages = listOfNotNull(entry?.get("pages") as? List<*>, ecu?.get("pages") as? List<*>, function?.get("livePages") as? List<*>).flatten().map(::range)
        return PackSystem(choice.id, choice.name, choice.enabled, why, counts, sources, trusted, pages)
    }

    fun unresolved(page: PackRange, checkCancelled: () -> Unit = {}): List<Map<*, *>> {
        val m = load(page, checkCancelled)
        require(m["kind"] == "disabled-items")
        return (m["items"] as? List<*>)?.map { raw ->
            val row = raw as? Map<*, *> ?: error("Invalid unresolved source item")
            row.text("id"); require(row["enabled"] == false && reasons(row).isNotEmpty()); row
        } ?: error("Missing unresolved items")
    }

    companion object {
        private val KINDS = setOf("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")
        private fun counts(raw: Any?): Map<String, FeatureCount> {
            val m = raw as? Map<*, *> ?: error("Missing feature counts")
            require(m.keys == KINDS) { "Invalid feature count categories" }
            return KINDS.associateWith { kind ->
                val c = m[kind] as? Map<*, *> ?: error("Missing $kind counts")
                require(c.keys == setOf("converted", "unresolved"))
                FeatureCount(whole(c["converted"]).toInt(), c["unresolved"]?.let { whole(it).toInt() })
            }
        }
    }
}
