package com.bod3.app.ui

import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.LiveDisplay
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.datapack.VehicleEntry
import com.bod3.app.diagnostics.DtcRecords
import com.bod3.app.live.LiveValueDef
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DtcReadResult

/** One tile on the live data screen. */
data class LiveItem(val def: LiveValueDef, val value: Double? = null, val status: String? = null) {
    val formatted: String? get() = value?.let(def::format)
}

/** Live data recording: running, or finished with a result that can be exported. */
data class RecordingUi(
    val active: Boolean = false,
    val rows: Int = 0,
    val startedAtMs: Long? = null,
    /** Rows in the last finished recording (null = nothing to export). */
    val lastRows: Int? = null,
    val full: Boolean = false,
)

/** Progress and outcome of a fault-memory clear. */
sealed interface ClearState {
    data object Running : ClearState
    data class Done(val message: String, val success: Boolean) : ClearState
}

/** Selected ECU of the vehicle list: identification, fault codes and per-code records. */
data class EcuDetailState(
    val ecu: EcuDefinition,
    val identification: List<DidReading> = emptyList(),
    val identificationLoading: Boolean = false,
    val dtcs: Readout<DtcReadResult> = Readout.NotRead,
    val records: Map<String, Readout<DtcRecords>> = emptyMap(),
    val clear: ClearState? = null,
)

/** Vehicle and ECU definitions, custom PIDs and DTC descriptions from data packs (bundled + user). */
data class DataState(
    val vehicles: List<VehicleEntry> = emptyList(),
    val selectedVehicle: VehicleEntry? = null,
    val editingVehicle: VehicleDefinition? = null,
    /** Key of the vehicle being edited before the edit (null for a new vehicle). */
    val editingVehicleKey: String? = null,
    val vehicleError: String? = null,
    val customPids: List<CustomPid> = emptyList(),
    val userPidIds: Set<String> = emptySet(),
    val editingPid: CustomPid? = null,
    val editingPidId: String? = null,
    val pidError: String? = null,
    val pidTest: String? = null,
    val descriptions: DtcDescriptions = DtcDescriptions(emptyMap(), emptyList()),
    /** Problems found in bundled or saved data packs. */
    val packErrors: List<String> = emptyList(),
)

/** Live data configuration beyond the tiles themselves. */
data class LiveConfig(
    val available: List<LiveValueDef> = emptyList(),
    val display: Map<String, LiveDisplay> = emptyMap(),
    val history: Map<String, List<Double>> = emptyMap(),
    val recording: RecordingUi = RecordingUi(),
) {
    fun displayOf(key: String): LiveDisplay = display[key] ?: LiveDisplay.VALUE
}

/** Saved vehicle profiles (the garage) and the state of a profile import. */
data class GarageState(
    val profiles: List<com.bod3.app.profile.VehicleProfile> = emptyList(),
    val loading: Boolean = false,
    /** One-line result of the last action (export, import, delete). */
    val message: String? = null,
    /** Problems found in an import file, each with its JSON path. */
    val importErrors: List<String> = emptyList(),
    /** A valid import whose VIN already has a profile, waiting for the user to confirm overwriting. */
    val pendingOverwrite: com.bod3.app.profile.ProfileBundle? = null,
)
