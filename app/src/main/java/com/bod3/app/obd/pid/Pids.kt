package com.bod3.app.obd.pid

import kotlin.math.roundToInt

/** A Service 01 parameter with its SAE J1979 formula. [decode] receives the data bytes after `41 <pid>`. */
class PidDefinition(
    val pid: Int,
    val name: String,
    val unit: String,
    val byteCount: Int,
    val decimals: Int,
    private val formula: (List<Int>) -> Double,
) {
    fun decode(data: List<Int>): Double? = if (data.size < byteCount) null else formula(data)

    fun format(value: Double): String =
        if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

    override fun toString(): String = "PID %02X %s".format(pid, name)
}

object Pids {
    private fun ab(d: List<Int>) = d[0] * 256.0 + d[1]
    private fun percent(d: List<Int>) = d[0] * 100.0 / 255.0
    private fun temperature(d: List<Int>) = d[0] - 40.0

    val ENGINE_LOAD = PidDefinition(0x04, "Engine load", "%", 1, 0, ::percent)
    val COOLANT_TEMP = PidDefinition(0x05, "Coolant temperature", "°C", 1, 0, ::temperature)
    val INTAKE_PRESSURE = PidDefinition(0x0B, "Intake manifold pressure", "kPa", 1, 0) { it[0].toDouble() }
    val ENGINE_RPM = PidDefinition(0x0C, "Engine speed", "rpm", 2, 0) { ab(it) / 4.0 }
    val VEHICLE_SPEED = PidDefinition(0x0D, "Vehicle speed", "km/h", 1, 0) { it[0].toDouble() }
    val TIMING_ADVANCE = PidDefinition(0x0E, "Timing advance", "°", 1, 1) { it[0] / 2.0 - 64.0 }
    val INTAKE_TEMP = PidDefinition(0x0F, "Intake air temperature", "°C", 1, 0, ::temperature)
    val MAF = PidDefinition(0x10, "Mass air flow", "g/s", 2, 2) { ab(it) / 100.0 }
    val THROTTLE = PidDefinition(0x11, "Throttle position", "%", 1, 0, ::percent)
    val RUN_TIME = PidDefinition(0x1F, "Run time since start", "s", 2, 0, ::ab)
    val FUEL_LEVEL = PidDefinition(0x2F, "Fuel level", "%", 1, 0, ::percent)
    val BAROMETRIC_PRESSURE = PidDefinition(0x33, "Barometric pressure", "kPa", 1, 0) { it[0].toDouble() }
    val MODULE_VOLTAGE = PidDefinition(0x42, "Control module voltage", "V", 2, 2) { ab(it) / 1000.0 }
    val AMBIENT_TEMP = PidDefinition(0x46, "Ambient air temperature", "°C", 1, 0, ::temperature)
    val OIL_TEMP = PidDefinition(0x5C, "Engine oil temperature", "°C", 1, 0, ::temperature)
    val FUEL_RATE = PidDefinition(0x5E, "Engine fuel rate", "L/h", 2, 1) { ab(it) / 20.0 }

    /** PIDs shown on the live data screen, in display order. */
    val live: List<PidDefinition> = listOf(
        ENGINE_RPM, VEHICLE_SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, INTAKE_PRESSURE,
        MAF, TIMING_ADVANCE, FUEL_LEVEL, FUEL_RATE, MODULE_VOLTAGE, OIL_TEMP, AMBIENT_TEMP,
        BAROMETRIC_PRESSURE, RUN_TIME,
    )

    fun byPid(pid: Int): PidDefinition? = live.firstOrNull { it.pid == pid }

    /**
     * Decodes a "supported PIDs" bitmap (reply to `01 00`, `01 20`, `01 40`, ...). The most significant
     * bit of the first byte is PID `base + 1`; the least significant bit of the last byte is `base + 32`.
     */
    fun decodeSupported(base: Int, bitmap: List<Int>): Set<Int> {
        val supported = sortedSetOf<Int>()
        bitmap.take(4).forEachIndexed { byteIndex, byte ->
            for (bit in 0 until 8) {
                if (byte and (0x80 shr bit) != 0) supported += base + byteIndex * 8 + bit + 1
            }
        }
        return supported
    }
}
