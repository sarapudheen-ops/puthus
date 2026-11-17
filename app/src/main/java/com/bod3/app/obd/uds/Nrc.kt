package com.bod3.app.obd.uds

/** UDS negative response codes (ISO 14229-1), for display. */
object Nrc {
    const val SERVICE_NOT_SUPPORTED = 0x11
    const val SUB_FUNCTION_NOT_SUPPORTED = 0x12
    const val BUSY_REPEAT_REQUEST = 0x21
    const val REQUEST_OUT_OF_RANGE = 0x31
    const val RESPONSE_PENDING = 0x78

    private val names = mapOf(
        0x10 to "general reject",
        0x11 to "service not supported",
        0x12 to "sub-function not supported",
        0x13 to "incorrect message length or format",
        0x14 to "response too long",
        0x21 to "busy, repeat request",
        0x22 to "conditions not correct",
        0x24 to "request sequence error",
        0x25 to "no response from sub-net component",
        0x26 to "failure prevents execution",
        0x31 to "request out of range",
        0x33 to "security access denied",
        0x35 to "invalid key",
        0x36 to "exceeded number of attempts",
        0x37 to "required time delay not expired",
        0x70 to "upload/download not accepted",
        0x72 to "general programming failure",
        0x78 to "response pending",
        0x7E to "sub-function not supported in active session",
        0x7F to "service not supported in active session",
    )

    fun name(code: Int): String = names[code] ?: "unknown"

    fun describe(code: Int): String = "NRC 0x%02X (%s)".format(code, name(code))

    /** Codes that simply mean "this ECU does not offer that", as opposed to a transient failure. */
    fun meansNotSupported(code: Int): Boolean =
        code in setOf(0x11, 0x12, 0x31, 0x7E, 0x7F)
}
