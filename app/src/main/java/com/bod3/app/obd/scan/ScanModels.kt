package com.bod3.app.obd.scan

import com.bod3.app.obd.protocol.HeaderFormat
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.KnownDids

enum class Bus(val format: HeaderFormat, val label: String) {
    CAN_11(HeaderFormat.CAN_11, "11-bit CAN"),
    CAN_29(HeaderFormat.CAN_29, "29-bit CAN"),
    ;

    companion object {
        fun of(format: HeaderFormat): Bus? = entries.firstOrNull { it.format == format }
    }
}

/** Where a module listens and answers. */
data class ModuleAddress(val bus: Bus, val requestHeader: String, val responseHeader: String) {
    val key: String get() = "$requestHeader>$responseHeader"
    val display: String get() = "$requestHeader → $responseHeader"

    /** Physical target with an exact receive filter and flow control on the request ID. */
    fun target(): EcuTarget = EcuTarget.Physical(requestHeader, responseHeader, flowControl = true)
}

enum class DetectionMethod(val label: String) {
    OBD_BROADCAST("OBD broadcast (01 00)"),
    OBD_REQUEST("OBD request (01 00)"),
    TESTER_PRESENT("TesterPresent (3E 00)"),
    VIN_READ("VIN read (22 F190)"),
    MANUAL_LIST("Manual / data-pack ECU list"),
}

data class DiscoveredModule(
    val address: ModuleAddress,
    val detectedBy: DetectionMethod,
    /** True when the module answered an OBD request (service 01). */
    val obd: Boolean,
    /** True when the only reply was a negative response (7F). It still counts as present. */
    val negativeReply: Boolean,
    /** First reply line, for the raw view. */
    val firstReply: String?,
)

data class ScanOptions(
    /** Also scan the other CAN identifier length (29-bit on an 11-bit vehicle and vice versa). */
    val includeOtherBus: Boolean = true,
    /** Probe with 22 F190 when an address ignores 3E 00. */
    val vinFallback: Boolean = true,
    /** Adapter-side reply timeout during discovery (`ATST`). Short, because silence is the common case. */
    val adapterTimeoutMs: Int = 100,
    /** Host-side guard per probe; the adapter normally answers `NO DATA` well before this. */
    val probeTimeoutMs: Long = 1_500,
    /** Identifiers read from each module. */
    val dids: List<Int> = KnownDids.default.map { it.id },
    /** Abort when this many probes in a row fail with an adapter error or timeout. */
    val maxConsecutiveFailures: Int = 8,
    /**
     * Addresses to probe directly with their exact reply ID (the vehicle's manual ECU list and the
     * selected data-pack vehicle's ECUs), after the range scan of their bus.
     */
    val listedModules: List<ModuleAddress> = emptyList(),
    /** Probe the OBD/UDS address ranges. Off = quick scan of [listedModules] only. */
    val rangeScan: Boolean = true,
)

enum class ScanPhase(val label: String) {
    PREPARING("Preparing adapter"),
    OBD_BROADCAST("OBD broadcast"),
    OBD_ADDRESSES("OBD addresses"),
    UDS_ADDRESSES("UDS addresses"),
    LISTED_ADDRESSES("Listed ECUs"),
    READING_MODULES("Reading modules"),
    DONE("Done"),
}

data class ScanProgress(
    val phase: ScanPhase,
    val bus: Bus?,
    val completed: Int,
    val total: Int,
    val current: String?,
    val modulesFound: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

/** Everything read from one module. */
data class ModuleReport(
    val module: DiscoveredModule,
    val name: String?,
    val identifiers: List<DidReading>,
    val dtcs: DtcReadResult,
    /** OBD service 09 PID 0A, for OBD modules. */
    val obdEcuName: String? = null,
    /** OBD service 09 PID 04, for OBD modules. */
    val calibrationId: String? = null,
    /** Where [name] came from when it was resolved by [com.bod3.app.profile.ModuleNamer]; not stored. */
    val nameSource: com.bod3.app.profile.NameSource? = null,
) {
    val address: ModuleAddress get() = module.address

    val displayName: String get() = name ?: "Module ${address.requestHeader}"

    fun identifier(did: Int): DidReading? = identifiers.firstOrNull { it.did == did }

    /** Text of an identifier when it was read successfully. */
    fun identifierText(did: Int): String? =
        (identifier(did)?.result as? DidResult.Value)?.let { it.text ?: it.hex }

    val partNumber: String? get() = identifierText(KnownDids.SPARE_PART_NUMBER.id)

    val softwareVersion: String?
        get() = identifierText(KnownDids.SOFTWARE_VERSION.id)
            ?: identifierText(KnownDids.SUPPLIER_SOFTWARE_VERSION.id)
            ?: calibrationId

    val hardwareVersion: String?
        get() = identifierText(KnownDids.HARDWARE_NUMBER.id) ?: identifierText(KnownDids.SUPPLIER_HARDWARE_VERSION.id)

    /** Current coding: VW long coding (0600) or the coding data set reference (F19E). */
    val coding: String? get() = identifierText(KnownDids.VW_CODING.id) ?: identifierText(KnownDids.ODX_FILE.id)
}

class ScanAbortedException(message: String) : Exception(message)

internal fun ObdProtocol.bus(): Bus? = Bus.of(headerFormat).takeIf { isCan }
