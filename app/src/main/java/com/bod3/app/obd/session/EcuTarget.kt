package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.HeaderFormat

/** Who an OBD/UDS request is addressed to. */
sealed interface EcuTarget {

    /** OBD functional (broadcast) addressing: `7DF` on 11-bit CAN, `18DB33F1` on 29-bit. Every OBD ECU may answer. */
    data object Broadcast : EcuTarget

    /**
     * Physical addressing of a single ECU on CAN.
     *
     * @param requestHeader CAN identifier to send on (`ATSH`), e.g. `7E0` or `18DA10F1`.
     * @param responseHeader CAN identifier to accept replies from. Alone it becomes an exact filter
     *   (`ATCRA`); together with [responseMask] it is a filter/mask pair (`ATCF` / `ATCM`), which lets a
     *   module scan accept replies from IDs it cannot predict. When null, the standard OBD response range
     *   is accepted (`7E8`-`7EF` / `18DAF1xx`).
     * @param flowControl Whether to set up ISO-TP flow control for this pair when the adapter would not
     *   get it right by itself. The adapter's automatic flow control replies on response - 8 (11-bit) or
     *   with swapped addresses (29-bit), which is wrong for modules with non-standard pairs. When true and
     *   the pair is non-standard, flow control frames are sent on [requestHeader] (`ATFCSH` / `ATFCSD` /
     *   `ATFCSM1`); otherwise automatic flow control (`ATFCSM0`) is used.
     */
    data class Physical(
        val requestHeader: String,
        val responseHeader: String? = null,
        val responseMask: String? = null,
        val flowControl: Boolean = true,
    ) : EcuTarget {
        init {
            require(requestHeader.isHex()) { "Request header must be hex: $requestHeader" }
            require(responseHeader == null || responseHeader.isHex()) { "Response header must be hex: $responseHeader" }
            require(responseMask == null || (responseMask.isHex() && responseHeader != null)) {
                "Response mask needs a hex response header: $responseMask"
            }
        }

        override fun toString(): String = buildString {
            append("Physical($requestHeader -> ")
            append(responseHeader ?: "OBD range")
            if (responseMask != null) append(" mask $responseMask")
            append(")")
        }
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

internal fun String.isHex(): Boolean = isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** CAN options from the connection profile that affect routing and parsing. */
data class SessionOptions(
    /** ISO 15765-2 extended addressing (`ATCEA`): each reply frame starts with an address byte. */
    val extendedAddressing: Boolean = false,
    /** Receive filter/mask for broadcast requests instead of automatic receive. */
    val broadcastFilter: String? = null,
    val broadcastMask: String? = null,
    /** Send custom flow control (`ATFCSH`/`ATFCSD`/`ATFCSM1`) for every physical target, not only non-standard pairs. */
    val customFlowControl: Boolean = false,
    /** Flow-control data bytes, e.g. `300000`. */
    val flowControlData: String = "300000",
) {
    companion object {
        val DEFAULT = SessionOptions()
    }
}

/**
 * The adapter settings that route requests to a target, split into groups so a [Session] only re-sends
 * the groups that actually changed.
 */
data class Routing(val header: List<String>, val filter: List<String>, val flowControl: List<String>) {
    val all: List<String> get() = header + filter + flowControl
}

/** Builds the adapter commands that route requests to an [EcuTarget]. */
object HeaderCommands {

    const val FUNCTIONAL_11 = "7DF"
    const val FUNCTIONAL_29 = "18DB33F1"

    /** Automatic receive: OBD replies to functional requests, request + 8 / swapped 29-bit IDs otherwise. */
    const val AUTO_RECEIVE = "ATAR"

    /** Automatic flow control (the adapter default). */
    val AUTO_FLOW_CONTROL = listOf("ATFCSM0")

    fun routingFor(target: EcuTarget, format: HeaderFormat, options: SessionOptions = SessionOptions.DEFAULT): Routing {
        require(format != HeaderFormat.LEGACY) { "Header targeting is only supported on CAN" }
        return when (target) {
            EcuTarget.Broadcast -> Routing(
                header = headerCommands(if (format == HeaderFormat.CAN_11) FUNCTIONAL_11 else FUNCTIONAL_29, format),
                filter = if (options.broadcastFilter != null && options.broadcastMask != null) {
                    listOf("ATCF ${options.broadcastFilter.uppercase()}", "ATCM ${options.broadcastMask.uppercase()}")
                } else {
                    listOf(AUTO_RECEIVE)
                },
                flowControl = AUTO_FLOW_CONTROL,
            )

            is EcuTarget.Physical -> {
                val request = target.requestHeader.uppercase()
                val response = target.responseHeader?.uppercase()
                val mask = target.responseMask?.uppercase()
                checkLength(request, format)
                response?.let { checkLength(it, format) }
                mask?.let { checkLength(it, format) }
                val customFlowControl = target.flowControl && response != null && mask == null &&
                    (options.customFlowControl || !isStandardPair(request, response, format))
                Routing(
                    header = headerCommands(request, format),
                    filter = when {
                        response != null && mask != null -> listOf("ATCF $response", "ATCM $mask")
                        response != null -> listOf("ATCRA $response")
                        else -> listOf(AUTO_RECEIVE)
                    },
                    flowControl = if (customFlowControl) {
                        listOf("ATFCSH $request", "ATFCSD ${options.flowControlData.uppercase()}", "ATFCSM1")
                    } else {
                        AUTO_FLOW_CONTROL
                    },
                )
            }
        }
    }

    /**
     * Header and receive-filter commands for [target], plus flow-control commands when they differ from
     * the adapter default, in the order they are sent.
     */
    fun forTarget(target: EcuTarget, format: HeaderFormat, options: SessionOptions = SessionOptions.DEFAULT): List<String> {
        val routing = routingFor(target, format, options)
        return routing.header + routing.filter + routing.flowControl.takeIf { it != AUTO_FLOW_CONTROL }.orEmpty()
    }

    /** True when the adapter's automatic flow control already answers on [request]. */
    fun isStandardPair(request: String, response: String, format: HeaderFormat): Boolean = when (format) {
        HeaderFormat.CAN_11 -> response.toInt(16) - 8 == request.toInt(16)
        HeaderFormat.CAN_29 -> request.length == 8 && response.length == 8 &&
            request.substring(0, 4) == response.substring(0, 4) &&
            request.substring(4, 6) == response.substring(6, 8) && request.substring(6, 8) == response.substring(4, 6)
        HeaderFormat.LEGACY -> true
    }
    private fun headerCommands(header: String, format: HeaderFormat): List<String> =
        if (format == HeaderFormat.CAN_11) {
            listOf("ATSH $header")
        } else {
            // 29-bit: priority byte via ATCP, remaining three bytes via ATSH. Works on all ELM327 versions.
            listOf("ATCP ${header.substring(0, 2)}", "ATSH ${header.substring(2)}")
        }

    private fun checkLength(header: String, format: HeaderFormat) {
        require(header.length == format.hexLength) {
            "Header $header does not match ${format.name} (${format.hexLength} hex digits)"
        }
    }
}
