package com.bod3.app.datapack

import com.bod3.app.obd.kwp.DiagnosticLink
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.KwpServices
import com.bod3.app.obd.pid.Formula
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ReadOnlyPolicy

private fun String.isHex() = isNotEmpty() && all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

internal fun parseHexBytes(text: String): List<Int> {
    val clean = text.filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0 && clean.isHex()) { "\"$text\" is not a sequence of hex bytes" }
    return clean.chunked(2).map { it.toInt(16) }
}

/** A read-only prerequisite on the SAME physical ECU, evaluated once per live run/link epoch. */
data class PidSupportCheck(
    val request: String,
    val formula: String,
    val dataOffset: Int = 0,
    val sourceRecords: List<String> = emptyList(),
    /** A801 returns [local ID, length, support bytes] records, NOT live sensor samples. */
    val recordId: Int? = null,
) {
    private val parsed: Formula? by lazy { Formula.parseOrNull(formula) }
    val requestBytes: List<Int> get() = parseHexBytes(request)

    fun problems(link: DiagnosticLink?): List<String> = buildList {
        val bytes = runCatching { requestBytes }.getOrNull()
        // Only reads with a completely echoed single identifier can be prerequisites. In
        // particular, no clear/session/security/tester-present/write can hide in this list.
        val shape = bytes != null && when (bytes.firstOrNull()) {
            0x01, 0x09, 0x1A, 0x21 -> bytes.size == 2
            0x22 -> bytes.size == 3
            0xA8 -> bytes == listOf(0xA8, 0x01) && recordId != null
            else -> false
        }
        if (recordId != null && (recordId !in 0..255 || bytes != listOf(0xA8, 0x01))) add("recordId: only an A801 support-record ID in 0-255 is allowed")
        if (!shape || !(if (link?.dialect == DiagnosticDialect.KWP2000)
                KwpServices.readAllowed(bytes) || (bytes.firstOrNull() in ReadOnlyPolicy.obdServices && ReadOnlyPolicy.isAllowed(bytes))
            else ReadOnlyPolicy.isAllowed(bytes))) add("request: must be an exact, read-only single-identifier request")
        if (formula.length > 2048 || parsed == null || parsed!!.bytesNeeded == 0) add("formula: needs a bounded byte-dependent arithmetic expression")
        if (dataOffset !in 0..4095) add("dataOffset: must be 0-4095")
        if (sourceRecords.any(String::isBlank)) add("sourceRecords: must not contain empty IDs")
    }

    companion object {
        /**
         * A801 alone permits multiple replies, with the exact E801 echo on EVERY message.
         * COMM GetKwpRecData retains the first service/identifier and strips both from subsequent
         * A8 messages; SendCmdAndGetReceive_EX drops the first service, and FDATA skips the remaining
         * identifier before reading TLVs. Ordinary 21xx samples/prerequisites stay single-message.
         */
        internal fun normalizeReply(request: List<Int>, payloads: List<List<Int>>): List<Int>? {
            if (request != listOf(0xA8, 1)) return payloads.singleOrNull()
            if (payloads.size !in 1..256 || payloads.any { it.size <= 2 || it.take(2) != listOf(0xE8, 1) || it.any { b -> b !in 0..255 } }) return null
            if (payloads.sumOf { it.size.toLong() - 2 } > 8192) return null
            return listOf(0xE8, 1) + payloads.flatMap { it.drop(2) }
        }
    }

    fun accepts(payload: List<Int>): Boolean {
        val data = pidReplyData(runCatching { requestBytes }.getOrNull(), payload, 0) ?: return false
        val bytes = if (recordId == null) data else {
            // Validate the WHOLE reply before admitting any record. A truncated or duplicate
            // trailing record cannot authorize a feature just because an earlier one matched.
            if (data.size > 8192) return false
            val seen = mutableSetOf<Int>(); var at = 0; var found: List<Int>? = null
            while (at < data.size) {
                if (at + 2 > data.size || !seen.add(data[at])) return false
                val id = data[at]; val size = data[at + 1]; at += 2
                if (size !in 0..255 || at + size > data.size) return false
                if (id == recordId) found = data.subList(at, at + size)
                at += size
            }
            found ?: return false
        }
        if (dataOffset !in 0..bytes.size) return false
        return parsed?.evaluate(bytes.drop(dataOffset))?.let { it != 0.0 } == true
    }
}

private fun pidReplyData(request: List<Int>?, payload: List<Int>, offset: Int): List<Int>? {
    if (request.isNullOrEmpty() || offset !in 0..4095 || payload.size < request.size + offset ||
        payload.any { it !in 0..255 } || payload.firstOrNull() != request[0] + 0x40 ||
        payload.subList(1, request.size) != request.drop(1)) return null
    return payload.drop(request.size + offset)
}

/**
 * A user-defined live value: a read request, an optional ECU address, and a formula over the reply's
 * data bytes. Requests must be read-only (e.g. `010C`, `22F40C`); the positive reply echo (`41 0C`,
 * `62 F4 0C`) is stripped before the formula sees the data, so `A` is the first data byte.
 */
data class CustomPid(
    val id: String,
    val name: String,
    val request: String,
    val formula: String,
    val unit: String = "",
    val min: Double = 0.0,
    val max: Double = 100.0,
    val decimals: Int = 1,
    /** CAN request ID (3 or 8 hex digits) or null to broadcast. */
    val header: String? = null,
    /** Expected CAN response ID, or null for the standard pair / automatic receive. */
    val responseHeader: String? = null,
    val notes: String? = null,
    val link: DiagnosticLink? = null,
    val dataOffset: Int = 0,
    val sourceRecords: List<String> = emptyList(),
    /** Ordered prerequisites, potentially on different local IDs, but never on another ECU. */
    val supportChecks: List<PidSupportCheck> = emptyList(),
) {
    /** Validation errors (empty when valid). Field names match the JSON keys. */
    fun problems(): List<String> = buildList {
        if (id.isBlank()) add("id: must not be empty")
        if (name.isBlank()) add("name: must not be empty")
        val bytes = runCatching { parseHexBytes(request) }.getOrNull()
        when {
            bytes == null || bytes.isEmpty() -> add("request: must be hex bytes, e.g. 010C or 22F40C")
            ReadOnlyPolicy.isClearRequest(bytes) || !(if (link?.dialect == DiagnosticDialect.KWP2000) KwpServices.readAllowed(bytes) else ReadOnlyPolicy.isAllowed(bytes)) ->
                add("request: %02X is not a read service; custom PIDs may only read (01, 09, 22 …)".format(bytes[0]))
        }
        try {
            Formula.parse(formula)
        } catch (e: IllegalArgumentException) {
            add("formula: ${e.message}")
        }
        if (supportChecks.size > 32) add("supportChecks: at most 32 prerequisite reads are allowed")
        if (supportChecks.isNotEmpty() && (header == null || responseHeader == null)) add("supportChecks: an exact physical ECU address pair is required")
        supportChecks.forEachIndexed { i, check -> addAll(check.problems(link).map { "supportChecks[$i].$it" }) }
        if (!(min < max)) add("min/max: min must be smaller than max")
        if (decimals !in 0..6) add("decimals: must be 0-6")
        if (dataOffset !in 0..4095) add("dataOffset: must be 0-4095")
        addAll(link?.problems().orEmpty())
        if (link?.kLine != null) {
            if (header == null || responseHeader == null) add("K-line PID needs an exact address pair")
            else addAll(link.addressProblems(header, responseHeader))
        } else {
            header?.let { if (!(it.isHex() && (it.length == 3 || it.length == 8))) add("header: must be a 3- or 8-digit hex CAN ID") }
            responseHeader?.let {
                if (header == null) add("responseHeader: needs a header")
                if (!(it.isHex() && (it.length == 3 || it.length == 8))) add("responseHeader: must be a 3- or 8-digit hex CAN ID")
            }
        }
    }

    val isValid: Boolean get() = problems().isEmpty()

    val key: String get() = "custom:$id"

    val requestBytes: List<Int> get() = parseHexBytes(request)

    val target: EcuTarget get() = header?.let { EcuTarget.Physical(it.uppercase(), responseHeader?.uppercase(), link = link) } ?: EcuTarget.Broadcast

    private val parsedFormula: Formula? by lazy { Formula.parseOrNull(formula) }

    /** Decodes a positive reply payload (starting with the response service byte), or null. */
    fun decode(payload: List<Int>): Double? = pidReplyData(runCatching { requestBytes }.getOrNull(), payload, dataOffset)
        ?.let { parsedFormula?.evaluate(it) }

    fun format(value: Double): String = if (decimals == 0) Math.round(value).toString() else "%.${decimals}f".format(value)
}

enum class EcuProtocol(val label: String) {
    /** ISO 14229 UDS on CAN (VW group MQB and later, most modern ECUs). */
    UDS("UDS"),

    /** Only OBD services (engine/transmission on many older cars). */
    OBD("OBD"),

    /** KWP enhanced services are not interchangeable with UDS; use documented read definitions. */
    KWP("KWP"),
}

/** A control unit of a vehicle: name and CAN addressing. */
data class EcuDefinition(
    /** Manufacturer address/short code, e.g. `09` for VW central electronics. Optional. */
    val address: String? = null,
    val name: String,
    val requestId: String,
    val responseId: String,
    val protocol: EcuProtocol = EcuProtocol.UDS,
    val notes: String? = null,
    val link: DiagnosticLink? = null,
    val sourceRecords: List<String> = emptyList(),
    /** Exact selected-ECU reads; an explicit empty set suppresses legacy generic UDS defaults. */
    val diagnostics: com.bod3.app.diagnostics.EcuReadDefinitions? = null,
) {
    val is29Bit: Boolean get() = requestId.length == 8

    val target: EcuTarget.Physical get() = EcuTarget.Physical(requestId.uppercase(), responseId.uppercase(), link = link)

    /** Stable key within a vehicle (the request ID). */
    val key: String get() = requestId.uppercase()

    val display: String get() = listOfNotNull(address, name).joinToString(" · ")

    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("name: must not be empty")
        fun check(field: String, id: String) {
            if (!(id.isHex() && (id.length == 3 || id.length == 8))) add("$field: must be a 3-digit (11-bit) or 8-digit (29-bit) hex CAN ID")
            else if ((id.toLongOrNull(16) ?: Long.MAX_VALUE) > if (id.length == 3) 0x7FFL else 0x1FFFFFFFL) add("$field: outside the ${if (id.length == 3) 11 else 29}-bit CAN ID range")
        }
        addAll(link?.problems().orEmpty())
        if (link?.kLine != null) addAll(link.addressProblems(requestId, responseId)) else {
            check("requestId", requestId)
            check("responseId", responseId)
        }
        if (requestId.length != responseId.length) add("responseId: must use the same ID length as requestId")
        if (requestId.equals(responseId, ignoreCase = true)) add("responseId: must differ from requestId")
        addAll(diagnostics?.problems(protocol, link).orEmpty().map { "diagnostics.$it" })
    }
}

/** A make/model with its control units, like the vehicle selection of Car Scanner. */
data class VehicleDefinition(
    val make: String,
    val model: String,
    val years: String? = null,
    val ecus: List<EcuDefinition> = emptyList(),
    val notes: String? = null,
) {
    val key: String get() = "$make|$model"
    val title: String get() = listOfNotNull(make, model, years).joinToString(" ")

    fun problems(): List<String> = buildList {
        if (make.isBlank()) add("make: must not be empty")
        if (model.isBlank()) add("model: must not be empty")
        ecus.forEachIndexed { i, ecu -> ecu.problems().forEach { add("ecus[$i].$it") } }
        ecus.groupBy { it.key }.filter { it.value.size > 1 }.keys.forEach { add("ecus: request ID $it is used twice") }
    }
}

/**
 * A name for a diagnostic address that applies to every vehicle of the listed [makes] (empty = any
 * make), e.g. VW group address `09 Central electronics` on request ID `70E`. Used to name modules
 * found by a scan when the selected vehicle's ECU list does not cover them.
 */
data class ModuleNameEntry(
    val requestId: String,
    val name: String,
    /** Expected response ID; null matches any. */
    val responseId: String? = null,
    /** Manufacturer address/short code shown before the name, e.g. `09`. */
    val address: String? = null,
    val makes: List<String> = emptyList(),
) {
    val display: String get() = listOfNotNull(address, name).joinToString(" · ")

    fun appliesTo(make: String?): Boolean =
        makes.isEmpty() || (make != null && makes.any { it.equals(make.trim(), ignoreCase = true) })

    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("name: must not be empty")
        if (!(requestId.isHex() && (requestId.length == 3 || requestId.length == 8))) add("requestId: must be a 3- or 8-digit hex CAN ID")
        responseId?.let { if (!(it.isHex() && it.length == requestId.length)) add("responseId: must be a hex CAN ID of the same length as requestId") }
        if (makes.any { it.isBlank() }) add("makes: entries must not be empty")
    }
}

/** Manufacturer-scoped fault text. It never applies when the vehicle's make is unknown. */
data class DtcGroup(
    /** Stable import/replace key, not a vehicle command. */
    val id: String,
    val makes: List<String>,
    val dtcs: Map<String, String>,
    /** Human-readable origin, retained when this group is imported/exported. */
    val source: String? = null,
) {
    fun appliesTo(make: String?): Boolean =
        !make.isNullOrBlank() && makes.any { it.equals(make.trim(), ignoreCase = true) }

    fun problems(): List<String> = buildList {
        if (id.isBlank()) add("id: must not be empty")
        if (makes.isEmpty() || makes.any { it.isBlank() }) add("makes: list at least one non-empty manufacturer")
        if (makes.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != makes.size) add("makes: duplicate manufacturer")
        if (dtcs.isEmpty()) add("dtcs: must contain at least one description")
        if (source != null && source.isBlank()) add("source: must not be empty when provided")
        dtcs.forEach { (code, text) ->
            if (!DataPack.DTC_KEY.matches(code)) add("dtcs.$code: not a DTC like P0420 or P0420-13")
            if (text.isBlank()) add("dtcs.$code: description must not be empty")
        }
    }
}

/**
 * A data pack: custom PIDs, vehicle ECU lists, module names and DTC descriptions. Bundled packs ship in the app's
 * assets; the user's own additions live in the user pack. Manufacturer-specific texts belong in [dtcGroups],
 * never [dtcs], so a Toyota code cannot change a VW (or unknown vehicle) description.
 */
data class DataPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val author: String? = null,
    val description: String? = null,
    val pids: List<CustomPid> = emptyList(),
    val vehicles: List<VehicleDefinition> = emptyList(),
    /** Code (`P0420`, or UDS `P0420-13`) to description. */
    val dtcs: Map<String, String> = emptyMap(),
    /** Generic names for diagnostic addresses, per make. */
    val moduleNames: List<ModuleNameEntry> = emptyList(),
    /** v2: passive DTC reference groups, selected only for an explicitly known make. */
    val dtcGroups: List<DtcGroup> = emptyList(),
) {
    val dtcDescriptionCount: Int get() = dtcs.size + dtcGroups.sumOf { it.dtcs.size }

    fun problems(): List<String> = buildList {
        if (id.isBlank()) add("id: must not be empty")
        if (name.isBlank()) add("name: must not be empty")
        pids.forEachIndexed { i, pid -> pid.problems().forEach { add("pids[$i] (${pid.name.ifBlank { pid.id }}).$it") } }
        pids.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("pids: id \"$it\" is used twice") }
        vehicles.forEachIndexed { i, v -> v.problems().forEach { add("vehicles[$i] (${v.title}).$it") } }
        moduleNames.forEachIndexed { i, m -> m.problems().forEach { add("moduleNames[$i] (${m.name}).$it") } }
        dtcs.keys.filterNot { DTC_KEY.matches(it) }.forEach { add("dtcs: \"$it\" is not a DTC like P0420 or P0420-13") }
        dtcGroups.forEachIndexed { i, group -> group.problems().forEach { add("dtcGroups[$i].$it") } }
        dtcGroups.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("dtcGroups: id \"$it\" is used twice") }
    }

    companion object {
        val DTC_KEY = Regex("[PCBU][0-3][0-9A-F]{3}(-[0-9A-F]{2})?")
        const val USER_PACK_ID = "user"
        fun emptyUserPack() = DataPack(id = USER_PACK_ID, name = "My additions")
    }
}
