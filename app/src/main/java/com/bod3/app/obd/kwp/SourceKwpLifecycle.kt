package com.bod3.app.obd.kwp

import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Portable FILE/SYSP/COMM transport-1/5 consumer, not a MIC3313 command mapping.
 * API arguments retain their source positions/units. In particular 5/60/600
 * and SetLinkKeep's 3/800 are NOT guessed P1/P2/P3 or Android timer settings.
 * The supplied source libraries never execute in the app.
 */
data class SourceKwpFrame(val template: Int, val frameId: Int, val request: List<Int>,
                          val mask: List<Int>, val response: List<Int>, val sourceRecords: List<String>)

data class SourceKwpPlan(
    val region: Int, val function: Long, val sourceRecord: String, val selector: Int,
    val nativeTransport: Int, val flags: Int, val baud: Int, val sendAddress: List<Int>,
    val addressInit: Int, val pinPairs: List<Pair<Int, Int>>, val timing: List<Int>,
    val initial: SourceKwpFrame, val keepalive: SourceKwpFrame, val exit: SourceKwpFrame,
    val sourceRecords: List<String>,
) {
    fun problems(): List<String> = buildList {
        if (region !in 1..3 || function !in 0..0xFFFFFFFFL || selector !in 0..255 || selector and 15 != 1 ||
            !sourceRecord.matches(Regex("SYSTEMENTER\\.BIN@[0-9a-f]{8}"))) add("Exact source entry/region/full function/selector required")
        if ((nativeTransport to flags) !in setOf(1 to 0x81, 5 to 0x33)) add("Not the reviewed native transport-1/5 class")
        if (baud !in 1..Int.MAX_VALUE || sendAddress.size != 2 || timing.size != 5 ||
            (sendAddress + timing + addressInit).any { it !in 0..255 } || timing.takeLast(2).any { it == 0 }) add("Invalid source baud/address/timing fields")
        if (pinPairs.isEmpty() || pinPairs.size > 127 || pinPairs.any { it.first !in 0..255 || it.second !in 0..255 }) add("Invalid declared source pin pairs")
        val regionName = listOf("EU", "NA", "JP").getOrNull(region - 1)
        for ((frame, limit) in listOf(initial to 50, keepalive to 20, exit to 50)) {
            if (frame.template !in 0..65535 || frame.frameId !in 0..65535 || frame.request.isEmpty() || frame.request.size + 4 > limit ||
                frame.mask.size > 255 || frame.response.size > 255 || (frame.request + frame.mask + frame.response).any { it !in 0..255 } ||
                frame.sourceRecords.isEmpty() || frame.sourceRecords.any { !it.startsWith("$regionName/") || !validSourceRecord(it) }) add("Invalid or cross-region lifecycle frame")
        }
        // Closed source class: ALL 284 pinned entries start with 81. A data
        // import cannot smuggle 105F, a write, unlock or active test into init.
        if (initial.request != listOf(0x81)) add("Unreviewed source initial command")
        if (!KwpServices.isSourceKeepAlive(keepalive.request)) add("Unreviewed source keepalive")
        if (exit.request !in listOf(listOf(0x82), listOf(0x3E), listOf(0x3E, 1))) add("Unreviewed source exit")
        if (sourceRecords.isEmpty() || sourceRecords.size > 8192 || sourceRecords.distinct().size != sourceRecords.size ||
            sourceRecords.any { !validSourceRecord(it) } || sourceRecord !in sourceRecords) add("Missing or invalid source provenance")
    }

    /** Deep snapshot before any suspend/callback: a mutable caller cannot change the selected entry. */
    internal fun snapshot(): SourceKwpPlan {
        fun SourceKwpFrame.frozen() = copy(request = request.toList(), mask = mask.toList(), response = response.toList(), sourceRecords = sourceRecords.toList())
        return copy(sendAddress = sendAddress.toList(), pinPairs = pinPairs.toList(), timing = timing.toList(),
            initial = initial.frozen(), keepalive = keepalive.frozen(), exit = exit.frozen(), sourceRecords = sourceRecords.toList())
            .also { require(it.problems().isEmpty()) { it.problems().joinToString("; ") } }
    }

    fun packet(payload: List<Int>): List<Int> {
        require(sendAddress.size == 2 && sendAddress.all { it in 0..255 } && payload.size in 1..63 && payload.all { it in 0..255 })
        return KLineFrames.encode(listOf(0x80, sendAddress[1], sendAddress[0]), payload)
    }

    internal fun completeC1(reply: SourceKwpReply): Boolean {
        if (reply.signedWord <= 0 || reply.service != 0xC1) return false
        val elm = reply.elm ?: return false
        if (!elm.isSuccess || elm.error != null || elm.lines.size != 1) return false
        val p = KLineFrames.decode(elm.lines.single(), listOf(0x80, sendAddress[0], sendAddress[1]))?.payload ?: return false
        return p.size == 3 && p[0] == 0xC1
    }

    companion object {
        internal fun validSourceRecord(value: String): Boolean = value.length <= 256 &&
            value.matches(Regex("[A-Za-z0-9_./-]+\\.(BIN|so)@[0-9a-f]{8}(#[^\\s]+)?"))
    }
}

/** Closed native API vocabulary, NEVER an arbitrary command or executable from a data pack. */
enum class SourceKwpApi(val sourceName: String) {
    BEGIN("BeginCombination"), ENABLE_L("EnableLLine"), SELECT_PIN("SelectPIN"),
    MAXIMUM_WAIT("SetCommunicationMaximumWaitTime"), VOLTAGE_LOGIC("SetEcuCommunicationVoltageLogic"),
    BAUD("SetEcuBaudRate"), PARAMETERS("SetCommunicationParameter"), INTERVALS("SetEcuCommTimeInterval"),
    END("EndCombination"), PULSE("SetCommunicationLineVoltage"), PRIMARY("CommSubFunc_00000003"),
    ALTERNATE("ToyotaKwpSendDataToEcuGetMultiFrameAnswer"), PAUSE_US("usleep"),
    ADDRESS_INIT("SendCmdAndGetReceiveAddressInit"), INITIAL("SendCmdAndGetReceiveInitial"),
    KEEPALIVE("SetLinkKeep"), EXIT("KwpSendDataToEcuGetAnswer"), CONTEXT("SetContext174d1"),
    /** Portable driver contract: complete raw packet plus HOST observation budget, not a native timing rename. */
    READ_PACKET("PortableReadPacket"),
}

data class SourceKwpCall(val api: SourceKwpApi, val arguments: List<Any>) {
    fun report(): Map<String, Any> = mapOf("api" to api.sourceName, "arguments" to arguments)
}

/**
 * EndCombination's signed LOW word selects fast retry, not the queued send's
 * return. A scripted/native status alone is NEVER ECU initialization evidence.
 * Only the separate, fully checked raw Elm reply can establish a C1 observation.
 */
data class SourceKwpReply(val word: Long = 0, val service: Int? = null, val elm: ElmResponse? = null) {
    init { require(word in Int.MIN_VALUE.toLong()..0xFFFFFFFFL && (service == null || service in 0..255)) }
    val signedWord: Int get() = word.toShort().toInt()
}

/**
 * Deliberate adapter boundary. Implementations must preserve each native API's
 * actual electrical/time/return semantics and reject unsupported configuration.
 * CONTEXT is host bookkeeping, NEVER an adapter/ECU command; PAUSE_US is not a
 * Bluetooth pulse. There is currently no approved native-to-MIC3313 implementation.
 * Tests use explicit scripted ports. The production converter therefore cannot
 * use this interface's existence to enable a vehicle-system or grant a write.
 */
fun interface SourceKwpIo { suspend fun invoke(call: SourceKwpCall): SourceKwpReply }

class SourceKwpInitializationException(message: String) : AdapterException(message)

data class SourceKwpEntryResult(val nativeStatus: Int, val completeC1: Boolean, val isoFallback: Boolean = false) {
    val initialized: Boolean get() = nativeStatus == 0 && completeC1
    fun wantsIsoFallback(nativeTransport: Int): Boolean {
        require(nativeTransport in setOf(1, 5))
        return nativeTransport == 5 && nativeStatus and 65535 == 65535
    }
}

/**
 * Bounded asynchronous source lifecycle core. Exact one-entry retry/fallback;
 * no protocol search, sibling selector, functional address or implicit reply.
 * Session owns the exclusive operation; an I/O exception/cancellation aborts,
 * rather than being converted to a native status or replayed diagnostic write.
 */
class SourceKwpLifecycle(plan: SourceKwpPlan, private val io: SourceKwpIo, private val connector: Int = 16) {
    private val plan = plan.snapshot()
    init { require(connector in 0..255) }
    private suspend fun call(api: SourceKwpApi, vararg args: Any): SourceKwpReply = io.invoke(SourceKwpCall(api, args.toList()))
    private fun wire(frame: SourceKwpFrame): String = Hex.format(plan.packet(frame.request), "").lowercase()

    private suspend fun setting(api: SourceKwpApi, vararg args: Any) {
        // Native ignores several configuration failures. Do not turn that into
        // app approval of rejected pin/baud/timing configuration, even with C1.
        val result = call(api, *args)
        if (result.signedWord < 0 || result.elm?.error != null)
            throw SourceKwpInitializationException("Source KWP configuration rejected: ${api.sourceName}")
    }

    private suspend fun configure(pin: Pair<Int, Int>) {
        setting(SourceKwpApi.BEGIN)
        if (connector == 16) setting(SourceKwpApi.ENABLE_L, plan.timing[2])
        else setting(SourceKwpApi.SELECT_PIN, connector, 14, plan.timing[2] or (plan.timing[3] shl 8), 0, 0)
        setting(SourceKwpApi.SELECT_PIN, connector, 1, 0, pin.first, pin.second)
        setting(SourceKwpApi.MAXIMUM_WAIT, 15000)
        setting(SourceKwpApi.VOLTAGE_LOGIC, 255, 255)
        setting(SourceKwpApi.BAUD, plan.baud.toFloat().toDouble())
        setting(SourceKwpApi.PARAMETERS, 1, 8, 1, 0)
        setting(SourceKwpApi.INTERVALS, plan.timing[0].toDouble(), plan.timing[1], if (plan.baud == 9600) 500 else 600)
        setting(SourceKwpApi.END)
    }

    private suspend fun combined(alternate: Boolean): SourceKwpReply {
        setting(SourceKwpApi.BEGIN)
        setting(SourceKwpApi.PULSE, 2, plan.timing[3], plan.timing[4])
        if (alternate) call(SourceKwpApi.ALTERNATE, 300, wire(plan.initial), 255, 50)
        else call(SourceKwpApi.PRIMARY, 255, 255, 1, wire(plan.initial))
        return call(SourceKwpApi.END)
    }

    suspend fun fastEnter(): SourceKwpEntryResult {
        for ((pinIndex, pin) in plan.pinPairs.withIndex()) {
            configure(pin)
            for (attempt in 0..1) {
                if (attempt != 0) call(SourceKwpApi.PAUSE_US, 500)
                var reply = combined(false)
                if (reply.signedWord < 0) reply = combined(true)
                var result = reply.signedWord
                if (result > 0 && reply.service != 0x7F)
                    return SourceKwpEntryResult(if (result == 255) -2 else 0, plan.completeC1(reply))
                if (result > 0) result = 255
                if (attempt == 0) continue
                if (result <= 0 && pinIndex + 1 < plan.pinPairs.size) break
                // Native zero/empty success is preserved for comparison, but
                // is never a successful app initialization or an ISO trigger.
                return SourceKwpEntryResult(when { result == 255 -> -2; result < 0 -> -1; else -> 0 }, false)
            }
        }
        return SourceKwpEntryResult(0, false)
    }

    /** Direct ISO consumer tests on transport 1 do NOT imply that it may dispatch ISO fallback. */
    suspend fun isoEnter(): SourceKwpEntryResult {
        for (pin in plan.pinPairs) {
            configure(pin)
            repeat(2) {
                val address = call(SourceKwpApi.ADDRESS_INIT, plan.addressInit, 0, 0, 0x55, 0)
                if (address.word and 65535L == 0L) {
                    val reply = call(SourceKwpApi.INITIAL, 255, Hex.format(plan.initial.request, "").lowercase(), plan.flags, 0)
                    return SourceKwpEntryResult(if (reply.signedWord < 0) -1 else 0, plan.completeC1(reply))
                }
            }
        }
        return SourceKwpEntryResult(-1, false)
    }

    suspend fun enter(): SourceKwpEntryResult {
        val fast = fastEnter()
        return if (fast.wantsIsoFallback(plan.nativeTransport)) isoEnter().copy(isoFallback = true) else fast
    }

    suspend fun keepalive(enabled: Boolean) {
        if (enabled) setting(SourceKwpApi.KEEPALIVE, wire(plan.keepalive), plan.packet(plan.keepalive.request).size, 3, 800)
        else setting(SourceKwpApi.KEEPALIVE, "", 0, 0, 0)
    }

    suspend fun exit() {
        call(SourceKwpApi.EXIT, 255, 255, 1, wire(plan.exit))
        call(SourceKwpApi.PAUSE_US, 500)
    }

    /**
     * DTCF: context FF -> exit -> enter -> restore -> ONE identical read. A
     * malformed/wrong-ECU exchange is not a retryable missing read. The caller
     * must classify the read failure, never pass a write/setup/clear here.
     * Unlike native, this core requires a successful complete C1 before retry.
     */
    internal suspend fun reenterRead(savedContext: Int) {
        require(savedContext in 0..255)
        call(SourceKwpApi.CONTEXT, 255)
        var failure: Throwable? = null
        try {
            exit()
            if (!enter().initialized) throw SourceKwpInitializationException("Source read re-entry did not establish a complete selected-ECU C1 reply")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                // CONTEXT is bounded host bookkeeping only, never wire I/O.
                // Restoration must also run after cancellation of entry/exit.
                withContext(NonCancellable) { call(SourceKwpApi.CONTEXT, savedContext) }
            } catch (restore: Throwable) {
                if (failure != null) failure.addSuppressed(restore) else throw restore
            }
        }
    }
}

/** Strict typed reader of the ALREADY-COMPILED source-plan projection; not an enabled link codec. */
object SourceKwpPlanCodec {
    const val COMM_SHA256 = "e4f02f44c116b30623ecba3e6fe2e3c70b97a976d61c585b1049888ed0d2f7f2"
    const val SYSP_SHA256 = "6c65bd75f924f17445bf9853b327a318c04debc88a17d14735aaa9c108b1c432"
    fun decode(raw: Any?): SourceKwpPlan {
        val errors = mutableListOf<String>(); val f = JsonFields(errors)
        fun obj(v: Any?, p: String, keys: Set<String>): Map<*, *> {
            val m = f.asObject(v, p) ?: emptyMap<Any, Any>()
            if (m.keys != keys) errors += "$p: missing/unknown source fields"
            return m
        }
        fun num(m: Map<*, *>, key: String): Int {
            val n = f.number(m, key, "source")
            if (n == null || !n.isFinite() || n % 1 != 0.0 || n !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                errors += "$key: exact bounded integer required"; return -1
            }
            return n.toInt()
        }
        fun refs(m: Map<*, *>) = f.strings(m, "sourceRecords", "source")
        fun bytes(m: Map<*, *>, key: String, size: IntRange): List<Int> {
            val value = m[key] as? String
            if (value == null || !value.matches(Regex("(?:[0-9a-f]{2})*")) || value.length / 2 !in size) {
                errors += "$key: invalid source byte string"; return emptyList()
            }
            return if (value.isEmpty()) emptyList() else Hex.parse(value).orEmpty()
        }
        fun frame(v: Any?): SourceKwpFrame {
            val m = obj(v, "frame", setOf("template", "frameId", "requestHex", "maskHex", "responseHex", "sourceRecords"))
            return SourceKwpFrame(num(m, "template"), num(m, "frameId"), bytes(m, "requestHex", 1..63),
                bytes(m, "maskHex", 0..255), bytes(m, "responseHex", 0..255), refs(m))
        }
        val m = obj(raw, "lifecycle", setOf("kind", "region", "function", "entry", "initial", "keepalive", "exit", "requiresIsoFallbackPort", "consumerLibrarySha256", "sourceRecords"))
        val e = obj(m["entry"], "entry", setOf("sourceRecord", "selector", "nativeTransport", "flags", "baud", "sendAddressSourceHex", "runtime107e4", "pinPairs", "sourceTimingHex", "initialTemplates", "keepaliveTemplates", "exitTemplates", "sourceRecords", "consumerLibrarySha256"))
        if (m["kind"] != "source-fast-kwp-lifecycle" || m["consumerLibrarySha256"] != SYSP_SHA256 || e["consumerLibrarySha256"] != COMM_SHA256)
            errors += "Wrong source projection kind/consumer identities"
        val functionKey = f.number(m, "function", "source")?.takeIf { it.isFinite() && it % 1 == 0.0 && it in 0.0..0xFFFFFFFFL.toDouble() }?.toLong()
        if (functionKey == null) errors += "Exact full unsigned function key required"
        val pairs = f.list(e, "pinPairs", "source").mapNotNull { row ->
            val values = row as? List<*>
            fun value(i: Int): Int? = (values?.getOrNull(i) as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1 == 0.0 && it in 0.0..255.0 }?.toInt()
            val a = value(0); val b = value(1)
            if (values?.size != 2 || a == null || b == null) { errors += "Malformed source pin pair"; null } else a to b
        }
        val initial = frame(m["initial"]); val keep = frame(m["keepalive"]); val exit = frame(m["exit"])
        for ((key, expected) in listOf("initialTemplates" to initial.template, "keepaliveTemplates" to keep.template, "exitTemplates" to exit.template)) {
            val list = f.list(e, key, "source")
            if (list.size != 1 || (list.singleOrNull() as? Number)?.toDouble() != expected.toDouble()) errors += "Source template binding differs: $key"
        }
        val record = f.string(e, "sourceRecord", "source") ?: ""
        val entryRecords = refs(e)
        if (entryRecords.isEmpty() || record !in entryRecords || entryRecords.any { !SourceKwpPlan.validSourceRecord(it) }) errors += "Malformed entry provenance"
        val nativeTransport = num(e, "nativeTransport")
        if (m["requiresIsoFallbackPort"] !is Boolean || m["requiresIsoFallbackPort"] != (nativeTransport == 5)) errors += "Source ISO-dispatch flag differs"
        val plan = SourceKwpPlan(num(m, "region"), functionKey ?: -1, record, num(e, "selector"), nativeTransport,
            num(e, "flags"), num(e, "baud"), bytes(e, "sendAddressSourceHex", 2..2), num(e, "runtime107e4"), pairs,
            bytes(e, "sourceTimingHex", 5..5), initial, keep, exit, refs(m))
        errors += plan.problems()
        require(errors.isEmpty()) { errors.distinct().joinToString("; ") }
        return plan.snapshot()
    }
}
