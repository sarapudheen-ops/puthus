package com.bod3.app.obd.testing

/**
 * Text-level simulation of a vLinker MC+ (MIC3313, ELM327 command set + VT extensions) plugged into a
 * vehicle that speaks [vehicleProtocol]. Use as the responder of [FakeAdapterTransport].
 *
 * Models: init commands, `ATSPx` selection, the automatic search (`ATSP0`), `ATDP`/`ATDPN`, `ATRV`,
 * and OBD replies framed for the selected protocol: ISO-TP on CAN 11/29-bit, and K-line / J1850 framing
 * with a 3-byte header and checksum (`48 6B 10` for ISO 9141-2, `8x F1 10` for KWP2000), including the
 * five-message VIN of K-line vehicles.
 */
class SimulatedVehicleElm(
    var vehicleProtocol: Char = '6',
    /** Whether the automatic search finds the vehicle (some K-line cars need an explicit ATSP). */
    var autoSearchWorks: Boolean = true,
    var vt: Boolean = true,
    var volts: String = "12.4V",
    var vin: String? = "WVGZZZ5NZJW000001",
    var storedDtcs: List<Int> = listOf(0x0420, 0x0171, 0x0300, 0xC100),
    var pendingDtcs: List<Int> = emptyList(),
    var permanentDtcs: List<Int> = listOf(0x0420),
    /** Commands that get no reply at all (simulating a hung adapter). */
    val silent: MutableSet<String> = mutableSetOf(),
    /** Commands the adapter does not know (answers `?`), e.g. `ATCEA` on old clones. */
    val rejected: MutableSet<String> = mutableSetOf(),
) {
    val commands = mutableListOf<String>()
    private var selected = '0'
    private var found: Char? = null
    private var auto = true

    private val names = mapOf(
        '1' to "SAE J1850 PWM", '2' to "SAE J1850 VPW", '3' to "ISO 9141-2", '4' to "ISO 14230-4 (KWP 5BAUD)",
        '5' to "ISO 14230-4 (KWP FAST)", '6' to "ISO 15765-4 (CAN 11/500)", '7' to "ISO 15765-4 (CAN 29/500)",
        '8' to "ISO 15765-4 (CAN 11/250)", '9' to "ISO 15765-4 (CAN 29/250)",
    )

    fun respond(raw: String): String? {
        val command = raw.filterNot { it.isWhitespace() }.uppercase()
        commands += command
        if (command in silent) return null
        if (command in rejected) return "?"
        return when {
            command == "ATZ" -> {
                selected = '0'; found = null; auto = true
                "\r\rELM327 v1.5"
            }
            command == "ATI" -> "ELM327 v1.5"
            command == "AT@1" -> "vLinker MC+"
            command == "VTVERS" -> if (vt) "vLinker MC+ V2.2.11" else "?"
            command == "STI" -> "?"
            command == "ATRV" -> volts
            command.startsWith("ATSP") -> {
                selected = command[4]
                auto = selected == '0'
                found = null
                "OK"
            }
            command == "ATDPN" -> when {
                found != null && auto -> "A${found}"
                found != null -> "$found"
                else -> "0"
            }
            command == "ATDP" -> when {
                found != null -> (if (auto) "AUTO, " else "") + names.getValue(found!!)
                auto -> "AUTO"
                else -> names[selected] ?: "?"
            }
            command.startsWith("VT") -> if (vt) "OK" else "?"
            command.startsWith("AT") -> "OK"
            else -> obd(command)
        }
    }

    private fun obd(command: String): String {
        if (found == null) {
            val reachable = if (auto) autoSearchWorks else selected == vehicleProtocol
            if (!reachable) {
                return when {
                    auto -> "SEARCHING...\rUNABLE TO CONNECT"
                    selected in '3'..'5' -> "BUS INIT: ...ERROR"
                    selected in '6'..'9' -> "CAN ERROR"
                    else -> "NO DATA"
                }
            }
            found = vehicleProtocol
        }
        val request = command.chunked(2).map { it.toInt(16) }
        val replies: List<List<Int>> = when (request) {
            listOf(0x01, 0x00) -> listOf(listOf(0x41, 0x00, 0xBE, 0x3E, 0xA8, 0x13))
            listOf(0x01, 0x0C) -> listOf(listOf(0x41, 0x0C, 0x1A, 0xF8))
            listOf(0x09, 0x02) -> vinReplies()
            listOf(0x03) -> dtcReplies(0x43, storedDtcs)
            listOf(0x07) -> dtcReplies(0x47, pendingDtcs)
            listOf(0x0A) -> dtcReplies(0x4A, permanentDtcs)
            else -> emptyList()
        }
        if (replies.isEmpty()) return "NO DATA"
        return replies.flatMap(::frame).joinToString("\r")
    }

    private val isCan: Boolean get() = vehicleProtocol in '6'..'9'

    private fun vinReplies(): List<List<Int>> {
        val text = vin ?: return emptyList()
        val bytes = text.map { it.code }
        return if (isCan) {
            listOf(listOf(0x49, 0x02, 0x01) + bytes)
        } else {
            // Five messages of 4 bytes each; the first is padded with three zeros.
            val padded = listOf(0, 0, 0) + bytes
            padded.chunked(4).mapIndexed { i, chunk -> listOf(0x49, 0x02, i + 1) + chunk }
        }
    }

    private fun dtcReplies(service: Int, codes: List<Int>): List<List<Int>> {
        val bytes = codes.flatMap { listOf(it shr 8, it and 0xFF) }
        return if (isCan) {
            listOf(listOf(service, codes.size) + bytes)
        } else {
            // K-line: three codes per message, no count byte, padded with 00 00.
            val chunks = bytes.chunked(6).ifEmpty { listOf(emptyList()) }
            chunks.map { chunk -> listOf(service) + chunk + List(6 - chunk.size) { 0 } }
        }
    }

    private fun frame(payload: List<Int>): List<String> {
        fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }
        return when (vehicleProtocol) {
            '6', '8' -> canFrames("7E8", payload)
            '7', '9' -> canFrames("18DAF110", payload)
            '3' -> {
                val bytes = listOf(0x48, 0x6B, 0x10) + payload
                listOf(hex(bytes + (bytes.sum() and 0xFF)))
            }
            else -> {
                val bytes = listOf(0x80 or payload.size, 0xF1, 0x10) + payload
                listOf(hex(bytes + (bytes.sum() and 0xFF)))
            }
        }
    }

    private fun canFrames(id: String, payload: List<Int>): List<String> {
        fun line(bytes: List<Int>) = id + bytes.joinToString("") { "%02X".format(it) }
        if (payload.size <= 7) return listOf(line(listOf(payload.size) + payload + List(7 - payload.size) { 0x00 }))
        val lines = mutableListOf(line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6)))
        var rest = payload.drop(6)
        var sequence = 1
        while (rest.isNotEmpty()) {
            val chunk = rest.take(7)
            lines += line(listOf(0x20 or sequence) + chunk + List(7 - chunk.size) { 0x00 })
            rest = rest.drop(7)
            sequence = (sequence + 1) and 0x0F
        }
        return lines
    }
}
