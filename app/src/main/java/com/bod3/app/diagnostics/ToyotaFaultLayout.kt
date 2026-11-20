package com.bod3.app.diagnostics

import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.obd.uds.DtcStatusMeaning
import com.bod3.app.obd.uds.ToyotaDtcState
import com.bod3.app.recipe.JsonFields

/** Independent of a request's service: KWP fault replies are not implicitly SAE/UDS records. */
sealed interface FaultRecordLayout {
    fun problems(): List<String>
    fun decode(data: List<Int>, source: String): DtcReadResult
    val sourceRecords: List<String> get() = emptyList()
}

/** Exact 32-bit TCODE key, not a suffix match or a global P-code lookup. */
data class ToyotaFaultLabel(val nativeCode: String, val code: String, val description: String,
                            val sourceRecords: List<String> = emptyList())

/** Explicit source-defined missing-key presentation; absent means keep the raw 32-bit key. */
data class ToyotaFaultFallback(val missingDescription: String, val sourceRecords: List<String>) {
    fun code(nativeCode: Long, assemblyKind: Int): String {
        require(nativeCode in 0..0xFFFFFFFFL && assemblyKind in 1..7)
        // GetShowDtcInfo masks to 24 bits before InitDtcNum selects the width.
        val digits = if (assemblyKind in setOf(4, 6)) 6 else 4
        val value = nativeCode and ((1L shl (digits * 4)) - 1)
        val family = "PCBU"[((value shr (digits * 4 - 2)) and 3).toInt()]
        return family + (value and ((1L shl (digits * 4 - 2)) - 1)).toString(16).uppercase().padStart(digits, '0')
    }
    fun description(nativeCode: Long): String {
        require(nativeCode in 0..0xFFFFFFFFL)
        fun byte(shift: Int) = ((nativeCode shr shift) and 255).toString(16).padStart(2, '0')
        return "[00, ${byte(16)}, ${byte(8)}, ${byte(0)}]-$missingDescription"
    }
}

/**
 * Port of DTCF GetDTCCodeFromAnsP3P4 @14ae8 / GetDtcStateResult @15618.
 * The input origin is AFTER the positive service byte (not after an arbitrary
 * subfunction). The converter must retain that origin in DiagnosticRead.
 * No bus addressing, support condition, query selection or service is inferred.
 */
data class ToyotaFaultLayout(
    val countIndex: Int,
    val recordStart: Int,
    val recordBytes: Int,
    val assemblyKind: Int,
    val countIsPresence: Boolean,
    val modeLowBits: Int,
    val queryKind: Int,
    val availabilityMask: Int,
    val labels: List<ToyotaFaultLabel> = emptyList(),
    override val sourceRecords: List<String> = emptyList(),
    val sourceFallback: ToyotaFaultFallback? = null,
) : FaultRecordLayout {
    private val codeBytes: Int get() = recordBytes - when (assemblyKind) { 5, 6 -> 1; 7 -> 3; else -> 0 }
    private fun validRecords(records: List<String>) = records.size <= 8192 && records.distinct().size == records.size &&
        records.all { it.isNotBlank() && it.length <= 256 }

    override fun problems(): List<String> = buildList {
        if (countIndex !in 0..255 || recordStart !in 0..255) add("count/start: needs native byte offsets")
        if (recordBytes !in 1..32 || assemblyKind !in 1..7 || codeBytes !in 1..4) add("assembly: invalid native record width/kind")
        if (modeLowBits !in 0..7 || queryKind !in 1..10 || availabilityMask !in 0..255) add("state: invalid native mode/query/mask")
        if (!validRecords(sourceRecords)) add("sourceRecords: blank, duplicate or excessive provenance")
        sourceFallback?.let {
            if (it.missingDescription.length > 4096 || '\u0000' in it.missingDescription ||
                it.sourceRecords.isEmpty() || !validRecords(it.sourceRecords)) add("sourceFallback: needs bounded exact source text and provenance")
        }
        if (labels.size > 65535 || labels.distinctBy { it.nativeCode.uppercase() }.size != labels.size) add("labels: excessive or duplicate exact TCODE keys")
        if (labels.any { !it.nativeCode.matches(Regex("[0-9a-fA-F]{8}")) || it.code.isBlank() || it.code.length > 64 ||
                it.code.any(Char::isISOControl) || it.description.length > 4096 || it.description.any { c -> c == '\u0000' } || !validRecords(it.sourceRecords) })
            add("labels: invalid exact code, text or provenance")
    }

    override fun decode(data: List<Int>, source: String): DtcReadResult {
        if (problems().isNotEmpty() || data.size > 4096 || data.any { it !in 0..255 })
            return DtcReadResult.Failed("Invalid Toyota fault-record layout or bytes")
        val countAt = if (queryKind == 3 && modeLowBits != 2) 255 else countIndex
        val start = if (queryKind == 3 && modeLowBits != 2) 0 else recordStart
        if (start > data.size || countAt != 255 && countAt >= data.size)
            return DtcReadResult.Failed("Short Toyota fault count/start")
        val body = data.drop(start)
        if (body.size % recordBytes != 0) return DtcReadResult.Failed("Truncated Toyota fault record; no partial results accepted")
        if (countAt != 255) {
            val count = data[countAt]
            if (!countIsPresence && count > body.size / recordBytes)
                return DtcReadResult.Failed("Toyota fault count exceeds complete records")
            if (count == 0) return DtcReadResult.Read(source, emptyList())
        }
        val byKey = labels.associateBy { it.nativeCode.toLong(16) }
        val records = linkedMapOf<Pair<Long, ToyotaDtcState>, DtcRecord>()
        for (row in body.chunked(recordBytes)) {
            val rawCode = row.take(codeBytes)
            val code = rawCode.fold(0L) { a, b -> (a shl 8) or b.toLong() }
            if (code == 0L) continue
            val status = when (assemblyKind) { 5, 6 -> row.last(); 3 -> ((code shr 8) and 0xF0).toInt(); else -> 0 }
            for (state in states(queryKind, status, availabilityMask)) {
                val label = byKey[code]
                records.putIfAbsent(code to state, DtcRecord(label?.code ?: sourceFallback?.code(code, assemblyKind) ?: "0x%08X".format(code),
                    status, row, DtcStatusMeaning.TOYOTA, code, state, label?.description ?: sourceFallback?.description(code)))
            }
        }
        return DtcReadResult.Read(source, records.values.toList())
    }

    internal fun map(): Map<String, Any?> = linkedMapOf("kind" to "TOYOTA_P3P4", "countIndex" to countIndex,
        "recordStart" to recordStart, "recordBytes" to recordBytes, "assemblyKind" to assemblyKind,
        "countIsPresence" to countIsPresence, "modeLowBits" to modeLowBits, "queryKind" to queryKind,
        "availabilityMask" to availabilityMask, "sourceRecords" to sourceRecords,
        "labels" to labels.map { linkedMapOf("nativeCode" to it.nativeCode, "code" to it.code,
            "description" to it.description, "sourceRecords" to it.sourceRecords) },
        "sourceFallback" to sourceFallback?.let { linkedMapOf("missingDescription" to it.missingDescription, "sourceRecords" to it.sourceRecords) }
    ).filterValues { it != null }

    companion object {
        /** Text IDs/order are from the native consumer, not guessed UDS meanings. */
        fun states(queryKind: Int, status: Int, availabilityMask: Int): List<ToyotaDtcState> {
            require(queryKind in 0..255 && status in 0..255 && availabilityMask in 0..255)
            return when (queryKind) {
                in 1..7 -> listOf(ToyotaDtcState.entries[queryKind - 1])
                8 -> if (status <= 0x28) listOf(ToyotaDtcState.CURRENT, ToyotaDtcState.HISTORY) else listOf(ToyotaDtcState.HISTORY)
                9 -> listOf(if (status and 0x10 != 0) ToyotaDtcState.CURRENT else ToyotaDtcState.HISTORY)
                10 -> buildList {
                    val bits = status and availabilityMask
                    if (bits and 3 != 0) add(ToyotaDtcState.TEST_FAILED)
                    if (bits and 4 != 0) add(ToyotaDtcState.PENDING)
                    if (bits and 8 != 0) add(ToyotaDtcState.CONFIRMED)
                }
                else -> emptyList()
            }
        }

        internal fun decode(map: Map<*, *>, path: String, errors: MutableList<String>): ToyotaFaultLayout? {
            val f = JsonFields(errors); val before = errors.size
            val fields = setOf("kind", "countIndex", "recordStart", "recordBytes", "assemblyKind", "countIsPresence", "modeLowBits", "queryKind", "availabilityMask", "sourceRecords", "labels", "sourceFallback")
            map.keys.filterNot { it in fields }.forEach { errors += "$path.$it: unknown field" }
            fun integer(key: String): Int? {
                // JSON Int conversion must never saturate an out-of-range value into a valid offset/kind.
                val value = f.number(map, key, path) ?: return null
                if (!value.isFinite() || value % 1 != 0.0 || value !in 0.0..255.0) { errors += "$path.$key: needs one whole byte"; return null }
                return value.toInt()
            }
            val count = integer("countIndex"); val start = integer("recordStart"); val width = integer("recordBytes")
            val assembly = integer("assemblyKind"); val mode = integer("modeLowBits"); val query = integer("queryKind"); val mask = integer("availabilityMask")
            val presence = f.boolean(map, "countIsPresence", path)
            if (presence == null) errors += "$path.countIsPresence: required boolean"
            val labels = f.list(map, "labels", path).mapIndexedNotNull { i, raw ->
                val p = "$path.labels[$i]"; val item = f.asObject(raw, p) ?: return@mapIndexedNotNull null
                item.keys.filterNot { it in setOf("nativeCode", "code", "description", "sourceRecords") }.forEach { errors += "$p.$it: unknown field" }
                val key = f.string(item, "nativeCode", p); val code = f.string(item, "code", p)
                val text = f.string(item, "description", p, false) ?: ""
                if (key == null || code == null) null else ToyotaFaultLabel(key, code, text, f.strings(item, "sourceRecords", p))
            }
            val fallback = f.obj(map, "sourceFallback", path, false)?.let { item ->
                val p = "$path.sourceFallback"
                item.keys.filterNot { it in setOf("missingDescription", "sourceRecords") }.forEach { errors += "$p.$it: unknown field" }
                val text = f.string(item, "missingDescription", p, false)
                if (text == null) { errors += "$p.missingDescription: required source text"; null }
                else ToyotaFaultFallback(text, f.strings(item, "sourceRecords", p))
            }
            if (errors.size != before || count == null || start == null || width == null || assembly == null || mode == null || query == null || mask == null || presence == null) return null
            return ToyotaFaultLayout(count, start, width, assembly, presence, mode, query, mask, labels, f.strings(map, "sourceRecords", path), fallback)
        }
    }
}
