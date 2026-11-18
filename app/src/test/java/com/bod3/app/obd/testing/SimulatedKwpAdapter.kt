package com.bod3.app.obd.testing

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.kwp.KLineFrames
import com.bod3.app.obd.transport.TransportException
import com.bod3.app.recipe.Hex

/** Deliberately synthetic ECU, not Toyota vehicle data. Every outgoing byte is inspectable. */
class SimKwpEcu {
    val locals = mutableMapOf(0x20 to listOf(0, 0x55))
    val identifiers = mutableMapOf(0x1234 to listOf(0xAB, 0xCD))
    var session = 1
    var output = false
    var routine = false
    var unlocked = false
    var dtcs = true
    var rejectWrite = false
    var wrongReadBack = false
    var pending = false
    val requests = mutableListOf<List<Int>>()

    fun respond(r: List<Int>): List<List<Int>> {
        requests += r
        val service = r.first()
        fun no(code: Int) = listOf(listOf(0x7F, service, code))
        val p = when (service) {
            0x81 -> listOf(0xC1, 0xE9, 0x8F)
            0x10 -> { session = r[1]; listOf(0x50, r[1]) }
            0x1A -> listOf(0x5A, r[1], 0x53, 0x49, 0x4D)
            0x21 -> listOf(0x61, r[1]) + (locals[r[1]] ?: return no(0x31))
            0x22 -> listOf(0x62, r[1], r[2]) + (identifiers[r[1] * 256 + r[2]] ?: return no(0x31))
            0x18 -> if (dtcs) listOf(0x58, 1, 0x01, 0x23, 0x20) else listOf(0x58, 0)
            0x19 -> listOf(0x59, 0)
            0x14 -> { dtcs = false; listOf(0x54) }
            0x27 -> if (r[1] % 2 == 1) listOf(0x67, r[1], 1, 2) else {
                if (r.drop(2) != listOf(1, 3)) return no(0x35)
                unlocked = true; listOf(0x67, r[1])
            }
            0x30 -> { output = r[2] != 0; listOf(0x70, r[1]) }
            0x31 -> { routine = true; listOf(0x71, r[1]) }
            0x32 -> { routine = false; listOf(0x72, r[1]) }
            0x33 -> listOf(0x73, r[1], if (routine) 1 else 0)
            0x3B -> {
                if (rejectWrite) return no(0x22)
                locals[r[1]] = if (wrongReadBack) listOf(0xEE, 0xEE) else r.drop(2)
                listOf(0x7B, r[1])
            }
            0x2E -> { identifiers[r[1] * 256 + r[2]] = r.drop(3); listOf(0x6E, r[1], r[2]) }
            0x3E -> listOf(0x7E, 0)
            else -> return no(0x11)
        }
        return (if (pending) listOf(listOf(0x7F, service, 0x78)) else emptyList()) + listOf(p)
    }
}

/** Real length/header/checksum text framing, programmable adapter-command rejections and link loss. */
class SimulatedKwpAdapter(val ecu: SimKwpEcu = SimKwpEcu(), val iso9141: Boolean = false) : CommandExecutor {
    val commands = mutableListOf<String>()
    val rejected = mutableSetOf<String>()
    var requestHeader = listOf(0x80, 0x10, 0xF1)
    var responseHeader = listOf(0x80, 0xF1, 0x10)
    var corruptChecksum = false
    var wrongEcu = false
    var disconnected = false
    var onRequest: (List<Int>) -> Unit = {}

    override suspend fun execute(command: String, timeoutMs: Long): com.bod3.app.obd.elm.ElmResponse {
        if (disconnected) throw TransportException("Synthetic Bluetooth disconnect")
        val c = command.filterNot(Char::isWhitespace).uppercase()
        commands += c
        val raw = when {
            c in rejected -> "?"
            c.startsWith("ATSH") -> { requestHeader = Hex.parse(c.drop(4))!!; "OK" }
            c == "ATSI" -> "BUS INIT: OK\rOK"
            c.startsWith("STIFI") -> frame(listOf(0xC1, 0xE9, 0x8F))
            c.startsWith("AT") || c.startsWith("ST") || c.startsWith("VT") -> "OK"
            else -> {
                val request = requireNotNull(Hex.parse(c))
                onRequest(request)
                if (disconnected) throw TransportException("Synthetic disconnect during exchange")
                ecu.respond(request).joinToString("\r", transform = ::frame)
            }
        }
        return ElmResponseParser.parse(command, raw)
    }

    private fun frame(payload: List<Int>): String {
        val header = responseHeader.toMutableList().also { if (wrongEcu) it[2] = it[2] xor 1 }
        val bytes = KLineFrames.encode(header, payload, iso9141).toMutableList()
        if (corruptChecksum) bytes[bytes.lastIndex] = bytes.last() xor 1
        return Hex.format(bytes)
    }
}
