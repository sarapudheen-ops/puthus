package com.bod3.app.obd.testing

/**
 * Text-level simulation of a vLinker MC+ (MIC3313, ELM327 + STN + VT command sets) plugged into a
 * vehicle that speaks [vehicleProtocol] (an [com.bod3.app.obd.protocol.ObdProtocol.code], e.g. `6`,
 * `B/29`, `STN 61`, `VT KWP1281`). Use as the responder of [FakeAdapterTransport].
 *
 * Models: init commands, `ATSPx` / `STP nn` / `VTP291` selection, user CAN `ATPB` (11/29-bit and
 * divisor) and `STPBR`, the automatic search (`ATSP0`, ELM protocols only), `ATDP`/`ATDPN`, `ATRV`,
 * J1939 PGN requests, raw ISO 11898 frames,
 * and OBD replies framed for the selected protocol: ISO-TP on CAN 11/29-bit, and K-line / J1850 framing
 * with a 3-byte header and checksum (`48 6B 10` for ISO 9141-2, `8x F1 10` for KWP2000), including the
 * five-message VIN of K-line vehicles.
 */
class SimulatedVehicleElm(
    var vehicleProtocol: String = "6",
    /** Bit rate of the vehicle bus for user CAN / STN CAN protocols (kbit/s); must match the adapter's. */
    var vehicleBaudKbps: Double? = null,
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
    /** DTC that stored freeze frame 0 (null = no freeze frame). */
    var freezeFrameDtc: Int? = 0x0420,
    /** PID 01 01 bytes A B C D: MIL on, 4 codes; spark; catalyst and O2 sensor incomplete. */
    var readiness: List<Int> = listOf(0x84, 0x07, 0x65, 0x21),
    /** Service 04 is refused with NRC 22 while the engine runs. */
    var engineRunning: Boolean = false,
    /** Commands the adapter does not know (answers `?`), e.g. `ATCEA` on old clones. */
    val rejected: MutableSet<String> = mutableSetOf(),
) {
    val commands = mutableListOf<String>()
    var clearCount = 0
        private set
    private var selected = "0"
    private var found: String? = null
    private var auto = true

    /** User CAN B options/divisor set with ATPB (default: 11-bit, 500/4 = 125k). */
    private var userBOptions = 0xC1
    private var userBDivisor = 4
    private var userCOptions = 0xC1
    private var userCDivisor = 10
    private var stnBaudKbps: Double? = null

    /** The protocol code the adapter is set to, taking ATPB's 11/29-bit option into account. */
    private val selectedCode: String get() = when (selected) {
        "B" -> if (userBOptions and 0x80 != 0) "B" else "B/29"
        "C" -> if (userCOptions and 0x80 != 0) "C" else "C/29"
        else -> selected
    }

    /** Bit rate the adapter would use for the selected protocol. */
    private val selectedBaudKbps: Double? get() = stnBaudKbps ?: when (selected) {
        "B" -> 500.0 / userBDivisor
        "C" -> 500.0 / userCDivisor
        "STN 31", "STN 32" -> 500.0
        else -> null
    }

    private val vehicleDefaultBaud: Double? get() = vehicleBaudKbps ?: when (vehicleProtocol) {
        "B", "B/29" -> 125.0
        "C", "C/29" -> 50.0
        "STN 31", "STN 32" -> 500.0
        else -> null
    }

    private val names = mapOf(
        "1" to "SAE J1850 PWM", "2" to "SAE J1850 VPW", "3" to "ISO 9141-2", "4" to "ISO 14230-4 (KWP 5BAUD)",
        "5" to "ISO 14230-4 (KWP FAST)", "6" to "ISO 15765-4 (CAN 11/500)", "7" to "ISO 15765-4 (CAN 29/500)",
        "8" to "ISO 15765-4 (CAN 11/250)", "9" to "ISO 15765-4 (CAN 29/250)", "A" to "SAE J1939 (CAN 29/250)",
        "B" to "USER1 (CAN 11/125)", "B/29" to "USER1 (CAN 29/125)", "C" to "USER2 (CAN 11/50)", "C/29" to "USER2 (CAN 29/50)",
        "STN 31" to "ISO 15765, 11-bit Tx, 500 kbps", "STN 61" to "Ford MS CAN ISO 15765, 11-bit",
        "STN 51" to "SW CAN ISO 15765, 11-bit", "VT KWP1281" to "KWP1281",
    )

    /** Protocols the automatic search (ATSP0) can find: ELM 1-9 only, like a real ELM327. */
    private val autoSearchable = setOf("1", "2", "3", "4", "5", "6", "7", "8", "9")

    fun respond(raw: String): String? {
        val command = raw.filterNot { it.isWhitespace() }.uppercase()
        commands += command
        if (command in silent) return null
        if (command in rejected) return "?"
        return when {
            command == "ATZ" -> {
                selected = "0"; found = null; auto = true; stnBaudKbps = null
                userBOptions = 0xC1; userBDivisor = 4
                "\r\rELM327 v1.5"
            }
            command == "ATI" -> "ELM327 v1.5"
            command == "AT@1" -> "vLinker MC+"
            command == "VTVERS" -> if (vt) "vLinker MC+ V2.2.11" else "?"
            command == "STI" -> "?"
            command == "ATRV" -> volts
            command.startsWith("ATSP") -> {
                selected = command.substring(4)
                auto = selected == "0"
                found = null
                stnBaudKbps = null
                "OK"
            }
            command.startsWith("ATPB") && command.length == 8 -> {
                userBOptions = command.substring(4, 6).toInt(16)
                userBDivisor = command.substring(6, 8).toInt(16)
                "OK"
            }
            command.startsWith("ATPP2ESV") -> { userCOptions = command.substring(8).toInt(16); "OK" }
            command.startsWith("ATPP2FSV") -> { userCDivisor = command.substring(8).toInt(16); "OK" }
            command.startsWith("STPBR") -> { stnBaudKbps = command.substring(5).toDouble() / 1000.0; "OK" }
            command.startsWith("STP") && command.length == 5 && command.substring(3).all(Char::isDigit) -> {
                selected = "STN " + command.substring(3)
                auto = false; found = null; stnBaudKbps = null
                "OK"
            }
            command == "VTP291" -> if (vt) {
                selected = "VT KWP1281"; auto = false; found = null
                "OK"
            } else {
                "?"
            }
            command == "ATDPN" -> when {
                found != null && auto -> "A${found!!.take(1)}"
                selected.startsWith("STN") || selected.startsWith("VT") -> "0"
                found != null -> found!!.take(1)
                else -> "0"
            }
            command == "ATDP" -> when {
                found != null -> (if (auto) "AUTO, " else "") + (names[found!!] ?: found!!)
                auto -> "AUTO"
                else -> names[selectedCode] ?: selectedCode
            }
            command.startsWith("VT") -> if (vt) "OK" else "?"
            command.startsWith("ST") -> "OK"
            command.startsWith("AT") -> "OK"
            else -> obd(command)
        }
    }

    private fun obd(command: String): String {
        if (found == null) {
            val baudMatches = vehicleDefaultBaud == null || selectedBaudKbps == null || Math.abs(vehicleDefaultBaud!! - selectedBaudKbps!!) < 0.01
            val reachable = if (auto) autoSearchWorks && vehicleProtocol in autoSearchable else selectedCode == vehicleProtocol && baudMatches
            if (!reachable) {
                return when {
                    auto -> "SEARCHING...\rUNABLE TO CONNECT"
                    selected in setOf("3", "4", "5", "VT KWP1281") -> "BUS INIT: ...ERROR"
                    selected == "1" || selected == "2" -> "NO DATA"
                    else -> "CAN ERROR"
                }
            }
            found = vehicleProtocol
        }
        if (vehicleProtocol == "A") return j1939(command)
        if (vehicleProtocol == "VT KWP1281") return "01 F6 B0 4B 30 33 39 30 36 30 33 32 41 20 20 03"
        val raw = vehicleProtocol in setOf("STN 41", "STN 42", "STN 53", "STN 54", "STN 63", "STN 64")
        val request = (if (raw) command.drop(2) else command).chunked(2).map { it.toInt(16) }
        val replies: List<List<Int>> = when (request) {
            listOf(0x01, 0x00) -> listOf(listOf(0x41, 0x00, 0xBE, 0x3E, 0xA8, 0x13))
            listOf(0x01, 0x0C) -> listOf(listOf(0x41, 0x0C, 0x1A, 0xF8))
            listOf(0x09, 0x02) -> vinReplies()
            listOf(0x03) -> dtcReplies(0x43, storedDtcs)
            listOf(0x07) -> dtcReplies(0x47, pendingDtcs)
            listOf(0x0A) -> dtcReplies(0x4A, permanentDtcs)
            listOf(0x01, 0x01) -> listOf(listOf(0x41, 0x01) + readiness)
            listOf(0x04) -> if (engineRunning) {
                listOf(listOf(0x7F, 0x04, 0x22))
            } else {
                clearCount++
                storedDtcs = emptyList(); pendingDtcs = emptyList(); freezeFrameDtc = null
                readiness = listOf(0x00, readiness[1] or 0x00, readiness[2], readiness[2]) // all monitors incomplete
                listOf(listOf(0x44))
            }
            else -> if (request.size == 3 && request[0] == 0x02) freezeFrame(request[1], request[2]) else emptyList()
        }
        if (replies.isEmpty()) return "NO DATA"
        return replies.flatMap(::frame).joinToString("\r")
    }

    private val isCan: Boolean get() = vehicleProtocol !in setOf("1", "2", "3", "4", "5", "VT KWP1281")
    private val is29: Boolean get() = vehicleProtocol in setOf("7", "9", "A", "B/29", "C/29") || (vehicleProtocol.startsWith("STN") && vehicleProtocol.last() in "24")

    /** J1939: PGN request 00FECA → one DM1 frame from source address 00 (engine). */
    private fun j1939(command: String): String =
        if (command == "00FECA") "18FECA00 00 FF 00 00 00 00 FF FF".filterNot { it == ' ' } else "NO DATA"

    /** Freeze frame 0: PIDs 02 (DTC), 04 load 50 %, 05 coolant 83 °C, 0C 750 rpm, 0D 0 km/h. */
    private fun freezeFrame(pid: Int, frame: Int): List<List<Int>> {
        val dtc = freezeFrameDtc
        if (frame != 0) return emptyList()
        val data = when (pid) {
            0x02 -> if (dtc == null) listOf(0x00, 0x00) else listOf(dtc shr 8, dtc and 0xFF)
            0x00 -> listOf(0x58, 0x18, 0x00, 0x00)
            0x04 -> listOf(0x80)
            0x05 -> listOf(0x7B)
            0x0C -> listOf(0x0B, 0xB8)
            0x0D -> listOf(0x00)
            else -> return emptyList()
        }
        if (dtc == null && pid != 0x02) return emptyList()
        return listOf(listOf(0x42, pid, frame) + data)
    }

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
        return when {
            isCan && !is29 -> canFrames("7E8", payload)
            isCan -> canFrames("18DAF110", payload)
            vehicleProtocol == "3" -> {
                val bytes = listOf(0x48, 0x6B, 0x10) + payload
                listOf(hex(bytes + (bytes.sum() and 0xFF)))
            }
            vehicleProtocol == "1" || vehicleProtocol == "2" -> {
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
