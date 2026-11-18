package com.bod3.app.catalog

import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.ConnectionProfileCodec
import com.bod3.app.obd.connection.FlowControlMode
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Sparse adapter patch. Missing = inherit; explicit null = clear a nullable option. */
data class CatalogConnection(val values: Map<String, Any?> = emptyMap()) {
    fun apply(base: ConnectionProfile): ConnectionProfile {
        require(problems().isEmpty()) { problems().joinToString("\n") }
        // MiniJson represents all JSON numbers as Double, as expected by the existing profile codec.
        return ConnectionProfileCodec.decode(MiniJson.parse(JsonWriter.write(ConnectionProfileCodec.encode(base) + values)) as Map<*, *>)
    }

    /** Validate local fields, not cross-field pairs: a child may supply the mask for an inherited filter. */
    fun problems(): List<String> = buildList {
        val booleans = setOf("tryFallbacks", "useVtTimeout")
        val numbers = mapOf("requestTimeoutMs" to 500.0..60_000.0, "searchTimeoutMs" to 1_000.0..60_000.0,
            "adapterTimeoutMs" to 4.0..1_044.0, "adaptiveTiming" to 0.0..2.0, "canBaudKbps" to 10.0..1_000.0)
        val texts = setOf("forcedProtocol", "broadcastFilter", "broadcastMask", "flowControlMode", "flowControlData", "extendedAddress")
        val nullable = setOf("forcedProtocol", "adapterTimeoutMs", "adaptiveTiming", "canBaudKbps", "broadcastFilter", "broadcastMask", "extendedAddress")
        values.forEach { (key, value) ->
            when {
                key !in booleans + numbers.keys + texts + "extraInitCommands" -> add("$key: unknown connection setting")
                value == null && key !in nullable -> add("$key: cannot be null")
                value == null -> Unit
                key in booleans && value !is Boolean -> add("$key: must be true or false")
                key in numbers && (value !is Number || !value.toDouble().isFinite() || value.toDouble() !in numbers.getValue(key) || (key != "canBaudKbps" && value.toDouble() % 1.0 != 0.0)) -> add("$key: must be ${numbers.getValue(key)}${if (key != "canBaudKbps") " (whole number)" else ""}")
                key in texts && value !is String -> add("$key: must be text")
                key == "extraInitCommands" && (value !is List<*> || value.any { it !is String }) -> add("$key: must be a list of adapter configuration commands")
            }
        }
        (values["forcedProtocol"] as? String)?.let { if (ObdProtocol.entries.none { p -> p.name == it }) add("forcedProtocol: unknown protocol $it") }
        (values["flowControlMode"] as? String)?.let { if (FlowControlMode.entries.none { p -> p.name == it }) add("flowControlMode: unknown mode $it") }
        for (key in listOf("broadcastFilter", "broadcastMask", "flowControlData", "extendedAddress")) {
            (values[key] as? String)?.let { text ->
                val validLength = when (key) { "extendedAddress" -> text.length == 2; "flowControlData" -> text.length in 2..10 && text.length % 2 == 0; else -> text.length in 1..8 }
                if (!validLength || !Regex("[0-9a-fA-F]+").matches(text)) add("$key: invalid hex value")
            }
        }
        (values["extraInitCommands"] as? List<*>)?.filterIsInstance<String>()?.forEachIndexed { i, cmd ->
            if (!isConfigurationCommand(cmd)) add("extraInitCommands[$i]: only adapter configuration is allowed; payload/write/security/monitor commands cannot be setup")
        }
    }

    companion object {
        /** No raw-payload macros (STPX), memory writes, bootloaders or session/security requests. */
        fun isConfigurationCommand(input: String): Boolean {
            if (input.isBlank() || input.length > 40 || !input.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " ,." }) return false
            val decision = com.bod3.app.obd.terminal.TerminalPolicy.decide(input, expertMode = true)
            if (decision !is com.bod3.app.obd.terminal.TerminalDecision.Send || decision.kind == com.bod3.app.obd.terminal.TerminalDecision.Kind.DIAGNOSTIC) return false
            val c = input.filterNot(Char::isWhitespace).uppercase()
            if (Regex("(?:STP|STPBR|VTP)[0-9A-F]+").matches(c)) return true
            return listOf("ATE", "ATL", "ATS", "ATH", "ATAT", "ATST", "ATSP", "ATTP", "ATSH", "ATCP", "ATCRA", "ATCF", "ATCM", "ATCEA", "ATCAF", "ATCFC", "ATFCSH", "ATFCSD", "ATFCSM", "ATPB", "ATSW", "ATIB", "ATIIA", "ATFI", "ATAL", "ATBI", "ATDP", "STCAF", "STCSEGR", "STCSEGT", "STPTRQ", "STCFCPA", "STCTR", "VTTOST", "VTIMSP", "VTIIAP", "VTISOSI", "VTVLCW")
                .any { prefix -> c.startsWith(prefix) && c.drop(prefix.length).all { it in '0'..'9' || it in 'A'..'F' || it in ",." } }
        }
    }
}
