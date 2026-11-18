package com.bod3.app.datapack

/** A fault code description and where it came from. */
data class DtcDescription(val text: String, val source: String, val isUserDefined: Boolean = false)

/**
 * Looks up fault code descriptions: the user's own additions first, then bundled packs, then a generic
 * description of the code's system (SAE J2012 structure). For UDS codes (`P0420-13`) the exact code is
 * tried before the base code, and the failure type byte is explained separately.
 */
class DtcDescriptions(
    private val user: Map<String, String>,
    private val packs: List<Pair<String, Map<String, String>>>,
) {
    fun describe(code: String): DtcDescription {
        val exact = code.trim().uppercase()
        val base = exact.substringBefore('-')
        for (key in listOf(exact, base).distinct()) {
            user[key]?.let { return DtcDescription(it, "My additions", isUserDefined = true) }
        }
        for (key in listOf(exact, base).distinct()) {
            for ((name, map) in packs) map[key]?.let { return DtcDescription(it, name) }
        }
        return DtcDescription(category(base), "Generic (code structure)")
    }

    /** Text for the UDS failure type byte of [code], e.g. `13` -> "circuit open", or null. */
    fun failureType(code: String): String? = code.substringAfter('-', "").takeIf { it.length == 2 }
        ?.toIntOrNull(16)?.let { FAILURE_TYPES[it] ?: "failure type 0x%02X".format(it) }

    companion object {
        /** SAE J2012-DA failure type bytes (common subset). */
        val FAILURE_TYPES: Map<Int, String> = mapOf(
            0x00 to "no sub-type information", 0x01 to "general electrical failure", 0x02 to "general signal failure",
            0x03 to "FM (frequency modulated) / PWM failure", 0x04 to "system internal failure", 0x05 to "system programming failure",
            0x06 to "algorithm-based failure", 0x07 to "mechanical failure", 0x08 to "bus signal / message failure",
            0x09 to "component failure", 0x11 to "circuit short to ground", 0x12 to "circuit short to battery",
            0x13 to "circuit open", 0x14 to "circuit short to ground or open", 0x15 to "circuit short to battery or open",
            0x16 to "circuit voltage below threshold", 0x17 to "circuit voltage above threshold", 0x18 to "circuit current below threshold",
            0x19 to "circuit current above threshold", 0x1A to "circuit resistance below threshold", 0x1B to "circuit resistance above threshold",
            0x1C to "circuit voltage out of range", 0x1D to "circuit current out of range", 0x1E to "circuit resistance out of range",
            0x1F to "circuit intermittent", 0x21 to "signal amplitude < minimum", 0x22 to "signal amplitude > maximum",
            0x23 to "signal stuck low", 0x24 to "signal stuck high", 0x26 to "signal rate of change below threshold",
            0x27 to "signal rate of change above threshold", 0x28 to "signal bias level out of range / zero adjustment failure",
            0x29 to "signal invalid", 0x2F to "signal erratic", 0x31 to "no signal", 0x41 to "general checksum failure",
            0x42 to "general memory failure", 0x44 to "data memory failure", 0x45 to "program memory failure",
            0x46 to "calibration / parameter memory failure", 0x47 to "watchdog / safety µC failure", 0x49 to "internal electronic failure",
            0x4B to "over temperature", 0x51 to "not programmed", 0x52 to "not activated", 0x53 to "deactivated",
            0x54 to "missing calibration", 0x55 to "not configured", 0x61 to "signal calculation failure", 0x62 to "signal compare failure",
            0x63 to "circuit / component protection time-out", 0x64 to "signal plausibility failure", 0x67 to "signal incorrect after event",
            0x71 to "actuator stuck", 0x72 to "actuator stuck open", 0x73 to "actuator stuck closed", 0x74 to "actuator slipping",
            0x75 to "emergency position not reachable", 0x77 to "commanded position not reachable", 0x7B to "low fluid level",
            0x81 to "invalid serial data received", 0x82 to "alive / sequence counter incorrect / not updated",
            0x83 to "value of signal protection calculation incorrect", 0x84 to "signal below allowable range",
            0x85 to "signal above allowable range", 0x86 to "signal invalid", 0x87 to "missing message", 0x88 to "bus off",
            0x8F to "erratic", 0x92 to "performance or incorrect operation", 0x93 to "no operation", 0x94 to "unexpected operation",
            0x96 to "component internal failure", 0x97 to "component or system operation obstructed or blocked",
            0x98 to "component or system over temperature",
        )

        /** Describes the system from the code structure when no description is known. */
        fun category(code: String): String {
            if (code.length != 5) return "Unknown code"
            val system = when (code[0]) {
                'P' -> "Powertrain"
                'C' -> "Chassis"
                'B' -> "Body"
                'U' -> "Network/communication"
                else -> return "Unknown code"
            }
            val manufacturer = when (code[0]) {
                // P1xxx and P30xx-P33xx are manufacturer-controlled; P0, P2 and P34xx-P39xx are generic.
                'P' -> code[1] == '1' || (code[1] == '3' && code[2] in '0'..'3')
                else -> code[1] == '1' || code[1] == '2'
            }
            if (manufacturer) return "$system – manufacturer-specific code (add a description from the manufacturer's documentation)"
            if (code[0] != 'P') return "$system – generic code"
            val area = when (code.substring(1, 3)) {
                "00", "01", "02" -> "fuel and air metering"
                "03" -> "ignition system or misfire"
                "04" -> "auxiliary emission controls"
                "05" -> "vehicle speed, idle control and auxiliary inputs"
                "06" -> "computer and output circuits"
                "07", "08", "09" -> "transmission"
                "0A" -> "hybrid propulsion"
                "20", "21", "22" -> "fuel and air metering"
                "23" -> "ignition system or misfire"
                "24" -> "auxiliary emission controls"
                "25" -> "auxiliary inputs"
                "26" -> "computer and output circuits"
                "27", "28", "29" -> "transmission"
                else -> null
            }
            return if (area != null) "Powertrain – $area (generic code)" else "Powertrain – generic code"
        }
    }
}
