package com.bod3.app.diagnostics

import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.PidSupportCheck
import com.bod3.app.datapack.PidSupportCheckCodec
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.DiagnosticLink
import com.bod3.app.obd.kwp.KwpServices
import com.bod3.app.obd.session.ReadOnlyPolicy
import com.bod3.app.obd.uds.DtcDecoder
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.obd.uds.DtcStatusMeaning
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields

/** A complete positive-reply prefix, then an explicitly bounded data window. No inferred byte origin. */
data class DiagnosticRead(
    val id: String,
    val name: String,
    val request: String,
    val responsePrefix: String,
    val dataOffset: Int = 0,
    val minBytes: Int = 0,
    val maxBytes: Int = 4096,
    val sourceRecords: List<String> = emptyList(),
    val supportChecks: List<PidSupportCheck> = emptyList(),
    /** Optional selected width; maxBytes still bounds the ENTIRE remaining reply before slicing. */
    val dataBytes: Int? = null,
) {
    val requestBytes: List<Int> get() = Hex.parse(request).orEmpty()
    val prefixBytes: List<Int> get() = Hex.parse(responsePrefix).orEmpty()

    fun problems(protocol: EcuProtocol, link: DiagnosticLink? = null): List<String> = buildList {
        if (id.isBlank() || name.isBlank()) add("id/name: must not be empty")
        val r = requestBytes; val p = prefixBytes
        val allowed = if (protocol == EcuProtocol.KWP) KwpServices.readAllowed(r) || r == listOf(0x09, 0x04) else ReadOnlyPolicy.isAllowed(r)
        if (r.isEmpty() || !allowed || r.first() !in setOf(0x09, 0x1A, 0x21, 0x22, 0x12, 0x13, 0x18, 0x19)) add("request: not an identification/fault read for this dialect")
        if (p.isEmpty() || r.isEmpty() || p.first() != r.first() + 0x40) add("responsePrefix: needs the exact positive service")
        val echo = when (r.firstOrNull()) { 0x09, 0x1A, 0x21 -> 1; 0x22 -> 2; 0x19 -> if (protocol == EcuProtocol.UDS) 1 else 0; else -> 0 }
        if (r.size <= echo || p.size <= echo || p.drop(1).take(echo) != r.drop(1).take(echo)) add("responsePrefix: identifier/subfunction echo does not match request")
        if (dataOffset !in 0..4095 || minBytes !in 0..4096 || maxBytes !in minBytes..4096 ||
            p.size.toLong() + dataOffset + maxBytes > 8192 ||
            (dataBytes != null && (dataBytes !in 1..4096 || dataBytes > minBytes))) add("data window: invalid bounds")
        if (sourceRecords.any { it.isBlank() } || sourceRecords.distinct().size != sourceRecords.size) add("sourceRecords: blank or duplicate ID")
        if (supportChecks.size > 32) add("supportChecks: at most 32 ordered prerequisites")
        supportChecks.forEachIndexed { i, check -> addAll(check.problems(link).map { "supportChecks[$i].$it" }) }
    }

    /** A short/mismatched/oversized positive response is a failure, never an empty successful read. */
    fun data(payload: List<Int>): List<Int>? {
        val prefix = prefixBytes
        if (prefix.isEmpty() || payload.any { it !in 0..255 } || payload.take(prefix.size) != prefix) return null
        val start = prefix.size.toLong() + dataOffset
        if (start !in 0L..payload.size.toLong()) return null
        val size = payload.size - start.toInt()
        if (size !in minBytes..maxBytes || (dataBytes != null && dataBytes !in 1..size)) return null
        val data = payload.drop(start.toInt())
        return dataBytes?.let(data::take) ?: data
    }
}

enum class IdentificationEncoding { ASCII, HEX, BYTE_ENUM, ASCII_RECORDS }

/** Bounded compiled enum values, not a vendor-script interpreter or a calibration-ID inference. */
data class IdentificationRead(
    val read: DiagnosticRead,
    val encoding: IdentificationEncoding = IdentificationEncoding.HEX,
    val byteValues: Map<Int, String> = emptyMap(),
    val defaultText: String? = null,
    /** Fixed-width records, compiled from an exact reader (not an inferred UDS DID). */
    val recordBytes: Int? = null,
) {
    fun problems(): List<String> = buildList {
        if (encoding == IdentificationEncoding.BYTE_ENUM) {
            if (read.dataBytes != 1) add("BYTE_ENUM requires exactly one selected data byte")
            if (byteValues.isEmpty() || byteValues.size > 256 || byteValues.keys.any { it !in 0..255 }) add("BYTE_ENUM requires exact byte keys")
            if (defaultText.isNullOrBlank()) add("BYTE_ENUM requires an explicit source default")
        } else if (byteValues.isNotEmpty() || defaultText != null) add("byteValues/defaultText are only valid for BYTE_ENUM")
        if (encoding == IdentificationEncoding.ASCII_RECORDS) {
            val width = recordBytes
            if (width == null || width !in 1..255 || read.minBytes < width || read.maxBytes > 4096 ||
                read.minBytes % (width ?: 1).coerceAtLeast(1) != 0 || read.maxBytes % (width ?: 1).coerceAtLeast(1) != 0 || read.dataBytes != null)
                add("ASCII_RECORDS needs an explicit whole-record data window and width")
        } else if (recordBytes != null) add("recordBytes is only valid for ASCII_RECORDS")
        if ((byteValues.values + listOfNotNull(defaultText)).any { it.isBlank() || it.length > 4096 || '\u0000' in it }) add("Invalid identification enum text")
    }
    /** Structural/text validity is also checked in fresh bring-up; invalid text is not ECU proof. */
    fun accepts(data: List<Int>): Boolean = data.isNotEmpty() && data.all { it in 0..255 } &&
        (encoding != IdentificationEncoding.ASCII_RECORDS || recordText(data) != null)

    private fun recordText(data: List<Int>): String? {
        val width = recordBytes?.takeIf { it in 1..255 } ?: return null
        if (data.isEmpty() || data.size > 4096 || data.size % width != 0) return null
        val rows = data.chunked(width).map { row ->
            val text = row.takeWhile { it != 0 }
            if (row.drop(text.size).any { it != 0 } || text.any { it !in 32..126 }) return null
            text.map(Int::toChar).joinToString("").trimEnd().takeIf { it.isNotBlank() } ?: return null
        }
        return rows.joinToString(" · ")
    }
    fun display(data: List<Int>): String? = when (encoding) {
        IdentificationEncoding.BYTE_ENUM -> data.singleOrNull()?.let { byteValues[it] ?: defaultText }
        IdentificationEncoding.ASCII_RECORDS -> recordText(data)
        else -> null
    }
}

/** Layout of one ECU's fault records, not a global assumption about service 18/19. */
data class FaultLayout(
    val recordBytes: Int,
    val codeOffset: Int,
    val codeBytes: Int,
    val statusOffset: Int? = null,
    val statusMeaning: DtcStatusMeaning = DtcStatusMeaning.RAW,
    /** Big-endian record count at the start of the selected data window, or 0 for no count. */
    val countBytes: Int = 0,
    val skipZeroCode: Boolean = false,
) : FaultRecordLayout {
    override fun problems(): List<String> = buildList {
        if (recordBytes !in 2..32 || codeBytes !in 2..3 || codeOffset < 0 || codeOffset.toLong() + codeBytes > recordBytes) add("code: invalid record width/position")
        if (statusOffset != null && (statusOffset !in 0 until recordBytes || statusOffset in codeOffset until (codeOffset + codeBytes))) add("statusOffset: outside record or overlaps code")
        if (countBytes !in 0..2) add("countBytes: must be 0, 1 or 2")
        if (statusMeaning == DtcStatusMeaning.UDS && statusOffset == null) add("statusMeaning: UDS bit meanings need a status byte")
        if (statusMeaning == DtcStatusMeaning.TOYOTA) add("statusMeaning: Toyota states need the explicit Toyota assembly/query layout")
    }

    override fun decode(data: List<Int>, source: String): DtcReadResult {
        if (problems().isNotEmpty() || data.size < countBytes || data.any { it !in 0..255 }) return DtcReadResult.Failed("Invalid fault-record layout or short count")
        val body = data.drop(countBytes)
        if (body.size % recordBytes != 0) return DtcReadResult.Failed("Truncated fault record; no partial results accepted")
        val n = body.size / recordBytes
        if (countBytes > 0 && data.take(countBytes).fold(0) { a, b -> a * 256 + b } != n) return DtcReadResult.Failed("Fault count disagrees with complete records")
        val records = body.chunked(recordBytes).mapNotNull { row ->
            val c = row.drop(codeOffset).take(codeBytes)
            if (skipZeroCode && c.all { it == 0 }) null else DtcRecord(
                if (codeBytes == 2) DtcDecoder.obdCode(c[0], c[1]) else DtcDecoder.udsCode(c[0], c[1], c[2]),
                statusOffset?.let { row[it] }, c, statusMeaning,
            )
        }
        return DtcReadResult.Read(source, records)
    }
}

data class DiagnosticClear(val request: String, val responsePrefix: String, val sourceRecords: List<String> = emptyList()) {
    val requestBytes: List<Int> get() = Hex.parse(request).orEmpty()
    val prefixBytes: List<Int> get() = Hex.parse(responsePrefix).orEmpty()
    fun problems(protocol: EcuProtocol): List<String> = buildList {
        val r = requestBytes; val p = prefixBytes
        val service04 = protocol == EcuProtocol.KWP && r == listOf(0x04)
        if (!service04 && (protocol == EcuProtocol.OBD || r.size != (if (protocol == EcuProtocol.KWP) 3 else 4) || r.firstOrNull() != 0x14))
            add("request: needs an exact dialect-specific 14 group or the documented KWP 04 variant")
        // Permit 44 only for physical KWP 04; never reinterpret that command as 14 or broadcast it.
        if (service04) {
            if (p != listOf(0x44)) add("responsePrefix: KWP 04 needs exactly 44")
        } else if (p != listOf(0x54) && p != listOf(0x54) + r.drop(1)) add("responsePrefix: must be 54 or 54 with the exact group echo")
        if (sourceRecords.any { it.isBlank() } || sourceRecords.distinct().size != sourceRecords.size) add("sourceRecords: blank or duplicate ID")
    }
}

data class FaultRead(val read: DiagnosticRead, val layout: FaultRecordLayout, val clear: DiagnosticClear? = null)

/** An empty explicit definition set deliberately suppresses legacy generic identification/DTC requests. */
data class EcuReadDefinitions(val identification: List<IdentificationRead> = emptyList(), val faults: FaultRead? = null) {
    fun problems(protocol: EcuProtocol, link: DiagnosticLink?): List<String> = buildList {
        if (protocol == EcuProtocol.OBD) add("definitions: enhanced reads require KWP or UDS")
        if (protocol == EcuProtocol.KWP && link?.dialect != DiagnosticDialect.KWP2000) add("definitions: KWP needs an explicit KWP2000 link")
        if (protocol == EcuProtocol.UDS && link?.dialect == DiagnosticDialect.KWP2000) add("definitions: UDS cannot use a KWP dialect")
        if (identification.size > 256 || identification.distinctBy { it.read.id }.size != identification.size) add("identification: excessive or duplicate definitions")
        identification.forEachIndexed { i, item ->
            addAll(item.read.problems(protocol, link).map { "identification[$i].$it" })
            addAll(item.problems().map { "identification[$i].$it" })
            if (item.read.requestBytes.firstOrNull() !in setOf(0x09, 0x1A, 0x21, 0x22) || item.read.requestBytes.size !in 2..3 ||
                item.read.requestBytes.firstOrNull() == 0x09 && (protocol != EcuProtocol.KWP || item.read.requestBytes != listOf(0x09, 0x04))) add("identification[$i]: needs one exact identifier")
        }
        faults?.let { item ->
            addAll(item.read.problems(protocol, link).map { "faults.read.$it" })
            val faultServices = if (protocol == EcuProtocol.KWP) setOf(0x12, 0x13, 0x18, 0x19) else setOf(0x18, 0x19)
            if (item.read.requestBytes.firstOrNull() !in faultServices) add("faults.read: needs a documented dialect-specific fault-read variant")
            addAll(item.layout.problems().map { "faults.layout.$it" })
            addAll(item.clear?.problems(protocol).orEmpty().map { "faults.clear.$it" })
        }
    }
    val sourceRecords: List<String> get() = identification.flatMap { it.read.sourceRecords + it.read.supportChecks.flatMap { c -> c.sourceRecords } } +
        faults?.let { it.read.sourceRecords + it.read.supportChecks.flatMap { c -> c.sourceRecords } +
            it.layout.sourceRecords + it.clear?.sourceRecords.orEmpty() }.orEmpty()
    val needsExtendedReadSchema: Boolean get() = identification.any { it.encoding in setOf(IdentificationEncoding.BYTE_ENUM, IdentificationEncoding.ASCII_RECORDS) ||
        it.read.supportChecks.isNotEmpty() || it.read.dataBytes != null } ||
        faults?.read?.let { it.supportChecks.isNotEmpty() || it.dataBytes != null } == true
}

/** Strict additive JSON schema. Imports never get write grants or trusted flags from these fields. */
object EcuReadDefinitionsCodec {
    fun encode(d: EcuReadDefinitions): Map<String, Any?> = linkedMapOf(
        "identification" to d.identification.map { item -> linkedMapOf("read" to readMap(item.read), "encoding" to item.encoding.name,
            "byteValues" to item.byteValues.takeIf { it.isNotEmpty() }?.mapKeys { "%02X".format(it.key) },
            "defaultText" to item.defaultText, "recordBytes" to item.recordBytes).filterValues { it != null } },
        "faults" to d.faults?.let { f -> linkedMapOf(
            "read" to readMap(f.read), "layout" to layoutMap(f.layout),
            "clear" to f.clear?.let { linkedMapOf("request" to it.request, "responsePrefix" to it.responsePrefix, "sourceRecords" to it.sourceRecords) },
        ).filterValues { it != null } },
    ).filterValues { it != null }

    private fun layoutMap(layout: FaultRecordLayout): Map<String, Any?> = when (layout) {
        is ToyotaFaultLayout -> layout.map()
        is ToyotaP5FaultLayout -> layout.map()
        is FaultLayout -> linkedMapOf("recordBytes" to layout.recordBytes, "codeOffset" to layout.codeOffset,
            "codeBytes" to layout.codeBytes, "statusOffset" to layout.statusOffset, "statusMeaning" to layout.statusMeaning.name,
            "countBytes" to layout.countBytes, "skipZeroCode" to layout.skipZeroCode).filterValues { it != null }
    }

    private fun readMap(r: DiagnosticRead) = linkedMapOf("id" to r.id, "name" to r.name, "request" to r.request,
        "responsePrefix" to r.responsePrefix, "dataOffset" to r.dataOffset, "minBytes" to r.minBytes, "maxBytes" to r.maxBytes,
        "sourceRecords" to r.sourceRecords, "dataBytes" to r.dataBytes,
        "supportChecks" to r.supportChecks.takeIf { it.isNotEmpty() }?.map(PidSupportCheckCodec::encode)).filterValues { it != null }

    fun decode(raw: Any?, path: String, errors: MutableList<String>): EcuReadDefinitions? {
        if (raw == null) return null
        val f = JsonFields(errors); val before = errors.size
        fun unknown(m: Map<*, *>, keys: Set<String>, p: String) { m.keys.filterNot { it in keys }.forEach { errors += "$p.$it: unknown field" } }
        fun read(rawRead: Any?, p: String): DiagnosticRead? {
            val m = f.asObject(rawRead, p) ?: return null
            unknown(m, setOf("id", "name", "request", "responsePrefix", "dataOffset", "minBytes", "maxBytes", "sourceRecords", "supportChecks", "dataBytes"), p)
            val id = f.string(m, "id", p); val name = f.string(m, "name", p)
            val request = f.string(m, "request", p); val prefix = f.string(m, "responsePrefix", p)
            val offset = f.int(m, "dataOffset", p, false) ?: 0
            val min = f.int(m, "minBytes", p, false) ?: 0; val max = f.int(m, "maxBytes", p, false) ?: 4096
            val records = f.strings(m, "sourceRecords", p)
            val support = f.list(m, "supportChecks", p).mapIndexedNotNull { i, v -> PidSupportCheckCodec.decode(v, "$p.supportChecks[$i]", errors) }
            val width = f.int(m, "dataBytes", p, false)
            return if (id == null || name == null || request == null || prefix == null) null else DiagnosticRead(id, name, request, prefix, offset, min, max, records, support, width)
        }
        val m = f.asObject(raw, path) ?: return null
        unknown(m, setOf("identification", "faults"), path)
        val ids = f.list(m, "identification", path).mapIndexedNotNull { i, value ->
            val p = "$path.identification[$i]"; val v = f.asObject(value, p) ?: return@mapIndexedNotNull null
            unknown(v, setOf("read", "encoding", "byteValues", "defaultText", "recordBytes"), p)
            val r = read(v["read"], "$p.read")
            val encoding = f.string(v, "encoding", p)?.let { text -> IdentificationEncoding.entries.firstOrNull { it.name == text } }
            if (encoding == null) errors += "$p.encoding: must be ASCII, HEX, BYTE_ENUM or ASCII_RECORDS"
            val values = f.obj(v, "byteValues", p, false)?.entries?.mapNotNull { (key, text) ->
                if (key !is String || !key.matches(Regex("[0-9A-F]{2}")) || text !is String) {
                    errors += "$p.byteValues: needs uppercase two-hex-digit byte keys and literal text"; null
                } else key.toInt(16) to text
            }?.toMap().orEmpty()
            val default = v["defaultText"]?.let { text -> (text as? String).also { if (it == null) errors += "$p.defaultText: must be text" } }
            val width = f.int(v, "recordBytes", p, false)
            if (r == null || encoding == null) null else IdentificationRead(r, encoding, values, default, width)
        }
        val faults = f.obj(m, "faults", path, false)?.let { v ->
            val p = "$path.faults"
            unknown(v, setOf("read", "layout", "clear"), p)
            val r = read(v["read"], "$p.read")
            val layout = f.obj(v, "layout", p)?.let { l ->
                val lp = "$p.layout"
                if (l["kind"] == "TOYOTA_P3P4") ToyotaFaultLayout.decode(l, lp, errors)
                else if (l["kind"] == "TOYOTA_P5") ToyotaP5FaultLayout.decode(l, lp, errors)
                else {
                    unknown(l, setOf("recordBytes", "codeOffset", "codeBytes", "statusOffset", "statusMeaning", "countBytes", "skipZeroCode"), lp)
                    val width = f.int(l, "recordBytes", lp); val offset = f.int(l, "codeOffset", lp); val size = f.int(l, "codeBytes", lp)
                    val status = f.int(l, "statusOffset", lp, false)
                    val meaning = f.string(l, "statusMeaning", lp)?.let { text -> DtcStatusMeaning.entries.firstOrNull { it.name == text && it != DtcStatusMeaning.TOYOTA } }
                    if (meaning == null) errors += "$lp.statusMeaning: must be RAW or UDS"
                    val count = f.int(l, "countBytes", lp, false) ?: 0; val skip = f.boolean(l, "skipZeroCode", lp) ?: false
                    if (width == null || offset == null || size == null || meaning == null) null else FaultLayout(width, offset, size, status, meaning, count, skip)
                }
            }
            val clear = f.obj(v, "clear", p, false)?.let { c ->
                val cp = "$p.clear"; unknown(c, setOf("request", "responsePrefix", "sourceRecords"), cp)
                val request = f.string(c, "request", cp); val prefix = f.string(c, "responsePrefix", cp); val records = f.strings(c, "sourceRecords", cp)
                if (request == null || prefix == null) null else DiagnosticClear(request, prefix, records)
            }
            if (r == null || layout == null) null else FaultRead(r, layout, clear)
        }
        return if (errors.size == before) EcuReadDefinitions(ids, faults) else null
    }
}
