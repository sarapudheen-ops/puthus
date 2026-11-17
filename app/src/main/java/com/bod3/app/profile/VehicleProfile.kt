package com.bod3.app.profile

import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.scan.ModuleReport

/** A saved vehicle: identity plus the result of the latest module scan. Keyed by VIN. */
data class VehicleProfile(
    val vin: String,
    val description: VehicleDescription?,
    /** Display name of the OBD protocol the vehicle was last connected with. */
    val protocol: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastScanAtMs: Long?,
    val modules: List<ModuleReport>,
) {
    val title: String get() = description?.title ?: vin
    val totalFaults: Int get() = modules.sumOf { it.dtcs.faultCount ?: 0 }
}

object RawLogFormatter {
    /** Plain-text log for copying/sharing, one exchange per block. */
    fun format(entries: List<RawExchange>, header: String? = null): String = buildString {
        if (header != null) appendLine(header)
        for (entry in entries) {
            append("#").append(entry.sequence).append(" +").append(entry.durationMs).append("ms")
            if (entry.header != null) append(" [").append(entry.header).append("]")
            append(" > ").appendLine(entry.command)
            if (entry.error != null) {
                append("  ! ").appendLine(entry.error)
            } else if (entry.response.isEmpty()) {
                appendLine("  < (no reply)")
            } else {
                entry.response.lines().forEach { append("  < ").appendLine(it) }
            }
        }
    }

    /**
     * The exchanges that went to one module: requests sent while the adapter header was the module's
     * request ID (setup commands such as ATSH are included so the log is reproducible).
     */
    fun forModule(entries: List<RawExchange>, requestHeader: String): List<RawExchange> =
        entries.filter { it.header.equals(requestHeader, ignoreCase = true) && !it.command.startsWith("AT") }
}

object VinRules {
    private val pattern = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    /** 17 characters, digits and capital letters except I, O and Q. */
    fun isValid(vin: String?): Boolean = vin != null && pattern.matches(vin)

    /**
     * VIN reported by the modules themselves (UDS 22 F190), used when OBD service 09 gave none.
     * The most common valid value wins, so one module with a stale VIN does not override the rest.
     */
    fun fromModules(reports: List<ModuleReport>): String? =
        reports.mapNotNull { it.identifierText(com.bod3.app.obd.uds.KnownDids.VIN.id)?.trim()?.uppercase() }
            .filter(::isValid)
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}
