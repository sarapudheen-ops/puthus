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
import com.bod3.app.obd.elm.VoltageParser
import com.bod3.app.obd.pid.PidDefinition
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ProtocolDetector
import com.bod3.app.obd.session.ProtocolInfo
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.transport.BluetoothSppTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { DEVICES, DASHBOARD, LIVE }

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
)

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetooth = BluetoothRepository(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var driver: AdapterDriver? = null
    private var session: Session? = null
    private var reader: VehicleReader? = null
    private var connectJob: Job? = null
    private var infoJob: Job? = null
    private var liveJob: Job? = null

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
        if (infoJob?.isActive == true) return
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

        val newSession = Session(currentDriver, protocolInfo.protocol)
        val newReader = VehicleReader(newSession, currentDriver)
        session = newSession
        reader = newReader
        _state.update { it.copy(protocol = Readout.Value(protocolInfo)) }

        val vin = readOrNull { newReader.readVin() }
        _state.update { it.copy(vin = vin?.let { v -> Readout.Value(v) } ?: Readout.Unavailable("Not reported by vehicle")) }

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

    private fun disconnectInternal() {
        liveJob?.cancel()
        infoJob?.cancel()
        connectJob?.cancel()
        driver?.close()
        driver = null
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
