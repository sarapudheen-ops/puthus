package com.bod3.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bod3.app.android.ReportSharer
import com.bod3.app.android.SharedPrefsKeyValueStore
import com.bod3.app.bluetooth.BluetoothRepository
import com.bod3.app.bluetooth.PairedAdapter
import com.bod3.app.obd.PidReading
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
import com.bod3.app.obd.pid.PidDefinition
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
import com.bod3.app.profile.RawLogFormatter
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { DEVICES, DASHBOARD, LIVE, SCAN, MODULES, MODULE_DETAIL, DTCS, SETTINGS, PROFILE_EDIT, TERMINAL }

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

    private val profiles: VehicleProfileService by lazy {
        VehicleProfileService(
            repository = RoomProfileRepository(Bod3Database.get(application).profileDao()),
            describer = VpicClient(currentYear = { Calendar.getInstance().get(Calendar.YEAR) }),
        )
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        refreshBluetooth()
        refreshSettings()
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
                connectedProfile = null,
                setup = emptyList(),
                attempts = emptyList(),
                voltageHistory = emptyList(),
                dtcs = emptyMap(),
                reconnects = emptyList(),
                terminal = emptyList(),
                terminalBusy = false,
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
        }
        refreshSettings()
    }

    // ---- Expert terminal --------------------------------------------------------------------------

    fun openTerminal() {
        val currentDriver = driver ?: return
        if (!connectionStore.expertModeEnabled || scanJob?.isActive == true) return
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
        terminalJob?.cancel()
        voltageJob?.cancel()
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
                val busy = scanJob?.isActive == true || _state.value.screen == Screen.TERMINAL
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

    /** Reads stored (03), pending (07) and permanent (0A) codes. Read-only: nothing is cleared. */
    fun readDtcs() {
        val currentSession = session ?: return
        if (dtcJob?.isActive == true || scanJob?.isActive == true) return
        dtcJob = viewModelScope.launch {
            _state.update { it.copy(dtcs = DtcKind.entries.associateWith { Readout.Loading }) }
            val reader = ObdDtcReader(currentSession)
            for (kind in DtcKind.entries) {
                val value: Readout<DtcReport> = try {
                    Readout.Value(reader.read(kind))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog("${kind.label} codes: ${e.message}")
                    Readout.Unavailable(e.message ?: "Read failed")
                }
                _state.update { it.copy(dtcs = it.dtcs + (kind to value)) }
            }
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
        const val VOLTAGE_INTERVAL_MS = 5_000L
        const val MAX_LOG_LINES = 300
    }
}
