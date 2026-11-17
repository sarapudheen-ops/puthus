package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterCapabilities
import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor

data class SetupStep(val command: String, val accepted: Boolean, val reply: String)

/**
 * Applies the adapter-level settings of a [ConnectionProfile] after initialisation. Every setting is
 * optional: a rejected command is logged and skipped, never fatal. VT commands are only used when
 * capability detection found VT support.
 */
object AdapterSetup {

    fun commands(profile: ConnectionProfile, capabilities: AdapterCapabilities): List<String> = buildList {
        profile.adaptiveTiming?.let { add("ATAT$it") }
        profile.adapterTimeoutUnits?.let { units ->
            if (profile.useVtTimeout && capabilities.supportsVt) {
                add("VTTOST %02X".format(units))
            } else {
                add("ATST %02X".format(units))
            }
        }
        add(profile.extendedAddress?.let { "ATCEA $it" } ?: "ATCEA")
    }

    suspend fun apply(
        executor: CommandExecutor,
        profile: ConnectionProfile,
        capabilities: AdapterCapabilities,
        logger: (String) -> Unit = {},
    ): List<SetupStep> = commands(profile, capabilities).map { command ->
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
