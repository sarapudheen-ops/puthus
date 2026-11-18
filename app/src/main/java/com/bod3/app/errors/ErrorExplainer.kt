package com.bod3.app.errors

import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.session.ObdRequestException
import java.util.Locale

/** A problem in plain words: what happened, the most likely cause, and what to try. */
data class Explanation(val title: String, val cause: String, val fix: String) {
    val text: String get() = "$title\nLikely cause: $cause\nWhat to do: $fix"
}

/**
 * Turns technical error messages (Bluetooth, adapter replies, UDS negative responses, file problems) into
 * an explanation of the likely cause. Returns null for messages it does not recognise, so the original
 * message is still shown on its own.
 */
object ErrorExplainer {

    fun explain(error: Throwable): Explanation? {
        if (error is ObdRequestException) return explain(error.error)
        val chain = generateSequence(error) { it.cause }.take(5).mapNotNull { it.message }.joinToString(" | ")
        return explain(chain.ifEmpty { error.javaClass.simpleName })
    }

    fun explain(error: ElmError): Explanation = when (error) {
        ElmError.NO_DATA -> NO_DATA
        ElmError.UNKNOWN_COMMAND -> UNKNOWN_COMMAND
        ElmError.STOPPED -> STOPPED
        ElmError.UNABLE_TO_CONNECT -> UNABLE_TO_CONNECT
        ElmError.BUS_INIT_ERROR -> BUS_INIT
        ElmError.CAN_ERROR -> CAN_ERROR
        ElmError.BUS_ERROR, ElmError.FEEDBACK_ERROR, ElmError.RX_ERROR, ElmError.DATA_ERROR -> BUS_NOISE
        ElmError.BUS_BUSY -> BUS_BUSY
        ElmError.BUFFER_FULL -> BUFFER_FULL
        ElmError.LOW_VOLTAGE_RESET -> LOW_VOLTAGE
        ElmError.ACTIVITY_ALERT -> ACTIVITY_ALERT
        ElmError.INTERNAL_ERROR -> INTERNAL
    }

    fun explain(message: String?): Explanation? {
        if (message.isNullOrBlank()) return null
        val m = message.lowercase(Locale.ROOT)
        nrc(m)?.let { return it }
        return when {
            "permission" in m -> PERMISSION
            "bluetooth is off" in m || "bluetooth disabled" in m || "not enabled" in m -> BLUETOOTH_OFF
            "invalid bluetooth address" in m -> Explanation(
                "The saved adapter address is not valid",
                "The paired device list changed or the address was entered wrongly.",
                "Pick the adapter again from the list of paired devices.",
            )
            "service discovery failed" in m || "could not connect" in m || "connect failed" in m || "connection refused" in m ||
                "host is down" in m -> CANNOT_REACH
            "reconnect attempts failed" in m || "connection lost" in m || "link lost" in m || "socket might closed" in m ||
                "broken pipe" in m || "connection reset" in m || "closed the connection" in m || "connection to adapter closed" in m ||
                "read failed" in m || "write failed" in m -> LINK_LOST
            "not connected" in m -> Explanation(
                "Not connected to the adapter",
                "The Bluetooth connection was closed before the request was sent.",
                "Connect to the adapter again from the device list.",
            )
            "unable to connect" in m || "no protocol answered" in m || "vehicle not detected" in m -> UNABLE_TO_CONNECT
            "bus init" in m || "bus initialisation" in m -> BUS_INIT
            "can error" in m || "can bus error" in m -> CAN_ERROR
            "buffer full" in m -> BUFFER_FULL
            "lv reset" in m || "low voltage" in m -> LOW_VOLTAGE
            "bus busy" in m -> BUS_BUSY
            "fb error" in m || "rx error" in m || "data error" in m || "bus error" in m || "feedback error" in m || "receive error" in m -> BUS_NOISE
            "act alert" in m || "activity alert" in m -> ACTIVITY_ALERT
            "internal error" in m || Regex("err\\d{2}").containsMatchIn(m) -> INTERNAL
            "stopped" in m || "interrupted" in m -> STOPPED
            "did not understand" in m || m.trim() == "?" -> UNKNOWN_COMMAND
            "no data" in m || "no ecu answered" in m || "no response from the module" in m || "not reported by vehicle" in m -> NO_DATA
            "timed out" in m || "timeout" in m || "no reply" in m -> TIMEOUT
            "not valid json" in m || ("unexpected" in m && " at " in m) -> Explanation(
                "The file is not valid JSON",
                "It was edited by hand and has a missing comma, quote or bracket, or it was cut off while copying.",
                "Open it in a JSON validator, fix the position shown, and import it again.",
            )
            "is newer than this app supports" in m -> Explanation(
                "The file was made by a newer version of bod3",
                "Its format version is higher than this app understands.",
                "Update the app, or ask for a file in the older format.",
            )
            "database" in m || "sqlite" in m -> Explanation(
                "Saved data could not be read or written",
                "Phone storage is full or the database is busy.",
                "Free some storage space and try again.",
            )
            else -> null
        }
    }

    /** Message plus explanation, for logs and reports. */
    fun describe(message: String?): String = explain(message)?.let { "${message.orEmpty()}\n${it.text}" } ?: message.orEmpty()

    private fun nrc(m: String): Explanation? {
        val code = Regex("nrc 0x([0-9a-f]{2})").find(m)?.groupValues?.get(1)?.toInt(16)
            ?: Regex("7f [0-9a-f]{2} ([0-9a-f]{2})").find(m)?.groupValues?.get(1)?.toInt(16)
            ?: return null
        return when (code) {
            0x11, 0x7F -> Explanation(
                "The module does not offer this service" + if (code == 0x7F) " in the current session" else "",
                if (code == 0x7F) "The service needs a different diagnostic session (usually extended, 10 03)." else "This module or software version does not implement it.",
                if (code == 0x7F) "Check the recipe's session; some functions need the extended session." else "Use a recipe made for this module, or skip this function.",
            )
            0x12, 0x7E -> Explanation("The module does not support this sub-function", "The recipe uses an option this module does not implement.", "Check the recipe against the module's documentation.")
            0x13 -> Explanation("The module rejected the message length", "The request has too few or too many bytes for this identifier.", "Check the identifier's length in the recipe (read it first to see its real size).")
            0x21 -> Explanation("The module is busy", "It is handling another request.", "Wait a moment and try again.")
            0x22 -> Explanation(
                "Conditions not correct",
                "The module refuses in the current vehicle state: engine running, vehicle moving, ignition position, low voltage, or another function active.",
                "Switch the engine off with the ignition on, connect a charger, close doors, and try again.",
            )
            0x24 -> Explanation("Request sequence error", "Steps were sent in the wrong order (e.g. a key without a seed, or a stop without a start).", "Start the operation again from the beginning.")
            0x31 -> Explanation("Request out of range", "The module does not know this identifier or the value is outside its allowed range.", "Check the identifier and value; this recipe may not suit this module or software version.")
            0x33 -> Explanation("Security access denied", "The function needs a security login first, or the login was lost (session changed).", "Use a recipe with the correct security access for this module.")
            0x35 -> Explanation("Invalid security key", "The key algorithm or constant in the recipe does not match this module.", "Do not retry repeatedly – modules lock after several wrong keys. Check the recipe's security settings.")
            0x36, 0x37 -> Explanation(
                "Security access is temporarily locked",
                "Too many wrong keys were sent; the module enforces a waiting time.",
                "Leave the ignition on for about 10 minutes (or switch it off and on), then try once with the correct key.",
            )
            0x78 -> Explanation("The module is still working", "It answered 'response pending' and did not finish in time.", "Wait and read the value again before repeating anything.")
            else -> null
        }
    }

    private val NO_DATA = Explanation(
        "No answer from the vehicle",
        "This vehicle or ECU does not support the request, the ignition is off, or the request went to the wrong ECU address.",
        "Switch the ignition on (engine may stay off). If it persists, the value is simply not available on this vehicle.",
    )
    private val UNKNOWN_COMMAND = Explanation(
        "The adapter did not understand a command",
        "The adapter firmware does not support this command (common with clones), or the command was mistyped.",
        "It is usually harmless. If connecting fails, choose a simpler connection profile in Settings.",
    )
    private val STOPPED = Explanation(
        "The request was interrupted",
        "Another command was sent while the adapter was still waiting for an answer.",
        "Try again; avoid starting several reads at once.",
    )
    private val UNABLE_TO_CONNECT = Explanation(
        "The adapter could not talk to the vehicle",
        "The ignition is off, the vehicle uses a protocol that was not tried, or it is not OBD2 compliant (petrol before 2001 / diesel before 2004 in Europe, before 1996 in the US).",
        "Switch the ignition on (engine may stay off) and connect again. If it still fails, pick the protocol manually in Settings → Connection profile.",
    )
    private val BUS_INIT = Explanation(
        "K-line initialisation failed",
        "The ignition is off, the ECU needs slow (5-baud) initialisation, or the K-line pin is not used on this vehicle.",
        "Switch the ignition on, wait 5 seconds and retry. Try ISO 9141-2 / KWP2000 5-baud in Settings.",
    )
    private val CAN_ERROR = Explanation(
        "The adapter cannot read the CAN bus",
        "Wrong CAN speed or ID length, the ignition is off, or the vehicle has no CAN on the OBD port (older K-line cars).",
        "Switch the ignition on and let automatic protocol detection run, or choose the right CAN protocol in Settings.",
    )
    private val BUS_NOISE = Explanation(
        "Transmission error on the vehicle bus",
        "A loose adapter, a damaged OBD socket, or electrical noise (e.g. engine cranking).",
        "Re-seat the adapter firmly, avoid cranking during reads, and retry.",
    )
    private val BUS_BUSY = Explanation(
        "The vehicle bus is too busy",
        "Heavy traffic or a module flooding the bus.",
        "Wait a moment and retry; reduce the number of live values.",
    )
    private val BUFFER_FULL = Explanation(
        "The adapter's buffer overflowed",
        "Too much data arrived at once, usually in monitoring or with many ECUs answering a broadcast.",
        "Retry; reading one ECU at a time (selected ECU) avoids it.",
    )
    private val LOW_VOLTAGE = Explanation(
        "The adapter reset because the voltage dropped",
        "The battery is weak, or the engine was being cranked.",
        "Charge the battery or connect a charger, then reconnect. Never code or test with a weak battery.",
    )
    private val ACTIVITY_ALERT = Explanation(
        "The adapter went to sleep",
        "There was no bus activity for a while (ignition off) and it entered low-power mode.",
        "Switch the ignition on and reconnect.",
    )
    private val INTERNAL = Explanation(
        "The adapter reported an internal error",
        "A firmware problem or a corrupted command.",
        "Unplug the adapter for 10 seconds, plug it back in and reconnect.",
    )
    private val TIMEOUT = Explanation(
        "The adapter did not answer in time",
        "The vehicle is slow to answer, the Bluetooth link is weak, or the adapter is busy.",
        "Keep the phone close to the adapter and retry. Slow K-line vehicles may need a longer timeout in the connection profile.",
    )
    private val PERMISSION = Explanation(
        "Bluetooth permission is missing",
        "Android needs the 'Nearby devices' permission to connect to the adapter.",
        "Allow 'Nearby devices' for bod3 in Android Settings → Apps → bod3 → Permissions.",
    )
    private val BLUETOOTH_OFF = Explanation("Bluetooth is off", "The phone's Bluetooth is switched off.", "Switch Bluetooth on and try again.")
    private val CANNOT_REACH = Explanation(
        "The adapter could not be reached",
        "It is not powered (not plugged into the OBD port), it is connected to another phone or app, or the pairing is stale.",
        "Check the adapter's light is on, close other OBD apps, keep the phone close, and retry. If it keeps failing, unpair and pair it again (PIN 1234 or 0000).",
    )
    private val LINK_LOST = Explanation(
        "The Bluetooth connection dropped",
        "The adapter lost power (unplugged, or the car switched the OBD port off), the phone moved out of range, or Android stopped the connection to save battery.",
        "Check the adapter is firmly plugged in and lit, keep the phone near the car, and connect again.",
    )
}
