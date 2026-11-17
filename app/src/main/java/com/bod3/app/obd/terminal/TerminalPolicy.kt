package com.bod3.app.obd.terminal

import com.bod3.app.obd.session.ReadOnlyPolicy

/** What the raw terminal will do with one line of input. */
sealed interface TerminalDecision {
    /** Send as typed (normalised). [affectsRouting]: the session must re-send its routing afterwards. */
    data class Send(val command: String, val kind: Kind, val affectsRouting: Boolean) : TerminalDecision

    data class Refuse(val reason: String) : TerminalDecision

    enum class Kind { AT, VENDOR, DIAGNOSTIC }
}

/**
 * Policy for the expert raw terminal. It is separate from the diagnostic [ReadOnlyPolicy] only for
 * adapter commands; diagnostic requests typed in the terminal still go through [ReadOnlyPolicy].
 *
 * - Expert mode must be on.
 * - **AT commands** are allowed except those that would break the link or the app's parser, or write
 *   to the adapter's memory: baud-rate changes (`ATBRD`, `ATBRT`), programmable parameters (`ATPP`),
 *   stored data (`ATSD`), low power (`ATLP`), monitor modes that never return a prompt (`ATMA`, `ATMR`,
 *   `ATMT`) and wake-up message changes (`ATWM`).
 * - **ST/VT commands** are allowed only from a short list of identification queries, and the driver
 *   additionally refuses them unless capability detection found the extension.
 * - **Diagnostic requests** (hex bytes) must pass [ReadOnlyPolicy]: reads only. Writes, clears and
 *   actuator tests are not possible here.
 */
object TerminalPolicy {

    private val blockedAt = listOf("ATBRD", "ATBRT", "ATPP", "ATSD", "ATLP", "ATMA", "ATMR", "ATMT", "ATWM")

    /** Commands after which the app can no longer trust the adapter's routing/protocol state. */
    private val routingAt = listOf(
        "ATSH", "ATCP", "ATCRA", "ATCF", "ATCM", "ATAR", "ATFC", "ATSP", "ATTP", "ATZ", "ATD", "ATWS", "ATCEA", "ATCAF", "ATH",
    )

    val allowedVendor: Set<String> = setOf("STI", "STDI", "STSN", "STMFR", "STPRS", "STVR", "VTVERS")

    fun decide(input: String, expertMode: Boolean): TerminalDecision {
        if (!expertMode) return TerminalDecision.Refuse("Turn on Expert mode in Settings to use the raw terminal.")
        val command = input.filterNot { it.isWhitespace() }.uppercase()
        if (command.isEmpty()) return TerminalDecision.Refuse("Empty command")

        return when {
            command.startsWith("AT") -> {
                blockedAt.firstOrNull { command.startsWith(it) }?.let {
                    return TerminalDecision.Refuse("$it is blocked: it could break the connection or change the adapter's stored settings.")
                }
                val displayOnly = command.startsWith("ATDP")
                TerminalDecision.Send(command, TerminalDecision.Kind.AT, affectsRouting = !displayOnly && routingAt.any { command.startsWith(it) })
            }

            command.startsWith("ST") || command.startsWith("VT") ->
                if (command in allowedVendor) {
                    TerminalDecision.Send(command, TerminalDecision.Kind.VENDOR, affectsRouting = false)
                } else {
                    TerminalDecision.Refuse("Only these vendor commands are allowed: ${allowedVendor.sorted().joinToString(", ")}.")
                }

            else -> {
                if (command.length % 2 != 0 || !command.all { it in '0'..'9' || it in 'A'..'F' }) {
                    return TerminalDecision.Refuse("Diagnostic requests must be hex bytes, e.g. 0100 or 22F190.")
                }
                val bytes = command.chunked(2).map { it.toInt(16) }
                if (!ReadOnlyPolicy.isAllowed(bytes)) {
                    return TerminalDecision.Refuse(
                        "Blocked by the read-only policy: %02X is not a read service. ".format(bytes[0]) +
                            "Writes, clears and actuator tests are only possible through their dedicated, guarded screens.",
                    )
                }
                TerminalDecision.Send(command, TerminalDecision.Kind.DIAGNOSTIC, affectsRouting = false)
            }
        }
    }

    /** Commands that put the adapter back into the state the app's parser expects. */
    val restoreCommands: List<String> = listOf("ATE0", "ATS0", "ATH1", "ATCAF1", "ATR1")
}
