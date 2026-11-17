package com.bod3.app.obd.dtc

import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session

data class MonitorStatus(val name: String, val supported: Boolean, val complete: Boolean) {
    val display: String get() = when {
        !supported -> "Not supported"
        complete -> "Complete"
        else -> "Incomplete"
    }
}

/** OBD readiness (PID 01 01) of one ECU. */
data class Readiness(
    val ecu: String,
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>,
) {
    val supported: List<MonitorStatus> get() = monitors.filter { it.supported }
    val incomplete: List<MonitorStatus> get() = supported.filterNot { it.complete }
    val ready: Boolean get() = incomplete.isEmpty()
}

/** Decodes PID 01 01 (monitor status since DTCs cleared) per SAE J1979. */
object ReadinessDecoder {
    private val sparkMonitors = listOf(
        "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
    )
    private val compressionMonitors = listOf(
        "NMHC catalyst", "NOx/SCR aftertreatment", null, "Boost pressure",
        null, "Exhaust gas sensor", "PM filter", "EGR/VVT system",
    )

    /** [data] = the four bytes A B C D after `41 01`. */
    fun decode(ecu: String, data: List<Int>): Readiness? {
        if (data.size < 4) return null
        val (a, b, c, d) = data
        val compression = b and 0x08 != 0
        val continuous = listOf("Misfire", "Fuel system", "Comprehensive components").mapIndexed { bit, name ->
            MonitorStatus(name, supported = b and (1 shl bit) != 0, complete = b and (1 shl (bit + 4)) == 0)
        }
        val names = if (compression) compressionMonitors else sparkMonitors
        val nonContinuous = names.mapIndexedNotNull { bit, name ->
            name?.let { MonitorStatus(it, supported = c and (1 shl bit) != 0, complete = d and (1 shl bit) == 0) }
        }
        return Readiness(ecu, milOn = a and 0x80 != 0, dtcCount = a and 0x7F, compressionIgnition = compression, monitors = continuous + nonContinuous)
    }

    suspend fun read(session: Session): List<Readiness> =
        session.request(EcuTarget.Broadcast, listOf(0x01, 0x01)).messages.mapNotNull { decode(it.header, it.payload.drop(2)) }
}
