package com.bod3.app.datapack

import com.bod3.app.obd.kwp.DiagnosticLinkCodec
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Result of reading a data pack file: the pack when it is valid, otherwise every problem found. */
data class DataPackResult(val pack: DataPack?, val errors: List<String>) {
    val isValid: Boolean get() = pack != null && errors.isEmpty()
}

/**
 * JSON format of data packs. Legacy v1 is retained for files without scoped fault references.
 * v2 adds `dtcGroups`: objects with `id`, non-empty `makes`, `dtcs` and optional `source`. These fields
 * always require formatVersion 2, so an older app refuses rather than silently discarding the scope.
 * See docs/toyota-data.md for a v2 example. A v1 example:
 * ```
 * {
 *   "format": "bod3-datapack", "formatVersion": 1,
 *   "id": "vw-mqb", "name": "VW group MQB", "version": 3, "author": "…", "description": "…",
 *   "pids": [ { "id": "boost", "name": "Boost", "request": "22F40C", "formula": "(A*256+B)/100",
 *               "unit": "bar", "min": 0, "max": 3, "decimals": 2, "header": "7E0", "responseHeader": "7E8" } ],
 *   "vehicles": [ { "make": "Volkswagen", "model": "Tiguan (AD1)", "years": "2016-2024",
 *                   "ecus": [ { "address": "09", "name": "Central electronics", "requestId": "70E",
 *                               "responseId": "778", "protocol": "UDS" } ] } ],
 *   "dtcs": { "P0420": "Catalyst system efficiency below threshold (bank 1)" },
 *   "moduleNames": [ { "requestId": "70E", "responseId": "778", "address": "09", "name": "Central electronics",
 *                      "makes": ["Volkswagen", "Audi", "Skoda", "SEAT", "Cupra"] } ]
 * }
 * ```
 */
object DataPackCodec {
    const val FORMAT = "bod3-datapack"
    const val FORMAT_VERSION = 2

    fun encode(pack: DataPack): String = JsonWriter.write(
        linkedMapOf(
            "format" to FORMAT,
            "formatVersion" to if (pack.dtcGroups.isEmpty()) 1 else FORMAT_VERSION,
            "id" to pack.id,
            "name" to pack.name,
            "version" to pack.version,
            "author" to pack.author,
            "description" to pack.description,
            "pids" to pack.pids.map(::encodePid),
            "vehicles" to pack.vehicles.map(::encodeVehicle),
            "dtcs" to pack.dtcs.toSortedMap(),
            "moduleNames" to pack.moduleNames.takeIf { it.isNotEmpty() }?.map(::encodeModuleName),
            "dtcGroups" to pack.dtcGroups.takeIf { it.isNotEmpty() }?.map { group ->
                linkedMapOf("id" to group.id, "makes" to group.makes, "dtcs" to group.dtcs.toSortedMap(), "source" to group.source).filterValues { it != null }
            },
        ).filterValues { it != null },
    )

    fun encodeModuleName(m: ModuleNameEntry): Map<String, Any?> = linkedMapOf(
        "requestId" to m.requestId, "responseId" to m.responseId, "address" to m.address, "name" to m.name,
        "makes" to m.makes.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    /** Decodes one ECU object (as written by [encodeEcu]); problems are added to [errors] with [path]. */
    fun decodeEcu(item: Any?, path: String, errors: MutableList<String>): EcuDefinition? = Reader(errors).ecu(item, path)

    fun encodePid(p: CustomPid): Map<String, Any?> = linkedMapOf(
        "id" to p.id, "name" to p.name, "request" to p.request, "formula" to p.formula, "unit" to p.unit,
        "min" to p.min, "max" to p.max, "decimals" to p.decimals, "header" to p.header,
        "responseHeader" to p.responseHeader, "notes" to p.notes, "link" to p.link?.let(DiagnosticLinkCodec::encode),
        "dataOffset" to p.dataOffset.takeIf { it != 0 }, "sourceRecords" to p.sourceRecords.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    fun encodeVehicle(v: VehicleDefinition): Map<String, Any?> = linkedMapOf(
        "make" to v.make, "model" to v.model, "years" to v.years, "notes" to v.notes,
        "ecus" to v.ecus.map(::encodeEcu),
    ).filterValues { it != null }

    fun encodeEcu(e: EcuDefinition): Map<String, Any?> = linkedMapOf(
        "address" to e.address, "name" to e.name, "requestId" to e.requestId, "responseId" to e.responseId,
        "protocol" to e.protocol.name, "addressing" to (if (e.link?.kLine != null) null else if (e.is29Bit) "29" else "11"), "notes" to e.notes,
        "link" to e.link?.let(DiagnosticLinkCodec::encode), "sourceRecords" to e.sourceRecords.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    /** Parses and validates [json]. Never throws for bad input; problems are listed in the result. */
    fun decode(json: String): DataPackResult {
        val errors = mutableListOf<String>()
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return DataPackResult(null, listOf("Not valid JSON: ${e.message}"))
        }
        if (root !is Map<*, *>) return DataPackResult(null, listOf("The file must contain a JSON object"))
        val reader = Reader(errors)
        val format = root["format"]
        if (format != null && format != FORMAT) errors += "format: expected \"$FORMAT\", got \"$format\""
        val formatVersion = reader.int(root, "formatVersion", "", required = false) ?: 1
        if (formatVersion > FORMAT_VERSION) errors += "formatVersion: $formatVersion is newer than this app supports ($FORMAT_VERSION); update the app"

        val pids = reader.list(root, "pids", "").mapIndexedNotNull { i, item -> reader.pid(item, "pids[$i]") }
        val vehicles = reader.list(root, "vehicles", "").mapIndexedNotNull { i, item -> reader.vehicle(item, "vehicles[$i]") }
        val moduleNames = reader.list(root, "moduleNames", "").mapIndexedNotNull { i, item -> reader.moduleName(item, "moduleNames[$i]") }
        val dtcs = reader.descriptions(root["dtcs"], "dtcs")
        val dtcGroups = reader.list(root, "dtcGroups", "").mapIndexedNotNull { i, item -> reader.dtcGroup(item, "dtcGroups[$i]") }
        if (dtcGroups.isNotEmpty() && formatVersion < 2) errors += "dtcGroups: requires formatVersion 2 so older apps cannot silently lose manufacturer scoping"
        val id = reader.string(root, "id", "")
        val name = reader.string(root, "name", "")
        if (id == null || name == null) return DataPackResult(null, errors)
        val pack = DataPack(
            id = id, name = name,
            version = reader.int(root, "version", "", required = false) ?: 1,
            author = reader.string(root, "author", "", required = false),
            description = reader.string(root, "description", "", required = false),
            pids = pids, vehicles = vehicles, dtcs = dtcs, moduleNames = moduleNames, dtcGroups = dtcGroups,
        )
        errors += pack.problems()
        return DataPackResult(if (errors.isEmpty()) pack else null, errors.distinct())
    }

    /** Decodes, throwing [IllegalArgumentException] with all problems when the pack is invalid. */
    fun decodeOrThrow(json: String): DataPack {
        val result = decode(json)
        return result.pack ?: throw IllegalArgumentException(result.errors.joinToString("\n"))
    }

    private class Reader(val errors: MutableList<String>) {
        fun descriptions(raw: Any?, base: String): Map<String, String> = when (raw) {
            null -> emptyMap()
            is Map<*, *> -> {
                val result = linkedMapOf<String, String>()
                raw.forEach { (k, v) ->
                    if (k !is String || v !is String) {
                        errors += "$base.$k: description must be text"
                    } else {
                        val code = k.trim().uppercase(java.util.Locale.ROOT)
                        if (code in result) errors += "$base.$k: duplicate code after normalisation"
                        result[code] = v.trim()
                    }
                }
                result
            }
            else -> { errors += "$base: must be an object of code → description"; emptyMap() }
        }

        fun dtcGroup(item: Any?, p: String): DtcGroup? {
            val m = obj(item, p) ?: return null
            val before = errors.size
            val id = string(m, "id", p)
            val makes = list(m, "makes", p).mapIndexedNotNull { i, v ->
                (v as? String)?.trim() ?: run { errors += "$p.makes[$i]: must be text"; null }
            }
            val dtcs = descriptions(m["dtcs"], "$p.dtcs")
            val source = string(m, "source", p, required = false)
            return if (errors.size == before && id != null) DtcGroup(id, makes, dtcs, source) else null
        }

        fun path(base: String, key: String) = if (base.isEmpty()) key else "$base.$key"

        fun string(map: Map<*, *>, key: String, base: String, required: Boolean = true): String? = when (val v = map[key]) {
            null -> { if (required) errors += "${path(base, key)}: missing"; null }
            is String -> v.trim()
            else -> { errors += "${path(base, key)}: must be text"; null }
        }

        fun number(map: Map<*, *>, key: String, base: String, required: Boolean = true): Double? = when (val v = map[key]) {
            null -> { if (required) errors += "${path(base, key)}: missing"; null }
            is Double -> v
            is Number -> v.toDouble()
            else -> { errors += "${path(base, key)}: must be a number"; null }
        }

        fun int(map: Map<*, *>, key: String, base: String, required: Boolean = true): Int? =
            number(map, key, base, required)?.let { d ->
                if (d % 1.0 != 0.0) { errors += "${path(base, key)}: must be a whole number"; null } else d.toInt()
            }

        fun list(map: Map<*, *>, key: String, base: String): List<Any?> = when (val v = map[key]) {
            null -> emptyList()
            is List<*> -> v
            else -> { errors += "${path(base, key)}: must be a list"; emptyList() }
        }

        fun obj(item: Any?, path: String): Map<*, *>? = item as? Map<*, *> ?: run { errors += "$path: must be an object"; null }

        fun pid(item: Any?, p: String): CustomPid? {
            val m = obj(item, p) ?: return null
            val before = errors.size
            val id = string(m, "id", p)
            val name = string(m, "name", p)
            val request = string(m, "request", p)
            val formula = string(m, "formula", p)
            val unit = string(m, "unit", p, required = false) ?: ""
            val min = number(m, "min", p, required = false) ?: 0.0
            val max = number(m, "max", p, required = false) ?: 100.0
            val decimals = int(m, "decimals", p, required = false) ?: 1
            val header = string(m, "header", p, required = false)?.uppercase()?.ifEmpty { null }
            val responseHeader = string(m, "responseHeader", p, required = false)?.uppercase()?.ifEmpty { null }
            val notes = string(m, "notes", p, required = false)
            if (errors.size > before || id == null || name == null || request == null || formula == null) return null
            val link = DiagnosticLinkCodec.decode(m["link"], "$p.link", errors)
            val offset = int(m, "dataOffset", p, false) ?: 0
            val records = com.bod3.app.recipe.JsonFields(errors).strings(m, "sourceRecords", p)
            return CustomPid(id, name, request.uppercase(), formula, unit, min, max, decimals, header, responseHeader, notes, link, offset, records)
        }

        fun ecu(item: Any?, p: String): EcuDefinition? {
            val m = obj(item, p) ?: return null
            val before = errors.size
            val name = string(m, "name", p)
            val request = string(m, "requestId", p)
            val response = string(m, "responseId", p)
            val protocolName = string(m, "protocol", p, required = false)
            val protocol = protocolName?.let { n -> EcuProtocol.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: run {
                errors += "$p.protocol: must be UDS, OBD or KWP"; null
            } } ?: EcuProtocol.UDS
            val addressing = m["addressing"]
            if (addressing != null) {
                val bits = when (addressing) {
                    is String -> when (addressing.lowercase()) { "11", "11-bit" -> 11; "29", "29-bit" -> 29; else -> null }
                    is Number -> addressing.toInt().takeIf { addressing.toDouble() == it.toDouble() && it in listOf(11, 29) }
                    else -> null
                }
                if (bits == null) errors += "$p.addressing: must be 11 or 29"
                else if (request?.length != (if (bits == 11) 3 else 8) || response?.length != (if (bits == 11) 3 else 8)) errors += "$p.addressing: ID lengths do not match $bits-bit addressing"
            }
            if (errors.size > before || name == null || request == null || response == null) return null
            return EcuDefinition(
                address = string(m, "address", p, required = false)?.ifEmpty { null },
                name = name, requestId = request.uppercase(), responseId = response.uppercase(), protocol = protocol,
                notes = string(m, "notes", p, required = false),
                link = DiagnosticLinkCodec.decode(m["link"], "$p.link", errors),
                sourceRecords = com.bod3.app.recipe.JsonFields(errors).strings(m, "sourceRecords", p),
            )
        }

        fun moduleName(item: Any?, p: String): ModuleNameEntry? {
            val m = obj(item, p) ?: return null
            val before = errors.size
            val request = string(m, "requestId", p)
            val name = string(m, "name", p)
            val makes = list(m, "makes", p).mapIndexedNotNull { i, v ->
                (v as? String)?.trim() ?: run { errors += "$p.makes[$i]: must be text"; null }
            }
            if (errors.size > before || request == null || name == null) return null
            return ModuleNameEntry(
                requestId = request.uppercase(), name = name,
                responseId = string(m, "responseId", p, required = false)?.uppercase()?.ifEmpty { null },
                address = string(m, "address", p, required = false)?.ifEmpty { null },
                makes = makes,
            )
        }

        fun vehicle(item: Any?, p: String): VehicleDefinition? {
            val m = obj(item, p) ?: return null
            val make = string(m, "make", p)
            val model = string(m, "model", p)
            val ecus = list(m, "ecus", p).mapIndexedNotNull { i, e -> ecu(e, "$p.ecus[$i]") }
            if (make == null || model == null) return null
            return VehicleDefinition(make, model, string(m, "years", p, required = false), ecus, string(m, "notes", p, required = false))
        }
    }
}
