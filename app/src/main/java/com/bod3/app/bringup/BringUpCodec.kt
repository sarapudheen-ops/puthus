package com.bod3.app.bringup

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Data only. Decoding a transfer NEVER installs source trust or a current-connection write permit. */
object BringUpCodec {
    const val PACK_FORMAT = "bod3-verified-ecu-setups"
    const val REPORT_FORMAT = "bod3-ecu-bring-up-report"
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_SETUPS = 32

    internal fun candidateMap(c: BringUpCandidate): Map<String, Any?> = linkedMapOf(
        "id" to c.id, "name" to c.name, "sourceRecords" to c.sourceRecords.sorted(),
        "supportRecords" to c.supportRecords.sorted(), "supportCount" to c.supportCount,
        "metadata" to c.metadata.toSortedMap(), "blockers" to c.blockers, "runtime" to c.runtime?.map(),
    )

    internal fun candidate(raw: Any?, pinned: Boolean = false): BringUpCandidate {
        val m = obj(raw, setOf("id", "name", "sourceRecords", "supportRecords", "supportCount", "metadata", "blockers", "runtime"))
        val metadata = obj(m["metadata"]).entries.associate { (k, v) ->
            require(k is String && v is String); k to v
        }
        val runtime = m["runtime"]?.let { value ->
            val r = obj(value, setOf("data", "identificationId", "livePidId", "session", "identityKind"))
            val errors = mutableListOf<String>()
            val data = CatalogCodec.readData(r["data"], "bringUp.runtime.data", errors).unverified()
            require(errors.isEmpty()) { errors.joinToString("; ") }
            BringUpRuntime(if (pinned) data.withPinnedWrites() else data, text(r, "identificationId"), text(r, "livePidId"), text(r, "session"), enumValueOf(text(r, "identityKind")))
        }
        return BringUpCandidate(text(m, "id"), text(m, "name"), strings(m["sourceRecords"], 8192), strings(m["supportRecords"], 8192),
            metadata, strings(m["blockers"], 64), runtime, pinned).also {
            it.validate(); require(whole(m["supportCount"], 8192) == it.supportCount.toLong()) { "Support count disagrees with distinct source records" }
        }
    }

    fun reportText(report: BringUpReport): String {
        report.validate()
        return bounded(JsonWriter.write(reportMap(report)))
    }
    internal fun reportMap(r: BringUpReport): Map<String, Any?> = linkedMapOf(
        "format" to REPORT_FORMAT, "formatVersion" to VERSION,
        "scope" to linkedMapOf("path" to r.scope.path, "systemId" to r.scope.systemId, "vehicleInstance" to r.scope.vehicleInstance),
        "startedAtMs" to r.startedAtMs, "finishedAtMs" to r.finishedAtMs, "outcome" to r.outcome.name,
        "candidates" to r.candidates.map(::candidateMap), "manualCandidateId" to r.manualCandidateId,
        "attempts" to r.attempts.map { a -> linkedMapOf(
            "candidateId" to a.candidateId, "fingerprint" to a.fingerprint, "startedAtMs" to a.startedAtMs, "finishedAtMs" to a.finishedAtMs,
            "outcome" to a.outcome.name, "detail" to a.detail,
            "probes" to a.probes.map { p -> linkedMapOf("role" to p.role.name, "request" to p.request, "payload" to p.payload, "passed" to p.passed, "detail" to p.detail) },
        ) },
        "exchanges" to r.exchanges.map { e -> linkedMapOf("candidateId" to e.candidateId, "command" to e.command,
            "timeoutMs" to e.timeoutMs, "timestampMs" to e.timestampMs, "durationMs" to e.durationMs, "rawResponse" to e.rawResponse, "error" to e.error) },
        "logComplete" to r.logComplete, "detail" to r.detail,
    )

    fun readReport(text: String): BringUpReport = report(MiniJson.parse(bounded(text).trimStart('\uFEFF')))
    internal fun report(raw: Any?): BringUpReport {
        val m = obj(raw, setOf("format", "formatVersion", "scope", "startedAtMs", "finishedAtMs", "outcome", "candidates", "manualCandidateId", "attempts", "exchanges", "logComplete", "detail"))
        require(m["format"] == REPORT_FORMAT && whole(m["formatVersion"], VERSION.toLong()) == VERSION.toLong()) { "Unsupported bring-up report version" }
        val s = obj(m["scope"], setOf("path", "systemId", "vehicleInstance"))
        val scope = BringUpScope(strings(s["path"], 4), text(s, "systemId"), text(s, "vehicleInstance"))
        val candidates = BringUpCandidate.ranked(array(m["candidates"], BringUpCandidate.MAX_CANDIDATES).map { candidate(it) })
        val attempts = array(m["attempts"], BringUpCandidate.MAX_CANDIDATES).map { value ->
            val a = obj(value, setOf("candidateId", "fingerprint", "startedAtMs", "finishedAtMs", "outcome", "detail", "probes"))
            val probes = array(a["probes"], 3).map { v ->
                val p = obj(v, setOf("role", "request", "payload", "passed", "detail"))
                BringUpProbe(enumValueOf(text(p, "role")), text(p, "request"), optionalText(p, "payload"), boolean(p, "passed"), text(p, "detail", allowEmpty = true))
            }
            BringUpAttempt(text(a, "candidateId"), text(a, "fingerprint"), whole(a["startedAtMs"]), whole(a["finishedAtMs"]),
                enumValueOf(text(a, "outcome")), text(a, "detail", allowEmpty = true), probes)
        }
        val exchanges = array(m["exchanges"], BringUpEngine.MAX_EXCHANGES).map { value ->
            val e = obj(value, setOf("candidateId", "command", "timeoutMs", "timestampMs", "durationMs", "rawResponse", "error"))
            BringUpExchange(optionalText(e, "candidateId"), text(e, "command"), whole(e["timeoutMs"]), whole(e["timestampMs"]),
                whole(e["durationMs"]), text(e, "rawResponse", allowEmpty = true), optionalText(e, "error"))
        }
        require(exchanges.sumOf { it.rawResponse.toByteArray(Charsets.UTF_8).size.toLong() + it.command.toByteArray(Charsets.UTF_8).size + 256 } <= BringUpEngine.MAX_LOG_BYTES)
        return BringUpReport(scope, whole(m["startedAtMs"]), whole(m["finishedAtMs"]), enumValueOf(text(m, "outcome")), candidates,
            attempts, exchanges, boolean(m, "logComplete"), optionalText(m, "manualCandidateId"), text(m, "detail", allowEmpty = true)).also { it.validate() }
    }

    fun packText(reports: List<BringUpReport>): String {
        require(reports.size <= MAX_SETUPS && reports.distinctBy { it.scope.key }.size == reports.size)
        reports.forEach { require(it.outcome == BringUpOutcome.WORKING); it.validate() }
        return bounded(JsonWriter.write(linkedMapOf("format" to PACK_FORMAT, "formatVersion" to VERSION, "setups" to reports.map(::reportMap))))
    }
    fun readPack(text: String): List<BringUpReport> {
        val m = obj(MiniJson.parse(bounded(text).trimStart('\uFEFF')), setOf("format", "formatVersion", "setups"))
        require(m["format"] == PACK_FORMAT && whole(m["formatVersion"], VERSION.toLong()) == VERSION.toLong()) { "Unsupported verified-setup pack version" }
        val reports = array(m["setups"], MAX_SETUPS).map(::report)
        require(reports.all { it.outcome == BringUpOutcome.WORKING } && reports.distinctBy { it.scope.key }.size == reports.size) { "Only unique successful setup reports may be transferred" }
        return reports
    }

    internal fun bounded(text: String): String = text.also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Bring-up file exceeds the 16 MiB limit" } }
    private fun obj(raw: Any?, keys: Set<String>? = null): Map<*, *> = (raw as? Map<*, *>)?.also { m ->
        require(m.keys.all { it is String } && (keys == null || m.keys == keys)) { "Unknown or missing bring-up fields" }
    } ?: error("Expected a bring-up object")
    private fun text(m: Map<*, *>, key: String, allowEmpty: Boolean = false): String = (m[key] as? String)?.also {
        require(allowEmpty || it.isNotBlank()) { "Missing $key" }
    } ?: error("Expected text for $key")
    private fun optionalText(m: Map<*, *>, key: String): String? = if (m[key] == null) null else text(m, key)
    private fun boolean(m: Map<*, *>, key: String): Boolean = m[key] as? Boolean ?: error("Expected boolean for $key")
    private fun array(raw: Any?, max: Int): List<*> = (raw as? List<*>)?.also { require(it.size <= max) { "Excessive bring-up list" } } ?: error("Expected bring-up list")
    private fun strings(raw: Any?, max: Int): List<String> = array(raw, max).map { it as? String ?: error("Expected source record text") }
    private fun whole(raw: Any?, max: Long = 9_007_199_254_740_991L): Long = (raw as? Number)?.toDouble()?.let {
        require(it.isFinite() && it % 1.0 == 0.0 && it >= 0 && it <= max.toDouble()) { "Invalid bring-up integer" }; it.toLong()
    } ?: error("Missing bring-up integer")
}
