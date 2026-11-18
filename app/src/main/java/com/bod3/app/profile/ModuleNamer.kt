package com.bod3.app.profile

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.ModuleNameEntry
import com.bod3.app.obd.scan.KnownModules
import com.bod3.app.obd.scan.ModuleAddress
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.uds.KnownDids

/** Where a module's display name came from, in priority order. */
enum class NameSource(val label: String) {
    /** Renamed by the user for this vehicle. */
    USER("Your name"),

    /** This vehicle's manual ECU list. */
    MANUAL("Manual ECU list"),

    /** The selected vehicle's ECU list from a data pack. */
    VEHICLE_PACK("Vehicle data pack"),

    /** The module's own system name (UDS 22 F197). */
    MODULE("Reported by module (F197)"),

    /** A data pack's generic address names for this make. */
    PACK_MAP("Data pack address names"),

    /** OBD ECU name (09 0A). */
    OBD("OBD ECU name (09 0A)"),

    /** Generic name for a standard OBD address. */
    GENERIC("Standard OBD address"),
}

data class ModuleName(val name: String, val source: NameSource, val detail: String? = null)

/**
 * Names scanned modules from, in order: the user's own names for this vehicle, its manual ECU list,
 * the selected data-pack vehicle's ECU list, the module's own system name, data-pack address names for
 * the vehicle's make, the OBD ECU name, and generic OBD address names.
 */
class ModuleNamer(
    /** Request ID → name the user gave the module on this vehicle. */
    private val userNames: Map<String, String> = emptyMap(),
    private val manualEcus: List<EcuDefinition> = emptyList(),
    /** ECUs of the data-pack vehicle chosen for this car, with the vehicle title. */
    private val vehicleEcus: List<EcuDefinition> = emptyList(),
    private val vehicleTitle: String? = null,
    /** Generic address names with their pack name. */
    private val packNames: List<Pair<ModuleNameEntry, String>> = emptyList(),
    /** The vehicle's make (from the VIN decode) for [ModuleNameEntry.makes]. */
    private val make: String? = null,
) {

    fun name(address: ModuleAddress, systemName: String?, obdEcuName: String?): ModuleName? {
        val request = address.requestHeader.uppercase()
        userNames.entries.firstOrNull { it.key.equals(request, ignoreCase = true) && it.value.isNotBlank() }?.let {
            return ModuleName(it.value.trim(), NameSource.USER)
        }
        match(manualEcus, address)?.let { return ModuleName(it.display, NameSource.MANUAL) }
        match(vehicleEcus, address)?.let { return ModuleName(it.display, NameSource.VEHICLE_PACK, vehicleTitle) }
        systemName?.trim()?.takeIf { it.isNotEmpty() }?.let { return ModuleName(it, NameSource.MODULE) }
        packNames.firstOrNull { (entry, _) ->
            entry.requestId.equals(request, ignoreCase = true) &&
                (entry.responseId == null || entry.responseId.equals(address.responseHeader, ignoreCase = true)) &&
                entry.appliesTo(make)
        }?.let { (entry, pack) -> return ModuleName(entry.display, NameSource.PACK_MAP, pack) }
        obdEcuName?.trim()?.takeIf { it.isNotEmpty() }?.let { return ModuleName(it, NameSource.OBD) }
        KnownModules.name(address)?.let { return ModuleName(it, NameSource.GENERIC) }
        return null
    }

    fun name(report: ModuleReport): ModuleName? =
        name(report.address, report.identifierText(KnownDids.SYSTEM_NAME.id), report.obdEcuName)

    /** Copies of [reports] with their names (and name sources) resolved. */
    fun apply(reports: List<ModuleReport>): List<ModuleReport> = reports.map { report ->
        val resolved = name(report)
        report.copy(name = resolved?.name ?: report.name, nameSource = resolved?.source)
    }

    private fun match(ecus: List<EcuDefinition>, address: ModuleAddress): EcuDefinition? =
        ecus.firstOrNull { it.requestId.equals(address.requestHeader, ignoreCase = true) && it.responseId.equals(address.responseHeader, ignoreCase = true) }
            ?: ecus.firstOrNull { it.requestId.equals(address.requestHeader, ignoreCase = true) }
}
