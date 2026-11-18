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
 * - **ST/VT commands** (the full STN and vLinker VT sets) are sent as typed, except the few that would
 *   break the link or write adapter memory: UART baud changes (`STBR`, `STSBR`, `STUBR`, `VTBR`),
 *   power-save/sleep (`STSLP`, `STSLX`, `STLP`), monitor modes that never return a prompt (`STM`,
 *   `STMA`), firmware update/reset to bootloader (`STBL`, `STFAC`, `VTFACT`). An adapter that does not
 *   know a command answers `?`, which is shown and otherwise ignored.
 * - **Diagnostic requests** (hex bytes) must pass [ReadOnlyPolicy]: reads only. Writes, clears and
 *   actuator tests are not possible here.
 */
object TerminalPolicy {

    private val blockedAt = listOf("ATBRD", "ATBRT", "ATPP", "ATSD", "ATLP", "ATMA", "ATMR", "ATMT", "ATWM")

    /** Commands after which the app can no longer trust the adapter's routing/protocol state. */
    private val routingAt = listOf(
        "ATSH", "ATCP", "ATCRA", "ATCF", "ATCM", "ATAR", "ATFC", "ATSP", "ATTP", "ATZ", "ATD", "ATWS", "ATCEA", "ATCAF", "ATH",
    )

    /** ST/VT command prefixes that would break the link, never return a prompt, or reflash/reset the adapter. */
    val blockedVendor: List<String> = listOf(
        "STBR", "STSBR", "STUBR", "STWBR", "VTBR", "VTSET_BAUD", "STSLP", "STSLX", "STLP", "STMA", "STBL", "STFAC", "VTFACT", "VTBOOT",
    )

    /** `STM` alone (monitor) never returns a prompt; `STMFR` etc. are fine. */
    private fun isBlockedVendor(command: String): String? =
        blockedVendor.firstOrNull { command.startsWith(it) } ?: "STM".takeIf { command == "STM" || command.startsWith("STM ") }

    /** Vendor commands that change protocol/CAN state the session relies on. */
    private val routingVendor = listOf("STP", "STCAF", "STCFCP", "STCSEG", "STFA", "STFB", "STFP", "STFFC", "STFC", "VTP", "VTISO", "VTIMSP", "VTIIAP")

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

            command.startsWith("ST") || command.startsWith("VT") -> {
                isBlockedVendor(command)?.let {
                    return TerminalDecision.Refuse("$it is blocked: it could break the connection, never return, or reset the adapter.")
                }
                val routing = routingVendor.any { command.startsWith(it) } && !command.startsWith("STPRS")
                TerminalDecision.Send(command, TerminalDecision.Kind.VENDOR, affectsRouting = routing)
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
