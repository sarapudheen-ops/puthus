package com.bod3.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bod3.app.bluetooth.BluetoothRepository
import com.bod3.app.bluetooth.PairedAdapter
import com.bod3.app.obd.PidReading
import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.elm.AdapterDriver
import com.bod3.app.obd.elm.AdapterInfo
import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.elm.RecordingExecutor
import com.bod3.app.obd.elm.VoltageParser
import com.bod3.app.obd.pid.PidDefinition
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.scan.DiscoveredModule
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.scan.ScanCoordinator
import com.bod3.app.obd.scan.ScanListener
import com.bod3.app.obd.scan.ScanOptions
import com.bod3.app.obd.scan.ScanProgress
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ProtocolDetector
import com.bod3.app.obd.session.ProtocolInfo
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.transport.BluetoothSppTransport
import com.bod3.app.profile.RawLogFormatter
import com.bod3.app.profile.VehicleProfile
import com.bod3.app.profile.VehicleProfileService
import com.bod3.app.profile.VinRules
import com.bod3.app.profile.VpicClient
import com.bod3.app.profile.db.Bod3Database
import com.bod3.app.profile.db.RoomProfileRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { DEVICES, DASHBOARD, LIVE, SCAN, MODULES, MODULE_DETAIL }

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val device: PairedAdapter, val step: String) : ConnectionState
    data class Connected(val device: PairedAdapter) : ConnectionState
    data class Failed(val device: PairedAdapter, val message: String) : ConnectionState
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

data class LiveItem(val definition: PidDefinition, val reading: PidReading? = null, val status: String? = null)

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
) {
    val savedProfile: VehicleProfile? get() = (profile as? ProfileState.Loaded)?.profile

    /** Modules to list: an unsaved scan result if there is one, otherwise the saved profile's modules. */
    val modules: List<ModuleReport>
        get() = scan.reports.takeIf { !scan.saved && it.isNotEmpty() } ?: savedProfile?.modules.orEmpty()

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

    private var driver: AdapterDriver? = null
    private var recorder: RecordingExecutor? = null
    private var session: Session? = null
    private var reader: VehicleReader? = null
    private var connectJob: Job? = null
    private var infoJob: Job? = null
    private var liveJob: Job? = null
    private var scanJob: Job? = null
    private var profileJob: Job? = null

    private val profiles: VehicleProfileService by lazy {
        VehicleProfileService(
            repository = RoomProfileRepository(Bod3Database.get(application).profileDao()),
            describer = VpicClient(currentYear = { Calendar.getInstance().get(Calendar.YEAR) }),
        )
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        refreshBluetooth()
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
            setStep(device, "Opening Bluetooth connection…")
            val bluetoothDevice = bluetooth.device(device.address)
            if (bluetoothDevice == null) {
                _state.update { it.copy(connection = ConnectionState.Failed(device, "Invalid Bluetooth address")) }
                return@launch
            }
            val newDriver = AdapterDriver(BluetoothSppTransport(bluetoothDevice), viewModelScope, logger = ::appendLog)
            driver = newDriver
            try {
                newDriver.open()
                setStep(device, "Initialising adapter…")
                val info = newDriver.initialize()
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected(device),
                        adapter = info,
                        screen = Screen.DASHBOARD,
                    )
                }
                detectVehicleInternal()
            } catch (e: CancellationException) {
                newDriver.close()
                throw e
            } catch (e: Exception) {
                newDriver.close()
                driver = null
                appendLog("Connection failed: ${e.message}")
                _state.update {
                    it.copy(connection = ConnectionState.Failed(device, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Re-runs protocol detection and the vehicle reads (e.g. after the ignition was turned on). */
    fun detectVehicle() {
        if (infoJob?.isActive == true || scanJob?.isActive == true) return
        infoJob = viewModelScope.launch { detectVehicleInternal() }
    }

    fun refreshBatteryVoltage() {
        val currentDriver = driver ?: return
        viewModelScope.launch {
            _state.update { it.copy(batteryVoltage = Readout.Loading) }
            readBatteryVoltage(currentDriver)
        }
    }

    fun openLiveData() {
        if (scanJob?.isActive == true) return
        val currentReader = reader ?: return
        val state = _state.value
        val protocolInfo = (state.protocol as? Readout.Value)?.value ?: return

        // Use the ECU that supports the most PIDs (normally the engine ECU).
        val primaryEcu = state.ecus.maxByOrNull { it.value.size }?.key
        val supported = primaryEcu?.let { state.ecus[it] }.orEmpty()
        val definitions = Pids.live.filter { supported.isEmpty() || it.pid in supported }
        // On CAN, address that ECU physically: faster (no waiting for other ECUs) and exercises ATSH/ATCRA.
        val target = primaryEcu?.let { EcuTarget.physicalFor(it, protocolInfo.protocol.headerFormat) }
            ?: EcuTarget.Broadcast

        _state.update {
            it.copy(screen = Screen.LIVE, live = definitions.map { d -> LiveItem(d) }, liveRunning = true, liveEcu = primaryEcu)
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (isActive) {
                for (definition in definitions) {
                    if (!isActive) break
                    val item = try {
                        val reading = currentReader.readPid(definition, target, preferredEcu = primaryEcu)
                        LiveItem(definition, reading, status = if (reading == null) "No data" else null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LiveItem(definition, status = e.message ?: "Error")
                    }
                    _state.update { s -> s.copy(live = s.live.map { if (it.definition.pid == definition.pid) item else it }) }
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
        if (_state.value.screen == Screen.LIVE && liveJob?.isActive != true) openLiveData()
    }

    fun closeLiveData() {
        pauseLiveData()
        // No need to reset headers here: the Session switches back to broadcast on the next broadcast request.
        _state.update { it.copy(screen = Screen.DASHBOARD) }
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
            )
        }
        refreshBluetooth()
    }

    fun dismissError() {
        _state.update { if (it.connection is ConnectionState.Failed) it.copy(connection = ConnectionState.Idle) else it }
    }

    override fun onCleared() {
        disconnectInternal()
        super.onCleared()
    }

    private suspend fun detectVehicleInternal() {
        val currentDriver = driver ?: return
        _state.update {
            it.copy(protocol = Readout.Loading, vin = Readout.Loading, batteryVoltage = Readout.Loading, ecus = emptyMap())
        }
        val currentRecorder = RecordingExecutor(currentDriver)
        recorder = currentRecorder

        readBatteryVoltage(currentDriver)

        val protocolInfo = try {
            ProtocolDetector.detect(currentDriver)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appendLog("Protocol detection failed: ${e.message}")
            val reason = e.message ?: "Vehicle not detected"
            _state.update {
                it.copy(protocol = Readout.Unavailable(reason), vin = Readout.Unavailable("Vehicle not detected"))
            }
            return
        }

        // Requests go through the recorder so module scans can keep a raw request/response log.
        val newSession = Session(currentRecorder, protocolInfo.protocol)
        val newReader = VehicleReader(newSession, currentRecorder)
        session = newSession
        reader = newReader
        _state.update { it.copy(protocol = Readout.Value(protocolInfo)) }

        val vin = readOrNull { newReader.readVin() }
        _state.update { it.copy(vin = vin?.let { v -> Readout.Value(v) } ?: Readout.Unavailable("Not reported by vehicle")) }
        if (VinRules.isValid(vin)) loadProfile(vin!!, protocolInfo.protocol.displayName)

        val ecus = readOrNull { newReader.supportedPids() }.orEmpty()
        _state.update { it.copy(ecus = ecus) }
    }

    /** Battery voltage as measured by the adapter (`ATRV`); works even when the ignition is off. */
    private suspend fun readBatteryVoltage(currentDriver: AdapterDriver) {
        val voltage = readOrNull {
            currentDriver.execute("ATRV", 2_000L).takeIf { it.isSuccess }?.let { VoltageParser.parse(it.text) }
        }
        _state.update {
            it.copy(batteryVoltage = voltage?.let { v -> Readout.Value(v) } ?: Readout.Unavailable("No reading"))
        }
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

    fun startScan() {
        val currentSession = session ?: return
        val currentRecorder = recorder ?: return
        if (scanJob?.isActive == true || !_state.value.canScan) return
        liveJob?.cancel()
        val includeOtherBus = _state.value.scan.includeOtherBus
        val protocolName = currentSession.protocol.displayName

        scanJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.SCAN,
                    liveRunning = false,
                    scan = ScanUiState(running = true, includeOtherBus = includeOtherBus, startedAtMs = System.currentTimeMillis()),
                )
            }
            appendLog("Module scan started")
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
                val reports = ScanCoordinator(currentSession, currentRecorder).run(ScanOptions(includeOtherBus = includeOtherBus), listener)
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
        _state.update { it.copy(screen = Screen.DASHBOARD) }
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
        _state.update { it.copy(connection = ConnectionState.Connecting(device, step)) }
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
        const val MAX_LOG_LINES = 300
    }
}
