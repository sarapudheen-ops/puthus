package com.bod3.app.diagnostics

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.KwpServices
import com.bod3.app.obd.session.DiagnosticSessionConfirmation
import com.bod3.app.obd.session.ObdResponse
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields

/** A source communication frame. The mask is NOT a prefix, nor an inferred UDS status mask. */
data class ToyotaReadFrame(
    val frameId: Int, val request: String, val mask: String, val response: String,
    val sourceRecords: List<String>,
) {
    val requestBytes: List<Int> get() = Hex.parse(request).orEmpty()
    val maskBytes: List<Int> get() = if (mask.isEmpty()) emptyList() else Hex.parse(mask).orEmpty()
    val responseBytes: List<Int> get() = if (response.isEmpty()) emptyList() else Hex.parse(response).orEmpty()
    val changesSession: Boolean get() = requestBytes == listOf(0x10, 0x5F)
    fun problems(allowSession: Boolean = false): List<String> = buildList {
        if (frameId !in 0..65535 || !validRecords(sourceRecords)) add("frame: missing bounded source ID/provenance")
        val r = requestBytes; val p = responseBytes
        if (!request.matches(Regex("(?:[0-9A-F]{2}){1,63}")) ||
            !mask.matches(Regex("(?:[0-9A-F]{2}){0,255}")) || !response.matches(Regex("(?:[0-9A-F]{2}){0,255}"))) add("frame: invalid canonical bytes")
        if (!(KwpServices.readAllowed(r) || (r.firstOrNull() in setOf(1, 9) && r.size == 2) || r in listOf(listOf(7), listOf(10)) || allowSession && changesSession))
            add("frame: not a source KWP read or the separately confirmed 105F setup")
        if (r.isEmpty() || p.isNotEmpty() && (p[0] != r[0] + 0x40 ||
                p.size >= 2 && r.firstOrNull() in setOf(0x09, 0x1A, 0x21, 0x10) && p[1] != r.getOrNull(1))) add("frame: mismatched positive service/identifier")
        if (changesSession && p != listOf(0x50, 0x5F)) add("frame: source session setup needs the exact 505F echo")
    }

    /** Strict transport/echo validation, independent of the two distinct source mask predicates. */
    fun postService(reply: ObdResponse): List<Int>? {
        if (!reply.complete || reply.error != null || reply.negativeResponses.isNotEmpty() || reply.messages.size != 1) return null
        val p = reply.messages.single().payload
        // Expected source bytes beyond the service/identifier belong to the source
        // predicate, not an unconditional prefix. E.g. 0101 setup rule 0 accepts
        // MIL-off 410100 as well as 410180; it must not become an ALL-mask test.
        if (!KwpServices.matches(requestBytes, p) || p.size > 4096) return null
        return p.drop(1)
    }
}

private fun validRecords(records: List<String>): Boolean = records.isNotEmpty() && records.size <= 8192 &&
    records.distinct().size == records.size && records.all { it.isNotBlank() && it.length <= 256 }

data class ToyotaSupportDecision(val supported: Boolean, val maskedByte: Int? = null)

/** FDATA GetSupportBit: unconditional rows never send a request. A failed read is NOT support. */
data class ToyotaFunctionDetail(
    val detailId: Int, val supportMode: Int, val frame: ToyotaReadFrame?,
    val expectedByte: Int, val minimumLength: Int, val sourceRecords: List<String>,
) {
    fun problems(): List<String> = buildList {
        if (detailId !in 0..65535 || supportMode !in 0..65535 || expectedByte !in 0..255 || minimumLength !in 0..255 || !validRecords(sourceRecords)) add("detail: invalid source fields")
        if ((supportMode == 0) != (frame == null)) add("detail: unconditional and queried forms must not be ambiguous")
        frame?.let { addAll(it.problems()) }
    }
    fun accepts(postService: List<Int>?): Boolean = decision(postService).supported
    fun decision(postService: List<Int>?): ToyotaSupportDecision {
        if (frame == null && supportMode == 0) return ToyotaSupportDecision(true)
        val f = frame ?: return ToyotaSupportDecision(false)
        val data = postService ?: return ToyotaSupportDecision(false)
        if (data.size > 255 || data.any { it !in 0..255 }) return ToyotaSupportDecision(false)
        val mask = f.maskBytes
        if (mask.size <= 1) return ToyotaSupportDecision(true)
        if (minimumLength != 0) return ToyotaSupportDecision(data.size + 1 >= minimumLength)
        if (data.size < mask.size - 1) return ToyotaSupportDecision(false)
        // ANY nonzero mask byte matching ONE expected byte, not DTC setup's ALL test.
        var matched = false; var last: Int? = null
        for (i in 1 until mask.size) if (mask[i] != 0) {
            last = data[i - 1] and mask[i]
            matched = matched || last == expectedByte
        }
        return ToyotaSupportDecision(matched, last)
    }
}

data class ToyotaDtcSetup(val template: Int, val rule: Int, val index: Int, val frames: List<ToyotaReadFrame>, val sourceRecords: List<String>) {
    fun problems(): List<String> = buildList {
        if (template !in 0..65535 || rule !in 0..4 || index !in 0..255 || frames.isEmpty() || frames.size > 20 || !validRecords(sourceRecords)) add("setup: invalid bounded source alternative")
        frames.forEach { addAll(it.problems()) }
    }
    /** GetDTCAskCmdResult: -1 tries the next source setup; zero stops without enabling the query. */
    fun result(data: List<Int>, frame: ToyotaReadFrame): Int? {
        if (data.size > 255 || data.any { it !in 0..255 }) return null
        return when (rule) {
            0 -> 1
            1 -> data.getOrNull(index)
            2, 3 -> data.getOrNull(index)?.let { if (it == 0 || it == 255) 0 else if (rule == 2) 1 else -1 }
            4 -> {
                val mask = frame.maskBytes; val expected = frame.responseBytes
                if (mask.size <= 1) 1
                else if (data.size < mask.size - 1 || expected.size < mask.size) null
                else if ((1 until mask.size).all { data[it - 1] and mask[it] == expected[it] }) 1 else -1
            }
            else -> null
        }
    }
}

data class ToyotaFaultQuery(
    val template: Int, val queryKind: Int, val membershipWord: Int,
    val setupAlternatives: List<ToyotaDtcSetup>, val frames: List<ToyotaReadFrame>, val sourceRecords: List<String>,
) {
    fun selected(details: Set<Int>): Boolean = listOf(membershipWord and 255, membershipWord ushr 8).any { it == 255 || it != 0 && it in details }
    fun problems(): List<String> = buildList {
        if (template !in 0..65535 || queryKind !in 1..10 || membershipWord !in 0..65535 ||
            setupAlternatives.size > 5 || frames.isEmpty() || frames.size > 20 || !validRecords(sourceRecords)) add("query: invalid bounded source fields")
        setupAlternatives.forEach { addAll(it.problems()) }; frames.forEach { addAll(it.problems()) }
    }
}

/**
 * DTCF P3/P4 caller, not a generic OBD request list. The record layout/labels are shared by all
 * selected queries, but each query keeps its own native state kind and setup alternatives.
 * Native modes 7 and 9 have extra overrides and are rejected until separately implemented.
 */
data class ToyotaFaultWorkflow(
    val functionKey: Long, val nativeMode: Int, val nativeTransport: Int,
    val details: List<ToyotaFunctionDetail>, val initial: List<ToyotaReadFrame>,
    val queries: List<ToyotaFaultQuery>, val sourceRecords: List<String>,
) {
    val requiresSessionConfirmation: Boolean get() = initial.any { it.changesSession }
    fun problems(): List<String> = buildList {
        if (functionKey !in 0..0xFFFFFFFFL || nativeMode !in 0..255 || nativeMode in setOf(7, 9) || nativeTransport !in 1..5 || !validRecords(sourceRecords)) add("workflow: unsupported source mode/transport or missing provenance")
        if (initial.size > 1 || details.isEmpty() || details.size > 255 || details.distinctBy { it.detailId }.size != details.size || queries.isEmpty() || queries.size > 255) add("workflow: invalid bounded source lists")
        initial.forEach { addAll(it.problems(allowSession = true)) }
        details.forEach { addAll(it.problems()) }; queries.forEach { addAll(it.problems()) }
    }
    val allSourceRecords: List<String> get() = sourceRecords + initial.flatMap { it.sourceRecords } +
        details.flatMap { it.sourceRecords + it.frame?.sourceRecords.orEmpty() } + queries.flatMap { q ->
            q.sourceRecords + q.frames.flatMap { it.sourceRecords } + q.setupAlternatives.flatMap { it.sourceRecords + it.frames.flatMap { f -> f.sourceRecords } }
        }
}

/**
 * Atomic source workflow execution. No partial fault list, generic fallback or source session
 * change without its separate one-operation confirmation. All discoveries and queries run under
 * one exact-ECU lease. Only failed READ exchanges may use the supplied source re-entry callback;
 * it never replays 105F, a clear, an actuator test or a coding operation.
 */
class ToyotaFaultWorkflowReader(private val session: Session) {
    private class InvalidExchange(message: String) : IllegalStateException(message)

    suspend fun read(
        ecu: EcuDefinition, definition: FaultRead,
        confirmation: DiagnosticSessionConfirmation? = null,
        reenter: (suspend () -> Unit)? = null,
    ): DtcReadResult {
        val workflow = requireNotNull(definition.workflow)
        require(ecu.link?.dialect == DiagnosticDialect.KWP2000 && workflow.problems().isEmpty()) { "Invalid selected source fault workflow" }
        val layout = definition.layout as? ToyotaFaultLayout ?: return DtcReadResult.Failed("P3/P4 workflow needs its exact source record layout")
        if (workflow.requiresSessionConfirmation && confirmation == null)
            return DtcReadResult.Failed("This source DTC workflow requires separately confirmed diagnostic session 10 5F. No session or substitute fault request was sent.")
        if (confirmation != null && !confirmation.belongsTo(session, ecu.target, workflow))
            return DtcReadResult.Failed("Confirmation differs from this exact source workflow, ECU or connection")
        val timeout = ecu.link.kLine?.p2StarMs?.toLong() ?: Session.DEFAULT_REQUEST_TIMEOUT_MS
        var retryableReadFailure = false
        suspend fun exchange(frame: ToyotaReadFrame, initial: Boolean = false): List<Int>? {
            retryableReadFailure = false
            val reply = session.request(ecu.target, frame.requestBytes, timeout,
                diagnosticSession = if (initial && frame.changesSession) confirmation else null)
            // A missing support reply is unsupported. A malformed/wrong-ECU/mixed
            // reply is NOT unsupported and cannot silently change query membership.
            // Nor may re-entry turn invalid transport/echo bytes into valid evidence.
            if (!reply.complete || reply.messages.size > 1 || reply.negativeResponses.size > 1 ||
                reply.messages.isNotEmpty() && reply.negativeResponses.isNotEmpty())
                throw InvalidExchange("Invalid source reply for ${frame.request}; no partial discovery or fault list accepted")
            if (reply.messages.isEmpty()) {
                retryableReadFailure = reply.negativeResponses.isEmpty() &&
                    (reply.error == com.bod3.app.obd.elm.ElmError.NO_DATA || reply.error == null && reply.elm.lines.isEmpty())
                return null
            }
            return frame.postService(reply)
                ?: throw InvalidExchange("Source reply exceeds its exact service/identifier/data bounds for ${frame.request}")
        }
        suspend fun execute(): DtcReadResult {
            // Exactly source order. Discovery failures mean unsupported, not a guessed fallback.
            val replies = linkedMapOf<Int, List<Int>?>(); val enabled = linkedSetOf<Int>()
            for (detail in workflow.details) {
                val frame = detail.frame
                val data = if (frame == null) null else {
                    if (frame.frameId !in replies) replies[frame.frameId] = exchange(frame)
                    replies[frame.frameId]
                }
                if (detail.accepts(data)) enabled += detail.detailId
            }
            for (frame in workflow.initial) if (exchange(frame, initial = true) == null)
                return DtcReadResult.Failed("Source DTC initial command failed; no fault queries or partial results accepted")
            val records = linkedMapOf<Pair<Long?, com.bod3.app.obd.uds.ToyotaDtcState?>, DtcRecord>()
            var selected = 0
            for (query in workflow.queries) {
                if (!query.selected(enabled)) continue
                if (query.setupAlternatives.isNotEmpty()) {
                    var decision = -1
                    for (setup in query.setupAlternatives) {
                        var data: List<Int>? = null; var used: ToyotaReadFrame? = null
                        for (frame in setup.frames) { data = exchange(frame); if (data != null) { used = frame; break } }
                        if (data == null || used == null) continue
                        decision = setup.result(data, used) ?: return DtcReadResult.Failed("Short source DTC setup predicate; no partial results accepted")
                        if (decision >= 0) break
                    }
                    if (decision < 1) continue
                }
                selected++
                var payload: List<Int>? = null
                for (frame in query.frames) {
                    payload = exchange(frame)
                    if (payload == null && retryableReadFailure && workflow.nativeTransport in setOf(1, 2, 5)) {
                        val entered = if (reenter != null) { reenter(); true }
                            else session.reenterSourceKwpRead(ecu.target, workflow.functionKey, workflow.nativeTransport)
                        if (entered) payload = exchange(frame)
                    }
                    if (payload != null) break
                }
                if (payload == null) return DtcReadResult.Failed("Source DTC query ${query.template} failed; no partial fault list accepted")
                // The actual sender returns success for kind 5 without calling the record decoder.
                if (query.queryKind == 5) continue
                when (val result = layout.copy(queryKind = query.queryKind, availabilityMask = 0).decode(payload, "Toyota source workflow")) {
                    is DtcReadResult.Read -> result.records.forEach { records.putIfAbsent(it.nativeCode to it.toyotaState, it) }
                    else -> return result
                }
            }
            return if (selected == 0) DtcReadResult.Failed("No source DTC query passed its current-ECU membership/setup conditions")
                else DtcReadResult.Read("Toyota function ${workflow.functionKey} · $selected exact source queries", records.values.toList())
        }
        return try {
            if (workflow.requiresSessionConfirmation) session.withSourceReadSessionOperation(ecu.target) { execute() }
            else session.withReadOnlyEcuOperation(ecu.target) { execute() }
        } catch (e: InvalidExchange) {
            DtcReadResult.Failed(e.message ?: "Invalid source reply")
        } catch (e: java.io.IOException) {
            DtcReadResult.Failed(e.message ?: "Source transport failed; no partial fault list accepted")
        } finally { confirmation?.revoke() }
    }
}

object ToyotaFaultWorkflowCodec {
    fun encode(w: ToyotaFaultWorkflow): Map<String, Any?> = linkedMapOf(
        "kind" to "TOYOTA_P3P4", "functionKey" to w.functionKey, "nativeMode" to w.nativeMode, "nativeTransport" to w.nativeTransport,
        "sourceRecords" to w.sourceRecords,
        "details" to w.details.map { linkedMapOf("detailId" to it.detailId, "supportMode" to it.supportMode, "frame" to it.frame?.let(::frameMap),
            "expectedByte" to it.expectedByte, "minimumLength" to it.minimumLength, "sourceRecords" to it.sourceRecords).filterValues { it != null } },
        "initial" to w.initial.map(::frameMap), "queries" to w.queries.map { q -> linkedMapOf(
            "template" to q.template, "queryKind" to q.queryKind, "membershipWord" to q.membershipWord, "sourceRecords" to q.sourceRecords,
            "frames" to q.frames.map(::frameMap), "setupAlternatives" to q.setupAlternatives.map { s -> linkedMapOf(
                "template" to s.template, "rule" to s.rule, "index" to s.index, "frames" to s.frames.map(::frameMap), "sourceRecords" to s.sourceRecords) }) },
    )
    private fun frameMap(f: ToyotaReadFrame): Map<String, Any?> = linkedMapOf("frameId" to f.frameId, "request" to f.request,
        "mask" to f.mask, "response" to f.response, "sourceRecords" to f.sourceRecords)
    fun decode(raw: Any?, path: String, errors: MutableList<String>): ToyotaFaultWorkflow? {
        if (raw == null) return null
        val f = JsonFields(errors); val before = errors.size
        fun obj(raw: Any?, p: String, fields: Set<String>): Map<*, *>? = f.asObject(raw, p)?.also { m ->
            m.keys.filterNot { it in fields }.forEach { errors += "$p.$it: unknown source-workflow field" }
        }
        fun frame(raw: Any?, p: String): ToyotaReadFrame? {
            val m = obj(raw, p, setOf("frameId", "request", "mask", "response", "sourceRecords")) ?: return null
            val id = f.int(m, "frameId", p); val request = f.string(m, "request", p)
            // Empty source masks and expected replies are meaningful and MUST be retained.
            val mask = m["mask"] as? String; val response = m["response"] as? String
            if (mask == null || response == null) errors += "$p: explicit source mask/response required"
            return if (id == null || request == null || mask == null || response == null) null
                else ToyotaReadFrame(id, request, mask, response, f.strings(m, "sourceRecords", p))
        }
        fun frames(m: Map<*, *>, key: String, p: String): List<ToyotaReadFrame> = f.list(m, key, p).mapIndexedNotNull { i, v -> frame(v, "$p.$key[$i]") }
        val m = obj(raw, path, setOf("kind", "functionKey", "nativeMode", "nativeTransport", "details", "initial", "queries", "sourceRecords")) ?: return null
        if (m["kind"] != "TOYOTA_P3P4") errors += "$path.kind: not a supported source caller"
        val key = f.number(m, "functionKey", path)?.takeIf { it.isFinite() && it % 1 == 0.0 && it in 0.0..0xFFFFFFFFL.toDouble() }?.toLong()
        if (key == null) errors += "$path.functionKey: needs a full unsigned 32-bit source key"
        val mode = f.int(m, "nativeMode", path); val transport = f.int(m, "nativeTransport", path)
        val details = f.list(m, "details", path).mapIndexedNotNull { i, v ->
            val p = "$path.details[$i]"; val d = obj(v, p, setOf("detailId", "supportMode", "frame", "expectedByte", "minimumLength", "sourceRecords")) ?: return@mapIndexedNotNull null
            val id = f.int(d, "detailId", p); val support = f.int(d, "supportMode", p)
            val expected = f.int(d, "expectedByte", p); val min = f.int(d, "minimumLength", p)
            val source = d["frame"]?.let { frame(it, "$p.frame") }
            if (id == null || support == null || expected == null || min == null) null else ToyotaFunctionDetail(id, support, source, expected, min, f.strings(d, "sourceRecords", p))
        }
        val queries = f.list(m, "queries", path).mapIndexedNotNull { i, v ->
            val p = "$path.queries[$i]"; val q = obj(v, p, setOf("template", "queryKind", "membershipWord", "setupAlternatives", "frames", "sourceRecords")) ?: return@mapIndexedNotNull null
            val template = f.int(q, "template", p); val kind = f.int(q, "queryKind", p); val membership = f.int(q, "membershipWord", p)
            val setups = f.list(q, "setupAlternatives", p).mapIndexedNotNull { j, value ->
                val sp = "$p.setupAlternatives[$j]"; val s = obj(value, sp, setOf("template", "rule", "index", "frames", "sourceRecords")) ?: return@mapIndexedNotNull null
                val st = f.int(s, "template", sp); val rule = f.int(s, "rule", sp); val index = f.int(s, "index", sp)
                if (st == null || rule == null || index == null) null else ToyotaDtcSetup(st, rule, index, frames(s, "frames", sp), f.strings(s, "sourceRecords", sp))
            }
            if (template == null || kind == null || membership == null) null else ToyotaFaultQuery(template, kind, membership, setups, frames(q, "frames", p), f.strings(q, "sourceRecords", p))
        }
        val initial = frames(m, "initial", path)
        if (errors.size != before || key == null || mode == null || transport == null) return null
        return ToyotaFaultWorkflow(key, mode, transport, details, initial, queries, f.strings(m, "sourceRecords", path)).also { w ->
            errors += w.problems().map { "$path.$it" }
        }
    }
}
