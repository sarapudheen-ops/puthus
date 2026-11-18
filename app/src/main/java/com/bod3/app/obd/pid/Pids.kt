package com.bod3.app.obd.pid

import kotlin.math.roundToInt

/**
 * A Service 01 parameter with its SAE J1979 formula. [decode] receives the data bytes after `41 <pid>`
 * (the same bytes follow `42 <pid> <frame>` in a freeze frame). [min]/[max] are the gauge range.
 */
class PidDefinition(
    val pid: Int,
    val name: String,
    val unit: String,
    val byteCount: Int,
    val decimals: Int,
    val min: Double = 0.0,
    val max: Double = 100.0,
    private val formula: (List<Int>) -> Double,
) {
    fun decode(data: List<Int>): Double? = if (data.size < byteCount) null else formula(data)

    fun format(value: Double): String =
        if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

    /** Stable key used for selections, recordings and CSV columns. */
    val key: String get() = "01%02X".format(pid)

    override fun toString(): String = "PID %02X %s".format(pid, name)
}

/** SAE J1979 Service 01 parameters with a single numeric value. */
object Pids {
    private fun ab(d: List<Int>) = d[0] * 256.0 + d[1]
    private fun percent(d: List<Int>) = d[0] * 100.0 / 255.0
    private fun temperature(d: List<Int>) = d[0] - 40.0
    private fun trim(d: List<Int>) = (d[0] - 128) * 100.0 / 128.0
    private fun lambda(d: List<Int>) = ab(d) * 2.0 / 65536.0
    private fun signed16(d: List<Int>): Double = ((((d[0] shl 8) or d[1]) xor 0x8000) - 0x8000).toDouble()
    private fun torque(d: List<Int>) = d[0] - 125.0

    private fun pct(pid: Int, name: String) = PidDefinition(pid, name, "%", 1, 0, 0.0, 100.0, ::percent)
    private fun temp(pid: Int, name: String) = PidDefinition(pid, name, "°C", 1, 0, -40.0, 215.0, ::temperature)
    private fun fuelTrim(pid: Int, name: String) = PidDefinition(pid, name, "%", 1, 1, -100.0, 99.2, ::trim)

    val ENGINE_LOAD = pct(0x04, "Engine load")
    val COOLANT_TEMP = temp(0x05, "Coolant temperature")
    val STFT_B1 = fuelTrim(0x06, "Short-term fuel trim bank 1")
    val LTFT_B1 = fuelTrim(0x07, "Long-term fuel trim bank 1")
    val STFT_B2 = fuelTrim(0x08, "Short-term fuel trim bank 2")
    val LTFT_B2 = fuelTrim(0x09, "Long-term fuel trim bank 2")
    val FUEL_PRESSURE = PidDefinition(0x0A, "Fuel pressure", "kPa", 1, 0, 0.0, 765.0) { it[0] * 3.0 }
    val INTAKE_PRESSURE = PidDefinition(0x0B, "Intake manifold pressure", "kPa", 1, 0, 0.0, 255.0) { it[0].toDouble() }
    val ENGINE_RPM = PidDefinition(0x0C, "Engine speed", "rpm", 2, 0, 0.0, 8_000.0) { ab(it) / 4.0 }
    val VEHICLE_SPEED = PidDefinition(0x0D, "Vehicle speed", "km/h", 1, 0, 0.0, 255.0) { it[0].toDouble() }
    val TIMING_ADVANCE = PidDefinition(0x0E, "Timing advance", "°", 1, 1, -64.0, 63.5) { it[0] / 2.0 - 64.0 }
    val INTAKE_TEMP = temp(0x0F, "Intake air temperature")
    val MAF = PidDefinition(0x10, "Mass air flow", "g/s", 2, 2, 0.0, 655.35) { ab(it) / 100.0 }
    val THROTTLE = pct(0x11, "Throttle position")
    val RUN_TIME = PidDefinition(0x1F, "Run time since start", "s", 2, 0, 0.0, 65_535.0, ::ab)
    val DISTANCE_MIL = PidDefinition(0x21, "Distance with MIL on", "km", 2, 0, 0.0, 65_535.0, ::ab)
    val FUEL_RAIL_PRESSURE_REL = PidDefinition(0x22, "Fuel rail pressure (rel. manifold)", "kPa", 2, 1, 0.0, 5_177.0) { ab(it) * 0.079 }
    val FUEL_RAIL_PRESSURE = PidDefinition(0x23, "Fuel rail gauge pressure", "kPa", 2, 0, 0.0, 655_350.0) { ab(it) * 10.0 }
    val COMMANDED_EGR = pct(0x2C, "Commanded EGR")
    val EGR_ERROR = fuelTrim(0x2D, "EGR error")
    val EVAP_PURGE = pct(0x2E, "Commanded evaporative purge")
    val FUEL_LEVEL = pct(0x2F, "Fuel level")
    val WARMUPS_SINCE_CLEAR = PidDefinition(0x30, "Warm-ups since codes cleared", "", 1, 0, 0.0, 255.0) { it[0].toDouble() }
    val DISTANCE_SINCE_CLEAR = PidDefinition(0x31, "Distance since codes cleared", "km", 2, 0, 0.0, 65_535.0, ::ab)
    val EVAP_VAPOR_PRESSURE = PidDefinition(0x32, "Evap. system vapour pressure", "Pa", 2, 2, -8_192.0, 8_191.75) { signed16(it) / 4.0 }
    val BAROMETRIC_PRESSURE = PidDefinition(0x33, "Barometric pressure", "kPa", 1, 0, 0.0, 255.0) { it[0].toDouble() }
    val MODULE_VOLTAGE = PidDefinition(0x42, "Control module voltage", "V", 2, 2, 0.0, 20.0) { ab(it) / 1000.0 }
    val ABSOLUTE_LOAD = PidDefinition(0x43, "Absolute load", "%", 2, 0, 0.0, 100.0) { ab(it) * 100.0 / 255.0 }
    val COMMANDED_LAMBDA = PidDefinition(0x44, "Commanded air-fuel equivalence ratio", "λ", 2, 3, 0.0, 2.0, ::lambda)
    val RELATIVE_THROTTLE = pct(0x45, "Relative throttle position")
    val AMBIENT_TEMP = temp(0x46, "Ambient air temperature")
    val THROTTLE_B = pct(0x47, "Absolute throttle position B")
    val THROTTLE_C = pct(0x48, "Absolute throttle position C")
    val PEDAL_D = pct(0x49, "Accelerator pedal position D")
    val PEDAL_E = pct(0x4A, "Accelerator pedal position E")
    val PEDAL_F = pct(0x4B, "Accelerator pedal position F")
    val COMMANDED_THROTTLE = pct(0x4C, "Commanded throttle actuator")
    val TIME_MIL = PidDefinition(0x4D, "Time run with MIL on", "min", 2, 0, 0.0, 65_535.0, ::ab)
    val TIME_SINCE_CLEAR = PidDefinition(0x4E, "Time since codes cleared", "min", 2, 0, 0.0, 65_535.0, ::ab)
    val ETHANOL = pct(0x52, "Ethanol fuel")
    val EVAP_ABS_PRESSURE = PidDefinition(0x53, "Absolute evap. system vapour pressure", "kPa", 2, 2, 0.0, 327.675) { ab(it) / 200.0 }
    val EVAP_PRESSURE_WIDE = PidDefinition(0x54, "Evap. system vapour pressure (wide)", "Pa", 2, 0, -32_768.0, 32_767.0, ::signed16)
    val FUEL_RAIL_ABS_PRESSURE = PidDefinition(0x59, "Fuel rail absolute pressure", "kPa", 2, 0, 0.0, 655_350.0) { ab(it) * 10.0 }
    val RELATIVE_PEDAL = pct(0x5A, "Relative accelerator pedal position")
    val HYBRID_BATTERY = pct(0x5B, "Hybrid battery pack remaining life")
    val OIL_TEMP = temp(0x5C, "Engine oil temperature")
    val INJECTION_TIMING = PidDefinition(0x5D, "Fuel injection timing", "°", 2, 2, -210.0, 301.992) { ab(it) / 128.0 - 210.0 }
    val FUEL_RATE = PidDefinition(0x5E, "Engine fuel rate", "L/h", 2, 1, 0.0, 3_212.75) { ab(it) / 20.0 }
    val DEMAND_TORQUE = PidDefinition(0x61, "Driver's demand engine torque", "%", 1, 0, -125.0, 130.0, ::torque)
    val ACTUAL_TORQUE = PidDefinition(0x62, "Actual engine torque", "%", 1, 0, -125.0, 130.0, ::torque)
    val REFERENCE_TORQUE = PidDefinition(0x63, "Engine reference torque", "Nm", 2, 0, 0.0, 65_535.0, ::ab)
    val COOLANT_TEMP_SENSOR = PidDefinition(0x67, "Coolant temperature (sensor 1)", "°C", 2, 0, -40.0, 215.0) { it[1] - 40.0 }
    val INTAKE_TEMP_SENSOR = PidDefinition(0x68, "Intake air temperature (sensor 1)", "°C", 2, 0, -40.0, 215.0) { it[1] - 40.0 }
    val EXHAUST_PRESSURE = PidDefinition(0x73, "Exhaust pressure (sensor 1)", "kPa", 3, 2, 0.0, 655.35) { (it[1] * 256.0 + it[2]) / 100.0 }
    val FRICTION_TORQUE = PidDefinition(0x8E, "Engine friction torque", "%", 1, 0, -125.0, 130.0, ::torque)
    val ODOMETER = PidDefinition(0xA6, "Odometer", "km", 4, 1, 0.0, 429_496_729.5) {
        ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
    }

    private val oxygenVoltage = (0x14..0x1B).map { pid ->
        val n = pid - 0x14
        PidDefinition(pid, "O2 sensor ${n / 4 + 1}-${n % 4 + 1} voltage", "V", 2, 3, 0.0, 1.275) { it[0] / 200.0 }
    }
    private val oxygenLambdaVoltage = (0x24..0x2B).map { pid ->
        PidDefinition(pid, "O2 sensor ${pid - 0x23} equivalence ratio", "λ", 4, 3, 0.0, 2.0, ::lambda)
    }
    private val oxygenLambdaCurrent = (0x34..0x3B).map { pid ->
        PidDefinition(pid, "O2 sensor ${pid - 0x33} equivalence ratio (current)", "λ", 4, 3, 0.0, 2.0, ::lambda)
    }
    private val catalystTemps = listOf(0x3C to "bank 1 sensor 1", 0x3D to "bank 2 sensor 1", 0x3E to "bank 1 sensor 2", 0x3F to "bank 2 sensor 2")
        .map { (pid, label) -> PidDefinition(pid, "Catalyst temperature $label", "°C", 2, 0, -40.0, 1_000.0) { ab(it) / 10.0 - 40.0 } }
    private val secondaryTrims = listOf(0x55 to "Short-term secondary O2 trim bank 1", 0x56 to "Long-term secondary O2 trim bank 1",
        0x57 to "Short-term secondary O2 trim bank 2", 0x58 to "Long-term secondary O2 trim bank 2")
        .map { (pid, name) -> fuelTrim(pid, name) }

    /** Every numeric Service 01 PID the app can decode, in PID order. */
    val all: List<PidDefinition> = (
        listOf(
            ENGINE_LOAD, COOLANT_TEMP, STFT_B1, LTFT_B1, STFT_B2, LTFT_B2, FUEL_PRESSURE, INTAKE_PRESSURE, ENGINE_RPM,
            VEHICLE_SPEED, TIMING_ADVANCE, INTAKE_TEMP, MAF, THROTTLE, RUN_TIME, DISTANCE_MIL, FUEL_RAIL_PRESSURE_REL,
            FUEL_RAIL_PRESSURE, COMMANDED_EGR, EGR_ERROR, EVAP_PURGE, FUEL_LEVEL, WARMUPS_SINCE_CLEAR, DISTANCE_SINCE_CLEAR,
            EVAP_VAPOR_PRESSURE, BAROMETRIC_PRESSURE, MODULE_VOLTAGE, ABSOLUTE_LOAD, COMMANDED_LAMBDA, RELATIVE_THROTTLE,
            AMBIENT_TEMP, THROTTLE_B, THROTTLE_C, PEDAL_D, PEDAL_E, PEDAL_F, COMMANDED_THROTTLE, TIME_MIL, TIME_SINCE_CLEAR,
            ETHANOL, EVAP_ABS_PRESSURE, EVAP_PRESSURE_WIDE, FUEL_RAIL_ABS_PRESSURE, RELATIVE_PEDAL, HYBRID_BATTERY, OIL_TEMP,
            INJECTION_TIMING, FUEL_RATE, DEMAND_TORQUE, ACTUAL_TORQUE, REFERENCE_TORQUE, COOLANT_TEMP_SENSOR,
            INTAKE_TEMP_SENSOR, EXHAUST_PRESSURE, FRICTION_TORQUE, ODOMETER,
        ) + oxygenVoltage + oxygenLambdaVoltage + oxygenLambdaCurrent + catalystTemps + secondaryTrims
        ).sortedBy { it.pid }

    /** Default live data selection when the user has not chosen any PIDs yet. */
    val live: List<PidDefinition> = listOf(
        ENGINE_RPM, VEHICLE_SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, INTAKE_PRESSURE,
        MAF, TIMING_ADVANCE, FUEL_LEVEL, FUEL_RATE, MODULE_VOLTAGE, OIL_TEMP, AMBIENT_TEMP,
        BAROMETRIC_PRESSURE, RUN_TIME,
    )

    fun byPid(pid: Int): PidDefinition? = all.firstOrNull { it.pid == pid }

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
