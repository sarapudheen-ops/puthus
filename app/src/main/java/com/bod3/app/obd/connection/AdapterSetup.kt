package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor

data class SetupStep(val command: String, val accepted: Boolean, val reply: String)

/**
 * Applies the adapter-level settings of a [ConnectionProfile] after initialisation. Every setting is
 * optional: a rejected command (including a `?` for an unknown ST/VT command) is logged and skipped,
 * never fatal. ST/VT commands are sent directly; there is no capability gate.
 */
object AdapterSetup {

    fun commands(profile: ConnectionProfile): List<String> = buildList {
        profile.adaptiveTiming?.let { add("ATAT$it") }
        profile.adapterTimeoutUnits?.let { units ->
            if (profile.useVtTimeout) {
                add("VTTOST %02X".format(units))
            } else {
                add("ATST %02X".format(units))
            }
        }
        add(profile.extendedAddress?.let { "ATCEA $it" } ?: "ATCEA")
        addAll(profile.extraInitCommands)
    }

    suspend fun apply(
        executor: CommandExecutor,
        profile: ConnectionProfile,
        logger: (String) -> Unit = {},
    ): List<SetupStep> = commands(profile).map { command ->
        val step = try {
            val response = executor.execute(command, 2_000L)
            SetupStep(command, response.isOk, response.error?.description ?: response.text)
        } catch (e: AdapterException) {
            SetupStep(command, false, e.message ?: "error")
        }
        // Turning extended addressing off is sent unconditionally and may be unknown to old clones: not worth a warning.
        if (!step.accepted && !(command == "ATCEA" && profile.extendedAddress == null)) {
            logger("Warning: adapter rejected $command (${step.reply}); continuing without it")
        }
        step
    }
}
