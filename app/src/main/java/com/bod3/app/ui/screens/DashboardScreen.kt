package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.R
import com.bod3.app.ui.ConnectionState
import com.bod3.app.ui.Readout
import com.bod3.app.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    log: List<String>,
    onRefreshVoltage: () -> Unit,
    onDetectVehicle: () -> Unit,
    onOpenLiveData: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val deviceName = (state.connection as? ConnectionState.Connected)?.device?.name
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
                    IconButton(onClick = onDisconnect) { Icon(Icons.Filled.Close, contentDescription = "Disconnect") }
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
            val busy = state.protocol is Readout.Loading || state.vin is Readout.Loading
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            AdapterCard(state)
            VehicleCard(state, onRefreshVoltage, onDetectVehicle)

            Button(
                onClick = onOpenLiveData,
                enabled = state.protocol is Readout.Value && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Live data") }

            LogCard(log)
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
            adapter.description?.let { InfoRow("Description", it) }
            InfoRow("Firmware (VTVERS)", adapter.capabilities.vtVersion ?: "Not supported")
            InfoRow(
                "Extensions",
                listOfNotNull(
                    "VT".takeIf { adapter.capabilities.supportsVt },
                    "ST".takeIf { adapter.capabilities.supportsSt },
                ).joinToString(", ").ifEmpty { "None (plain ELM327 commands only)" },
            )
        }
    }
}

@Composable
private fun VehicleCard(state: UiState, onRefreshVoltage: () -> Unit, onDetectVehicle: () -> Unit) {
    SectionCard("Vehicle") {
        InfoRow("Protocol", state.protocol.display { it.description })
        InfoRow("VIN", state.vin.display { it })
        InfoRow("Battery", state.batteryVoltage.display { "%.1f V".format(it) })
        if (state.ecus.isNotEmpty()) {
            InfoRow(
                "ECUs",
                state.ecus.entries.joinToString("\n") { (header, pids) -> "$header · ${pids.size} PIDs" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefreshVoltage) { Text("Refresh voltage") }
            if (state.protocol is Readout.Unavailable) {
                OutlinedButton(onClick = onDetectVehicle) { Text("Detect vehicle") }
            }
        }
        if (state.protocol is Readout.Unavailable) {
            Text(
                "Turn the ignition on (engine may stay off), then tap Detect vehicle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogCard(log: List<String>) {
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
    }
}

private fun <T> Readout<T>.display(format: (T) -> String): String = when (this) {
    Readout.NotRead -> "—"
    Readout.Loading -> "Reading…"
    is Readout.Value -> format(value)
    is Readout.Unavailable -> reason
}
