package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.dtc.DtcKind
import com.bod3.app.ui.Readout
import com.bod3.app.ui.UiState

/** OBD fault codes (services 03, 07, 0A) on any protocol, CAN or K-line. Read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(state: UiState, onBack: () -> Unit, onRefresh: () -> Unit) {
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
                    val loading = state.dtcs.values.any { it is Readout.Loading }
                    IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = "Read again") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                                    InfoRow("ECU ${ecu.ecu}", ecu.codes.joinToString("  "))
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "Reading only. Clearing codes will come with the diagnostics milestone and always asks for confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Default,
            )
        }
    }
}
