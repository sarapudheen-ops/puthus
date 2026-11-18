package com.bod3.app.audit

import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What kind of non-read operation a record describes. */
enum class AuditKind(val label: String) {
    ACTUATOR_TEST("Actuator test"),
    CODING_WRITE("Coding write"),
    CODING_ROLLBACK("Coding rollback"),
}

/** One step of an operation: what was sent, what came back, and whether it was OK. */
data class AuditStep(
    val timeMs: Long,
    val label: String,
    val request: String? = null,
    val response: String? = null,
    val ok: Boolean = true,
)

/**
 * The permanent record of one actuator test or coding write. Every non-read operation produces one,
 * whatever its outcome (refused, failed, completed), and records are never edited afterwards.
 */
data class AuditRecord(
    val id: String,
    val kind: AuditKind,
    val title: String,
    val recipeId: String,
    /** Module name and CAN IDs, e.g. `Central electronics (09) 74E/7B8`. */
    val module: String,
    val vin: String?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val success: Boolean,
    val outcome: String,
    val details: Map<String, String> = emptyMap(),
    val steps: List<AuditStep> = emptyList(),
)

/** Collects [AuditStep]s while an operation runs. */
class AuditRecorder(private val clock: () -> Long) {
    private val steps = mutableListOf<AuditStep>()
    val startedAtMs: Long = clock()

    val all: List<AuditStep> get() = synchronized(steps) { steps.toList() }

    fun step(label: String, request: String? = null, response: String? = null, ok: Boolean = true): AuditStep =
        AuditStep(clock(), label, request, response, ok).also { synchronized(steps) { steps += it } }
}

/**
 * Persists [AuditRecord]s in the key-value store, newest last. There is deliberately no way to delete or
 * edit single records; the log is capped at [maxRecords] (oldest dropped) and can be exported as text.
 */
class AuditStore(private val store: KeyValueStore, private val maxRecords: Int = 500) {

    fun records(): List<AuditRecord> {
        val list = store.get(KEY)?.let { runCatching { MiniJson.parse(it) as? List<*> }.getOrNull() }.orEmpty()
        return list.mapNotNull { (it as? Map<*, *>)?.let(AuditCodec::decode) }
    }

    fun append(record: AuditRecord) {
        val updated = (records() + record).takeLast(maxRecords)
        store.put(KEY, JsonWriter.write(updated.map(AuditCodec::encode)))
    }

    fun record(id: String): AuditRecord? = records().firstOrNull { it.id == id }

    companion object {
        const val KEY = "audit.log"
    }
}

object AuditCodec {
    fun encode(r: AuditRecord): Map<String, Any?> = linkedMapOf(
        "id" to r.id, "kind" to r.kind.name, "title" to r.title, "recipeId" to r.recipeId, "module" to r.module,
        "vin" to r.vin, "startedAt" to r.startedAtMs, "endedAt" to r.endedAtMs, "success" to r.success,
        "outcome" to r.outcome, "details" to r.details,
        "steps" to r.steps.map { s ->
            linkedMapOf("t" to s.timeMs, "label" to s.label, "request" to s.request, "response" to s.response, "ok" to s.ok)
                .filterValues { it != null }
        },
    )

    fun decode(m: Map<*, *>): AuditRecord? {
        val kind = AuditKind.entries.firstOrNull { it.name == m["kind"] } ?: return null
        return AuditRecord(
            id = m["id"] as? String ?: return null,
            kind = kind,
            title = m["title"] as? String ?: "",
            recipeId = m["recipeId"] as? String ?: "",
            module = m["module"] as? String ?: "",
            vin = m["vin"] as? String,
            startedAtMs = (m["startedAt"] as? Number)?.toLong() ?: 0L,
            endedAtMs = (m["endedAt"] as? Number)?.toLong() ?: 0L,
            success = m["success"] as? Boolean ?: false,
            outcome = m["outcome"] as? String ?: "",
            details = (m["details"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v.toString() } }?.toMap().orEmpty(),
            steps = (m["steps"] as? List<*>).orEmpty().mapNotNull { item ->
                val s = item as? Map<*, *> ?: return@mapNotNull null
                AuditStep(
                    timeMs = (s["t"] as? Number)?.toLong() ?: 0L,
                    label = s["label"] as? String ?: "",
                    request = s["request"] as? String,
                    response = s["response"] as? String,
                    ok = s["ok"] as? Boolean ?: true,
                )
            },
        )
    }
}

object AuditFormatter {
    private fun time(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + "Z"

    fun format(record: AuditRecord): String = buildString {
        appendLine("${record.kind.label}: ${record.title}")
        appendLine("Recipe: ${record.recipeId}")
        appendLine("Module: ${record.module}")
        record.vin?.let { appendLine("VIN: $it") }
        appendLine("Started: ${time(record.startedAtMs)}")
        appendLine("Ended: ${time(record.endedAtMs)}")
        appendLine("Result: ${if (record.success) "OK" else "NOT OK"} – ${record.outcome}")
        record.details.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Steps:")
        record.steps.forEach { s ->
            append("  ${time(s.timeMs)} ${if (s.ok) "  " else "! "}${s.label}")
            s.request?.let { append("  >> $it") }
            s.response?.let { append("  << $it") }
            appendLine()
        }
    }

    fun formatAll(records: List<AuditRecord>): String = buildString {
        appendLine("bod3 audit log – ${records.size} operation(s)")
        appendLine("=".repeat(40))
        records.forEach { appendLine(format(it)); appendLine("-".repeat(40)) }
    }
}
