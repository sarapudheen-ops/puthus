package com.bod3.app.ui.screens

import com.bod3.app.settings.UnitPreferences
import com.bod3.app.settings.Units
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.dtc.DtcKind
import com.bod3.app.ui.Readout
import com.bod3.app.ui.UiState

/** OBD fault codes (03, 07, 0A), readiness, freeze frame and a confirmed clear (04), on CAN or K-line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDismissClear: () -> Unit,
    onSaveDescription: (code: String, text: String) -> Unit,
    units: UnitPreferences = UnitPreferences(),
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val loading = state.dtcs.values.any { it is Readout.Loading } || state.readiness is Readout.Loading || state.freezeFrames is Readout.Loading
    val descriptions = state.data.descriptions

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fault codes (OBD)")
                        (state.protocol as? Readout.Value)?.value?.let { Text(it.protocol.displayName, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = "Read again") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                state.data.dtcReferenceMake?.let { "$it reference text where available. Confirm the meaning for your ECU/model." }
                    ?: "Generic reference text only. Manufacturer descriptions require a known make in the loaded vehicle profile or a vehicle explicitly linked to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DtcKind.entries.forEach { kind ->
                SectionCard("${kind.label} (service %02X)".format(kind.service)) {
                    when (val readout = state.dtcs[kind] ?: Readout.NotRead) {
                        Readout.NotRead -> Text("Not read")
                        Readout.Loading -> Text("Reading…")
                        is Readout.Unavailable -> Text(readout.reason, color = MaterialTheme.colorScheme.error)
                        is Readout.Value -> {
                            val report = readout.value
                            when {
                                report.noData -> Text("No reply (the vehicle may not support this service)")
                                report.codes.isEmpty() -> Text("No codes")
                                else -> report.ecus.filter { it.codes.isNotEmpty() }.forEach { ecu ->
                                    Text("ECU ${ecu.ecu}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ecu.codes.forEach { code -> DtcRow(code, descriptions, onEditDescription = { editing = it }) }
                                }
                            }
                        }
                    }
                }
            }

            SectionCard("Readiness monitors (01 01)") {
                when (val readout = state.readiness) {
                    Readout.NotRead -> Text("Not read")
                    Readout.Loading -> Text("Reading…")
                    is Readout.Unavailable -> Text(readout.reason, color = MaterialTheme.colorScheme.error)
                    is Readout.Value -> if (readout.value.isEmpty()) Text("No reply") else readout.value.forEach { r ->
                        InfoRow("ECU ${r.ecu}", "MIL ${if (r.milOn) "on" else "off"} · ${r.dtcCount} code(s) · ${if (r.compressionIgnition) "diesel" else "petrol"}")
                        InfoRow("Status", if (r.ready) "All supported monitors complete" else "${r.incomplete.size} incomplete")
                        r.supported.forEach { m -> InfoRow(m.name, m.display) }
                        HorizontalDivider()
                    }
                }
            }

            SectionCard("Freeze frame (02)") {
                when (val readout = state.freezeFrames) {
                    Readout.NotRead -> Text("Not read")
                    Readout.Loading -> Text("Reading…")
                    is Readout.Unavailable -> Text(readout.reason, color = MaterialTheme.colorScheme.error)
                    is Readout.Value -> if (readout.value.isEmpty()) Text("No freeze frame stored") else readout.value.forEach { frame ->
                        InfoRow("ECU ${frame.ecu}", "Frame ${frame.frame}")
                        frame.dtc?.let { DtcRow(it, descriptions, detail = "Stored the freeze frame", onEditDescription = { c -> editing = c }) }
                        frame.values.forEach { v -> InfoRow(v.definition.name, Units.format(v.value, v.definition.unit, v.definition.decimals, units)) }
                        HorizontalDivider()
                    }
                }
            }

            SectionCard("Clear fault codes (04)") {
                Text(
                    "Clears stored and pending codes, freeze frames and readiness monitors on all OBD ECUs. Permanent codes are only cleared by the ECU after it has verified the repair.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !loading && state.obdClear == null && state.isConnected,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear fault codes…") }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            title = "Clear OBD fault codes?",
            target = "All emission-related ECUs (service 04)",
            consequences = listOf("Stored and pending codes", "Freeze frame data", "Readiness monitors (an inspection may fail until the car has been driven)"),
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
        )
    }
    state.obdClear?.let { ClearStateDialog(it, onDismissClear) }
    editing?.let { code ->
        DtcDescriptionDialog(code, descriptions, onSave = { onSaveDescription(code, it); editing = null }, onDismiss = { editing = null })
    }
}
