package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorRecipeCodec
import com.bod3.app.coding.CodingRecipeCodec
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields
import java.util.Locale

data class CatalogResult(val pack: CatalogPack?, val errors: List<String>) {
    val isValid: Boolean get() = pack != null && errors.isEmpty()
}

/** One invalid object rejects the entire document. There is no forgiving/partial catalog decoder. */
object CatalogCodec {
    const val FORMAT = "bod3-vehicle-catalog"
    const val VERSION = 3
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(pack: CatalogPack): String = JsonWriter.write(packMap(pack))
    fun packMap(p: CatalogPack): Map<String, Any?> = linkedMapOf(
        "format" to FORMAT, "formatVersion" to requiredVersion(p), "id" to p.id, "name" to p.name, "version" to p.version,
        "description" to p.description, "nodes" to p.nodes.map(::nodeMap),
        "platforms" to p.platforms.map { linkedMapOf("id" to it.id, "name" to it.name, "notes" to it.notes, "data" to dataMap(it.data)) },
        "library" to dataMap(p.library),
    )
    fun nodeMap(n: CatalogNode): Map<String, Any?> = linkedMapOf(
        "id" to n.id, "parentId" to n.parentId, "level" to n.level.name.lowercase(Locale.ROOT), "name" to n.name,
        "yearFrom" to n.yearFrom, "yearTo" to n.yearTo, "engine" to n.engine, "fuel" to n.fuel, "transmission" to n.transmission,
        "platformIds" to n.platformIds, "notes" to n.notes, "data" to dataMap(n.data),
    ).filterValues { it != null }

    fun dataMap(d: CatalogData): Map<String, Any?> = linkedMapOf(
        "ecus" to d.ecus.map(DataPackCodec::encodeEcu), "pids" to d.pids.map(DataPackCodec::encodePid),
        "dtcs" to d.dtcs.toSortedMap(), "moduleNames" to d.moduleNames.map(DataPackCodec::encodeModuleName),
        "dtcGroups" to d.dtcGroups.map { linkedMapOf("id" to it.id, "makes" to it.makes, "source" to it.source, "dtcs" to it.dtcs.toSortedMap()) },
        "actuators" to d.actuators.map(ActuatorRecipeCodec::encodeRecipe), "coding" to d.coding.map(CodingRecipeCodec::encodeRecipe),
        "security" to d.security.map { linkedMapOf("id" to it.id, "name" to it.name, "level" to "%02X".format(it.spec.level),
            "algorithm" to it.spec.algorithm.name, "constant" to Hex.format(it.spec.constant), "verified" to it.verified) },
        "connection" to d.connection.values,
        "exclude" to linkedMapOf("ecus" to d.exclude.ecus.sorted(), "pids" to d.exclude.pids.sorted(), "dtcs" to d.exclude.dtcs.sorted(),
            "actuators" to d.exclude.actuators.sorted(), "coding" to d.exclude.coding.sorted(), "security" to d.exclude.security.sorted(),
            "dtcGroups" to d.exclude.dtcGroups.sorted(), "moduleNames" to d.exclude.moduleNames.sorted()),
    )

    /** Context is used only for saved sparse user overlays/editor validation, not standalone bulk files. */
    fun decode(json: String, untrusted: Boolean = true, context: CatalogPack? = null): CatalogResult {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return CatalogResult(null, listOf("File exceeds the 16 MiB catalog limit"))
        val errors = mutableListOf<String>()
        val root = try { MiniJson.parse(json.trimStart('\uFEFF')) } catch (e: IllegalArgumentException) { return CatalogResult(null, listOf("Not valid JSON: ${e.message}")) }
        val f = JsonFields(errors)
        val m = f.asObject(root, "catalog") ?: return CatalogResult(null, errors)
        unknown(m, setOf("format", "formatVersion", "id", "name", "version", "description", "nodes", "platforms", "library"), "catalog", errors)
        if (m["format"] != FORMAT) errors += "format: expected $FORMAT"
        val formatVersion = f.int(m, "formatVersion", "")
        if (formatVersion !in 1..VERSION) errors += "formatVersion: supported catalog versions are 1-$VERSION"
        val id = f.string(m, "id", ""); val name = f.string(m, "name", "")
        val nodes = f.list(m, "nodes", "").mapIndexedNotNull { i, raw ->
            val p = "nodes[$i]"; val n = f.asObject(raw, p) ?: return@mapIndexedNotNull null
            unknown(n, setOf("id", "parentId", "level", "name", "yearFrom", "yearTo", "engine", "fuel", "transmission", "platformIds", "data", "notes"), p, errors)
            val ni = f.string(n, "id", p); val nn = f.string(n, "name", p)
            val level = f.string(n, "level", p)?.let { text -> CatalogLevel.entries.firstOrNull { it.name.equals(text, true) }
                ?: run { errors += "$p.level: must be make, model, generation or variant"; null } }
            val data = readData(n["data"], "$p.data", errors)
            val parent = f.string(n, "parentId", p, false)
            val from = f.int(n, "yearFrom", p, false); val to = f.int(n, "yearTo", p, false)
            val engine = f.string(n, "engine", p, false); val fuel = f.string(n, "fuel", p, false)
            val transmission = f.string(n, "transmission", p, false); val platforms = f.strings(n, "platformIds", p)
            val notes = f.string(n, "notes", p, false)
            if (ni == null || nn == null || level == null) null else CatalogNode(ni, parent, level, nn, from, to, engine, fuel, transmission, platforms, data, notes)
        }
        val platforms = f.list(m, "platforms", "").mapIndexedNotNull { i, raw ->
            val p = "platforms[$i]"; val item = f.asObject(raw, p) ?: return@mapIndexedNotNull null
            unknown(item, setOf("id", "name", "data", "notes"), p, errors)
            val pi = f.string(item, "id", p); val pn = f.string(item, "name", p)
            val data = readData(item["data"], "$p.data", errors); val notes = f.string(item, "notes", p, false)
            if (pi == null || pn == null) null else CatalogPlatform(pi, pn, data, notes)
        }
        val library = readData(m["library"], "library", errors)
        val version = f.int(m, "version", "", false) ?: 1
        val description = f.string(m, "description", "", false)
        if (id == null || name == null) return CatalogResult(null, errors.distinct())
        var pack = CatalogPack(id, name, version, nodes, platforms, library, description)
        if (formatVersion != null && formatVersion < requiredVersion(pack)) errors +=
            "diagnostics/supportChecks/testerPresent: requires catalog formatVersion ${requiredVersion(pack)} so older apps cannot silently discard exact reads, prerequisites or keepalive"
        if (context != null) {
            for ((kind, ids) in listOf("nodes" to nodes.map { it.id }, "platforms" to platforms.map { it.id })) {
                ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "$kind: duplicate id $it" }
            }
        }
        errors += CatalogTree(context?.let { mergeCatalog(it, pack) } ?: pack).problems()
        if (untrusted) pack = pack.unverified()
        return CatalogResult(pack.takeIf { errors.isEmpty() }, errors.distinct())
    }

    fun decodeOrThrow(json: String, untrusted: Boolean = true, context: CatalogPack? = null): CatalogPack = decode(json, untrusted, context).let { result ->
        result.pack ?: throw IllegalArgumentException(result.errors.joinToString("\n"))
    }

    fun dataOrThrow(json: String): CatalogData {
        val errors = mutableListOf<String>()
        val data = readData(MiniJson.parse(json), "data", errors).unverified()
        errors += data.problems()
        require(errors.isEmpty()) { errors.distinct().joinToString("\n") }
        return data
    }

    /** Shared with the in-app declaration editor. All individual recipes still use the guarded codecs. */
    fun readData(raw: Any?, p: String, errors: MutableList<String>): CatalogData {
        if (raw == null) return CatalogData()
        val f = JsonFields(errors); val m = f.asObject(raw, p) ?: return CatalogData()
        unknown(m, setOf("ecus", "pids", "dtcs", "dtcGroups", "moduleNames", "actuators", "coding", "security", "connection", "exclude"), p, errors)
        val legacy = DataPackCodec.decode(JsonWriter.write(linkedMapOf("formatVersion" to DataPackCodec.FORMAT_VERSION, "id" to "catalog-data", "name" to "Catalog data",
            "pids" to m["pids"], "dtcs" to m["dtcs"], "moduleNames" to m["moduleNames"], "dtcGroups" to m["dtcGroups"])))
        errors += legacy.errors.map { "$p.$it" }
        val ecus = f.list(m, "ecus", p).mapIndexedNotNull { i, e -> DataPackCodec.decodeEcu(e, "$p.ecus[$i]", errors) }
        fun recipePack(format: String, key: String) = JsonWriter.write(linkedMapOf("format" to format, "formatVersion" to 1,
            "id" to "catalog-data", "name" to "Catalog data", "recipes" to m[key]))
        val actuators = if (f.list(m, "actuators", p).isNotEmpty()) ActuatorRecipeCodec.decode(recipePack(ActuatorRecipeCodec.FORMAT, "actuators")).let {
            errors += it.errors.map { e -> "$p.actuators.$e" }; it.pack?.recipes.orEmpty()
        } else emptyList()
        val coding = if (f.list(m, "coding", p).isNotEmpty()) CodingRecipeCodec.decode(recipePack(CodingRecipeCodec.FORMAT, "coding")).let {
            errors += it.errors.map { e -> "$p.coding.$e" }; it.pack?.recipes.orEmpty()
        } else emptyList()
        val security = f.list(m, "security", p).mapIndexedNotNull { i, rawSpec ->
            val sp = "$p.security[$i]"; val s = f.asObject(rawSpec, sp) ?: return@mapIndexedNotNull null
            unknown(s, setOf("id", "name", "level", "algorithm", "constant", "verified"), sp, errors)
            val id = f.string(s, "id", sp); val name = f.string(s, "name", sp); val level = f.hexInt(s, "level", sp, max = 0xFF)
            val algorithm = f.string(s, "algorithm", sp)?.let { text -> SecurityAlgorithm.entries.firstOrNull { it.name == text }
                ?: run { errors += "$sp.algorithm: unknown security algorithm"; null } }
            val constant = f.hex(s, "constant", sp)
            f.boolean(s, "verified", sp) // Type check, but never trust this assertion.
            if (id == null || name == null || level == null || algorithm == null || constant == null) null
            else CatalogSecurity(id, name, SecurityAccessSpec(level, algorithm, constant), verified = false)
        }
        val connection = f.obj(m, "connection", p, false)?.entries?.associate { (k, v) -> k.toString() to v }.orEmpty()
        val exclude = f.obj(m, "exclude", p, false).orEmpty()
        unknown(exclude, setOf("ecus", "pids", "dtcs", "actuators", "coding", "security", "dtcGroups", "moduleNames"), "$p.exclude", errors)
        return CatalogData(ecus = ecus, pids = legacy.pack?.pids.orEmpty(), dtcs = legacy.pack?.dtcs.orEmpty(),
            moduleNames = legacy.pack?.moduleNames.orEmpty(), actuators = actuators, coding = coding, security = security,
            connection = CatalogConnection(connection), dtcGroups = legacy.pack?.dtcGroups.orEmpty(),
            exclude = CatalogExclusions(ecus = f.strings(exclude, "ecus", "$p.exclude").map { it.uppercase(Locale.ROOT) }.toSet(),
                pids = f.strings(exclude, "pids", "$p.exclude").toSet(), dtcs = f.strings(exclude, "dtcs", "$p.exclude").map { it.uppercase(Locale.ROOT) }.toSet(),
                actuators = f.strings(exclude, "actuators", "$p.exclude").toSet(), coding = f.strings(exclude, "coding", "$p.exclude").toSet(),
                security = f.strings(exclude, "security", "$p.exclude").toSet(), dtcGroups = f.strings(exclude, "dtcGroups", "$p.exclude").toSet(),
                moduleNames = f.strings(exclude, "moduleNames", "$p.exclude").toSet()))
    }

    private fun requiredVersion(pack: CatalogPack): Int {
        val data = listOf(pack.library) + pack.nodes.map { it.data } + pack.platforms.map { it.data }
        if (data.any { d -> d.pids.any { it.supportChecks.isNotEmpty() || it.link?.testerPresent == listOf(0x3E) } ||
                d.ecus.any { it.link?.testerPresent == listOf(0x3E) } ||
                d.actuators.any { it.module.target.link?.testerPresent == listOf(0x3E) } ||
                d.coding.any { it.module.target.link?.testerPresent == listOf(0x3E) } }) return 3
        return if (data.any { d -> d.ecus.any { it.diagnostics != null } }) 2 else 1
    }

    private fun unknown(map: Map<*, *>, allowed: Set<String>, path: String, errors: MutableList<String>) {
        map.keys.filterNot { it in allowed }.forEach { errors += "$path.$it: unknown field (nothing is silently discarded)" }
    }
}
