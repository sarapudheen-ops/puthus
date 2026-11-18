package com.bod3.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.R
import com.bod3.app.android.ReportSharer
import com.bod3.app.obd.connection.VoltageMonitor
import com.bod3.app.obd.connection.VoltageStatus
import com.bod3.app.ui.ConnectionState
import com.bod3.app.ui.ProfileState
import com.bod3.app.ui.Readout
import com.bod3.app.ui.UiState
import com.bod3.app.ui.device
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Everything the dashboard can ask the view model to do. */
class DashboardActions(
    val onRefreshVoltage: () -> Unit,
    val onDetectVehicle: () -> Unit,
    val onOpenLiveData: () -> Unit,
    val onOpenDtcs: () -> Unit,
    val onOpenScan: () -> Unit,
    val onOpenModules: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenVehicles: () -> Unit,
    val onOpenCustomPids: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDisconnect: () -> Unit,
    val logText: () -> String,
    val onClearLog: () -> Unit,
    val attemptsText: () -> String,
    val writeReport: suspend () -> File,
    val onOpenGarage: () -> Unit = {},
    val onOpenActuators: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: UiState, log: List<String>, actions: DashboardActions) {
    val deviceName = state.connection.device?.name
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.dashboard_title))
                        if (deviceName != null) Text(deviceName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = actions.onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Connection settings") }
                    IconButton(onClick = actions.onDisconnect) { Icon(Icons.Filled.Close, contentDescription = "Disconnect") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val connected = state.connection is ConnectionState.Connected
            val busy = state.protocol is Readout.Loading || state.vin is Readout.Loading
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            (state.connection as? ConnectionState.Reconnecting)?.let { ReconnectBanner(it) }

            VehicleCard(state, connected, actions)
            DiagnosticButtons(state, connected, busy, actions)
            VoltageCard(state, connected, actions.onRefreshVoltage)
            ProtocolCard(state, actions.attemptsText)
            AdapterCard(state)
            ProfileCard(state, busy || !connected, actions.onOpenScan, actions.onOpenModules)
            ReportCard(actions.writeReport)
            LogCard(log, actions.logText, actions.onClearLog)
        }
    }
}

@Composable
private fun ReconnectBanner(state: ConnectionState.Reconnecting) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection lost – reconnecting", style = MaterialTheme.typography.titleMedium)
            Text("Attempt ${state.attempt} of ${state.maxAttempts}. ${state.reason}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun VehicleCard(state: UiState, connected: Boolean, actions: DashboardActions) {
    SectionCard("Vehicle") {
        InfoRow("Protocol", state.protocol.display { it.description })
        InfoRow("VIN", state.vin.display { it })
        if (state.ecus.isNotEmpty()) {
            InfoRow("ECUs", state.ecus.entries.joinToString("\n") { (header, pids) -> "$header · ${pids.size} PIDs" })
        }
        if (state.protocol is Readout.Unavailable) {
            Text(
                "Turn the ignition on (engine may stay off), then tap Detect vehicle. If it still fails, " +
                    "try a connection profile with a forced protocol or longer search timeout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.onDetectVehicle, enabled = connected) { Text("Detect vehicle") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticButtons(state: UiState, connected: Boolean, busy: Boolean, actions: DashboardActions) {
    val ready = connected && state.protocol is Readout.Value && !busy && !state.scan.running
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = actions.onOpenLiveData, enabled = ready) { Text("Live data") }
        Button(onClick = actions.onOpenDtcs, enabled = ready) { Text("Fault codes") }
        OutlinedButton(onClick = actions.onOpenVehicles, enabled = !busy && !state.scan.running) {
            Text(state.data.selectedVehicle?.let { "ECUs · ${it.vehicle.model}" } ?: "Vehicle & ECUs")
        }
        OutlinedButton(onClick = actions.onOpenCustomPids) { Text("Custom PIDs") }
        OutlinedButton(onClick = actions.onOpenGarage, enabled = !state.scan.running) { Text("Saved vehicles") }
        OutlinedButton(onClick = actions.onOpenActuators, enabled = !state.scan.running) { Text("Bidirectional tests") }
        if (state.settings.expertMode) {
            OutlinedButton(onClick = actions.onOpenTerminal, enabled = connected && !state.scan.running) { Text("Expert terminal") }
        }
    }
}

@Composable
private fun VoltageCard(state: UiState, connected: Boolean, onRefresh: () -> Unit) {
    SectionCard("Battery voltage") {
        val latest = (state.batteryVoltage as? Readout.Value)?.value
        val status = VoltageMonitor.classify(latest)
        val color = when (status) {
            VoltageStatus.CRITICAL, VoltageStatus.LOW, VoltageStatus.HIGH -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            state.batteryVoltage.display { "%.2f V".format(it) } + if (latest != null) " · ${status.label}" else "",
            style = MaterialTheme.typography.headlineSmall,
            color = color,
        )
        val history = state.voltageHistory
        if (history.size >= 2) {
            VoltageGraph(history)
            InfoRow("Min / max", "%.2f V / %.2f V (%d samples)".format(history.minOf { it.volts }, history.maxOf { it.volts }, history.size))
        }
        Text(
            "Measured by the adapter (ATRV) every 5 s. Below 12.0 V the battery is low; above 13.2 V the engine is charging it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh, enabled = connected) { Text("Measure now") }
    }
}

@Composable
private fun ProtocolCard(state: UiState, attemptsText: () -> String) {
    if (state.attempts.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard("Protocol detection") {
        val ok = state.attempts.lastOrNull { it.success }
        Text(
            if (ok != null) "Found ${ok.label} after ${state.attempts.size} attempt(s)" else "No protocol answered (${state.attempts.size} attempts)",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide attempts" else "Show attempts") }
        if (expanded) {
            state.attempts.forEach { a ->
                Text(
                    "${if (a.success) "✓" else "✗"} ${a.selectCommand} · ${a.label} · ${a.durationMs} ms\n   ${a.requested?.probe?.request ?: "0100"} → ${a.result}" +
                        (a.atdp?.let { "\n   ATDP → $it" } ?: ""),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RawLogActions(attemptsText, "attempts")
        }
    }
}

@Composable
private fun AdapterCard(state: UiState) {
    val adapter = state.adapter
    SectionCard("Adapter") {
        if (adapter == null) {
            Text("Not initialised")
        } else {
            InfoRow("ATI", adapter.identity.raw.ifBlank { "—" })
            adapter.description?.let { InfoRow("Description (AT@1)", it) }
            InfoRow("VT firmware (VTVERS)", adapter.capabilities.vtVersion ?: "Not reported")
            InfoRow("ST firmware (STI)", adapter.capabilities.stVersion ?: "Not reported")
            InfoRow("Command sets", "ELM AT, STN ST, vLinker VT (unknown commands are logged and skipped)")
            state.connectedProfile?.let { InfoRow("Profile", it.name) }
            if (state.setup.isNotEmpty()) {
                InfoRow("Setup", state.setup.joinToString("\n") { "${if (it.accepted) "✓" else "✗"} ${it.command}" })
            }
        }
    }
}

@Composable
private fun ProfileCard(state: UiState, busy: Boolean, onOpenScan: () -> Unit, onOpenModules: () -> Unit) {
    SectionCard("Vehicle profile & modules") {
        when (val profile = state.profile) {
            ProfileState.None -> Text(
                if (state.vin is Readout.Value) "—" else "A profile is saved once the VIN is known (from OBD or a module scan).",
                style = MaterialTheme.typography.bodySmall,
            )
            ProfileState.Loading -> Text("Loading profile…")
            is ProfileState.Failed -> Text("Profile unavailable: ${profile.message}", color = MaterialTheme.colorScheme.error)
            is ProfileState.Loaded -> {
                val p = profile.profile
                InfoRow("Vehicle", p.description?.title ?: "Unknown make/model")
                p.description?.note?.let { InfoRow("Decoding", it) }
                InfoRow("Status", if (profile.isNew) "New profile" else "Saved profile loaded")
                InfoRow("Saved modules", if (p.lastScanAtMs == null) "Not scanned yet" else "${p.modules.size} · ${p.totalFaults} fault(s)")
                p.lastScanAtMs?.let { InfoRow("Last scan", formatTime(it)) }
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenScan, enabled = (state.canScan && !busy) || state.scan.running) {
                Text(if (state.scan.running) "Scan running…" else "Scan modules")
            }
            OutlinedButton(onClick = onOpenModules, enabled = state.modules.isNotEmpty()) { Text("Modules (${state.modules.size})") }
        }
        if (state.protocol is Readout.Value && !state.canScan && !state.scan.running) {
            Text(
                "Module scan needs a CAN vehicle (ISO 15765-4). K-line vehicles support live data, VIN and fault codes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportCard(writeReport: suspend () -> File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    SectionCard("Diagnostic report") {
        Text(
            "A text file with adapter info, protocol attempts, vehicle info, voltage, fault codes and all logs – useful when asking for help.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !working,
            onClick = {
                working = true
                scope.launch {
                    try {
                        ReportSharer.share(context, writeReport())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not create report: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        working = false
                    }
                }
            },
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(" Share diagnostic report")
        }
    }
}

@Composable
private fun LogCard(log: List<String>, logText: () -> String, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard("Adapter log") {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide" else "Show (${log.size} lines)")
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    log.takeLast(80).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        RawLogActions(logText, "adapter log", enabled = log.isNotEmpty(), onClear = onClear)
    }
}

internal fun <T> Readout<T>.display(format: (T) -> String): String = when (this) {
    Readout.NotRead -> "—"
    Readout.Loading -> "Reading…"
    is Readout.Value -> format(value)
    is Readout.Unavailable -> reason
}
