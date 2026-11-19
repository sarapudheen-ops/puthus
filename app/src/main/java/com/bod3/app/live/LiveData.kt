package com.bod3.app.live

import com.bod3.app.datapack.CustomPid
import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.pid.PidDefinition
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import java.util.Locale

/** A value that can be shown on the live data screen: a standard PID or a custom PID. */
sealed interface LiveValueDef {
    val key: String
    val name: String
    val unit: String
    val min: Double
    val max: Double
    fun format(value: Double): String

    data class Standard(val definition: PidDefinition) : LiveValueDef {
        override val key get() = definition.key
        override val name get() = definition.name
        override val unit get() = definition.unit
        override val min get() = definition.min
        override val max get() = definition.max
        override fun format(value: Double) = definition.format(value)
    }

    data class Custom(val pid: CustomPid) : LiveValueDef {
        override val key get() = pid.key
        override val name get() = pid.name
        override val unit get() = pid.unit
        override val min get() = pid.min
        override val max get() = pid.max
        override fun format(value: Double) = pid.format(value)
    }
}

object LiveCatalog {
    /**
     * Values offered for selection: standard PIDs the vehicle reports as supported (all of them when the
     * supported list is unknown) followed by custom PIDs.
     */
    fun available(supported: Set<Int>, customPids: List<CustomPid>): List<LiveValueDef> =
        Pids.all.filter { supported.isEmpty() || it.pid in supported }.map { LiveValueDef.Standard(it) } +
            customPids.filter { it.isValid }.map { LiveValueDef.Custom(it) }

    /** A selected converted system is a closed scope: no global PIDs or broadcast fallback. */
    fun forSystem(ecu: com.bod3.app.datapack.EcuDefinition, pids: List<CustomPid>): List<LiveValueDef> =
        pids.filter { it.isValid && it.target == ecu.target }.map { LiveValueDef.Custom(it) }

    /** Default selection: the classic dashboard PIDs the vehicle supports. */
    fun defaultKeys(supported: Set<Int>): List<String> =
        Pids.live.filter { supported.isEmpty() || it.pid in supported }.map { it.key }
}

/** Reads one live value per call. Standard PIDs use the primary ECU target; custom PIDs their own. */
class LiveReader(private val session: Session, private val vehicleReader: VehicleReader) {
    // A new reader is created for each poll run. Cache the prerequisite REPLIES, not a guessed
    // sensor-nonzero rule: Toyota's A801 support bytes are not the 21xx sensor sample bytes.
    private var scope: Pair<EcuTarget.Physical, Long>? = null
    private val supportReplies = linkedMapOf<List<Int>, List<Int>?>()

    private suspend fun reply(target: EcuTarget, request: List<Int>, prerequisite: Boolean = false): List<Int>? {
        val response = session.request(target, request)
        if (!response.complete || response.negativeResponses.isNotEmpty()) return null
        return if (prerequisite) com.bod3.app.datapack.PidSupportCheck.normalizeReply(request, response.messages.map { it.payload })
            else response.messages.singleOrNull()?.payload
    }

    suspend fun read(def: LiveValueDef, standardTarget: EcuTarget, preferredEcu: String?): Double? = when (def) {
        is LiveValueDef.Standard -> vehicleReader.readPid(def.definition, standardTarget, preferredEcu)?.value
        is LiveValueDef.Custom -> if (!def.pid.isValid) null else readCustom(def.pid)
    }

    private suspend fun readCustom(pid: CustomPid): Double? {
        if (pid.supportChecks.isEmpty()) return reply(pid.target, pid.requestBytes)?.let(pid::decode)
        val target = pid.target as? EcuTarget.Physical ?: return null
        return session.withEcuOperation(target) {
            val current = session.currentLinkEpoch(target)?.let { target to it }
            if (current == null || scope != current) { supportReplies.clear(); scope = current }
            for (check in pid.supportChecks) {
                val request = check.requestBytes
                if (request !in supportReplies) {
                    val before = scope
                    val value = reply(target, request, prerequisite = true)
                    val after = session.currentLinkEpoch(target)?.let { target to it }
                    if (after == null) { supportReplies.clear(); scope = null; return@withEcuOperation null }
                    if (before != after) {
                        supportReplies.clear(); scope = after
                        // Initial routing is expected. A reset halfway through discovery must
                        // NOT combine bits from two ECU sessions; the next poll starts afresh.
                        if (before != null) return@withEcuOperation null
                    }
                    if (supportReplies.size >= 512) supportReplies.remove(supportReplies.keys.first())
                    supportReplies[request] = value
                }
                val reply = supportReplies[request] ?: return@withEcuOperation null
                if (!check.accepts(reply)) return@withEcuOperation null
            }
            val payload = reply(target, pid.requestBytes)
            if (session.currentLinkEpoch(target)?.let { target to it } != scope) {
                supportReplies.clear(); scope = null; null
            } else payload?.let(pid::decode)
        }
    }
}

/** One recorded poll cycle: elapsed time and the value of every selected item (null = no reading). */
data class RecordedRow(val timestampMs: Long, val values: Map<String, Double?>)

data class Recording(val startedAtMs: Long, val columns: List<LiveValueDef>, val rows: List<RecordedRow>) {
    val durationMs: Long get() = (rows.lastOrNull()?.timestampMs ?: startedAtMs) - startedAtMs
}

/** Collects rows while recording is on. Bounded so a forgotten recording cannot exhaust memory. */
class LiveRecorder(private val maxRows: Int = 100_000) {
    private var startedAtMs: Long? = null
    private var columns: List<LiveValueDef> = emptyList()
    private val rows = ArrayList<RecordedRow>()

    val isRecording: Boolean get() = startedAtMs != null
    val rowCount: Int get() = synchronized(rows) { rows.size }
    val isFull: Boolean get() = rowCount >= maxRows

    fun start(columns: List<LiveValueDef>, nowMs: Long) {
        synchronized(rows) { rows.clear() }
        this.columns = columns
        startedAtMs = nowMs
    }

    fun add(row: RecordedRow) {
        if (startedAtMs == null) return
        synchronized(rows) { if (rows.size < maxRows) rows += row }
    }

    fun stop(): Recording? {
        val start = startedAtMs ?: return null
        startedAtMs = null
        return Recording(start, columns, synchronized(rows) { rows.toList() })
    }
}

object CsvExporter {
    /**
     * CSV with a header row, then one row per poll cycle: elapsed seconds, ISO time (UTC), then one
     * column per value (`name (unit)`), `.` as decimal separator, empty cells for missing readings.
     */
    fun format(recording: Recording, title: String? = null): String = buildString {
        if (title != null) append("# ").append(title.replace("\n", " ")).append('\n')
        append("elapsed_s,time_utc")
        recording.columns.forEach { append(',').append(escape(if (it.unit.isBlank()) it.name else "${it.name} (${it.unit})")) }
        append('\n')
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        for (row in recording.rows) {
            append(String.format(Locale.US, "%.3f", (row.timestampMs - recording.startedAtMs) / 1000.0))
            append(',').append(iso.format(java.util.Date(row.timestampMs)))
            recording.columns.forEach { column ->
                append(',')
                row.values[column.key]?.let { append(String.format(Locale.US, "%.4f", it).trimEnd('0').trimEnd('.')) }
            }
            append('\n')
        }
    }

    fun fileName(startedAtMs: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(startedAtMs))
        return "bod3-live-$stamp.csv"
    }

    private fun escape(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
