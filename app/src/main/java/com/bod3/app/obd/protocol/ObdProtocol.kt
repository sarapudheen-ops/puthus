package com.bod3.app.obd.protocol

import java.util.Locale

/** How many hex characters of each response line form the header when headers are on (`ATH1`, `ATS0`). */
enum class HeaderFormat(val hexLength: Int) {
    /** 11-bit CAN identifier, e.g. `7E8`. */
    CAN_11(3),

    /** 29-bit CAN identifier, e.g. `18DAF110`. */
    CAN_29(8),

    /** J1850 / ISO 9141 / KWP: three header bytes, e.g. `486B10`. A checksum byte ends each line. */
    LEGACY(6),
}

/** Which command set selects the protocol on a vLinker MC+ (MIC3313). */
enum class ProtocolFamily(val label: String) {
    /** ELM327/ELM329 `ATSPx`. */
    ELM("ELM"),

    /** STN-compatible `STP nn` (the MC+ implements the STN ST command set). */
    STN("STN"),

    /** vLinker VT macro commands (e.g. `VTP291` for KWP1281). */
    VT("VT"),
}

/** Physical bus / pins the protocol uses. */
enum class PhysicalBus(val label: String) {
    HS_CAN("HS-CAN (pins 6/14)"),
    MS_CAN("MS-CAN (pins 3/11)"),
    SW_CAN("SW-CAN (pin 1)"),
    K_LINE("K-line (pin 7)"),
    J1850("J1850 (pins 2/10)"),
}

/** The request used to check whether a protocol works during detection. */
enum class ProtocolProbe(val request: String, val description: String) {
    /** Legislated OBD "supported PIDs" request; success = a `41 00` reply. */
    OBD("0100", "OBD 01 00, expects 41 00"),

    /** Raw ISO 11898 CAN: the ISO-TP single-frame byte is sent by hand. Success = a `41 00` reply. */
    RAW_CAN("020100", "raw CAN frame 02 01 00, expects 41 00"),

    /** SAE J1939: request PGN 00FECA (DM1, active faults). Success = any reply frame. */
    J1939("00FECA", "J1939 PGN request 00FECA, expects any reply"),

    /** VAG KWP1281: 5-baud init of address 01 (engine), then any reply. */
    KWP1281("0100", "KWP1281 init to address 01, expects any reply"),
}

/**
 * Every vehicle protocol a vLinker MC+ (MIC3313) can speak.
 *
 * - The ELM protocols `1`–`C` (`ATSPx`), including J1939 (`A`) and the two user-defined CAN protocols
 *   `B`/`C` with a custom baud rate and 11- or 29-bit identifiers (set with `ATPB` / `ATPP 2E/2F`, or
 *   `STPBR` for rates that are no whole divisor of 500 kbit/s, e.g. 1 Mbit/s).
 * - STN protocols (`STP nn`): ISO 15765 with a configurable baud rate, raw ISO 11898 CAN, single-wire
 *   CAN (GMLAN) and medium-speed CAN (Ford MS-CAN), each with 11- and 29-bit identifiers.
 * - The vLinker VT protocol KWP1281 (`VTP291`).
 *
 * [code] is the identifier shown to the user; [dpn] is the number `ATDPN` reports (ELM protocols only).
 */
enum class ObdProtocol(
    val code: String,
    val displayName: String,
    val headerFormat: HeaderFormat,
    val family: ProtocolFamily,
    val bus: PhysicalBus,
    val probe: ProtocolProbe = ProtocolProbe.OBD,
    /** `ATDPN` number for ELM protocols, null for STN/VT protocols. */
    val dpn: Char? = null,
    /** STN `STP` protocol number, VT protocol command suffix, or null. */
    private val selectArgument: String? = null,
    /** Default bit rate in kbit/s for CAN protocols whose rate can be changed (B, C, STN 31/32). */
    val defaultBaudKbps: Double? = null,
) {
    SAE_J1850_PWM("1", "SAE J1850 PWM (41.6 kbaud)", HeaderFormat.LEGACY, ProtocolFamily.ELM, PhysicalBus.J1850, dpn = '1'),
    SAE_J1850_VPW("2", "SAE J1850 VPW (10.4 kbaud)", HeaderFormat.LEGACY, ProtocolFamily.ELM, PhysicalBus.J1850, dpn = '2'),
    ISO_9141_2("3", "ISO 9141-2 (5 baud init)", HeaderFormat.LEGACY, ProtocolFamily.ELM, PhysicalBus.K_LINE, dpn = '3'),
    ISO_14230_4_KWP_SLOW("4", "ISO 14230-4 KWP (5 baud init)", HeaderFormat.LEGACY, ProtocolFamily.ELM, PhysicalBus.K_LINE, dpn = '4'),
    ISO_14230_4_KWP_FAST("5", "ISO 14230-4 KWP (fast init)", HeaderFormat.LEGACY, ProtocolFamily.ELM, PhysicalBus.K_LINE, dpn = '5'),
    ISO_15765_4_CAN_11_500("6", "ISO 15765-4 CAN (11 bit, 500 kbaud)", HeaderFormat.CAN_11, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = '6'),
    ISO_15765_4_CAN_29_500("7", "ISO 15765-4 CAN (29 bit, 500 kbaud)", HeaderFormat.CAN_29, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = '7'),
    ISO_15765_4_CAN_11_250("8", "ISO 15765-4 CAN (11 bit, 250 kbaud)", HeaderFormat.CAN_11, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = '8'),
    ISO_15765_4_CAN_29_250("9", "ISO 15765-4 CAN (29 bit, 250 kbaud)", HeaderFormat.CAN_29, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = '9'),
    SAE_J1939_CAN_29_250("A", "SAE J1939 CAN (29 bit, 250 kbaud)", HeaderFormat.CAN_29, ProtocolFamily.ELM, PhysicalBus.HS_CAN, ProtocolProbe.J1939, dpn = 'A'),
    USER1_CAN_11("B", "User1 CAN (11 bit, custom baud, default 125k)", HeaderFormat.CAN_11, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = 'B', defaultBaudKbps = 125.0),
    USER1_CAN_29("B/29", "User1 CAN (29 bit, custom baud, default 125k)", HeaderFormat.CAN_29, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = 'B', defaultBaudKbps = 125.0),
    USER2_CAN_11("C", "User2 CAN (11 bit, custom baud, default 50k)", HeaderFormat.CAN_11, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = 'C', defaultBaudKbps = 50.0),
    USER2_CAN_29("C/29", "User2 CAN (29 bit, custom baud, default 50k)", HeaderFormat.CAN_29, ProtocolFamily.ELM, PhysicalBus.HS_CAN, dpn = 'C', defaultBaudKbps = 50.0),

    STN_ISO_15765_11("STN 31", "ISO 15765 CAN (11 bit, custom baud, default 500k)", HeaderFormat.CAN_11, ProtocolFamily.STN, PhysicalBus.HS_CAN, selectArgument = "31", defaultBaudKbps = 500.0),
    STN_ISO_15765_29("STN 32", "ISO 15765 CAN (29 bit, custom baud, default 500k)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.HS_CAN, selectArgument = "32", defaultBaudKbps = 500.0),
    // Enum names are retained for saved-profile compatibility; displayed protocols and buses
    // reflect the adapter's ATDP replies, not the old names. Header width for 51/52/61/62
    // still needs physical verification and is deliberately not asserted in the labels.
    STN_ISO_11898_11("STN 41", "SAE J1939 CAN (29 bit, 500 kbaud)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.HS_CAN, ProtocolProbe.J1939, selectArgument = "41"),
    STN_ISO_11898_29("STN 42", "SAE J1939 CAN (29 bit, 250 kbaud)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.HS_CAN, ProtocolProbe.J1939, selectArgument = "42"),
    STN_SW_CAN_15765_11("STN 51", "MS-CAN (125 kbaud; adapter reported)", HeaderFormat.CAN_11, ProtocolFamily.STN, PhysicalBus.MS_CAN, selectArgument = "51"),
    STN_SW_CAN_15765_29("STN 52", "MS-CAN (125 kbaud; adapter reported)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.MS_CAN, selectArgument = "52"),
    STN_SW_CAN_11898_11("STN 53", "SW-CAN GMLAN raw ISO 11898 (11 bit)", HeaderFormat.CAN_11, ProtocolFamily.STN, PhysicalBus.SW_CAN, ProtocolProbe.RAW_CAN, selectArgument = "53"),
    STN_SW_CAN_11898_29("STN 54", "SW-CAN GMLAN raw ISO 11898 (29 bit)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.SW_CAN, ProtocolProbe.RAW_CAN, selectArgument = "54"),
    STN_MS_CAN_15765_11("STN 61", "SW-CAN (33 kbaud; adapter reported)", HeaderFormat.CAN_11, ProtocolFamily.STN, PhysicalBus.SW_CAN, selectArgument = "61"),
    STN_MS_CAN_15765_29("STN 62", "SW-CAN (33 kbaud; adapter reported)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.SW_CAN, selectArgument = "62"),
    STN_MS_CAN_11898_11("STN 63", "MS-CAN raw ISO 11898 (11 bit)", HeaderFormat.CAN_11, ProtocolFamily.STN, PhysicalBus.MS_CAN, ProtocolProbe.RAW_CAN, selectArgument = "63"),
    STN_MS_CAN_11898_29("STN 64", "MS-CAN raw ISO 11898 (29 bit)", HeaderFormat.CAN_29, ProtocolFamily.STN, PhysicalBus.MS_CAN, ProtocolProbe.RAW_CAN, selectArgument = "64"),

    VT_KWP1281("VT KWP1281", "KWP1281 (VAG K-line, VTP291)", HeaderFormat.LEGACY, ProtocolFamily.VT, PhysicalBus.K_LINE, ProtocolProbe.KWP1281, selectArgument = "291"),
    ;

    val isCan: Boolean get() = headerFormat != HeaderFormat.LEGACY

    /** Whether the bit rate can be set (user CAN B/C and STN ISO 15765). */
    val hasCustomBaud: Boolean get() = defaultBaudKbps != null

    /** Short label for lists: `6 · ISO 15765-4 CAN (11 bit, 500 kbaud)`. */
    val label: String get() = "$code · $displayName"

    /**
     * Commands that select this protocol. [baudKbps] overrides the bit rate of protocols with
     * [hasCustomBaud]; null keeps the default.
     */
    fun selectCommands(baudKbps: Double? = null): List<String> = when (family) {
        ProtocolFamily.ELM -> when (dpn) {
            'B' -> userCanCommands(baudKbps, protocolC = false)
            'C' -> userCanCommands(baudKbps, protocolC = true)
            else -> listOf("ATSP$dpn")
        }
        ProtocolFamily.STN -> buildList {
            add("STP $selectArgument")
            if (hasCustomBaud && baudKbps != null && baudKbps != defaultBaudKbps) add("STPBR ${bitsPerSecond(baudKbps)}")
        }
        ProtocolFamily.VT -> listOf("VTP$selectArgument")
    }

    /**
     * ELM user CAN protocols. The options byte: bit 7 = 11-bit IDs, bit 6 = fixed 8-byte frames,
     * bits 2-0 = ISO 15765-4 formatting. The divisor sets `500 kbit/s ÷ n`; rates without a whole
     * divisor (e.g. 1000 or 33.3 kbit/s) are set exactly with `STPBR` afterwards.
     *
     * Options are always sent, so the 11/29-bit choice never leaks from an earlier attempt. `ATPB`
     * (protocol B) is volatile; protocol C has no volatile command, so its options go to the
     * adapter's programmable parameters `2E`/`2F`.
     */
    private fun userCanCommands(baudKbps: Double?, protocolC: Boolean): List<String> {
        val options = if (headerFormat == HeaderFormat.CAN_11) 0xC1 else 0x41
        val rate = baudKbps ?: defaultBaudKbps!!
        val exactDivisor = (500.0 / rate).let { d -> d.toInt().takeIf { it in 1..0x40 && Math.abs(d - it) < 1e-6 } }
        val divisor = exactDivisor ?: 1
        val select = if (protocolC) "ATSPC" else "ATSPB"
        return buildList {
            if (protocolC) {
                add("ATPP 2E SV %02X".format(options))
                add("ATPP 2E ON")
                add("ATPP 2F SV %02X".format(divisor))
                add("ATPP 2F ON")
            } else {
                add("ATPB %02X %02X".format(options, divisor))
            }
            add(select)
            if (exactDivisor == null) add("STPBR ${bitsPerSecond(rate)}")
        }
    }

    /** Whether an `ATDPN` reply describes this protocol (e.g. `A6` or `6`). */
    fun matchesProtocolNumber(reply: String?): Boolean = dpn != null && parseNumber(reply) == dpn

    companion object {
        /** All protocols in display order. */
        val all: List<ObdProtocol> get() = entries

        private fun parseNumber(reply: String?): Char? {
            val text = reply?.trim()?.uppercase() ?: return null
            return when {
                text.length == 2 && text[0] == 'A' -> text[1]
                text.length == 1 -> text[0]
                else -> null
            }
        }

        /**
         * Parses an `ATDPN` reply. `A6` means "automatically selected protocol 6", `6` means protocol 6
         * set explicitly. `0` (auto, nothing found yet) and anything unrecognised return null. For `B`
         * and `C` the 11-bit variant is returned; callers that selected the 29-bit one keep theirs.
         */
        fun fromProtocolNumber(reply: String): ObdProtocol? {
            val number = parseNumber(reply) ?: return null
            return entries.firstOrNull { it.dpn == number }
        }

        fun byCode(code: String): ObdProtocol? = entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }

        private fun bitsPerSecond(kbps: Double): String = String.format(Locale.US, "%d", Math.round(kbps * 1000))
    }
}
