package com.bod3.app.obd.elm

/** Error conditions an ELM327-compatible adapter reports in place of data. */
enum class ElmError(val description: String) {
    NO_DATA("No data: no ECU answered the request"),
    UNKNOWN_COMMAND("The adapter did not understand the command"),
    STOPPED("The request was interrupted"),
    UNABLE_TO_CONNECT("Unable to connect to the vehicle bus. Is the ignition on?"),
    BUS_INIT_ERROR("Bus initialisation failed"),
    CAN_ERROR("CAN bus error"),
    BUS_ERROR("Bus error"),
    BUS_BUSY("Bus busy"),
    FEEDBACK_ERROR("Feedback error"),
    RX_ERROR("Receive error"),
    DATA_ERROR("Data error"),
    BUFFER_FULL("Adapter buffer full"),
    LOW_VOLTAGE_RESET("Adapter reset due to low voltage"),
    ACTIVITY_ALERT("Adapter activity alert"),
    INTERNAL_ERROR("Adapter internal error"),
}

/**
 * One response from the adapter: everything it printed between sending [command] and the `>` prompt,
 * split into meaningful [lines] (echo, blank lines and status noise such as `SEARCHING...` removed).
 */
data class ElmResponse(
    val command: String,
    val raw: String,
    val lines: List<String>,
    val error: ElmError?,
) {
    val isSuccess: Boolean get() = error == null

    /** True for configuration commands that were acknowledged with `OK`. */
    val isOk: Boolean get() = error == null && lines.any { it.equals("OK", ignoreCase = true) }

    val text: String get() = lines.joinToString("\n")
}

object ElmResponseParser {

    private val internalErrorPattern = Regex("^ERR\\d+$")

    fun parse(command: String, raw: String): ElmResponse {
        val normalizedCommand = compact(command)
        val lines = raw.split('\r', '\n')
            .map { line -> line.filter { it in ' '..'~' }.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { normalizedCommand.isNotEmpty() && compact(it) == normalizedCommand }
            .filterNot(::isStatusNoise)
        val error = lines.firstNotNullOfOrNull(::classifyError)
        return ElmResponse(command = command, raw = raw, lines = lines, error = error)
    }

    /** Informational lines printed while the adapter works, which carry no response data. */
    fun isStatusNoise(line: String): Boolean {
        val upper = line.uppercase()
        return upper.startsWith("SEARCHING") ||
            (upper.startsWith("BUS INIT") && upper.endsWith("OK"))
    }

    fun classifyError(line: String): ElmError? {
        val upper = line.trim().uppercase()
        return when {
            upper == "?" -> ElmError.UNKNOWN_COMMAND
            upper == "NO DATA" || upper == "NODATA" -> ElmError.NO_DATA
            upper == "STOPPED" -> ElmError.STOPPED
            upper.startsWith("UNABLE TO CONNECT") -> ElmError.UNABLE_TO_CONNECT
            upper.startsWith("BUS INIT") && upper.contains("ERROR") -> ElmError.BUS_INIT_ERROR
            upper.contains("CAN ERROR") -> ElmError.CAN_ERROR
            upper.contains("BUS ERROR") -> ElmError.BUS_ERROR
            upper.contains("BUS BUSY") -> ElmError.BUS_BUSY
            upper.contains("FB ERROR") -> ElmError.FEEDBACK_ERROR
            upper.contains("RX ERROR") -> ElmError.RX_ERROR
            upper.contains("DATA ERROR") -> ElmError.DATA_ERROR
            upper.contains("BUFFER FULL") -> ElmError.BUFFER_FULL
            upper.startsWith("LV RESET") -> ElmError.LOW_VOLTAGE_RESET
            upper.startsWith("ACT ALERT") -> ElmError.ACTIVITY_ALERT
            internalErrorPattern.matches(upper) -> ElmError.INTERNAL_ERROR
            else -> null
        }
    }

    private fun compact(text: String): String = text.filterNot { it.isWhitespace() }.uppercase()
}
