package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.Applicability
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields

data class CodingPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val author: String? = null,
    val description: String? = null,
    val recipes: List<CodingRecipe>,
) {
    fun problems(): List<String> = buildList {
        recipes.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("recipes: id \"$it\" is used more than once") }
        recipes.forEachIndexed { i, r -> r.problems().forEach { add("recipes[$i] (${r.id}).$it") } }
    }

    companion object {
        const val USER_PACK_ID = "user-coding"
    }
}

data class CodingPackResult(val pack: CodingPack?, val errors: List<String>) {
    val isValid: Boolean get() = pack != null && errors.isEmpty()
}

/**
 * JSON format of coding recipes:
 * ```
 * {
 *   "format": "bod3-coding", "formatVersion": 1, "id": "vw-mqb-coding", "name": "VW MQB coding",
 *   "recipes": [ {
 *     "id": "vw-09-comfort-closing", "name": "Comfort closing via remote", "description": "…",
 *     "module": { "name": "Central electronics", "address": "09", "requestId": "70E", "responseId": "778" },
 *     "method": "long_coding", "did": "0600", "byte": 2, "mask": "01", "length": 1,
 *     "values": [ { "value": 0, "label": "Off" }, { "value": 1, "label": "On" } ],
 *     "min": 0, "max": 255, "factor": 1, "offset": 0, "unit": "s",
 *     "session": "03", "securityAccess": { "level": "03", "algorithm": "SEED_PLUS_CONSTANT", "constant": "00 00 4E 47" },
 *     "preconditions": { "minVoltage": 12.0, "allowEngineRunning": false },
 *     "applicability": { "makes": ["Volkswagen"], "models": ["Tiguan"], "partNumbers": ["5Q0937"], "softwareVersions": [] },
 *     "source": "…", "notes": "…", "verified": false
 *   } ]
 * }
 * ```
 * A file may also contain a single recipe object (without the pack wrapper), e.g. from the in-app editor.
 */
object CodingRecipeCodec {
    const val FORMAT = "bod3-coding"
    const val FORMAT_VERSION = 1

    fun encode(pack: CodingPack): String = JsonWriter.write(
        linkedMapOf(
            "format" to FORMAT, "formatVersion" to FORMAT_VERSION, "id" to pack.id, "name" to pack.name,
            "version" to pack.version, "author" to pack.author, "description" to pack.description,
            "recipes" to pack.recipes.map(::encodeRecipe),
        ).filterValues { it != null },
    )

    fun encodeRecipeJson(r: CodingRecipe): String = JsonWriter.write(encodeRecipe(r))

    fun encodeRecipe(r: CodingRecipe): Map<String, Any?> = linkedMapOf(
        "id" to r.id, "name" to r.name, "description" to r.description,
        "module" to linkedMapOf("name" to r.module.name, "address" to r.module.address, "requestId" to r.module.requestId, "responseId" to r.module.responseId)
            .filterValues { it != null },
        "method" to r.method.name.lowercase(),
        "did" to "%04X".format(r.did), "byte" to r.byteIndex, "mask" to r.mask?.let { "%02X".format(it) }, "length" to r.length,
        "values" to r.options.takeIf { it.isNotEmpty() }?.map { linkedMapOf("value" to it.value, "label" to it.label) },
        "min" to r.min, "max" to r.max,
        "factor" to r.factor.takeIf { it != 1.0 }, "offset" to r.offset.takeIf { it != 0.0 }, "unit" to r.unit,
        "session" to "%02X".format(r.session),
        "securityAccess" to r.security?.let {
            linkedMapOf("level" to "%02X".format(it.level), "algorithm" to it.algorithm.name, "constant" to Hex.format(it.constant))
        },
        "preconditions" to linkedMapOf("minVoltage" to r.preconditions.minVoltage, "allowEngineRunning" to r.preconditions.allowEngineRunning),
        "applicability" to linkedMapOf(
            "makes" to r.applicability.makes, "models" to r.applicability.models,
            "partNumbers" to r.applicability.partNumbers, "softwareVersions" to r.applicability.softwareVersions,
        ),
        "source" to r.source, "notes" to r.notes, "verified" to r.verified,
    ).filterValues { it != null }

    /** Parses a pack, or a single recipe object. Never throws for bad input; problems carry their JSON path. */
    fun decode(json: String): CodingPackResult {
        val errors = mutableListOf<String>()
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return CodingPackResult(null, listOf("Not valid JSON: ${e.message}"))
        }
        if (root !is Map<*, *>) return CodingPackResult(null, listOf("The file must contain a JSON object"))
        val f = JsonFields(errors)
        if (root["format"] == null && root["recipes"] == null && root["module"] != null) {
            // A single recipe, e.g. typed in the editor.
            val recipe = recipe(f, root, "") ?: return CodingPackResult(null, errors.distinct())
            val pack = CodingPack(CodingPack.USER_PACK_ID, recipe.name, recipes = listOf(recipe))
            errors += pack.problems().map { it.substringAfter(").") }
            return CodingPackResult(if (errors.isEmpty()) pack else null, errors.distinct())
        }
        val format = root["format"]
        if (format != FORMAT) errors += "format: expected \"$FORMAT\", got ${format?.let { "\"$it\"" } ?: "nothing"}"
        val formatVersion = f.int(root, "formatVersion", "", required = false) ?: FORMAT_VERSION
        if (formatVersion > FORMAT_VERSION) errors += "formatVersion: $formatVersion is newer than this app supports ($FORMAT_VERSION); update the app"
        val id = f.string(root, "id", "")
        val name = f.string(root, "name", "")
        val raw = f.list(root, "recipes", "")
        if (raw.isEmpty()) errors += "recipes: the file contains no recipes"
        val recipes = raw.mapIndexedNotNull { i, item -> f.asObject(item, "recipes[$i]")?.let { recipe(f, it, "recipes[$i]") } }
        if (id == null || name == null || errors.isNotEmpty()) return CodingPackResult(null, errors.distinct())
        val pack = CodingPack(
            id, name, f.int(root, "version", "", required = false) ?: 1,
            f.string(root, "author", "", required = false), f.string(root, "description", "", required = false), recipes,
        )
        errors += pack.problems()
        return CodingPackResult(if (errors.isEmpty()) pack else null, errors.distinct())
    }

    fun decodeOrThrow(json: String): CodingPack {
        val result = decode(json)
        return result.pack ?: throw IllegalArgumentException(result.errors.joinToString("\n"))
    }

    /** A whole number given as a JSON number (decimal) or a hex string (`"0x1F"` / `"1F"`). */
    private fun long(f: JsonFields, m: Map<*, *>, key: String, p: String, required: Boolean): Long? {
        val path = f.path(p, key)
        return when (val v = m[key]) {
            null -> { if (required) f.errors += "$path: missing"; null }
            is Number -> v.toDouble().takeIf { it % 1.0 == 0.0 && it >= 0 }?.toLong() ?: run { f.errors += "$path: must be a whole number ≥ 0"; null }
            is String -> v.trim().removePrefix("0x").removePrefix("0X").takeIf { it.isNotEmpty() && it.length <= 8 }?.toLongOrNull(16)
                ?: run { f.errors += "$path: \"$v\" is not a hex number"; null }
            else -> { f.errors += "$path: must be a number"; null }
        }
    }

    private fun recipe(f: JsonFields, m: Map<*, *>, p: String): CodingRecipe? {
        val before = f.errors.size
        val id = f.string(m, "id", p)
        val name = f.string(m, "name", p)
        val module = f.obj(m, "module", p)?.let { mod ->
            val mp = f.path(p, "module")
            val modName = f.string(mod, "name", mp)
            val req = f.string(mod, "requestId", mp)?.uppercase()
            val res = f.string(mod, "responseId", mp)?.uppercase()
            if (modName == null || req == null || res == null) null
            else ActuatorModule(modName, f.string(mod, "address", mp, required = false)?.ifEmpty { null }, req, res)
        }
        val methodName = f.string(m, "method", p)
        val method = methodName?.let { n ->
            CodingMethod.entries.firstOrNull { it.name.equals(n.replace('-', '_'), true) }
                ?: run { f.errors += "${f.path(p, "method")}: must be long_coding, adaptation or did_value"; null }
        }
        val did = f.hexInt(m, "did", p, required = method != CodingMethod.LONG_CODING) ?: 0x0600
        val byteIndex = f.int(m, "byte", p)
        val mask = f.hexInt(m, "mask", p, required = false, max = 0xFF)
        val length = f.int(m, "length", p, required = false) ?: 1
        val options = f.list(m, "values", p).mapIndexedNotNull { i, item ->
            val vp = "${f.path(p, "values")}[$i]"
            val o = f.asObject(item, vp) ?: return@mapIndexedNotNull null
            val value = long(f, o, "value", vp, true)
            val label = f.string(o, "label", vp)
            if (value == null || label == null) null else CodingOption(value, label)
        }
        val security = f.obj(m, "securityAccess", p, required = false)?.let { sec ->
            val sp = f.path(p, "securityAccess")
            val level = f.hexInt(sec, "level", sp, max = 0xFF)
            val algorithm = f.string(sec, "algorithm", sp)?.let { n ->
                SecurityAlgorithm.entries.firstOrNull { it.name.equals(n, true) }
                    ?: run { f.errors += "$sp.algorithm: must be one of ${SecurityAlgorithm.entries.joinToString { it.name }}"; null }
            }
            val constant = f.hex(sec, "constant", sp)
            if (level == null || algorithm == null || constant == null) null else SecurityAccessSpec(level, algorithm, constant)
        }
        val preconditions = f.obj(m, "preconditions", p, required = false)?.let { pre ->
            val pp = f.path(p, "preconditions")
            CodingPreconditions(
                minVoltage = f.number(pre, "minVoltage", pp, required = false) ?: 12.0,
                allowEngineRunning = f.boolean(pre, "allowEngineRunning", pp) ?: false,
            )
        } ?: CodingPreconditions()
        val applicability = f.obj(m, "applicability", p, required = false)?.let { a ->
            val ap = f.path(p, "applicability")
            Applicability(f.strings(a, "makes", ap), f.strings(a, "models", ap), f.strings(a, "partNumbers", ap), f.strings(a, "softwareVersions", ap))
        } ?: Applicability()
        val recipe = CodingRecipe(
            id = id ?: "", name = name ?: "", description = f.string(m, "description", p, required = false) ?: "",
            module = module ?: ActuatorModule("", null, "", ""), method = method ?: CodingMethod.DID_VALUE,
            did = did, byteIndex = byteIndex ?: 0, mask = mask, length = length, options = options,
            min = long(f, m, "min", p, false), max = long(f, m, "max", p, false),
            factor = f.number(m, "factor", p, required = false) ?: 1.0,
            offset = f.number(m, "offset", p, required = false) ?: 0.0,
            unit = f.string(m, "unit", p, required = false)?.ifEmpty { null },
            session = f.hexInt(m, "session", p, required = false, max = 0xFF) ?: 0x03,
            security = security, preconditions = preconditions, applicability = applicability,
            source = f.string(m, "source", p, required = false), notes = f.string(m, "notes", p, required = false),
            verified = f.boolean(m, "verified", p) ?: false,
        )
        if (f.errors.size > before || id == null || name == null || module == null || method == null || byteIndex == null) return null
        return recipe
    }
}

data class CodingEntry(val recipe: CodingRecipe, val packName: String, val isUser: Boolean)

data class CodingImportResult(val added: List<String>, val replaced: List<String>)

/** Bundled coding packs plus the user's own recipes; user recipes replace bundled ones with the same id. */
class CodingLibrary(private val bundled: List<CodingPack>, private val store: KeyValueStore,
    private val catalog: com.bod3.app.catalog.CatalogAccess? = null,
) {

    val bundledPacks: List<CodingPack> get() = bundled

    fun userPack(): CodingPack {
        catalog?.let { return empty().copy(recipes = it.local().coding) }
        val json = store.get(KEY_USER) ?: return empty()
        return CodingRecipeCodec.decode(json).pack ?: empty().also { store.put(KEY_USER_BROKEN, json) }
    }

    fun entries(): List<CodingEntry> {
        catalog?.let { return it.coding() }
        val user = userPack().recipes
        val userIds = user.map { it.id }.toSet()
        val fromBundles = bundled.flatMap { pack -> pack.recipes.filterNot { it.id in userIds }.map { CodingEntry(it, pack.name, false) } }
            .distinctBy { it.recipe.id }
        return (fromBundles + user.map { CodingEntry(it, MY_RECIPES, true) })
            .sortedWith(compareBy({ it.recipe.module.name.lowercase() }, { it.recipe.name.lowercase() }))
    }

    fun entry(id: String): CodingEntry? = entries().firstOrNull { it.recipe.id == id }

    fun import(pack: CodingPack): CodingImportResult {
        val problems = pack.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val safe = pack.recipes.map { it.copy(verified = false) }
        catalog?.let { access ->
            val existing = entries().map { it.recipe.id }.toSet()
            val ids = safe.map { it.id }.toSet()
            access.edit { it.copy(coding = it.coding.filterNot { r -> r.id in ids } + safe) }
            return CodingImportResult(safe.map { it.id }.filterNot { it in existing }, safe.map { it.id }.filter { it in existing })
        }
        val current = userPack().recipes
        val existing = (current.map { it.id } + bundled.flatMap { b -> b.recipes.map { it.id } }).toSet()
        val incoming = pack.recipes.map { it.id }.toSet()
        save(current.filterNot { it.id in incoming } + safe)
        return CodingImportResult(pack.recipes.map { it.id }.filterNot { it in existing }, pack.recipes.map { it.id }.filter { it in existing })
    }

    /** Adds or replaces one recipe (from the editor); [replacingId] is removed first when the id changed. */
    fun save(recipe: CodingRecipe, replacingId: String? = null) {
        val problems = recipe.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val safe = recipe.copy(verified = false)
        catalog?.let { access ->
            access.edit { it.copy(coding = it.coding.filterNot { r -> r.id == safe.id || r.id == replacingId } + safe) }
            return
        }
        save(userPack().recipes.filterNot { it.id == recipe.id || it.id == replacingId } + safe)
    }

    fun deleteUserRecipe(id: String) {
        catalog?.let { access -> access.edit { it.copy(coding = it.coding.filterNot { r -> r.id == id }) }; return }
        save(userPack().recipes.filterNot { it.id == id })
    }

    fun export(recipes: List<CodingRecipe> = userPack().recipes, name: String = MY_RECIPES): String =
        CodingRecipeCodec.encode(CodingPack(CodingPack.USER_PACK_ID, name, recipes = recipes))

    private fun save(recipes: List<CodingRecipe>) {
        if (recipes.isEmpty()) return store.put(KEY_USER, null)
        val pack = empty().copy(recipes = recipes)
        val problems = pack.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        store.put(KEY_USER, CodingRecipeCodec.encode(pack))
    }

    private fun empty() = CodingPack(CodingPack.USER_PACK_ID, MY_RECIPES, recipes = emptyList())

    companion object {
        const val KEY_USER = "coding.user"
        const val KEY_USER_BROKEN = "coding.user.unreadable"
        const val MY_RECIPES = "My recipes"
    }
}
