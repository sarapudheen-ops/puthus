package com.bod3.app.obd.protocol

/** How many hex characters of each response line form the header when headers are on (`ATH1`, `ATS0`). */
enum class HeaderFormat(val hexLength: Int) {
    /** 11-bit CAN identifier, e.g. `7E8`. */
    CAN_11(3),

    /** 29-bit CAN identifier, e.g. `18DAF110`. */
    CAN_29(8),

    /** J1850 / ISO 9141 / KWP: three header bytes, e.g. `486B10`. A checksum byte ends each line. */
    LEGACY(6),
}

/** OBD protocols an ELM327 can speak, keyed by the number `ATDPN` reports. */
enum class ObdProtocol(val code: Char, val displayName: String, val headerFormat: HeaderFormat) {
    SAE_J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", HeaderFormat.LEGACY),
    SAE_J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", HeaderFormat.LEGACY),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", HeaderFormat.LEGACY),
    ISO_14230_4_KWP_SLOW('4', "ISO 14230-4 KWP (5 baud init)", HeaderFormat.LEGACY),
    ISO_14230_4_KWP_FAST('5', "ISO 14230-4 KWP (fast init)", HeaderFormat.LEGACY),
    ISO_15765_4_CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", HeaderFormat.CAN_11),
    ISO_15765_4_CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", HeaderFormat.CAN_29),
    ISO_15765_4_CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", HeaderFormat.CAN_11),
    ISO_15765_4_CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", HeaderFormat.CAN_29),
    SAE_J1939_CAN_29_250('A', "SAE J1939 CAN (29 bit, 250 kbaud)", HeaderFormat.CAN_29),
    USER1_CAN_11_125('B', "User1 CAN (11 bit, 125 kbaud)", HeaderFormat.CAN_11),
    USER2_CAN_11_50('C', "User2 CAN (11 bit, 50 kbaud)", HeaderFormat.CAN_11),
    ;

    val isCan: Boolean get() = headerFormat != HeaderFormat.LEGACY

    companion object {
        /**
         * Parses an `ATDPN` reply. `A6` means "automatically selected protocol 6", `6` means protocol 6
         * set explicitly. `0` (auto, nothing found yet) and anything unrecognised return null.
         */
        fun fromProtocolNumber(reply: String): ObdProtocol? {
            val text = reply.trim().uppercase()
            val code = when {
                text.length == 2 && text[0] == 'A' -> text[1]
                text.length == 1 -> text[0]
                else -> return null
            }
            return entries.firstOrNull { it.code == code }
        }
    }
}
