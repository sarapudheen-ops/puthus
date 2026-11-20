package com.bod3.app.obd.kwp

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.recipe.Hex
import com.bod3.app.recipe.JsonFields

/** Application dialect is independent of the wire: Toyota also uses KWP services over CAN. */
enum class DiagnosticDialect { UDS, KWP2000 }
enum class KLineInit { FIVE_BAUD, FAST }
enum class IsoParity { N, O, E }

/** Timing is delegated to the adapter, NOT bit-banged across Bluetooth/Android scheduling. */
data class KLineConfig(
    val init: KLineInit,
    val initAddress: Int,
    val baud: Int = 10400,
    val p1MaxMs: Int = 20,
    val p2MaxMs: Int = 50,
    val p2StarMs: Int = 5000,
    val p3MinMs: Int = 55,
    val p3MaxMs: Int = 5000,
    val p4Ms: Int = 5,
    val fastLowMs: Int = 25,
    val fastHighMs: Int = 25,
    val initParity: IsoParity = IsoParity.N,
    val dataParity: IsoParity = IsoParity.N,
    /** Optional documented W0..W5 values, in the order accepted by STITW. */
    val slowInitTimingsMs: List<Int> = emptyList(),
) {
    fun problems(): List<String> = buildList {
        if (initAddress !in 0..255) add("initAddress: must be one byte")
        if (baud !in 1200..115200) add("baud: must be 1200-115200")
        if (p1MaxMs !in 1..65535 || p2MaxMs !in 1..65535 || p2StarMs !in p2MaxMs..65535) add("P1/P2/P2*: invalid timing")
        if (p3MinMs !in 1..65535 || p3MaxMs !in (p3MinMs + 1)..65535 || p4Ms !in 0..65535) add("P3/P4: invalid timing")
        if (fastLowMs !in 1..1000 || fastHighMs !in 1..1000) add("fast init: invalid pulse timing")
        if (slowInitTimingsMs.size > 6 || slowInitTimingsMs.any { it !in 0..65535 }) add("slow init: at most six bounded timings")
    }
    val keepAliveMs: Long get() = (p3MaxMs / 2L).coerceAtLeast(p3MinMs.toLong())
}

data class DiagnosticLink(
    val protocol: ObdProtocol,
    val dialect: DiagnosticDialect = DiagnosticDialect.UDS,
    val baudKbps: Double? = null,
    val kLine: KLineConfig? = null,
    /** Exact per-ECU keepalive; bare 3E is a KWP variant, never inferred from CAN transport. */
    val testerPresent: List<Int> = listOf(0x3E, 0x00),
) {
    fun problems(): List<String> = buildList {
        if (testerPresent != listOf(0x3E, 0x00) && !(dialect == DiagnosticDialect.KWP2000 && KwpServices.isSourceKeepAlive(testerPresent)))
            add("testerPresent: needs an exact supported, non-writing per-ECU keepalive")
        if (protocol.isCan) {
            if (kLine != null) add("CAN link cannot contain K-line initialization")
            if (baudKbps != null && (!protocol.hasCustomBaud || baudKbps !in 10.0..1000.0)) add("CAN baud is invalid for this protocol")
        } else {
            if (protocol !in K_LINE_PROTOCOLS || kLine == null) add("enhanced legacy link needs an ISO 9141/14230 protocol and per-ECU K-line configuration")
            if (dialect != DiagnosticDialect.KWP2000) add("K-line enhanced services need the KWP2000 dialect")
            if (baudKbps != null) add("K-line baud belongs in kLine.baud")
            kLine?.let { config ->
                addAll(config.problems())
                if ((protocol == ObdProtocol.ISO_14230_4_KWP_FAST) != (config.init == KLineInit.FAST)) add("init type disagrees with selected protocol")
            }
        }
    }
    val needsExtendedKeepAliveSchema: Boolean get() = testerPresent !in listOf(listOf(0x3E), listOf(0x3E, 0x00))

    fun addressProblems(request: String, response: String): List<String> = buildList {
        if (kLine != null) {
            val tx = Hex.parse(request); val rx = Hex.parse(response)
            if (tx?.size != 3 || rx?.size != 3) add("K-line needs complete three-byte request/response headers")
            else if (tx[1] != rx[2] || tx[2] != rx[1]) add("K-line source/target addresses must be an exact pair")
            else if (protocol != ObdProtocol.ISO_9141_2 && ((tx[0] and 0xC0) != 0x80 || (rx[0] and 0xC0) != 0x80)) add("enhanced KWP must use physical, addressed headers")
        }
    }
    companion object {
        val K_LINE_PROTOCOLS = setOf(ObdProtocol.ISO_9141_2, ObdProtocol.ISO_14230_4_KWP_SLOW, ObdProtocol.ISO_14230_4_KWP_FAST)
    }
}

/** Shared by ECU, PID, actuator, coding and backup codecs: no transport metadata is lost on export. */
object DiagnosticLinkCodec {
    fun encode(link: DiagnosticLink): Map<String, Any?> = linkedMapOf(
        "protocol" to link.protocol.name, "dialect" to link.dialect.name, "baudKbps" to link.baudKbps,
        "testerPresent" to link.testerPresent.takeIf { it != listOf(0x3E, 0x00) }?.let { Hex.format(it, "") },
        "kLine" to link.kLine?.let { k -> linkedMapOf(
            "init" to k.init.name, "initAddress" to k.initAddress, "baud" to k.baud,
            "p1MaxMs" to k.p1MaxMs, "p2MaxMs" to k.p2MaxMs, "p2StarMs" to k.p2StarMs,
            "p3MinMs" to k.p3MinMs, "p3MaxMs" to k.p3MaxMs, "p4Ms" to k.p4Ms,
            "fastLowMs" to k.fastLowMs, "fastHighMs" to k.fastHighMs,
            "initParity" to k.initParity.name, "dataParity" to k.dataParity.name,
            "slowInitTimingsMs" to k.slowInitTimingsMs,
        ) },
    ).filterValues { it != null }

    fun decode(raw: Any?, path: String, errors: MutableList<String>): DiagnosticLink? {
        if (raw == null) return null
        val f = JsonFields(errors); val m = f.asObject(raw, path) ?: return null
        val before = errors.size
        fun unknown(map: Map<*, *>, allowed: Set<String>, p: String) { map.keys.filterNot { it in allowed }.forEach { errors += "$p.$it: unknown field" } }
        unknown(m, setOf("protocol", "dialect", "baudKbps", "kLine", "testerPresent"), path)
        fun <T : Enum<T>> readEnum(value: String?, choices: Array<T>, p: String): T? = choices.firstOrNull { it.name == value }.also { if (it == null) errors += "$p: unknown value $value" }
        val protocol = readEnum(f.string(m, "protocol", path), ObdProtocol.entries.toTypedArray(), "$path.protocol")
        val dialect = readEnum(f.string(m, "dialect", path), DiagnosticDialect.entries.toTypedArray(), "$path.dialect")
        val baud = f.number(m, "baudKbps", path, false)
        val keepAlive = f.string(m, "testerPresent", path, false)?.let { text ->
            Hex.parse(text).also { if (it == null) errors += "$path.testerPresent: must be hex bytes" }
        } ?: listOf(0x3E, 0x00)
        val k = f.obj(m, "kLine", path, false)?.let { km ->
            val p = "$path.kLine"
            unknown(km, setOf("init", "initAddress", "baud", "p1MaxMs", "p2MaxMs", "p2StarMs", "p3MinMs", "p3MaxMs", "p4Ms", "fastLowMs", "fastHighMs", "initParity", "dataParity", "slowInitTimingsMs"), p)
            val init = readEnum(f.string(km, "init", p), KLineInit.entries.toTypedArray(), "$p.init")
            val address = f.int(km, "initAddress", p)
            val initParity = readEnum(f.string(km, "initParity", p, false) ?: "N", IsoParity.entries.toTypedArray(), "$p.initParity")
            val dataParity = readEnum(f.string(km, "dataParity", p, false) ?: "N", IsoParity.entries.toTypedArray(), "$p.dataParity")
            val timings = f.list(km, "slowInitTimingsMs", p).mapIndexedNotNull { i, v -> (v as? Number)?.toDouble()?.takeIf { it % 1 == 0.0 && it in 0.0..65535.0 }?.toInt().also { if (it == null) errors += "$p.slowInitTimingsMs[$i]: invalid timing" } }
            if (init == null || address == null || initParity == null || dataParity == null) null else KLineConfig(
                init, address, f.int(km, "baud", p, false) ?: 10400,
                f.int(km, "p1MaxMs", p, false) ?: 20, f.int(km, "p2MaxMs", p, false) ?: 50,
                f.int(km, "p2StarMs", p, false) ?: 5000, f.int(km, "p3MinMs", p, false) ?: 55,
                f.int(km, "p3MaxMs", p, false) ?: 5000, f.int(km, "p4Ms", p, false) ?: 5,
                f.int(km, "fastLowMs", p, false) ?: 25, f.int(km, "fastHighMs", p, false) ?: 25,
                initParity, dataParity, timings,
            )
        }
        if (protocol == null || dialect == null || errors.size != before) return null
        val result = DiagnosticLink(protocol, dialect, baud, k, keepAlive)
        errors += result.problems().map { "$path.$it" }
        return result
    }
}
