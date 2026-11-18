package com.bod3.app.actuator

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.VoltageParser
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DidText
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.UdsClient
import java.io.IOException

/** Reads the vehicle state that preconditions depend on. Every value may be unknown (null). */
interface VehicleStateProbe {
    suspend fun batteryVoltage(): Double?
    /** Engine speed from OBD PID 0C; null when no ECU answered (typically ignition off). */
    suspend fun engineRpm(): Int?
    /** Vehicle speed from OBD PID 0D; null when not available. */
    suspend fun vehicleSpeedKmh(): Int?
    /** An identification string (`F187`, `F189` …) of [target]; null when it cannot be read. */
    suspend fun readIdentification(target: EcuTarget.Physical, did: Int): String?
}

/** [VehicleStateProbe] over the live session: `ATRV`, OBD `01 0C` / `01 0D` and UDS `22`. All read-only. */
class SessionStateProbe(private val session: Session, private val executor: CommandExecutor) : VehicleStateProbe {
    override suspend fun batteryVoltage(): Double? = try {
        executor.execute("ATRV", 2_000L).takeIf { it.isSuccess }?.let { VoltageParser.parse(it.text) }
    } catch (e: IOException) {
        null
    }

    private suspend fun obdPid(pid: Int): List<Int>? = try {
        session.request(EcuTarget.Broadcast, 0x01, pid).messages.firstOrNull()?.payload?.drop(2)
    } catch (e: IOException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    override suspend fun engineRpm(): Int? = obdPid(0x0C)?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }

    override suspend fun vehicleSpeedKmh(): Int? = obdPid(0x0D)?.firstOrNull()

    override suspend fun readIdentification(target: EcuTarget.Physical, did: Int): String? =
        when (val result = UdsClient(session).readDid(target, did)) {
            is DidResult.Value -> DidText.decode(result.data)?.trim()?.ifEmpty { null }
            else -> null
        }
}

enum class CheckStatus { PASS, FAIL, UNKNOWN }

/**
 * One precondition. [overridable] items (applicability) may be overridden by the user after a warning;
 * the others (voltage, engine, speed, ignition) never.
 */
data class PreconditionItem(
    val label: String,
    val status: CheckStatus,
    val detail: String,
    val overridable: Boolean = false,
)

data class PreconditionReport(val items: List<PreconditionItem>) {
    /** Items that block the test (failed or unknown); unknown counts as failed for safety. */
    val blocking: List<PreconditionItem> get() = items.filter { it.status != CheckStatus.PASS && !it.overridable }
    val overridableFailures: List<PreconditionItem> get() = items.filter { it.status != CheckStatus.PASS && it.overridable }

    fun passed(overrideApplicability: Boolean): Boolean =
        blocking.isEmpty() && (overrideApplicability || overridableFailures.isEmpty())

    val summary: String get() = (blocking + overridableFailures).joinToString("; ") { "${it.label}: ${it.detail}" }.ifEmpty { "All preconditions met" }
}

/** The vehicle selected in the app, for make/model applicability. */
data class VehicleContext(val make: String? = null, val model: String? = null, val vin: String? = null)

object PreconditionChecker {

    suspend fun check(recipe: ActuatorRecipe, probe: VehicleStateProbe, vehicle: VehicleContext = VehicleContext()): PreconditionReport =
        check(recipe.preconditions, recipe.applicability, recipe.module.target, probe, vehicle)

    suspend fun check(
        pre: ActuatorPreconditions,
        applicability: Applicability,
        target: EcuTarget.Physical,
        probe: VehicleStateProbe,
        vehicle: VehicleContext,
    ): PreconditionReport {
        val items = mutableListOf<PreconditionItem>()

        val volts = probe.batteryVoltage()
        items += when {
            volts == null -> PreconditionItem("Battery voltage", CheckStatus.UNKNOWN, "No reading from the adapter")
            volts < pre.minVoltage -> PreconditionItem(
                "Battery voltage", CheckStatus.FAIL,
                "%.1f V is below %.1f V – connect a charger or battery support".format(volts, pre.minVoltage),
            )
            else -> PreconditionItem("Battery voltage", CheckStatus.PASS, "%.1f V".format(volts))
        }

        val rpm = probe.engineRpm()
        if (pre.ignitionOn) {
            items += if (rpm == null) {
                PreconditionItem("Ignition", CheckStatus.FAIL, "The engine ECU does not answer – switch the ignition on")
            } else {
                PreconditionItem("Ignition", CheckStatus.PASS, "On")
            }
        }
        items += when (pre.engine) {
            EngineRequirement.ANY -> PreconditionItem("Engine", CheckStatus.PASS, rpm?.let { "$it rpm (any state allowed)" } ?: "Any state allowed")
            EngineRequirement.OFF -> when {
                rpm == null -> PreconditionItem("Engine", CheckStatus.UNKNOWN, "Engine speed unknown")
                rpm > 0 -> PreconditionItem("Engine", CheckStatus.FAIL, "Running at $rpm rpm – switch the engine off (ignition on)")
                else -> PreconditionItem("Engine", CheckStatus.PASS, "Off")
            }
            EngineRequirement.RUNNING -> when {
                rpm == null -> PreconditionItem("Engine", CheckStatus.UNKNOWN, "Engine speed unknown")
                rpm == 0 -> PreconditionItem("Engine", CheckStatus.FAIL, "Not running – start the engine")
                else -> PreconditionItem("Engine", CheckStatus.PASS, "Running at $rpm rpm")
            }
        }
        if (pre.speedZero) {
            val speed = probe.vehicleSpeedKmh()
            items += when {
                speed == null -> PreconditionItem("Vehicle speed", CheckStatus.UNKNOWN, "Speed unknown – the vehicle must be stationary")
                speed > 0 -> PreconditionItem("Vehicle speed", CheckStatus.FAIL, "$speed km/h – stop the vehicle")
                else -> PreconditionItem("Vehicle speed", CheckStatus.PASS, "Stationary")
            }
        }

        if (applicability.makes.isNotEmpty() || applicability.models.isNotEmpty()) {
            val ok = applicability.matchesMake(vehicle.make) == true && applicability.matchesModel(vehicle.model) == true
            val car = listOfNotNull(vehicle.make, vehicle.model).joinToString(" ").ifEmpty { "No vehicle selected" }
            items += PreconditionItem(
                "Vehicle", if (ok) CheckStatus.PASS else CheckStatus.FAIL,
                if (ok) car else "$car – written for ${(applicability.makes + applicability.models).joinToString(", ")}",
                overridable = true,
            )
        }
        if (applicability.partNumbers.isNotEmpty()) {
            val part = probe.readIdentification(target, KnownDids.SPARE_PART_NUMBER.id)
            items += when (applicability.matchesPartNumber(part)) {
                true -> PreconditionItem("Module part number", CheckStatus.PASS, part.orEmpty(), overridable = true)
                false -> PreconditionItem("Module part number", CheckStatus.FAIL, "$part – written for ${applicability.partNumbers.joinToString(", ")}", overridable = true)
                null -> PreconditionItem("Module part number", CheckStatus.UNKNOWN, "Could not read F187", overridable = true)
            }
        }
        if (applicability.softwareVersions.isNotEmpty()) {
            val version = probe.readIdentification(target, KnownDids.SOFTWARE_VERSION.id)
            items += when (applicability.matchesSoftware(version)) {
                true -> PreconditionItem("Module software", CheckStatus.PASS, version.orEmpty(), overridable = true)
                false -> PreconditionItem("Module software", CheckStatus.FAIL, "$version – written for ${applicability.softwareVersions.joinToString(", ")}", overridable = true)
                null -> PreconditionItem("Module software", CheckStatus.UNKNOWN, "Could not read F189", overridable = true)
            }
        }
        return PreconditionReport(items)
    }
}
