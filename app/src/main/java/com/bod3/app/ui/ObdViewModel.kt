package com.bod3.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bod3.app.actuator.ActuatorController
import com.bod3.app.actuator.ActuatorEnvironment
import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.actuator.SessionStateProbe
import com.bod3.app.actuator.VehicleContext
import com.bod3.app.android.AssetActuatorPacks
import com.bod3.app.android.AssetCodingPacks
import com.bod3.app.coding.CodingBackupStore
import com.bod3.app.coding.CodingController
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.android.AssetDataPacks
import com.bod3.app.audit.AuditStore
import com.bod3.app.android.ReportSharer
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.datapack.DataRepository
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.LiveDisplay
import com.bod3.app.datapack.LiveSelection
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.datapack.VehicleSource
import com.bod3.app.diagnostics.EcuWorkbench
import com.bod3.app.live.CsvExporter
import com.bod3.app.live.LiveCatalog
import com.bod3.app.live.LiveHistory
import com.bod3.app.live.LiveReader
import com.bod3.app.live.LiveRecorder
import com.bod3.app.live.LiveValueDef
import com.bod3.app.live.RecordedRow
import com.bod3.app.obd.dtc.FreezeFrame
import com.bod3.app.obd.dtc.FreezeFrameReader
import com.bod3.app.obd.dtc.Readiness
import com.bod3.app.obd.dtc.ReadinessDecoder
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.uds.Nrc
import com.bod3.app.obd.uds.UdsClearResult
import com.bod3.app.android.SharedPrefsKeyValueStore
import com.bod3.app.bluetooth.BluetoothRepository
import com.bod3.app.bluetooth.PairedAdapter
import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.connection.ConnectionManager
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.ConnectionResult
import com.bod3.app.obd.connection.ConnectionStore
import com.bod3.app.obd.connection.NegotiationResult
import com.bod3.app.obd.connection.ProtocolAttempt
import com.bod3.app.obd.connection.ReconnectPolicy
import com.bod3.app.obd.connection.RememberedProtocol
import com.bod3.app.obd.connection.SetupStep
import com.bod3.app.obd.connection.VoltageMonitor
import com.bod3.app.obd.connection.VoltageSample
import com.bod3.app.obd.connection.retry
import com.bod3.app.obd.dtc.DtcKind
import com.bod3.app.obd.dtc.DtcReport
import com.bod3.app.obd.dtc.ObdDtcReader
import com.bod3.app.obd.elm.AdapterDriver
import com.bod3.app.obd.elm.AdapterInfo
import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.elm.RecordingExecutor
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.scan.DiscoveredModule
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.scan.ScanCoordinator
import com.bod3.app.obd.scan.ScanListener
import com.bod3.app.obd.scan.ScanOptions
import com.bod3.app.obd.scan.ScanProgress
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ProtocolInfo
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.terminal.RawTerminal
import com.bod3.app.obd.terminal.TerminalLine
import com.bod3.app.obd.transport.BluetoothSppTransport
import com.bod3.app.profile.ModuleNamer
import com.bod3.app.profile.ProfileExtras
import com.bod3.app.profile.ProfileExtrasStore
import com.bod3.app.profile.ProfileTransferCodec
import com.bod3.app.profile.RawLogFormatter
import com.bod3.app.profile.moduleAddress
import com.bod3.app.profile.VehicleProfile
import com.bod3.app.profile.VehicleProfileService
import com.bod3.app.profile.VinRules
import com.bod3.app.profile.VpicClient
import com.bod3.app.profile.db.Bod3Database
import com.bod3.app.profile.db.RoomProfileRepository
import com.bod3.app.report.DiagnosticReport
import com.bod3.app.report.ReportData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    DEVICES, DASHBOARD, LIVE, LIVE_SELECT, SCAN, MODULES, MODULE_DETAIL, DTCS, SETTINGS, PROFILE_EDIT, TERMINAL,
    VEHICLES, VEHICLE_EDIT, ECU_DETAIL, CUSTOM_PIDS, PID_EDIT, GARAGE, PROFILE_ECUS,
    ACTUATORS, ACTUATOR_RUN, CODING, CODING_RUN, CODING_EDITOR,
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val device: PairedAdapter, val step: String) : ConnectionState
    data class Connected(val device: PairedAdapter) : ConnectionState

    /** The Bluetooth link dropped; reconnect attempt [attempt] of [maxAttempts] is pending or running. */
    data class Reconnecting(val device: PairedAdapter, val attempt: Int, val maxAttempts: Int, val reason: String) : ConnectionState
    data class Failed(val device: PairedAdapter, val message: String) : ConnectionState
}

val ConnectionState.device: PairedAdapter?
    get() = when (this) {
        ConnectionState.Idle -> null
        is ConnectionState.Connecting -> device
        is ConnectionState.Connected -> device
        is ConnectionState.Reconnecting -> device
        is ConnectionState.Failed -> device
    }

/** Saved connection settings shown on the settings screen. */
data class ConnectionSettingsState(
    val profiles: List<ConnectionProfile> = listOf(ConnectionProfile.DEFAULT),
    val activeProfileId: String = ConnectionProfile.DEFAULT_ID,
    val remembered: List<RememberedProtocol> = emptyList(),
    val expertMode: Boolean = false,
) {
    val activeProfile: ConnectionProfile get() = profiles.firstOrNull { it.id == activeProfileId } ?: ConnectionProfile.DEFAULT
}

/** A value that is being loaded, has loaded, or could not be read. */
sealed interface Readout<out T> {
    data object NotRead : Readout<Nothing>
    data object Loading : Readout<Nothing>
    data class Value<T>(val value: T) : Readout<T>
    data class Unavailable(val reason: String) : Readout<Nothing>
}

/** State of the module scan on the scan screen. */
data class ScanUiState(
    val running: Boolean = false,
    val includeOtherBus: Boolean = true,
    /** Also probe the vehicle's manual ECU list and the data-pack vehicle's ECUs directly. */
    val includeListed: Boolean = true,
    /** Probe only the listed ECUs (no address-range sweep). */
    val quickScan: Boolean = false,
    val progress: ScanProgress? = null,
    val found: List<DiscoveredModule> = emptyList(),
    val reports: List<ModuleReport> = emptyList(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
    /** True once the result was stored in the vehicle profile. */
    val saved: Boolean = false,
    /** Why the result was not stored (no VIN, cancelled, database error). */
    val notSavedReason: String? = null,
)

/** Saved vehicle profile for the connected VIN. */
sealed interface ProfileState {
    data object None : ProfileState
    data object Loading : ProfileState
    data class Loaded(val profile: VehicleProfile, val isNew: Boolean) : ProfileState
    data class Failed(val message: String) : ProfileState
}

data class UiState(
    val screen: Screen = Screen.DEVICES,
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val permissionDenied: Boolean = false,
    val pairedDevices: List<PairedAdapter> = emptyList(),
    val connection: ConnectionState = ConnectionState.Idle,
    val adapter: AdapterInfo? = null,
    val protocol: Readout<ProtocolInfo> = Readout.NotRead,
    val vin: Readout<String> = Readout.NotRead,
    val batteryVoltage: Readout<Double> = Readout.NotRead,
    /** Supported Service 01 PIDs per responding ECU header. */
    val ecus: Map<String, Set<Int>> = emptyMap(),
    val live: List<LiveItem> = emptyList(),
    val liveRunning: Boolean = false,
    val liveEcu: String? = null,
    val profile: ProfileState = ProfileState.None,
    val scan: ScanUiState = ScanUiState(),
    /** Raw request/response log belonging to [modules]: from the latest scan or the saved profile. */
    val rawLog: List<RawExchange> = emptyList(),
    val selectedModuleKey: String? = null,
    val settings: ConnectionSettingsState = ConnectionSettingsState(),
    /** Profile used for the current connection. */
    val connectedProfile: ConnectionProfile? = null,
    val setup: List<SetupStep> = emptyList(),
    /** Every protocol tried during the latest detection, in order. */
    val attempts: List<ProtocolAttempt> = emptyList(),
    val voltageHistory: List<VoltageSample> = emptyList(),
    val dtcs: Map<DtcKind, Readout<DtcReport>> = emptyMap(),
    val reconnects: List<String> = emptyList(),
    val terminal: List<TerminalLine> = emptyList(),
    val terminalBusy: Boolean = false,
    /** Profile being edited (a copy; saved explicitly), and where the settings screen returns to. */
    val editingProfile: ConnectionProfile? = null,
    val settingsReturn: Screen = Screen.DEVICES,
    val data: DataState = DataState(),
    val liveConfig: LiveConfig = LiveConfig(),
    val freezeFrames: Readout<List<FreezeFrame>> = Readout.NotRead,
    val readiness: Readout<List<Readiness>> = Readout.NotRead,
    val obdClear: ClearState? = null,
    val ecuDetail: EcuDetailState? = null,
    /** Where the vehicle screen returns to (device list or dashboard). */
    val vehiclesReturn: Screen = Screen.DASHBOARD,
    val garage: GarageState = GarageState(),
    val garageReturn: Screen = Screen.DEVICES,
    /** Manual ECUs, module names and data-pack vehicle of the loaded profile. */
    val profileExtras: com.bod3.app.profile.ProfileExtras = com.bod3.app.profile.ProfileExtras(),
    /** Names modules from user names, manual ECUs and data packs; null until data is loaded. */
    val moduleNamer: com.bod3.app.profile.ModuleNamer? = null,
    /** ECUs probed directly by a scan: manual list plus data-pack vehicle ECUs. */
    val listedModules: List<com.bod3.app.obd.scan.ModuleAddress> = emptyList(),
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected && protocol is Readout.Value

    val savedProfile: VehicleProfile? get() = (profile as? ProfileState.Loaded)?.profile

    /** Modules to list: an unsaved scan result if there is one, otherwise the saved profile's modules. */
    val modules: List<ModuleReport>
        get() = (scan.reports.takeIf { !scan.saved && it.isNotEmpty() } ?: savedProfile?.modules.orEmpty())
            .let { reports -> moduleNamer?.apply(reports) ?: reports }

    /** No adapter link: saved profiles are being browsed offline. */
    val offline: Boolean get() = connection is ConnectionState.Idle || connection is ConnectionState.Failed

    val selectedModule: ModuleReport? get() = modules.firstOrNull { it.address.key == selectedModuleKey }

    val canScan: Boolean
        get() = (protocol as? Readout.Value)?.value?.protocol?.isCan == true && !scan.running
}

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetooth = BluetoothRepository(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val connectionStore = ConnectionStore(SharedPrefsKeyValueStore(application))
    private val connectionManager = ConnectionManager(viewModelScope, connectionStore, logger = ::appendLog)
    private val reconnectPolicy = ReconnectPolicy()

    private var driver: AdapterDriver? = null
    private var adapterAddress: String? = null
    private var recorder: RecordingExecutor? = null
    private var voltageMonitor: VoltageMonitor? = null
    private var terminal: RawTerminal? = null
    private var voltageJob: Job? = null
    private var linkJob: Job? = null
    private var reconnectJob: Job? = null
    private var dtcJob: Job? = null
    private var terminalJob: Job? = null
    private var session: Session? = null
    private var reader: VehicleReader? = null
    private var connectJob: Job? = null
    private var infoJob: Job? = null
    private var liveJob: Job? = null
    private var scanJob: Job? = null
    private var profileJob: Job? = null
    private var ecuJob: Job? = null

    private val dataStore = DataPackStore(SharedPrefsKeyValueStore(application))
    private val bundledPacks = AssetDataPacks.load(application)
    private val repository = DataRepository(bundledPacks.packs, dataStore)

    private var liveDefs: List<LiveValueDef> = emptyList()
    private var liveTarget: EcuTarget = EcuTarget.Broadcast
    private var liveEcu: String? = null
    private val liveHistory = LiveHistory()
    private val liveRecorder = LiveRecorder()
    private var lastRecording: com.bod3.app.live.Recording? = null

    private val extrasStore = ProfileExtrasStore(SharedPrefsKeyValueStore(application))

    // ---- Bidirectional tests (M4) ----
    private val auditStore = AuditStore(SharedPrefsKeyValueStore(application))
    private val bundledActuators = AssetActuatorPacks.load(application)

    /** Actuator test screens; every run needs Expert mode, a confirmation and passing preconditions. */
    val actuators = ActuatorController(
        scope = viewModelScope,
        library = ActuatorLibrary(bundledActuators.packs, SharedPrefsKeyValueStore(application)),
        audit = auditStore,
        expertMode = { connectionStore.expertModeEnabled },
        environment = ::actuatorEnvironment,
        log = ::appendLog,
        bundledErrors = bundledActuators.errors,
    )

    // ---- Coding (M5) ----
    private val bundledCoding = AssetCodingPacks.load(application)

    /** Coding screens; writes need Expert mode, a backup, a confirmed diff and passing safety checks. */
    val coding = CodingController(
        scope = viewModelScope,
        library = CodingLibrary(bundledCoding.packs, SharedPrefsKeyValueStore(application)),
        audit = auditStore,
        backups = CodingBackupStore(SharedPrefsKeyValueStore(application)),
        expertMode = { connectionStore.expertModeEnabled },
        environment = ::actuatorEnvironment,
        log = ::appendLog,
        bundledErrors = bundledCoding.errors,
    )

    private val profiles: VehicleProfileService by lazy {
        VehicleProfileService(
            repository = RoomProfileRepository(Bod3Database.get(application).profileDao()),
            describer = VpicClient(currentYear = { Calendar.getInstance().get(Calendar.YEAR) }),
            extras = extrasStore,
        )
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        refreshBluetooth()
        refreshSettings()
        refreshData()
        bundledPacks.errors.forEach { appendLog("Data pack: $it") }
    }

    fun refreshBluetooth() {
        _state.update {
            it.copy(
                bluetoothAvailable = bluetooth.isAvailable,
                bluetoothEnabled = bluetooth.isEnabled,
                permissionGranted = bluetooth.hasPermission,
                pairedDevices = bluetooth.pairedAdapters(),
            )
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionDenied = !granted) }
        refreshBluetooth()
    }

    fun connect(device: PairedAdapter) {
        if (connectJob?.isActive == true) return
        disconnectInternal()
        connectJob = viewModelScope.launch {
            try {
                val result = openConnection(device)
                appendLog("Connected with profile \"${result.profile.name}\"")
                _state.update { it.copy(screen = Screen.DASHBOARD) }
                onConnected(device, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Connection failed: ${e.message}")
                _state.update {
                    it.copy(connection = ConnectionState.Failed(device, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Opens the transport, initialises the adapter, applies the active profile and negotiates the protocol. */
    private suspend fun openConnection(device: PairedAdapter): ConnectionResult {
        setStep(device, "Opening Bluetooth connection…")
        val bluetoothDevice = bluetooth.device(device.address) ?: throw IllegalArgumentException("Invalid Bluetooth address")
        val profile = connectionStore.activeProfile()
        return connectionManager.connect(BluetoothSppTransport(bluetoothDevice), device.address, profile) { step ->
            setStep(device, step)
        }
    }

    /** Installs a fresh connection (first connect or reconnect) and reads the vehicle. */
    private suspend fun onConnected(device: PairedAdapter, result: ConnectionResult) {
        driver = result.driver
        adapterAddress = device.address
        recorder = RecordingExecutor(result.driver)
        voltageMonitor = VoltageMonitor(result.driver).also { monitor ->
            // Keep the graph across reconnects.
            _state.value.voltageHistory.forEach { monitor.restore(it) }
        }
        _state.update {
            it.copy(
                connection = ConnectionState.Connected(device),
                adapter = result.adapter,
                connectedProfile = result.profile,
                setup = result.setup,
            )
        }
        refreshSettings()
        watchLink(device, result.driver)
        startVoltageMonitor()
        applyNegotiation(result.negotiation)
    }

    /** Re-runs protocol detection and the vehicle reads (e.g. after the ignition was turned on). */
    fun detectVehicle() {
        if (infoJob?.isActive == true || scanJob?.isActive == true) return
        val currentDriver = driver ?: return
        val address = adapterAddress ?: return
        val profile = _state.value.connectedProfile ?: connectionStore.activeProfile()
        infoJob = viewModelScope.launch {
            _state.update { it.copy(protocol = Readout.Loading, vin = Readout.Loading, ecus = emptyMap(), attempts = emptyList()) }
            val negotiation = try {
                connectionManager.detectProtocol(currentDriver, address, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NegotiationResult(null, emptyList()).also { appendLog("Protocol detection failed: ${e.message}") }
            }
            refreshSettings()
            applyNegotiation(negotiation)
        }
    }

    fun refreshBatteryVoltage() {
        viewModelScope.launch { sampleVoltage() }
    }

    fun openLiveData() {
        if (scanJob?.isActive == true) return
        if (reader == null || session == null) return
        val state = _state.value
        val protocolInfo = (state.protocol as? Readout.Value)?.value ?: return

        // Use the ECU that supports the most PIDs (normally the engine ECU).
        val primaryEcu = state.ecus.maxByOrNull { it.value.size }?.key
        val supported = primaryEcu?.let { state.ecus[it] }.orEmpty()
        // On CAN, address that ECU physically: faster (no waiting for other ECUs) and exercises ATSH/ATCRA.
        liveTarget = primaryEcu?.let { EcuTarget.physicalFor(it, protocolInfo.protocol.headerFormat) } ?: EcuTarget.Broadcast
        liveEcu = primaryEcu

        val available = LiveCatalog.available(supported, repository.customPids())
        val saved = dataStore.liveSelection()
        val keys = saved?.keys?.filter { key -> available.any { it.key == key } }?.takeIf { it.isNotEmpty() }
            ?: LiveCatalog.defaultKeys(supported)
        liveDefs = keys.mapNotNull { key -> available.firstOrNull { it.key == key } }
        liveHistory.clear()
        _state.update {
            it.copy(
                screen = Screen.LIVE,
                live = liveDefs.map { def -> LiveItem(def) },
                liveEcu = primaryEcu,
                liveConfig = it.liveConfig.copy(available = available, display = saved?.display.orEmpty(), history = emptyMap()),
            )
        }
        startLiveLoop()
    }

    private fun startLiveLoop() {
        val currentSession = session ?: return
        val currentReader = reader ?: return
        val defs = liveDefs
        val target = liveTarget
        val primaryEcu = liveEcu
        val liveReader = LiveReader(currentSession, currentReader)
        liveJob?.cancel()
        _state.update { it.copy(liveRunning = true) }
        liveJob = viewModelScope.launch {
            while (true) {
                val row = mutableMapOf<String, Double?>()
                for (def in defs) {
                    currentCoroutineContext().ensureActive()
                    val item = try {
                        val value = liveReader.read(def, target, primaryEcu)
                        row[def.key] = value
                        value?.let { liveHistory.add(def.key, it) }
                        LiveItem(def, value, status = if (value == null) "No data" else null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        row[def.key] = null
                        LiveItem(def, status = e.message ?: "Error")
                    }
                    val history = liveHistory.snapshot()
                    _state.update { s ->
                        s.copy(
                            live = s.live.map { if (it.def.key == def.key) item else it },
                            liveConfig = s.liveConfig.copy(history = history),
                        )
                    }
                }
                if (liveRecorder.isRecording) {
                    liveRecorder.add(RecordedRow(System.currentTimeMillis(), row))
                    if (liveRecorder.isFull) {
                        appendLog("Recording stopped: limit of ${liveRecorder.rowCount} rows reached")
                        stopRecording(full = true)
                    } else {
                        _state.update { it.copy(liveConfig = it.liveConfig.copy(recording = it.liveConfig.recording.copy(rows = liveRecorder.rowCount))) }
                    }
                }
                delay(LIVE_PAUSE_MS)
            }
        }
    }

    fun pauseLiveData() {
        liveJob?.cancel()
        _state.update { it.copy(liveRunning = false) }
    }

    fun resumeLiveData() {
        if (_state.value.screen == Screen.LIVE && liveJob?.isActive != true) {
            if (liveDefs.isEmpty() && _state.value.live.isEmpty()) openLiveData() else startLiveLoop()
        }
    }

    fun closeLiveData() {
        pauseLiveData()
        if (liveRecorder.isRecording) stopRecording()
        // No need to reset headers here: the Session switches back to broadcast on the next broadcast request.
        _state.update { it.copy(screen = Screen.DASHBOARD) }
    }

    fun openLiveSelection() {
        _state.update { it.copy(screen = Screen.LIVE_SELECT) }
    }

    fun closeLiveSelection() {
        _state.update { it.copy(screen = Screen.LIVE) }
    }

    /** Saves which values are shown (in this order) and restarts polling with them. */
    fun setLiveSelection(keys: List<String>) {
        val config = _state.value.liveConfig
        if (liveRecorder.isRecording) stopRecording()
        dataStore.saveLiveSelection(LiveSelection(keys, config.display.filterKeys { it in keys }))
        liveDefs = keys.mapNotNull { key -> config.available.firstOrNull { it.key == key } }
        _state.update { s ->
            val old = s.live.associateBy { it.def.key }
            s.copy(screen = Screen.LIVE, live = liveDefs.map { old[it.key] ?: LiveItem(it) })
        }
        if (session != null) startLiveLoop()
    }

    fun setLiveDisplay(key: String, display: LiveDisplay) {
        val config = _state.value.liveConfig
        val newDisplay = config.display + (key to display)
        dataStore.saveLiveSelection(LiveSelection(liveDefs.map { it.key }, newDisplay))
        _state.update { it.copy(liveConfig = it.liveConfig.copy(display = newDisplay)) }
    }

    fun startRecording() {
        if (liveDefs.isEmpty()) return
        val now = System.currentTimeMillis()
        liveRecorder.start(liveDefs, now)
        lastRecording = null
        appendLog("Recording ${liveDefs.size} live values")
        _state.update { it.copy(liveConfig = it.liveConfig.copy(recording = RecordingUi(active = true, startedAtMs = now))) }
        if (liveJob?.isActive != true) startLiveLoop()
    }

    fun stopRecording(full: Boolean = false) {
        val recording = liveRecorder.stop() ?: return
        lastRecording = recording
        appendLog("Recording stopped: ${recording.rows.size} rows, ${recording.durationMs / 1000} s")
        _state.update {
            it.copy(liveConfig = it.liveConfig.copy(recording = RecordingUi(lastRows = recording.rows.size, full = full)))
        }
    }

    /** Writes the last recording as CSV to the share cache; null when there is nothing to export. */
    suspend fun writeRecordingCsv(): File? {
        val recording = lastRecording ?: return null
        val state = _state.value
        val title = listOfNotNull(
            "bod3 live data",
            state.data.selectedVehicle?.vehicle?.title,
            (state.vin as? Readout.Value)?.value,
            (state.protocol as? Readout.Value)?.value?.protocol?.displayName,
            state.liveEcu?.let { "ECU $it" },
        ).joinToString(" · ")
        return withContext(Dispatchers.IO) {
            ReportSharer.write(getApplication<Application>(), CsvExporter.fileName(recording.startedAtMs), CsvExporter.format(recording, title))
        }
    }

    fun disconnect() {
        disconnectInternal()
        _state.update {
            it.copy(
                screen = Screen.DEVICES,
                connection = ConnectionState.Idle,
                adapter = null,
                protocol = Readout.NotRead,
                vin = Readout.NotRead,
                batteryVoltage = Readout.NotRead,
                ecus = emptyMap(),
                live = emptyList(),
                liveRunning = false,
                liveEcu = null,
                profile = ProfileState.None,
                scan = ScanUiState(),
                rawLog = emptyList(),
                selectedModuleKey = null,
                connectedProfile = null,
                setup = emptyList(),
                attempts = emptyList(),
                voltageHistory = emptyList(),
                dtcs = emptyMap(),
                reconnects = emptyList(),
                terminal = emptyList(),
                terminalBusy = false,
                freezeFrames = Readout.NotRead,
                readiness = Readout.NotRead,
                obdClear = null,
                ecuDetail = null,
            )
        }
        refreshBluetooth()
    }

    fun dismissError() {
        _state.update { if (it.connection is ConnectionState.Failed) it.copy(connection = ConnectionState.Idle) else it }
    }

    // ---- Connection settings ----------------------------------------------------------------------

    private fun refreshSettings() {
        _state.update {
            it.copy(
                settings = ConnectionSettingsState(
                    profiles = connectionStore.profiles(),
                    activeProfileId = connectionStore.activeProfileId(),
                    remembered = connectionStore.remembered(),
                    expertMode = connectionStore.expertModeEnabled,
                ),
            )
        }
    }

    fun openSettings() {
        pauseLiveData()
        refreshSettings()
        _state.update { if (it.screen == Screen.SETTINGS) it else it.copy(screen = Screen.SETTINGS, settingsReturn = it.screen) }
    }

    fun closeSettings() {
        _state.update { it.copy(screen = it.settingsReturn, editingProfile = null) }
    }

    /** Takes effect on the next connection (or reconnect). */
    fun selectProfile(id: String) {
        runCatching { connectionStore.setActiveProfile(id) }.onFailure { appendLog("Profile: ${it.message}") }
        refreshSettings()
    }

    fun newProfile() {
        val base = connectionStore.activeProfile()
        val profile = base.copy(id = "profile-${System.currentTimeMillis()}", name = "${base.name} (copy)")
        _state.update { it.copy(screen = Screen.PROFILE_EDIT, editingProfile = profile) }
    }

    fun editProfile(id: String) {
        val profile = connectionStore.profile(id) ?: return
        _state.update { it.copy(screen = Screen.PROFILE_EDIT, editingProfile = profile) }
    }

    fun saveProfile(profile: ConnectionProfile) {
        connectionStore.saveProfile(profile)
        connectionStore.setActiveProfile(profile.id)
        appendLog("Saved connection profile \"${profile.name}\"")
        refreshSettings()
        _state.update { it.copy(screen = Screen.SETTINGS, editingProfile = null) }
    }

    fun cancelProfileEdit() {
        _state.update { it.copy(screen = Screen.SETTINGS, editingProfile = null) }
    }

    fun deleteProfile(id: String) {
        runCatching { connectionStore.deleteProfile(id) }.onFailure { appendLog("Profile: ${it.message}") }
        refreshSettings()
    }

    fun forgetProtocol(address: String) {
        connectionStore.forgetProtocol(address)
        refreshSettings()
    }

    fun setExpertMode(enabled: Boolean, riskAcknowledged: Boolean = false) {
        if (enabled) {
            if (!riskAcknowledged) return
            connectionStore.enableExpertMode(riskAcknowledged = true)
            appendLog("Expert mode enabled")
        } else {
            connectionStore.disableExpertMode()
            appendLog("Expert mode disabled")
            if (_state.value.screen == Screen.TERMINAL) closeTerminal()
            actuators.stop()
        }
        refreshSettings()
    }

    // ---- Expert terminal --------------------------------------------------------------------------

    fun openTerminal() {
        val currentDriver = driver ?: return
        if (!connectionStore.expertModeEnabled || scanJob?.isActive == true || coding.state.value.busy || actuators.state.value.running) return
        pauseLiveData()
        // The terminal talks to the adapter directly (not through the session's routing) and every
        // diagnostic request still passes the read-only policy.
        terminal = RawTerminal(currentDriver, session, expertMode = { connectionStore.expertModeEnabled })
        _state.update { it.copy(screen = Screen.TERMINAL, terminal = terminal?.transcript.orEmpty()) }
    }

    fun sendTerminal(input: String) {
        val current = terminal ?: return
        if (input.isBlank() || terminalJob?.isActive == true) return
        terminalJob = viewModelScope.launch {
            _state.update { it.copy(terminalBusy = true) }
            try {
                current.send(input)
            } finally {
                _state.update { it.copy(terminalBusy = false, terminal = current.transcript) }
            }
        }
    }

    fun clearTerminal() {
        terminal?.clear()
        _state.update { it.copy(terminal = emptyList()) }
    }

    fun terminalText(): String = terminal?.text().orEmpty()

    /** Restores the adapter settings the app depends on, then returns to the dashboard. */
    fun closeTerminal() {
        val current = terminal
        terminalJob?.cancel()
        _state.update { it.copy(screen = Screen.DASHBOARD, terminalBusy = false) }
        if (current != null) {
            viewModelScope.launch {
                readOrNull { current.restore() }
                appendLog("Terminal closed, adapter settings restored")
            }
        }
    }

    // ---- Logs and report --------------------------------------------------------------------------

    fun logText(): String = _log.value.joinToString("\n")

    fun clearLog() {
        _log.value = emptyList()
    }

    /** Clears the raw log shown on screen. The log saved with the vehicle profile is kept. */
    fun clearRawLog() {
        _state.update { it.copy(rawLog = emptyList()) }
    }

    fun attemptsText(): String = _state.value.attempts.joinToString("\n") { a ->
        "${a.selectCommand} (${a.label}): ${if (a.success) "OK" else "failed"} – ${a.result}" + (a.atdp?.let { " · ATDP $it" } ?: "")
    }

    /** Writes the diagnostic report to the cache and returns the file for the share sheet. */
    suspend fun writeReport(): File {
        val state = _state.value
        val now = System.currentTimeMillis()
        val vin = (state.vin as? Readout.Value)?.value?.takeIf(VinRules::isValid)
        val app = getApplication<Application>()
        val version = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val data = ReportData(
            generatedAtMs = now,
            appVersion = version,
            deviceName = state.connection.device?.name,
            deviceAddress = state.connection.device?.address,
            adapter = state.adapter,
            profile = state.connectedProfile ?: state.settings.activeProfile,
            setup = state.setup,
            attempts = state.attempts,
            protocol = (state.protocol as? Readout.Value)?.value?.description,
            vin = (state.vin as? Readout.Value)?.value,
            vehicle = state.savedProfile?.description?.title,
            ecus = state.ecus,
            voltage = state.voltageHistory,
            dtcs = state.dtcs.values.mapNotNull { (it as? Readout.Value)?.value },
            modules = state.modules.map { "${it.address.display} · ${it.displayName}" },
            reconnects = state.reconnects,
            adapterLog = _log.value,
            rawLog = state.rawLog,
            terminal = terminal?.text(),
        )
        val text = DiagnosticReport.build(data)
        return withContext(Dispatchers.IO) { ReportSharer.write(app, DiagnosticReport.fileName(now, vin), text) }
    }

    override fun onCleared() {
        disconnectInternal()
        super.onCleared()
    }

    /** Creates the session for the negotiated protocol and reads VIN and supported PIDs. */
    private suspend fun applyNegotiation(negotiation: NegotiationResult) {
        val currentRecorder = recorder ?: return
        val protocolInfo = negotiation.protocol
        _state.update { it.copy(attempts = negotiation.attempts, dtcs = emptyMap()) }
        if (protocolInfo == null) {
            val tried = negotiation.attempts.joinToString(", ") { it.selectCommand }.ifEmpty { "none" }
            session = null
            reader = null
            _state.update {
                it.copy(
                    protocol = Readout.Unavailable("No protocol answered (tried $tried)"),
                    vin = Readout.Unavailable("Vehicle not detected"),
                    ecus = emptyMap(),
                )
            }
            return
        }

        // Requests go through the recorder so module scans can keep a raw request/response log.
        val options = (_state.value.connectedProfile ?: ConnectionProfile.DEFAULT).sessionOptions()
        val newSession = Session(currentRecorder, protocolInfo.protocol, options)
        val newReader = VehicleReader(newSession, currentRecorder)
        session = newSession
        reader = newReader
        _state.update { it.copy(protocol = Readout.Value(protocolInfo), vin = Readout.Loading) }

        val vin = readOrNull { newReader.readVin() }
        _state.update { it.copy(vin = vin?.let { v -> Readout.Value(v) } ?: Readout.Unavailable("Not reported by vehicle")) }
        if (VinRules.isValid(vin)) loadProfile(vin!!, protocolInfo.protocol.displayName)

        val ecus = readOrNull { newReader.supportedPids() }.orEmpty()
        _state.update { it.copy(ecus = ecus) }
    }

    // ---- Link loss and reconnect ------------------------------------------------------------------

    private fun watchLink(device: PairedAdapter, currentDriver: AdapterDriver) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            val error = currentDriver.linkLost.filterNotNull().first()
            if (driver === currentDriver) onLinkLost(device, error.message ?: "Bluetooth link lost")
        }
    }

    /**
     * The adapter went away (out of range, unplugged, ignition cut its power). Stops everything that
     * talks to it and reconnects with the policy's back-off; the remembered protocol is tried first.
     */
    private fun onLinkLost(device: PairedAdapter, reason: String) {
        val wasLive = _state.value.screen == Screen.LIVE && _state.value.liveRunning
        appendLog("Connection lost: $reason")
        addReconnectNote("Link lost: $reason")
        scanJob?.cancel()
        liveJob?.cancel()
        infoJob?.cancel()
        dtcJob?.cancel()
        ecuJob?.cancel()
        terminalJob?.cancel()
        voltageJob?.cancel()
        if (liveRecorder.isRecording) stopRecording()
        val lost = ClearState.Done("Connection lost while clearing. Read the fault codes again to see whether they were cleared.", success = false)
        _state.update { s ->
            s.copy(
                obdClear = if (s.obdClear == ClearState.Running) lost else s.obdClear,
                ecuDetail = s.ecuDetail?.let { d ->
                    d.copy(
                        identificationLoading = false,
                        dtcs = if (d.dtcs == Readout.Loading) Readout.Unavailable("Connection lost") else d.dtcs,
                        records = d.records.mapValues { (_, r) -> if (r == Readout.Loading) Readout.Unavailable("Connection lost") else r },
                        clear = if (d.clear == ClearState.Running) lost else d.clear,
                    )
                },
                freezeFrames = if (s.freezeFrames == Readout.Loading) Readout.Unavailable("Connection lost") else s.freezeFrames,
                readiness = if (s.readiness == Readout.Loading) Readout.Unavailable("Connection lost") else s.readiness,
            )
        }
        driver?.close()
        driver = null
        session = null
        reader = null
        terminal = null
        _state.update {
            it.copy(
                liveRunning = false,
                terminalBusy = false,
                screen = if (it.screen == Screen.TERMINAL) Screen.DASHBOARD else it.screen,
                connection = ConnectionState.Reconnecting(device, 1, reconnectPolicy.maxAttempts, reason),
            )
        }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            try {
                val result = reconnectPolicy.retry(
                    onAttempt = { attempt, waitMs ->
                        appendLog("Reconnect attempt $attempt/${reconnectPolicy.maxAttempts} in ${waitMs / 1000} s")
                        _state.update { it.copy(connection = ConnectionState.Reconnecting(device, attempt, reconnectPolicy.maxAttempts, reason)) }
                    },
                    onFailure = { attempt, error -> addReconnectNote("Attempt $attempt failed: ${error.message}") },
                ) { openConnection(device) }
                addReconnectNote("Reconnected (${result.negotiation.protocol?.description ?: "no protocol"})")
                onConnected(device, result)
                if (wasLive && _state.value.screen == Screen.LIVE) openLiveData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addReconnectNote("Gave up after ${reconnectPolicy.maxAttempts} attempts: ${e.message}")
                appendLog("Reconnect failed: ${e.message}")
                _state.update {
                    it.copy(
                        screen = Screen.DEVICES,
                        connection = ConnectionState.Failed(
                            device,
                            "Connection lost ($reason) and ${reconnectPolicy.maxAttempts} reconnect attempts failed. " +
                                "Check the adapter is plugged in and powered, then connect again.",
                        ),
                    )
                }
            }
        }
    }

    private fun addReconnectNote(note: String) {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        _state.update { it.copy(reconnects = (it.reconnects + "$time  $note").takeLast(50)) }
    }

    // ---- Battery voltage --------------------------------------------------------------------------

    /** Samples `ATRV` periodically; paused during scans and while the expert terminal is open. */
    private fun startVoltageMonitor() {
        voltageJob?.cancel()
        voltageJob = viewModelScope.launch {
            while (isActive) {
                val busy = scanJob?.isActive == true || _state.value.screen == Screen.TERMINAL || actuators.state.value.running || coding.state.value.busy
                if (!busy) sampleVoltage()
                delay(VOLTAGE_INTERVAL_MS)
            }
        }
    }

    /** Battery voltage as measured by the adapter (`ATRV`); works even when the ignition is off. */
    private suspend fun sampleVoltage() {
        val monitor = voltageMonitor ?: return
        val sample = readOrNull { monitor.sample() }
        _state.update {
            it.copy(
                batteryVoltage = sample?.let { s -> Readout.Value(s.volts) } ?: if (it.batteryVoltage is Readout.Value) it.batteryVoltage else Readout.Unavailable("No reading"),
                voltageHistory = monitor.history,
            )
        }
    }

    // ---- OBD fault codes --------------------------------------------------------------------------

    fun openDtcs() {
        pauseLiveData()
        _state.update { it.copy(screen = Screen.DTCS) }
        if (_state.value.dtcs.isEmpty()) readDtcs()
    }

    /** Reads stored (03), pending (07) and permanent (0A) codes, then readiness (01 01) and freeze frame (02). */
    fun readDtcs() {
        val currentSession = session ?: return
        if (dtcJob?.isActive == true || scanJob?.isActive == true) return
        dtcJob = viewModelScope.launch {
            _state.update {
                it.copy(dtcs = DtcKind.entries.associateWith { Readout.Loading }, readiness = Readout.Loading, freezeFrames = Readout.Loading)
            }
            val reader = ObdDtcReader(currentSession)
            for (kind in DtcKind.entries) {
                val value: Readout<DtcReport> = readout("${kind.label} codes") { reader.read(kind) }
                _state.update { it.copy(dtcs = it.dtcs + (kind to value)) }
            }
            val readiness = readout("Readiness") { ReadinessDecoder.read(currentSession) }
            _state.update { it.copy(readiness = readiness) }
            val frames = readout("Freeze frame") { FreezeFrameReader(currentSession).read() }
            _state.update { it.copy(freezeFrames = frames) }
        }
    }

    private suspend fun <T> readout(what: String, block: suspend () -> T): Readout<T> = try {
        Readout.Value(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        appendLog("$what: ${e.message}")
        Readout.Unavailable(e.message ?: "Read failed")
    }

    /**
     * Clears emission-related fault codes (service 04) on all OBD ECUs. Called only from the confirmation
     * dialog: the one-shot [ClearConfirmation] created here is what lets the Session send the request.
     * Refused while the engine is running (most ECUs reject it anyway, with NRC 22).
     */
    fun clearObdDtcs() {
        val currentSession = session ?: return
        val currentReader = reader ?: return
        if (dtcJob?.isActive == true || scanJob?.isActive == true) return
        pauseLiveData()
        dtcJob = viewModelScope.launch {
            _state.update { it.copy(obdClear = ClearState.Running) }
            val result = try {
                val rpm = readOrNull { currentReader.readPid(Pids.ENGINE_RPM) }?.value
                if (rpm != null && rpm > ENGINE_RUNNING_RPM) {
                    ClearState.Done("Engine running (%.0f rpm). Switch the engine off, leave the ignition on and try again.".format(rpm), false)
                } else {
                    appendLog("Clearing OBD fault codes (service 04), confirmed by the user")
                    val outcome = ObdDtcReader(currentSession).clear(ClearConfirmation.obdClear())
                    val refused = outcome.refused.entries.joinToString("; ") { (ecu, nrc) -> "ECU $ecu refused: ${Nrc.describe(nrc)}" }
                    when {
                        outcome.noResponse -> ClearState.Done("No ECU answered. Check that the ignition is on.", false)
                        outcome.cleared.isEmpty() -> ClearState.Done(refused, false)
                        else -> ClearState.Done(
                            "Cleared by ECU ${outcome.cleared.joinToString(", ")}." + if (refused.isNotEmpty()) " $refused" else "",
                            outcome.refused.isEmpty(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClearState.Done("Clear failed: ${e.message}", false)
            }
            appendLog("OBD clear: ${result.message}")
            _state.update { it.copy(obdClear = result) }
        }
        dtcJob?.invokeOnCompletion { cause -> if (cause == null) viewModelScope.launch { readDtcs() } }
    }

    fun dismissClearResult() {
        _state.update { it.copy(obdClear = null, ecuDetail = it.ecuDetail?.copy(clear = null)) }
    }

    /** Saves (or with blank [text] removes) the user's own description of a fault code. */
    fun setDtcDescription(code: String, text: String) {
        runCatching { dataStore.setDtcDescription(code, text) }.onFailure { appendLog("Description: ${it.message}") }
        refreshData()
    }

    // ---- Vehicle profile -------------------------------------------------------------------------

    /** Loads the saved profile for [vin] (or creates one). Runs alongside the OBD reads: vPIC may be slow. */
    private fun loadProfile(vin: String, protocolName: String) {
        profileJob?.cancel()
        _state.update { it.copy(profile = ProfileState.Loading) }
        profileJob = viewModelScope.launch {
            try {
                val result = profiles.onConnected(vin, protocolName)
                val log = profiles.rawLog(vin)
                appendLog(if (result.isNew) "New vehicle profile for $vin" else "Loaded saved profile for $vin (${result.profile.modules.size} modules)")
                _state.update {
                    it.copy(
                        profile = ProfileState.Loaded(result.profile, result.isNew),
                        rawLog = if (it.scan.reports.isEmpty()) log else it.rawLog,
                    )
                }
                refreshNaming()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Profile load failed: ${e.message}")
                _state.update { it.copy(profile = ProfileState.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    // ---- Module scan ------------------------------------------------------------------------------

    fun openScan() {
        pauseLiveData()
        _state.update { it.copy(screen = Screen.SCAN) }
    }

    fun setScanOtherBus(include: Boolean) {
        _state.update { if (it.scan.running) it else it.copy(scan = it.scan.copy(includeOtherBus = include)) }
    }

    fun setScanListed(include: Boolean) {
        _state.update { if (it.scan.running) it else it.copy(scan = it.scan.copy(includeListed = include)) }
    }

    fun setQuickScan(quick: Boolean) {
        _state.update { if (it.scan.running) it else it.copy(scan = it.scan.copy(quickScan = quick)) }
    }

    fun startScan() {
        val currentSession = session ?: return
        val currentRecorder = recorder ?: return
        if (scanJob?.isActive == true || !_state.value.canScan) return
        liveJob?.cancel()
        val includeOtherBus = _state.value.scan.includeOtherBus
        val includeListed = _state.value.scan.includeListed
        val quickScan = _state.value.scan.quickScan
        val listed = if (includeListed || quickScan) _state.value.listedModules else emptyList()
        val options = ScanOptions(includeOtherBus = includeOtherBus, listedModules = listed, rangeScan = !(quickScan && listed.isNotEmpty()))
        val protocolName = currentSession.protocol.displayName

        scanJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.SCAN,
                    liveRunning = false,
                    scan = ScanUiState(
                        running = true, includeOtherBus = includeOtherBus, includeListed = includeListed, quickScan = quickScan,
                        startedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            appendLog(
                "Module scan started" + (if (!options.rangeScan) " (quick: listed ECUs only)" else "") +
                    (if (listed.isNotEmpty()) ", ${listed.size} listed ECUs" else ""),
            )
            currentRecorder.startRecording()
            val listener = object : ScanListener {
                override fun onProgress(progress: ScanProgress) {
                    _state.update { it.copy(scan = it.scan.copy(progress = progress)) }
                }

                override fun onModuleFound(module: DiscoveredModule) {
                    appendLog("Module found: ${module.address.display} (${module.detectedBy.label})")
                    _state.update { it.copy(scan = it.scan.copy(found = it.scan.found + module)) }
                }

                override fun onModuleRead(report: ModuleReport) {
                    _state.update { it.copy(scan = it.scan.copy(reports = it.scan.reports + report)) }
                }
            }
            try {
                val reports = ScanCoordinator(currentSession, currentRecorder).run(options, listener)
                val log = currentRecorder.stopRecording()
                appendLog("Module scan finished: ${reports.size} modules, ${log.size} requests")
                saveScanResult(reports, log, protocolName)
            } catch (e: CancellationException) {
                val log = currentRecorder.stopRecording()
                withContext(NonCancellable) {
                    appendLog("Module scan cancelled")
                    _state.update {
                        it.copy(
                            rawLog = log,
                            scan = it.scan.copy(
                                running = false, cancelled = true, finishedAtMs = System.currentTimeMillis(),
                                notSavedReason = "Scan cancelled: partial results are shown but not saved",
                            ),
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                val log = currentRecorder.stopRecording()
                appendLog("Module scan failed: ${e.message}")
                _state.update {
                    it.copy(
                        rawLog = log,
                        scan = it.scan.copy(
                            running = false, error = e.message ?: e.javaClass.simpleName, finishedAtMs = System.currentTimeMillis(),
                            notSavedReason = "Scan failed: partial results are shown but not saved",
                        ),
                    )
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    private suspend fun saveScanResult(reports: List<ModuleReport>, log: List<RawExchange>, protocolName: String) {
        val obdVin = (_state.value.vin as? Readout.Value)?.value?.takeIf(VinRules::isValid)
        val vin = obdVin ?: VinRules.fromModules(reports)
        val finished = System.currentTimeMillis()
        if (vin == null) {
            _state.update {
                it.copy(
                    rawLog = log,
                    scan = it.scan.copy(running = false, reports = reports, finishedAtMs = finished, notSavedReason = "No VIN reported: results are not saved"),
                )
            }
            return
        }
        try {
            profileJob?.join()
            if (_state.value.savedProfile?.vin != vin) profiles.onConnected(vin, protocolName)
            val profile = profiles.saveScan(vin, protocolName, reports, log)
            val previous = _state.value.profile
            _state.update {
                it.copy(
                    vin = if (obdVin == null) Readout.Value(vin) else it.vin,
                    profile = ProfileState.Loaded(profile, isNew = (previous as? ProfileState.Loaded)?.isNew ?: true),
                    rawLog = log,
                    scan = it.scan.copy(running = false, reports = reports, finishedAtMs = finished, saved = true),
                )
            }
            refreshNaming()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appendLog("Saving profile failed: ${e.message}")
            _state.update {
                it.copy(
                    rawLog = log,
                    scan = it.scan.copy(running = false, reports = reports, finishedAtMs = finished, notSavedReason = "Could not save: ${e.message}"),
                )
            }
        }
    }

    // ---- Data packs: vehicles, ECUs, custom PIDs ------------------------------------------------------

    private fun refreshData() {
        val vehicles = runCatching { repository.vehicles() }.getOrElse { appendLog("Vehicles: ${it.message}"); emptyList() }
        val selectedKey = dataStore.selectedVehicleKey
        val userPack = dataStore.userPack()
        _state.update {
            it.copy(
                data = it.data.copy(
                    vehicles = vehicles,
                    selectedVehicle = vehicles.firstOrNull { v -> v.vehicle.key == selectedKey },
                    customPids = repository.customPids(),
                    userPidIds = userPack.pids.map { p -> p.id }.toSet(),
                    descriptions = repository.dtcDescriptions(),
                    packErrors = bundledPacks.errors,
                ),
            )
        }
        refreshNaming()
    }

    /**
     * Rebuilds the module namer and the scan's listed ECUs from the loaded profile's extras, its
     * data-pack vehicle (or the globally selected one) and the packs' address names.
     */
    private fun refreshNaming() {
        val current = _state.value
        val vin = current.savedProfile?.vin
        val extras = vin?.let { runCatching { extrasStore.get(it) }.getOrNull() } ?: ProfileExtras()
        val vehicle = (extras.vehicleKey?.let { key -> current.data.vehicles.firstOrNull { it.vehicle.key == key } } ?: current.data.selectedVehicle)?.vehicle
        val namer = ModuleNamer(
            userNames = extras.moduleNames,
            manualEcus = extras.manualEcus,
            vehicleEcus = vehicle?.ecus.orEmpty(),
            vehicleTitle = vehicle?.title,
            packNames = runCatching { repository.moduleNames() }.getOrElse { emptyList() },
            make = current.savedProfile?.description?.make,
        )
        val listed = (extras.manualEcus + vehicle?.ecus.orEmpty()).map { it.moduleAddress() }.distinctBy { it.requestHeader }
        _state.update { it.copy(profileExtras = extras, moduleNamer = namer, listedModules = listed) }
    }

    fun openVehicles() {
        pauseLiveData()
        refreshData()
        _state.update {
            val back = if (it.screen == Screen.DEVICES || it.screen == Screen.DASHBOARD) it.screen else it.vehiclesReturn
            it.copy(screen = Screen.VEHICLES, vehiclesReturn = back)
        }
    }

    fun closeVehicles() {
        _state.update { it.copy(screen = if (it.vehiclesReturn == Screen.DASHBOARD && it.connection is ConnectionState.Idle) Screen.DEVICES else it.vehiclesReturn) }
    }

    fun selectVehicle(key: String?) {
        dataStore.selectedVehicleKey = key
        refreshData()
    }

    /** Opens the editor for an existing vehicle, or for a new one when [key] is null. */
    fun editVehicle(key: String?) {
        val existing = key?.let { repository.vehicle(it)?.vehicle }
        val vehicle = existing ?: VehicleDefinition(make = "", model = "", ecus = listOf(EcuDefinition(address = "01", name = "Engine", requestId = "7E0", responseId = "7E8")))
        _state.update { it.copy(screen = Screen.VEHICLE_EDIT, data = it.data.copy(editingVehicle = vehicle, editingVehicleKey = existing?.key, vehicleError = null)) }
    }

    fun saveVehicle(vehicle: VehicleDefinition) {
        val originalKey = _state.value.data.editingVehicleKey
        val cleaned = vehicle.copy(
            make = vehicle.make.trim(),
            model = vehicle.model.trim(),
            years = vehicle.years?.trim()?.ifEmpty { null },
            ecus = vehicle.ecus.map { e ->
                e.copy(
                    address = e.address?.trim()?.uppercase()?.ifEmpty { null },
                    name = e.name.trim(),
                    requestId = e.requestId.trim().uppercase(),
                    responseId = e.responseId.trim().uppercase(),
                )
            },
        )
        val error = runCatching {
            if (originalKey == null && repository.vehicle(cleaned.key) != null) {
                throw IllegalArgumentException("${cleaned.title} already exists. Edit that vehicle instead.")
            }
            dataStore.saveVehicle(cleaned)
            // Renamed a user vehicle: remove the old entry (bundled vehicles cannot be removed).
            if (originalKey != null && originalKey != cleaned.key && repository.bundledVehicle(originalKey) == null) {
                dataStore.deleteVehicle(originalKey)
            }
        }.exceptionOrNull()
        if (error != null) {
            _state.update { it.copy(data = it.data.copy(vehicleError = error.message)) }
            return
        }
        appendLog("Saved vehicle ${cleaned.title} (${cleaned.ecus.size} ECUs)")
        if (originalKey == null || dataStore.selectedVehicleKey == originalKey) dataStore.selectedVehicleKey = cleaned.key
        _state.update { it.copy(screen = Screen.VEHICLES, data = it.data.copy(editingVehicle = null, editingVehicleKey = null, vehicleError = null)) }
        refreshData()
    }

    fun cancelVehicleEdit() {
        _state.update { it.copy(screen = Screen.VEHICLES, data = it.data.copy(editingVehicle = null, editingVehicleKey = null, vehicleError = null)) }
    }

    /** Deletes a user vehicle, or for a bundled vehicle removes the user's changes. */
    fun deleteVehicle(key: String) {
        val entry = repository.vehicle(key) ?: return
        if (entry.source == VehicleSource.BUNDLED) return
        dataStore.deleteVehicle(key)
        if (entry.source == VehicleSource.USER && dataStore.selectedVehicleKey == key) dataStore.selectedVehicleKey = null
        appendLog(if (entry.source == VehicleSource.USER) "Deleted vehicle ${entry.vehicle.title}" else "Restored bundled ECU list of ${entry.vehicle.title}")
        _state.update { it.copy(screen = Screen.VEHICLES, data = it.data.copy(editingVehicle = null, editingVehicleKey = null)) }
        refreshData()
    }

    /** Opens an ECU of the selected vehicle and reads its identification and fault codes. */
    fun openEcu(ecu: EcuDefinition) {
        pauseLiveData()
        _state.update { it.copy(screen = Screen.ECU_DETAIL, ecuDetail = EcuDetailState(ecu)) }
        readEcu()
    }

    fun closeEcu() {
        ecuJob?.cancel()
        _state.update { it.copy(screen = Screen.VEHICLES, ecuDetail = null) }
    }

    private fun updateEcu(change: (EcuDetailState) -> EcuDetailState) {
        _state.update { s -> s.ecuDetail?.let { s.copy(ecuDetail = change(it)) } ?: s }
    }

    /** Only one bus user at a time: scans, ECU reads, actuator tests and coding writes exclude each other. */
    private fun canUseBus(): Boolean =
        session != null && scanJob?.isActive != true && ecuJob?.isActive != true && !actuators.state.value.running && !coding.state.value.busy

    fun readEcu() {
        val currentSession = session ?: return
        val ecu = _state.value.ecuDetail?.ecu ?: return
        if (!canUseBus()) return
        val workbench = EcuWorkbench(currentSession)
        ecuJob = viewModelScope.launch {
            updateEcu { d -> d.copy(identification = emptyList(), identificationLoading = true, dtcs = Readout.Loading, records = emptyMap()) }
            try {
                workbench.readIdentification(ecu) { reading ->
                    updateEcu { d -> d.copy(identification = d.identification + reading) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("${ecu.display} identification: ${e.message}")
            }
            updateEcu { d -> d.copy(identificationLoading = false) }
            val dtcs = readout("${ecu.display} fault codes") { workbench.readDtcs(ecu) }
            updateEcu { d -> d.copy(dtcs = dtcs) }
        }
    }

    fun readEcuRecords(code: String) {
        val currentSession = session ?: return
        val detail = _state.value.ecuDetail ?: return
        val record = ((detail.dtcs as? Readout.Value)?.value as? com.bod3.app.obd.uds.DtcReadResult.Read)?.records?.firstOrNull { it.code == code } ?: return
        if (!canUseBus() || detail.ecu.protocol != EcuProtocol.UDS) return
        ecuJob = viewModelScope.launch {
            updateEcu { d -> d.copy(records = d.records + (code to Readout.Loading)) }
            val records = readout("$code records") { EcuWorkbench(currentSession).readRecords(detail.ecu, record) }
            updateEcu { d -> d.copy(records = d.records + (code to records)) }
        }
    }

    /** Clears this ECU's fault memory (`14 FF FF FF`). Called only from the confirmation dialog. */
    fun clearEcuDtcs() {
        val currentSession = session ?: return
        val ecu = _state.value.ecuDetail?.ecu ?: return
        if (!canUseBus() || ecu.protocol != EcuProtocol.UDS) return
        ecuJob = viewModelScope.launch {
            updateEcu { d -> d.copy(clear = ClearState.Running) }
            appendLog("Clearing fault memory of ${ecu.display} (${ecu.requestId}), confirmed by the user")
            val result = try {
                when (val outcome = EcuWorkbench(currentSession).clear(ecu, ClearConfirmation.udsClear(ecu.target))) {
                    UdsClearResult.Cleared -> ClearState.Done("Fault memory cleared.", true)
                    else -> ClearState.Done(outcome.display, false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClearState.Done("Clear failed: ${e.message}", false)
            }
            appendLog("${ecu.display} clear: ${result.message}")
            updateEcu { d -> d.copy(clear = result) }
            // Read the codes again to show what is left.
            val dtcs = readout("${ecu.display} fault codes") { EcuWorkbench(currentSession).readDtcs(ecu) }
            updateEcu { d -> d.copy(dtcs = dtcs, records = emptyMap()) }
        }
    }

    fun openCustomPids() {
        pauseLiveData()
        refreshData()
        _state.update { it.copy(screen = Screen.CUSTOM_PIDS) }
    }

    fun closeCustomPids() {
        _state.update { it.copy(screen = if (it.connection is ConnectionState.Idle) Screen.DEVICES else Screen.DASHBOARD) }
    }

    fun editPid(id: String?) {
        val existing = id?.let { key -> repository.customPids().firstOrNull { it.id == key } }
        val pid = existing ?: CustomPid(id = "", name = "", request = "01", formula = "A", unit = "")
        _state.update { it.copy(screen = Screen.PID_EDIT, data = it.data.copy(editingPid = pid, editingPidId = existing?.id, pidError = null, pidTest = null)) }
    }

    fun savePid(pid: CustomPid) {
        val originalId = _state.value.data.editingPidId
        val error = runCatching {
            if (pid.id != originalId && repository.customPids().any { it.id == pid.id } && !repository.isUserPid(pid.id)) {
                // Saving under a bundled id deliberately overrides that bundled PID.
                appendLog("Custom PID ${pid.id} overrides the bundled definition")
            }
            dataStore.savePid(pid)
            if (originalId != null && originalId != pid.id && repository.isUserPid(originalId)) dataStore.deletePid(originalId)
        }.exceptionOrNull()
        if (error != null) {
            _state.update { it.copy(data = it.data.copy(pidError = error.message)) }
            return
        }
        appendLog("Saved custom PID ${pid.name} (${pid.request}: ${pid.formula})")
        _state.update { it.copy(screen = Screen.CUSTOM_PIDS, data = it.data.copy(editingPid = null, editingPidId = null, pidError = null, pidTest = null)) }
        refreshData()
    }

    fun deletePid(id: String) {
        if (!repository.isUserPid(id)) return
        dataStore.deletePid(id)
        _state.update { it.copy(screen = Screen.CUSTOM_PIDS, data = it.data.copy(editingPid = null, editingPidId = null)) }
        refreshData()
    }

    fun cancelPidEdit() {
        _state.update { it.copy(screen = Screen.CUSTOM_PIDS, data = it.data.copy(editingPid = null, editingPidId = null, pidError = null, pidTest = null)) }
    }

    /** Sends the PID's request once and shows the decoded value (only when connected and valid). */
    fun testPid(pid: CustomPid) {
        val currentSession = session ?: return
        val problems = pid.problems()
        if (problems.isNotEmpty()) {
            _state.update { it.copy(data = it.data.copy(pidTest = problems.joinToString("\n"))) }
            return
        }
        if (!canUseBus()) return
        ecuJob = viewModelScope.launch {
            _state.update { it.copy(data = it.data.copy(pidTest = "Reading…")) }
            val text = try {
                val response = currentSession.request(pid.target, pid.requestBytes)
                val value = response.messages.firstNotNullOfOrNull { pid.decode(it.payload) }
                val raw = response.messages.joinToString("; ") { m -> "${m.header}: ${m.payload.joinToString(" ") { "%02X".format(it) }}" }
                when {
                    value != null -> "${pid.format(value)} ${pid.unit}".trim() + "\nReply $raw"
                    response.messages.isEmpty() -> "No reply"
                    else -> "Reply could not be decoded with this formula\nReply $raw"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            _state.update { it.copy(data = it.data.copy(pidTest = text)) }
        }
    }

    // ---- Module screens ---------------------------------------------------------------------------

    fun openModules() {
        _state.update { it.copy(screen = Screen.MODULES) }
    }

    fun openModule(key: String) {
        _state.update { it.copy(screen = Screen.MODULE_DETAIL, selectedModuleKey = key) }
    }

    fun closeModuleDetail() {
        _state.update { it.copy(screen = Screen.MODULES, selectedModuleKey = null) }
    }

    fun backToDashboard() {
        _state.update { it.copy(screen = if (it.offline) Screen.GARAGE else Screen.DASHBOARD) }
    }

    // ---- Bidirectional (actuator) tests ------------------------------------------------------------

    private fun actuatorEnvironment(): ActuatorEnvironment? {
        val current = _state.value
        val activeSession = session ?: return null
        val executor = recorder ?: return null
        if (!current.isConnected || !activeSession.protocol.isCan) return null
        val description = current.savedProfile?.description
        val pack = current.data.selectedVehicle?.vehicle
        return ActuatorEnvironment(
            session = activeSession,
            probe = SessionStateProbe(activeSession, executor),
            vehicle = VehicleContext(
                make = description?.make ?: pack?.make,
                model = description?.model ?: pack?.model,
                vin = (current.vin as? Readout.Value)?.value,
            ),
        )
    }

    fun openActuators() {
        if (coding.state.value.busy) return
        pauseLiveData()
        actuators.refresh()
        _state.update { it.copy(screen = Screen.ACTUATORS) }
    }

    fun closeActuators() {
        actuators.close()
        _state.update { it.copy(screen = if (it.offline) Screen.DEVICES else Screen.DASHBOARD) }
    }

    fun openActuator(id: String) {
        actuators.select(id)
        _state.update { it.copy(screen = Screen.ACTUATOR_RUN) }
    }

    /** Leaving the run screen stops a running test (the stop request is sent at once). */
    fun closeActuator() {
        actuators.close()
        _state.update { it.copy(screen = Screen.ACTUATORS) }
    }

    /** Writes recipes (one, or all of the user's) as a JSON pack for the share sheet. */
    suspend fun writeActuatorExport(recipeId: String?): File {
        val json = actuators.exportJson(recipeId)
        val app = getApplication<Application>()
        val name = "bod3-actuators-${recipeId ?: "mine"}-${System.currentTimeMillis()}.json"
        return withContext(Dispatchers.IO) { ReportSharer.write(app, name, json) }
    }

    // ---- Coding ------------------------------------------------------------------------------------

    fun openCoding() {
        if (actuators.state.value.running || scanJob?.isActive == true) return
        pauseLiveData()
        coding.refresh()
        _state.update { it.copy(screen = Screen.CODING) }
    }

    /** Leaving is blocked while a read or write is running, so it always finishes cleanly. */
    fun closeCoding() {
        if (coding.state.value.busy) return
        coding.select(null)
        _state.update { it.copy(screen = if (it.offline) Screen.DEVICES else Screen.DASHBOARD) }
    }

    fun openCodingRecipe(id: String) {
        coding.select(id)
        _state.update { it.copy(screen = Screen.CODING_RUN) }
    }

    fun closeCodingRecipe() {
        if (coding.state.value.busy) return
        coding.select(null)
        coding.refresh()
        _state.update { it.copy(screen = Screen.CODING) }
    }

    fun openCodingEditor(id: String?) {
        if (coding.state.value.busy) return
        coding.openEditor(id)
        _state.update { it.copy(screen = Screen.CODING_EDITOR) }
    }

    fun codingEditorTemplate() {
        coding.openEditor(null)
    }

    fun saveCodingEditor() {
        val id = coding.saveEditor() ?: return
        coding.select(id)
        _state.update { it.copy(screen = Screen.CODING_RUN) }
    }

    fun closeCodingEditor() {
        val back = if (coding.state.value.selectedId != null) Screen.CODING_RUN else Screen.CODING
        coding.closeEditor()
        _state.update { it.copy(screen = back) }
    }

    fun deleteCodingRecipe(id: String) {
        coding.deleteUserRecipe(id)
        if (coding.state.value.selectedId == null) _state.update { it.copy(screen = Screen.CODING) }
    }

    suspend fun writeCodingExport(recipeId: String?): File {
        val json = coding.exportJson(recipeId)
        val app = getApplication<Application>()
        val name = "bod3-coding-${recipeId ?: "mine"}-${System.currentTimeMillis()}.json"
        return withContext(Dispatchers.IO) { ReportSharer.write(app, name, json) }
    }

    /** Writes the full audit log (actuator tests and coding writes) as text for the share sheet. */
    suspend fun writeAuditLog(): File {
        val text = actuators.auditText()
        val app = getApplication<Application>()
        return withContext(Dispatchers.IO) { ReportSharer.write(app, "bod3-audit-${System.currentTimeMillis()}.txt", text) }
    }

    // ---- Garage: saved vehicle profiles, export / import, manual ECUs, module names -------------------

    fun openGarage() {
        pauseLiveData()
        _state.update {
            val back = if (it.screen == Screen.DEVICES || it.screen == Screen.DASHBOARD) it.screen else it.garageReturn
            it.copy(screen = Screen.GARAGE, garageReturn = back, garage = it.garage.copy(message = null, importErrors = emptyList()))
        }
        loadGarage()
    }

    fun closeGarage() {
        _state.update { it.copy(screen = if (it.offline) Screen.DEVICES else Screen.DASHBOARD) }
    }

    private fun loadGarage() {
        _state.update { it.copy(garage = it.garage.copy(loading = true)) }
        viewModelScope.launch {
            val list = try {
                profiles.allProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Saved vehicles: ${e.message}")
                emptyList()
            }
            _state.update { it.copy(garage = it.garage.copy(profiles = list, loading = false)) }
        }
    }

    /** Opens a saved profile's modules and raw log. While connected only the connected vehicle can be opened. */
    fun openSavedProfile(vin: String) {
        val current = _state.value
        if (!current.offline && current.savedProfile?.vin != vin) {
            _state.update { it.copy(garage = it.garage.copy(message = "Disconnect first to browse another vehicle; the connected car's profile stays loaded while connected.")) }
            return
        }
        viewModelScope.launch {
            try {
                val profile = profiles.load(vin) ?: run {
                    _state.update { it.copy(garage = it.garage.copy(message = "Profile $vin no longer exists")) }
                    loadGarage()
                    return@launch
                }
                val log = profiles.rawLog(vin)
                _state.update {
                    it.copy(
                        profile = ProfileState.Loaded(profile, isNew = false),
                        rawLog = log,
                        scan = if (it.offline) ScanUiState(includeOtherBus = it.scan.includeOtherBus) else it.scan,
                        screen = Screen.MODULES,
                    )
                }
                refreshNaming()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(garage = it.garage.copy(message = "Could not open $vin: ${e.message}")) }
            }
        }
    }

    /** Writes [vin]'s profile as JSON to the cache for the share sheet. */
    suspend fun writeProfileExport(vin: String, includeRawLog: Boolean = true): File {
        val bundle = profiles.export(vin, includeRawLog) ?: throw IllegalStateException("No saved profile for $vin")
        val now = System.currentTimeMillis()
        val app = getApplication<Application>()
        val json = ProfileTransferCodec.encode(bundle, now)
        appendLog("Exported profile $vin (${bundle.modules.size} modules, ${bundle.rawLog.size} log entries)")
        return withContext(Dispatchers.IO) { ReportSharer.write(app, ProfileTransferCodec.fileName(vin, now), json) }
    }

    /** Validates and imports a profile file's text. Problems are listed; an existing VIN asks first. */
    fun importProfile(text: String) {
        val result = ProfileTransferCodec.decode(text)
        val bundle = result.bundle
        if (bundle == null) {
            _state.update { it.copy(garage = it.garage.copy(importErrors = result.errors.ifEmpty { listOf("Unknown problem") }, message = null)) }
            return
        }
        storeImport(bundle, overwrite = false)
    }

    fun confirmImportOverwrite() {
        val bundle = _state.value.garage.pendingOverwrite ?: return
        storeImport(bundle, overwrite = true)
    }

    fun cancelImportOverwrite() {
        _state.update { it.copy(garage = it.garage.copy(pendingOverwrite = null)) }
    }

    fun dismissGarageMessage() {
        _state.update { it.copy(garage = it.garage.copy(message = null, importErrors = emptyList())) }
    }

    private fun storeImport(bundle: com.bod3.app.profile.ProfileBundle, overwrite: Boolean) {
        viewModelScope.launch {
            try {
                val profile = profiles.import(bundle, overwrite)
                appendLog("Imported profile ${profile.vin} (${profile.modules.size} modules)")
                _state.update {
                    it.copy(
                        garage = it.garage.copy(pendingOverwrite = null, importErrors = emptyList(), message = "Imported ${profile.title} with ${profile.modules.size} modules"),
                        profile = if (it.savedProfile?.vin == profile.vin) ProfileState.Loaded(profile, false) else it.profile,
                    )
                }
                if (_state.value.savedProfile?.vin == profile.vin) refreshNaming()
                loadGarage()
            } catch (e: VehicleProfileService.ProfileExistsException) {
                _state.update { it.copy(garage = it.garage.copy(pendingOverwrite = bundle)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(garage = it.garage.copy(pendingOverwrite = null, importErrors = listOf("Import failed: ${e.message}"))) }
            }
        }
    }

    fun deleteSavedProfile(vin: String) {
        viewModelScope.launch {
            try {
                profiles.delete(vin)
                appendLog("Deleted profile $vin")
                _state.update {
                    it.copy(
                        garage = it.garage.copy(message = "Deleted $vin"),
                        profile = if (it.savedProfile?.vin == vin) ProfileState.None else it.profile,
                        rawLog = if (it.savedProfile?.vin == vin) emptyList() else it.rawLog,
                    )
                }
                refreshNaming()
                loadGarage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(garage = it.garage.copy(message = "Could not delete $vin: ${e.message}")) }
            }
        }
    }

    fun openProfileEcus() {
        if (_state.value.savedProfile == null) return
        refreshNaming()
        _state.update { it.copy(screen = Screen.PROFILE_ECUS) }
    }

    fun closeProfileEcus() {
        _state.update { it.copy(screen = Screen.MODULES) }
    }

    /** Applies [change] to the loaded profile's extras; returns the validation error, or null when saved. */
    private fun updateExtras(change: (ProfileExtras) -> ProfileExtras): String? {
        val vin = _state.value.savedProfile?.vin ?: return "No vehicle profile loaded"
        return try {
            extrasStore.update(vin, change)
            refreshNaming()
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Invalid entry"
        }
    }

    /** Adds or replaces a manual ECU; [originalKey] is the request ID being edited. Returns an error or null. */
    fun saveManualEcu(ecu: EcuDefinition, originalKey: String?): String? {
        val problems = ecu.problems()
        if (problems.isNotEmpty()) return problems.joinToString("\n")
        return updateExtras { it.withEcu(ecu, originalKey) }
    }

    fun deleteManualEcu(key: String) {
        updateExtras { it.withoutEcu(key) }?.let { appendLog("Manual ECUs: $it") }
    }

    /** Copies the data-pack vehicle's ECUs into the manual list (existing request IDs are kept). */
    fun copyPackEcusToProfile() {
        val current = _state.value
        val key = current.profileExtras.vehicleKey ?: current.data.selectedVehicle?.vehicle?.key
        val vehicle = current.data.vehicles.firstOrNull { it.vehicle.key == key }?.vehicle ?: return
        updateExtras { extras ->
            val existing = extras.manualEcus.map { it.key }.toSet()
            extras.copy(manualEcus = extras.manualEcus + vehicle.ecus.filterNot { it.key in existing })
        }?.let { appendLog("Manual ECUs: $it") }
    }

    /** Chooses the data-pack vehicle used to name this VIN's modules (null = the globally selected vehicle). */
    fun setProfileVehicle(key: String?) {
        updateExtras { it.copy(vehicleKey = key) }?.let { appendLog("Profile vehicle: $it") }
    }

    /** Sets (or with a blank name, removes) the user's name for a module of the loaded profile. */
    fun renameModule(requestId: String, name: String) {
        updateExtras { it.withName(requestId, name) }?.let { appendLog("Rename: $it") }
    }

    /** Plain-text raw log of the whole scan, or only the requests sent to [module]. */
    fun rawLogText(module: ModuleReport? = null): String {
        val state = _state.value
        val entries = if (module == null) state.rawLog else RawLogFormatter.forModule(state.rawLog, module.address.requestHeader)
        val vin = state.savedProfile?.vin ?: (state.vin as? Readout.Value)?.value ?: "unknown VIN"
        val title = state.savedProfile?.title ?: vin
        val heading = buildString {
            append("bod3 raw log · ").append(title)
            if (title != vin) append(" · ").append(vin)
            (state.protocol as? Readout.Value)?.value?.let { append(" · ").append(it.protocol.displayName) }
            if (module != null) append("\nModule ").append(module.displayName).append(" · ").append(module.address.display)
            append("\n").append(entries.size).append(" exchanges")
        }
        return RawLogFormatter.format(entries, heading)
    }

    private fun disconnectInternal() {
        if (liveRecorder.isRecording) stopRecording()
        ecuJob?.cancel()
        reconnectJob?.cancel()
        linkJob?.cancel()
        voltageJob?.cancel()
        dtcJob?.cancel()
        terminalJob?.cancel()
        terminal = null
        voltageMonitor = null
        adapterAddress = null
        scanJob?.cancel()
        profileJob?.cancel()
        liveJob?.cancel()
        infoJob?.cancel()
        connectJob?.cancel()
        driver?.close()
        driver = null
        recorder = null
        session = null
        reader = null
    }

    private fun setStep(device: PairedAdapter, step: String) {
        appendLog(step)
        // While reconnecting the banner keeps showing the attempt number; the steps go to the log.
        _state.update { if (it.connection is ConnectionState.Reconnecting) it else it.copy(connection = ConnectionState.Connecting(device, step)) }
    }

    private suspend fun <T> readOrNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        appendLog("Read failed: ${e.message}")
        null
    }

    private fun appendLog(line: String) {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val stamped = "$time  $line"
        _log.update { (it + stamped).takeLast(MAX_LOG_LINES) }
    }

    private companion object {
        const val LIVE_PAUSE_MS = 250L
        const val ENGINE_RUNNING_RPM = 300.0
        const val VOLTAGE_INTERVAL_MS = 5_000L
        const val MAX_LOG_LINES = 300
    }
}
