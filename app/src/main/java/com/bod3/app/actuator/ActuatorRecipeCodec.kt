package com.bod3.app.actuator

import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.obd.kwp.DiagnosticLinkCodec
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields

/** A file of actuator recipes (bundled in `assets/actuators` or imported by the user). */
data class ActuatorPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val author: String? = null,
    val description: String? = null,
    val recipes: List<ActuatorRecipe>,
) {
    fun problems(): List<String> = buildList {
        recipes.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("recipes: id \"$it\" is used more than once") }
        recipes.forEachIndexed { i, r -> r.problems().forEach { add("recipes[$i] (${r.id}).$it") } }
    }

    companion object {
        const val USER_PACK_ID = "user-actuators"
    }
}

data class ActuatorPackResult(val pack: ActuatorPack?, val errors: List<String>) {
    val isValid: Boolean get() = pack != null && errors.isEmpty()
}

/**
 * JSON format of actuator recipes:
 * ```
 * {
 *   "format": "bod3-actuators", "formatVersion": 1, "id": "vw-mqb-tests", "name": "VW MQB tests", "version": 1,
 *   "recipes": [ {
 *     "id": "vw-09-horn", "name": "Horn", "description": "Sounds the horn briefly",
 *     "module": { "name": "Central electronics", "address": "09", "requestId": "70E", "responseId": "778" },
 *     "session": "03",
 *     "securityAccess": { "level": "03", "algorithm": "SEED_PLUS_CONSTANT", "constant": "00 00 12 34" },
 *     "start": "2F 06 00 03 01", "stop": "2F 06 00 00", "status": "22 06 00",
 *     "durationSeconds": 3, "pollIntervalMs": 1000,
 *     "preconditions": { "minVoltage": 12.0, "engine": "off", "ignitionOn": true, "speedZero": true },
 *     "safety": ["Warn people nearby; the horn is loud"],
 *     "applicability": { "makes": ["Volkswagen"], "models": ["Tiguan"], "partNumbers": ["5Q0937084"], "softwareVersions": [] },
 *     "source": "…", "notes": "…", "verified": false
 *   } ]
 * }
 * ```
 */
object ActuatorRecipeCodec {
    const val FORMAT = "bod3-actuators"
    const val FORMAT_VERSION = 1

    fun encode(pack: ActuatorPack): String = JsonWriter.write(
        linkedMapOf(
            "format" to FORMAT, "formatVersion" to FORMAT_VERSION, "id" to pack.id, "name" to pack.name,
            "version" to pack.version, "author" to pack.author, "description" to pack.description,
            "recipes" to pack.recipes.map(::encodeRecipe),
        ).filterValues { it != null },
    )

    fun encodeRecipe(r: ActuatorRecipe): Map<String, Any?> = linkedMapOf(
        "id" to r.id, "name" to r.name, "description" to r.description,
        "module" to linkedMapOf("name" to r.module.name, "address" to r.module.address, "requestId" to r.module.requestId, "responseId" to r.module.responseId, "link" to r.module.link?.let(DiagnosticLinkCodec::encode))
            .filterValues { it != null },
        "session" to "%02X".format(r.session),
        "securityAccess" to r.security?.let {
            linkedMapOf("level" to "%02X".format(it.level), "algorithm" to it.algorithm.name, "constant" to Hex.format(it.constant))
        },
        "start" to Hex.format(r.start), "stop" to Hex.format(r.stop), "status" to r.status?.let { Hex.format(it) },
        "durationSeconds" to r.durationSeconds, "pollIntervalMs" to r.pollIntervalMs,
        "preconditions" to linkedMapOf(
            "minVoltage" to r.preconditions.minVoltage, "engine" to r.preconditions.engine.name.lowercase(),
            "ignitionOn" to r.preconditions.ignitionOn, "speedZero" to r.preconditions.speedZero,
        ),
        "safety" to r.safetyNotes,
        "applicability" to linkedMapOf(
            "makes" to r.applicability.makes, "models" to r.applicability.models,
            "partNumbers" to r.applicability.partNumbers, "softwareVersions" to r.applicability.softwareVersions,
        ),
        "source" to r.source, "notes" to r.notes, "verified" to r.verified,
        "sourceRecords" to r.sourceRecords.takeIf { it.isNotEmpty() }, "defaultSession" to "%02X".format(r.defaultSession),
    ).filterValues { it != null }

    /** Parses and validates [json]. Never throws for bad input; every problem is listed with its path. */
    fun decode(json: String): ActuatorPackResult {
        val errors = mutableListOf<String>()
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return ActuatorPackResult(null, listOf("Not valid JSON: ${e.message}"))
        }
        if (root !is Map<*, *>) return ActuatorPackResult(null, listOf("The file must contain a JSON object"))
        val f = JsonFields(errors)
        val format = root["format"]
        if (format != FORMAT) errors += "format: expected \"$FORMAT\", got ${format?.let { "\"$it\"" } ?: "nothing"}"
        val formatVersion = f.int(root, "formatVersion", "", required = false) ?: FORMAT_VERSION
        if (formatVersion > FORMAT_VERSION) errors += "formatVersion: $formatVersion is newer than this app supports ($FORMAT_VERSION); update the app"
        val id = f.string(root, "id", "")
        val name = f.string(root, "name", "")
        val rawRecipes = f.list(root, "recipes", "")
        if (rawRecipes.isEmpty()) errors += "recipes: the file contains no recipes"
        val recipes = rawRecipes.mapIndexedNotNull { i, item -> recipe(f, item, "recipes[$i]") }
        if (id == null || name == null || errors.isNotEmpty()) return ActuatorPackResult(null, errors.distinct())
        val pack = ActuatorPack(
            id = id, name = name,
            version = f.int(root, "version", "", required = false) ?: 1,
            author = f.string(root, "author", "", required = false),
            description = f.string(root, "description", "", required = false),
            recipes = recipes,
        )
        errors += pack.problems()
        return ActuatorPackResult(if (errors.isEmpty()) pack else null, errors.distinct())
    }

    fun decodeOrThrow(json: String): ActuatorPack {
        val result = decode(json)
        return result.pack ?: throw IllegalArgumentException(result.errors.joinToString("\n"))
    }

    private fun recipe(f: JsonFields, item: Any?, p: String): ActuatorRecipe? {
        val m = f.asObject(item, p) ?: return null
        val before = f.errors.size
        val id = f.string(m, "id", p)
        val name = f.string(m, "name", p)
        val description = f.string(m, "description", p, required = false) ?: ""
        val module = f.obj(m, "module", p)?.let { mod ->
            val mp = "$p.module"
            val modName = f.string(mod, "name", mp)
            val req = f.string(mod, "requestId", mp)?.uppercase()
            val res = f.string(mod, "responseId", mp)?.uppercase()
            if (modName == null || req == null || res == null) null
            else ActuatorModule(modName, f.string(mod, "address", mp, required = false)?.ifEmpty { null }, req, res, DiagnosticLinkCodec.decode(mod["link"], "$mp.link", f.errors))
        }
        val session = f.hexInt(m, "session", p, required = false, max = 0xFF) ?: 0x03
        val security = f.obj(m, "securityAccess", p, required = false)?.let { sec ->
            val sp = "$p.securityAccess"
            val level = f.hexInt(sec, "level", sp, max = 0xFF)
            val algorithmName = f.string(sec, "algorithm", sp)
            val algorithm = algorithmName?.let { n ->
                SecurityAlgorithm.entries.firstOrNull { it.name.equals(n, true) }
                    ?: run { f.errors += "$sp.algorithm: must be one of ${SecurityAlgorithm.entries.joinToString { it.name }}"; null }
            }
            val constant = f.hex(sec, "constant", sp)
            if (level == null || algorithm == null || constant == null) null else SecurityAccessSpec(level, algorithm, constant)
        }
        val start = f.hex(m, "start", p)
        val stop = f.hex(m, "stop", p)
        val status = f.hex(m, "status", p, required = false)
        val duration = f.int(m, "durationSeconds", p, required = false) ?: 10
        val poll = f.int(m, "pollIntervalMs", p, required = false) ?: 1_000
        val preconditions = f.obj(m, "preconditions", p, required = false)?.let { pre ->
            val pp = "$p.preconditions"
            val engineName = f.string(pre, "engine", pp, required = false)
            val engine = engineName?.let { n ->
                EngineRequirement.entries.firstOrNull { it.name.equals(n, true) }
                    ?: run { f.errors += "$pp.engine: must be off, running or any"; null }
            } ?: EngineRequirement.OFF
            ActuatorPreconditions(
                minVoltage = f.number(pre, "minVoltage", pp, required = false) ?: 12.0,
                engine = engine,
                ignitionOn = f.boolean(pre, "ignitionOn", pp) ?: true,
                speedZero = f.boolean(pre, "speedZero", pp) ?: true,
            )
        } ?: ActuatorPreconditions()
        val safety = f.strings(m, "safety", p)
        val applicability = f.obj(m, "applicability", p, required = false)?.let { a ->
            val ap = "$p.applicability"
            Applicability(f.strings(a, "makes", ap), f.strings(a, "models", ap), f.strings(a, "partNumbers", ap), f.strings(a, "softwareVersions", ap))
        } ?: Applicability()
        val source = f.string(m, "source", p, required = false)
        val notes = f.string(m, "notes", p, required = false)
        val verified = f.boolean(m, "verified", p) ?: false
        if (f.errors.size > before || id == null || name == null || module == null || start == null || stop == null) return null
        return ActuatorRecipe(
            id = id, name = name, description = description, module = module, session = session, security = security,
            start = start, stop = stop, status = status, durationSeconds = duration, pollIntervalMs = poll.toLong(),
            preconditions = preconditions, safetyNotes = safety, applicability = applicability, source = source,
            notes = notes, verified = verified, sourceRecords = f.strings(m, "sourceRecords", p),
            defaultSession = f.hexInt(m, "defaultSession", p, false, 0xFF) ?: 0x01,
        )
    }
}
