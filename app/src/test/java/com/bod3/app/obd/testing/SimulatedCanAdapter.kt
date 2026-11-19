package com.bod3.app.obd.testing

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.session.ReadOnlyPolicy
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.transport.TransportException

/**
 * A module on the simulated bus.
 *
 * @param protocolCode ELM protocol number of its bus ('6' = 11-bit 500k, '7' = 29-bit 500k).
 * @param respond Returns the reply payloads for a request payload (several for e.g. 7F 78 then the
 *   answer); an empty list means the module stays silent.
 * @param obd Whether it answers functional OBD requests (7DF / 18DB33F1).
 * @param truncateMultiFrame Simulates a module whose flow control never arrives: only the first frame
 *   of a multi-frame reply is sent.
 */
class SimModule(
    val protocolCode: Char,
    val requestId: String,
    val responseId: String,
    val obd: Boolean = false,
    val truncateMultiFrame: Boolean = false,
    val dialect: com.bod3.app.obd.kwp.DiagnosticDialect = com.bod3.app.obd.kwp.DiagnosticDialect.UDS,
    val respond: (List<Int>) -> List<List<Int>>,
)

/**
 * Simulated ELM327 on a CAN bus: tracks `ATSP`, `ATSH`/`ATCP`, `ATAR`/`ATCRA`/`ATCF`/`ATCM`, flow
 * control and `ATST`, routes data requests to [SimModule]s by ID, frames their replies as ISO-TP lines
 * with headers (as `ATH1 ATS0 ATCAF1` would print them) and applies the receive filter.
 *
 * Every data request is recorded in [requests]; anything outside the read-only policy is recorded in
 * [violations] (and still answered with `NO DATA`), so tests can assert nothing unsafe was sent.
 */
class SimulatedCanAdapter(
    private val modules: List<SimModule>,
    var protocolCode: Char = '6',
) : CommandExecutor {

    data class SentRequest(val protocolCode: Char, val header: String, val payload: List<Int>)

    val commands = mutableListOf<String>()
    val requests = mutableListOf<SentRequest>()
    val violations = mutableListOf<String>()

    /** Fault-memory clear requests (04 / 14) that reached the bus. Only possible with a ClearConfirmation. */
    val clears = mutableListOf<SentRequest>()

    /** Whitelisted non-read requests (session, security, 2F, 31, 2E) that reached the bus; only possible with a WriteGrant. */
    val writes = mutableListOf<SentRequest>()

    /** When true, every command fails like a dropped Bluetooth link. */
    var disconnected = false

    /** Called before every data request; tests use it to cancel mid-scan or count probes. */
    var onRequest: (SentRequest) -> Unit = {}

    /** When set, data requests fail with this adapter error line (e.g. "CAN ERROR"). */
    var failWith: String? = null

    private var priority = "18"
    private var header: String? = null
    private var filter: Filter = Filter.Auto
    var adapterTimeout: String = "32"
        private set
    var flowControlMode: String = "0"
        private set
    var flowControlHeader: String? = null
        private set

    private sealed interface Filter {
        data object Auto : Filter
        data class Exact(val id: String) : Filter
        data class Masked(val pattern: String?, val mask: String?) : Filter
    }

    private val is29: Boolean get() = protocolCode == '7' || protocolCode == '9'

    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse {
        val c = command.filterNot { it.isWhitespace() }.uppercase()
        if (disconnected) throw TransportException("Bluetooth link lost")
        commands += c
        val raw = if (c.startsWith("AT")) handleAt(c) else handleData(c)
        return ElmResponseParser.parse(command, "$raw\r\r")
    }

    private fun handleAt(c: String): String {
        val arg = c.drop(4)
        when {
            c.startsWith("ATSP") -> {
                protocolCode = c[4]
                header = null
                filter = Filter.Auto
            }
            c.startsWith("ATSH") -> header = if (arg.length == 6) priority + arg else arg
            c.startsWith("ATCP") -> priority = arg
            c == "ATAR" -> filter = Filter.Auto
            c.startsWith("ATCRA") -> filter = if (c.length > 5) Filter.Exact(c.drop(5)) else Filter.Auto
            c.startsWith("ATCF") -> filter = Filter.Masked(arg, (filter as? Filter.Masked)?.mask)
            c.startsWith("ATCM") -> filter = Filter.Masked((filter as? Filter.Masked)?.pattern, arg)
            c.startsWith("ATST") -> adapterTimeout = arg
            c.startsWith("ATFCSM") -> flowControlMode = c.drop(6)
            c.startsWith("ATFCSH") -> flowControlHeader = c.drop(6)
            c.startsWith("ATFCSD") -> Unit
            else -> return "?"
        }
        return "OK"
    }

    private fun handleData(c: String): String {
        val payload = c.chunked(2).map { it.toInt(16) }
        val tx = header ?: if (is29) "18DB33F1" else "7DF"
        val sent = SentRequest(protocolCode, tx, payload)
        requests += sent
        val kwpTarget = modules.singleOrNull { it.protocolCode == protocolCode && it.requestId == tx }
            ?.dialect == com.bod3.app.obd.kwp.DiagnosticDialect.KWP2000
        when {
            ReadOnlyPolicy.isClearRequest(payload) -> clears += sent
            ReadOnlyPolicy.isAllowed(payload) || (kwpTarget && com.bod3.app.obd.kwp.KwpServices.readAllowed(payload)) -> Unit
            payload[0] in WritePolicy.allServices -> writes += sent
            else -> violations += "$tx $c"
        }
        onRequest(sent)
        if (disconnected) throw TransportException("Bluetooth link lost")
        failWith?.let { return it }

        val functional = tx == "7DF" || tx == "18DB33F1"
        val targets = modules.filter { it.protocolCode == protocolCode }
            .filter { if (functional) it.obd else it.requestId == tx }
        val lines = targets.flatMap { module ->
            if (!accepts(module.responseId, tx, functional)) return@flatMap emptyList()
            module.respond(payload).flatMap { frame(module.responseId, it, module.truncateMultiFrame) }
        }
        return if (lines.isEmpty()) "NO DATA" else lines.joinToString("\r")
    }

    private fun accepts(responseId: String, tx: String, functional: Boolean): Boolean = when (val f = filter) {
        Filter.Auto -> if (functional) {
            if (is29) responseId.startsWith("18DAF1") else responseId.toInt(16) in 0x7E8..0x7EF
        } else if (is29) {
            responseId == tx.substring(0, 4) + tx.substring(6, 8) + tx.substring(4, 6)
        } else {
            responseId.toInt(16) == tx.toInt(16) + 8
        }
        is Filter.Exact -> responseId == f.id
        is Filter.Masked -> {
            val pattern = (f.pattern ?: "0").toLong(16)
            val mask = (f.mask ?: "0").toLong(16)
            (responseId.toLong(16) and mask) == (pattern and mask)
        }
    }

    private fun frame(id: String, payload: List<Int>, truncate: Boolean): List<String> {
        fun line(bytes: List<Int>) = id + bytes.joinToString("") { "%02X".format(it) }
        if (payload.size <= 7) return listOf(line(listOf(payload.size) + payload + List(7 - payload.size) { 0x55 }))
        val lines = mutableListOf(line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6)))
        if (truncate) return lines
        var rest = payload.drop(6)
        var sequence = 1
        while (rest.isNotEmpty()) {
            val chunk = rest.take(7)
            lines += line(listOf(0x20 or sequence) + chunk + List(7 - chunk.size) { 0x55 })
            rest = rest.drop(7)
            sequence = (sequence + 1) and 0x0F
        }
        return lines
    }

    companion object {
        /**
         * Replies to `22 <DID>` from a table (ASCII strings or raw bytes); unknown identifiers get
         * `7F 22 31` (request out of range). Other requests go to [other].
         */
        fun didResponder(
            values: Map<Int, String>,
            rawValues: Map<Int, List<Int>> = emptyMap(),
            other: (List<Int>) -> List<List<Int>> = { emptyList() },
        ): (List<Int>) -> List<List<Int>> = { request ->
            if (request.size == 3 && request[0] == 0x22) {
                val did = (request[1] shl 8) or request[2]
                val data = rawValues[did] ?: values[did]?.map { it.code }
                listOf(if (data != null) listOf(0x62, request[1], request[2]) + data else listOf(0x7F, 0x22, 0x31))
            } else {
                other(request)
            }
        }
    }
}
