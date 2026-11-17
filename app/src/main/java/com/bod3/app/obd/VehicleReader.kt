package com.bod3.app.obd

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.VoltageParser
import com.bod3.app.obd.pid.PidDefinition
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.pid.VinDecoder
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session

data class PidReading(val definition: PidDefinition, val ecu: String, val value: Double) {
    val formatted: String get() = definition.format(value)
}

/** Read-only vehicle queries built on top of [Session]. */
class VehicleReader(
    private val session: Session,
    private val executor: CommandExecutor,
) {

    /** Supported Service 01 PIDs per answering ECU header, following the `01 00` / `01 20` / ... chain. */
    suspend fun supportedPids(target: EcuTarget = EcuTarget.Broadcast): Map<String, Set<Int>> {
        val result = linkedMapOf<String, MutableSet<Int>>()
        var base = 0x00
        while (base <= 0xE0) {
            val response = session.request(target, 0x01, base)
            for (message in response.messages) {
                if (message.payload.size < 6) continue
                result.getOrPut(message.header) { sortedSetOf() } +=
                    Pids.decodeSupported(base, message.payload.subList(2, 6))
            }
            val next = base + 0x20
            if (result.values.none { next in it }) break
            base = next
        }
        return result
    }

    suspend fun readVin(target: EcuTarget = EcuTarget.Broadcast): String? {
        val response = session.request(target, listOf(0x09, 0x02), timeoutMs = VIN_TIMEOUT_MS)
        return VinDecoder.decode(response.messages)
    }

    /** Battery voltage measured by the adapter at the OBD socket (`ATRV`). */
    suspend fun readBatteryVoltage(): Double? {
        val response = executor.execute("ATRV", 2_000L)
        return if (response.isSuccess) VoltageParser.parse(response.text) else null
    }

    /**
     * Reads one PID. When several ECUs answer, [preferredEcu] is used if present, otherwise the first.
     * Returns null when no ECU returned the PID.
     */
    suspend fun readPid(
        definition: PidDefinition,
        target: EcuTarget = EcuTarget.Broadcast,
        preferredEcu: String? = null,
    ): PidReading? {
        val response = session.request(target, 0x01, definition.pid)
        val message = response.messages.firstOrNull { it.header == preferredEcu } ?: response.messages.firstOrNull()
            ?: return null
        val value = definition.decode(message.payload.drop(2)) ?: return null
        return PidReading(definition, message.header, value)
    }

    companion object {
        const val VIN_TIMEOUT_MS = 8_000L
    }
}
