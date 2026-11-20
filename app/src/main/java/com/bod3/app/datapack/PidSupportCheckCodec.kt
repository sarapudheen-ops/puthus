package com.bod3.app.datapack

import com.bod3.app.recipe.JsonFields

/** Same strict ordered-prerequisite schema for live data and explicit diagnostic reads. */
object PidSupportCheckCodec {
    fun encode(check: PidSupportCheck): Map<String, Any?> = linkedMapOf(
        "request" to check.request, "formula" to check.formula, "recordId" to check.recordId,
        "dataOffset" to check.dataOffset.takeIf { it != 0 },
        "sourceRecords" to check.sourceRecords.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    fun decode(raw: Any?, path: String, errors: MutableList<String>): PidSupportCheck? {
        val f = JsonFields(errors); val before = errors.size
        val m = f.asObject(raw, path) ?: return null
        m.keys.filterNot { it in setOf("request", "formula", "dataOffset", "sourceRecords", "recordId") }
            .forEach { errors += "$path.$it: unknown prerequisite field" }
        val request = f.string(m, "request", path); val formula = f.string(m, "formula", path)
        val offset = f.int(m, "dataOffset", path, false) ?: 0
        val record = f.int(m, "recordId", path, false)
        val sources = f.strings(m, "sourceRecords", path)
        return if (errors.size != before || request == null || formula == null) null
            else PidSupportCheck(request.uppercase(), formula, offset, sources, record)
    }
}
