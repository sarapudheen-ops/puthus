package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.HeaderFormat

/** Who an OBD request is addressed to. */
sealed interface EcuTarget {

    /** OBD functional (broadcast) addressing: `7DF` on 11-bit CAN, `18DB33F1` on 29-bit. Every ECU may answer. */
    data object Broadcast : EcuTarget

    /**
     * Physical addressing of a single ECU on CAN.
     *
     * @param requestHeader CAN identifier to send on (`ATSH`), e.g. `7E0` or `18DA10F1`.
     * @param responseHeader CAN identifier to accept replies from (`ATCRA`), e.g. `7E8` or `18DAF110`.
     *   When null the adapter's automatic receive filter is used.
     */
    data class Physical(val requestHeader: String, val responseHeader: String? = null) : EcuTarget {
        init {
            require(requestHeader.isHex()) { "Request header must be hex: $requestHeader" }
            require(responseHeader == null || responseHeader.isHex()) { "Response header must be hex: $responseHeader" }
        }

        override fun toString(): String = "Physical($requestHeader -> ${responseHeader ?: "auto"})"
    }

    companion object {
        /** Primary engine ECU on 11-bit CAN. */
        val ENGINE_CAN_11 = Physical("7E0", "7E8")

        /**
         * Works out the physical request header for the ECU that answered with [responseHeader]:
         * `7E8..7EF` -> `7E0..7E7` on 11-bit CAN, and `18DAF1xx` -> `18DAxxF1` on 29-bit CAN.
         * Returns null when the response header is not one of the standard OBD diagnostic IDs.
         */
        fun physicalFor(responseHeader: String, format: HeaderFormat): Physical? {
            val header = responseHeader.uppercase()
            return when (format) {
                HeaderFormat.CAN_11 -> {
                    val id = header.toIntOrNull(16) ?: return null
                    if (header.length != 3 || id !in 0x7E8..0x7EF) return null
                    Physical("%03X".format(id - 8), header)
                }

                HeaderFormat.CAN_29 -> {
                    if (header.length != 8 || !header.startsWith("18DAF1")) return null
                    val ecu = header.substring(6)
                    Physical("18DA${ecu}F1", header)
                }

                HeaderFormat.LEGACY -> null
            }
        }
    }
}

private fun String.isHex(): Boolean = isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** Builds the adapter commands that route requests to a [EcuTarget]. */
object HeaderCommands {

    const val FUNCTIONAL_11 = "7DF"
    const val FUNCTIONAL_29 = "18DB33F1"

    fun forTarget(target: EcuTarget, format: HeaderFormat): List<String> {
        require(format != HeaderFormat.LEGACY) { "Header targeting is only supported on CAN in this version" }
        val (requestHeader, responseHeader) = when (target) {
            EcuTarget.Broadcast -> (if (format == HeaderFormat.CAN_11) FUNCTIONAL_11 else FUNCTIONAL_29) to null
            is EcuTarget.Physical -> target.requestHeader.uppercase() to target.responseHeader?.uppercase()
        }
        require(requestHeader.length == format.hexLength) {
            "Header $requestHeader does not match ${format.name} (${format.hexLength} hex digits)"
        }
        require(responseHeader == null || responseHeader.length == format.hexLength) {
            "Header $responseHeader does not match ${format.name} (${format.hexLength} hex digits)"
        }

        val commands = mutableListOf<String>()
        if (format == HeaderFormat.CAN_11) {
            commands += "ATSH $requestHeader"
        } else {
            // 29-bit: priority byte via ATCP, remaining three bytes via ATSH. Works on all ELM327 versions.
            commands += "ATCP ${requestHeader.substring(0, 2)}"
            commands += "ATSH ${requestHeader.substring(2)}"
        }
        // ATCRA filters replies to one ECU; ATAR restores automatic receive filtering for broadcasts.
        commands += if (responseHeader != null) "ATCRA $responseHeader" else "ATAR"
        return commands
    }
}
