package com.bod3.app.profile

import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.scan.Bus
import com.bod3.app.obd.scan.DetectionMethod

/** Everything about one vehicle profile, as exported to / imported from a file. */
data class ProfileBundle(
    val profile: StoredProfile,
    val modules: List<StoredModule>,
    val rawLog: List<RawExchange>,
    val extras: ProfileExtras,
)

data class ProfileImportResult(val bundle: ProfileBundle?, val errors: List<String>) {
    val isValid: Boolean get() = bundle != null && errors.isEmpty()
}

/**
 * JSON file format for sharing a vehicle profile:
 * ```
 * { "format": "bod3-vehicle-profile", "formatVersion": 1, "exportedAtMs": …, "app": "bod3",
 *   "profile": { "vin": "…", "make": "…", "model": "…", "year": 2018, "descriptionSource": "VPIC", … },
 *   "modules": [ { "bus": "CAN_11", "requestHeader": "70E", "responseHeader": "778", "name": "…", … } ],
 *   "extras": { "manualEcus": [ … ], "moduleNames": { "70E": "BCM" } },
 *   "rawLog": [ { "sequence": 1, "timestampMs": …, "header": "7E0", "command": "22F190", "response": "…", "durationMs": 40 } ] }
 * ```
 * Decoding never throws for bad input: every problem is reported with its JSON path.
 */
object ProfileTransferCodec {
    const val FORMAT = "bod3-vehicle-profile"
    const val FORMAT_VERSION = 1

    fun encode(bundle: ProfileBundle, exportedAtMs: Long): String = JsonWriter.write(
        linkedMapOf(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "app" to "bod3",
            "exportedAtMs" to exportedAtMs,
            "profile" to encodeProfile(bundle.profile),
            "modules" to bundle.modules.map(::encodeModule),
            "extras" to ProfileExtrasCodec.encode(bundle.extras),
            "rawLog" to bundle.rawLog.map(::encodeExchange),
        ),
    )

    fun fileName(vin: String, timeMs: Long): String = "bod3-vehicle-$vin-$timeMs.json"

    private fun encodeProfile(p: StoredProfile) = linkedMapOf(
        "vin" to p.vin, "make" to p.make, "model" to p.model, "year" to p.year,
        "descriptionSource" to p.descriptionSource, "descriptionJson" to p.descriptionJson, "protocol" to p.protocol,
        "createdAtMs" to p.createdAtMs, "updatedAtMs" to p.updatedAtMs, "lastScanAtMs" to p.lastScanAtMs,
    ).filterValues { it != null }

    private fun encodeModule(m: StoredModule) = linkedMapOf(
        "bus" to m.bus, "requestHeader" to m.requestHeader, "responseHeader" to m.responseHeader, "name" to m.name,
        "detectedBy" to m.detectedBy, "obd" to m.obd, "negativeReply" to m.negativeReply, "firstReply" to m.firstReply,
        "partNumber" to m.partNumber, "softwareVersion" to m.softwareVersion, "hardwareVersion" to m.hardwareVersion,
        "coding" to m.coding, "dtcCount" to m.dtcCount, "identifiersJson" to m.identifiersJson, "dtcsJson" to m.dtcsJson,
        "obdEcuName" to m.obdEcuName, "calibrationId" to m.calibrationId, "scannedAtMs" to m.scannedAtMs,
    ).filterValues { it != null }

    private fun encodeExchange(e: RawExchange) = linkedMapOf(
        "sequence" to e.sequence, "timestampMs" to e.timestampMs, "header" to e.header, "command" to e.command,
        "response" to e.response, "durationMs" to e.durationMs, "error" to e.error,
    ).filterValues { it != null }

    fun decode(json: String): ProfileImportResult {
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return ProfileImportResult(null, listOf("Not valid JSON: ${e.message}"))
        }
        if (root !is Map<*, *>) return ProfileImportResult(null, listOf("The file must contain a JSON object"))
        val errors = mutableListOf<String>()
        val r = Fields(errors)
        when (val format = root["format"]) {
            FORMAT -> Unit
            "bod3-datapack" -> return ProfileImportResult(null, listOf("This is a data pack, not a vehicle profile: import it under Data packs"))
            null -> errors += "format: missing (expected \"$FORMAT\")"
            else -> errors += "format: expected \"$FORMAT\", got \"$format\""
        }
        val version = r.long(root, "formatVersion", "", required = false) ?: FORMAT_VERSION.toLong()
        if (version > FORMAT_VERSION) errors += "formatVersion: $version is newer than this app supports ($FORMAT_VERSION); update the app"

        val p = root["profile"] as? Map<*, *>
        if (p == null) {
            errors += "profile: missing or not an object"
            return ProfileImportResult(null, errors)
        }
        val vin = r.string(p, "vin", "profile")?.uppercase()
        if (vin != null && !VinRules.isValid(vin)) errors += "profile.vin: \"$vin\" is not a valid 17-character VIN"
        val created = r.long(p, "createdAtMs", "profile") ?: 0L
        val profile = vin?.let {
            StoredProfile(
                vin = it,
                make = r.string(p, "make", "profile", required = false),
                model = r.string(p, "model", "profile", required = false),
                year = r.long(p, "year", "profile", required = false)?.toInt(),
                descriptionSource = r.string(p, "descriptionSource", "profile", required = false)?.also { src ->
                    if (DescriptionSource.entries.none { d -> d.name == src }) errors += "profile.descriptionSource: unknown value \"$src\""
                },
                descriptionJson = r.string(p, "descriptionJson", "profile", required = false),
                protocol = r.string(p, "protocol", "profile", required = false),
                createdAtMs = created,
                updatedAtMs = r.long(p, "updatedAtMs", "profile", required = false) ?: created,
                lastScanAtMs = r.long(p, "lastScanAtMs", "profile", required = false),
            )
        }

        val modules = r.list(root, "modules", "").mapIndexedNotNull { i, item -> module(item, "modules[$i]", vin ?: "", r) }
        modules.groupBy { "${it.requestHeader}>${it.responseHeader}" }.filter { it.value.size > 1 }.keys
            .forEach { errors += "modules: $it is listed twice" }
        val log = r.list(root, "rawLog", "").mapIndexedNotNull { i, item -> exchange(item, "rawLog[$i]", r) }
        val extras = ProfileExtrasCodec.decode(root["extras"], "extras", errors)

        if (profile == null || errors.isNotEmpty()) return ProfileImportResult(null, errors.distinct())
        return ProfileImportResult(ProfileBundle(profile, modules, log, extras), emptyList())
    }

    private fun module(item: Any?, p: String, vin: String, r: Fields): StoredModule? {
        val m = item as? Map<*, *> ?: run { r.errors += "$p: must be an object"; return null }
        val before = r.errors.size
        val bus = r.string(m, "bus", p)
        if (bus != null && Bus.entries.none { it.name == bus }) r.errors += "$p.bus: must be CAN_11 or CAN_29"
        val request = r.string(m, "requestHeader", p)?.uppercase()
        val response = r.string(m, "responseHeader", p)?.uppercase()
        val expected = if (bus == Bus.CAN_29.name) 8 else 3
        for ((field, id) in listOf("requestHeader" to request, "responseHeader" to response)) {
            if (id != null && !(id.length == expected && id.all { it in '0'..'9' || it in 'A'..'F' })) {
                r.errors += "$p.$field: must be a $expected-digit hex CAN ID for $bus"
            }
        }
        val detectedBy = r.string(m, "detectedBy", p, required = false) ?: DetectionMethod.TESTER_PRESENT.name
        if (DetectionMethod.entries.none { it.name == detectedBy }) r.errors += "$p.detectedBy: unknown value \"$detectedBy\""
        val identifiers = r.string(m, "identifiersJson", p, required = false) ?: "[]"
        if (runCatching { MiniJson.parse(identifiers) as List<*> }.isFailure) r.errors += "$p.identifiersJson: must be a JSON list"
        val dtcs = r.string(m, "dtcsJson", p, required = false) ?: "{\"kind\":\"none\"}"
        if (runCatching { MiniJson.parse(dtcs) as Map<*, *> }.isFailure) r.errors += "$p.dtcsJson: must be a JSON object"
        if (r.errors.size > before || bus == null || request == null || response == null) return null
        return StoredModule(
            vin = vin, bus = bus, requestHeader = request, responseHeader = response,
            name = r.string(m, "name", p, required = false),
            detectedBy = detectedBy,
            obd = m["obd"] as? Boolean ?: false,
            negativeReply = m["negativeReply"] as? Boolean ?: false,
            firstReply = r.string(m, "firstReply", p, required = false),
            partNumber = r.string(m, "partNumber", p, required = false),
            softwareVersion = r.string(m, "softwareVersion", p, required = false),
            hardwareVersion = r.string(m, "hardwareVersion", p, required = false),
            coding = r.string(m, "coding", p, required = false),
            dtcCount = r.long(m, "dtcCount", p, required = false)?.toInt(),
            identifiersJson = identifiers,
            dtcsJson = dtcs,
            obdEcuName = r.string(m, "obdEcuName", p, required = false),
            calibrationId = r.string(m, "calibrationId", p, required = false),
            scannedAtMs = r.long(m, "scannedAtMs", p, required = false) ?: 0L,
        )
    }

    private fun exchange(item: Any?, p: String, r: Fields): RawExchange? {
        val m = item as? Map<*, *> ?: run { r.errors += "$p: must be an object"; return null }
        val command = r.string(m, "command", p) ?: return null
        return RawExchange(
            sequence = r.long(m, "sequence", p, required = false)?.toInt() ?: 0,
            timestampMs = r.long(m, "timestampMs", p, required = false) ?: 0L,
            header = r.string(m, "header", p, required = false),
            command = command,
            response = r.string(m, "response", p, required = false) ?: "",
            durationMs = r.long(m, "durationMs", p, required = false) ?: 0L,
            error = r.string(m, "error", p, required = false),
        )
    }

    private class Fields(val errors: MutableList<String>) {
        private fun path(base: String, key: String) = if (base.isEmpty()) key else "$base.$key"

        fun string(map: Map<*, *>, key: String, base: String, required: Boolean = true): String? = when (val v = map[key]) {
            null -> { if (required) errors += "${path(base, key)}: missing"; null }
            is String -> v
            else -> { errors += "${path(base, key)}: must be text"; null }
        }

        fun long(map: Map<*, *>, key: String, base: String, required: Boolean = true): Long? = when (val v = map[key]) {
            null -> { if (required) errors += "${path(base, key)}: missing"; null }
            is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0) d.toLong() else { errors += "${path(base, key)}: must be a whole number"; null } }
            else -> { errors += "${path(base, key)}: must be a number"; null }
        }

        fun list(map: Map<*, *>, key: String, base: String): List<Any?> = when (val v = map[key]) {
            null -> emptyList()
            is List<*> -> v
            else -> { errors += "${path(base, key)}: must be a list"; emptyList() }
        }
    }
}
