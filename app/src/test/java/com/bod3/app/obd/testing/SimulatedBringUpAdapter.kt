package com.bod3.app.obd.testing

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.kwp.KLineFrames
import com.bod3.app.recipe.Hex

/** Synthetic serial-number ECU. Baud selects a test scenario, NEVER an inferred Toyota mapping. */
class SimulatedBringUpAdapter(val modes: Map<Int, Mode> = mapOf(10400 to Mode.WORKING)) : CommandExecutor {
    enum class Mode { WORKING, SILENT, WRONG_FRAMING, WRONG_CHECKSUM, WRONG_ECU, WRONG_ECHO, MIXED, SHORT_LIVE, BAD_DTC }
    data class Sent(val baud: Int, val header: String, val bytes: List<Int>)
    val commands = mutableListOf<Pair<String, Long>>()
    val requests = mutableListOf<Sent>()
    val rejected = mutableSetOf<String>()
    var serial = "SYNTHETIC-ECU-001"
    var baud = 10400
    var header = "8010F1"
    var hugeIdentification = false
    var emptyDtcs = false
    var beforeInit: suspend () -> Unit = {}
    var beforeRead: suspend (List<Int>) -> Unit = {}
    var afterRead: (List<Int>) -> Unit = {}
    var rawOverride: ((List<Int>) -> String?)? = null

    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse {
        val c = command.filterNot(Char::isWhitespace).uppercase()
        commands += c to timeoutMs
        if (c in rejected) return ElmResponseParser.parse(command, "?\r")
        val mode = modes[baud] ?: Mode.SILENT
        val raw = when {
            c.startsWith("STPBR") -> { baud = c.drop(5).toInt(); "OK\r" }
            c.startsWith("ATSH") -> { header = c.drop(4); "OK\r" }
            c.startsWith("STIFI") -> { beforeInit(); frame(listOf(0xC1, 0xE9, 0x8F), Mode.WORKING) }
            c == "ATSI" -> { beforeInit(); "OK\r" }
            c.startsWith("AT") || c.startsWith("ST") -> "OK\r"
            else -> {
                val request = requireNotNull(Hex.parse(c))
                requests += Sent(baud, header, request)
                beforeRead(request)
                val payload = when (request.first()) {
                    0x1A -> listOf(0x5A, if (mode == Mode.WRONG_ECHO) 0x91 else 0x90) +
                        (if (hugeIdentification) "X".repeat(10000) else serial).map(Char::code)
                    0x21 -> listOf(0x61, 0x20) + if (mode == Mode.SHORT_LIVE) emptyList() else listOf(0x64)
                    0x18 -> if (mode == Mode.BAD_DTC) listOf(0x58, 1, 0x01) else if (emptyDtcs) listOf(0x58, 0) else listOf(0x58, 1, 0x01, 0x23, 0x20)
                    0x3B -> listOf(0x7B, request[1])
                    0x10 -> listOf(0x50, request[1])
                    0x14 -> listOf(0x54)
                    else -> error("Unexpected synthetic bring-up request: $c")
                }
                val override = rawOverride?.invoke(request)
                afterRead(request)
                override ?: if (mode == Mode.SILENT) "NO DATA\r" else
                    (if (mode == Mode.MIXED) frame(listOf(0x7F, request.first(), 0x78), Mode.WORKING) + "\r" else "") + frame(payload, mode)
            }
        }
        return ElmResponseParser.parse(command, raw)
    }
    private fun frame(payload: List<Int>, mode: Mode): String {
        val h = listOf(0x80, 0xF1, if (mode == Mode.WRONG_ECU) 0x11 else 0x10)
        if (payload.size > 255) return "X".repeat(10000) // Evidence overflow, not a forged valid large K-line message.
        val bytes = KLineFrames.encode(h, payload).toMutableList()
        if (mode == Mode.WRONG_CHECKSUM) bytes[bytes.lastIndex] = bytes.last() xor 1
        if (mode == Mode.WRONG_FRAMING) {
            bytes[0] = bytes[0] xor 1
            bytes[bytes.lastIndex] = KLineFrames.checksum(bytes.dropLast(1)) // VALID checksum, WRONG length.
        }
        return Hex.format(bytes) + "\r"
    }
}
