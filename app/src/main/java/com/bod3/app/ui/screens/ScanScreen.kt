package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.scan.ScanPhase
import com.bod3.app.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOtherBusChange: (Boolean) -> Unit,
    onOpenModules: () -> Unit,
    rawLogText: () -> String,
) {
    val scan = state.scan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Module scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Scan") {
                    Text(
                        "Looks for diagnostic modules with read-only requests: OBD addresses, then UDS addresses " +
                            "700–7FF with TesterPresent and a VIN read, then 29-bit addressing. Each module found is " +
                            "asked for its part number, versions, coding and fault codes. Nothing is written or cleared.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Also scan the other CAN ID length (11/29-bit)", modifier = Modifier.weight(1f))
                        Switch(checked = scan.includeOtherBus, onCheckedChange = onOtherBusChange, enabled = !scan.running)
                    }
                    if (scan.running) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel scan") }
                    } else {
                        Button(onClick = onStart, enabled = state.canScan, modifier = Modifier.fillMaxWidth()) {
                            Text(if (scan.startedAtMs == null) "Start scan" else "Scan again")
                        }
                        if (!state.canScan) {
                            Text("Module scan needs a CAN vehicle (ISO 15765-4).", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            scan.progress?.let { progress ->
                item {
                    SectionCard("Progress") {
                        val active = scan.running
                        if (active) {
                            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        InfoRow("Phase", buildString {
                            append(progress.phase.label)
                            progress.bus?.let { append(" · ").append(it.label) }
                        })
                        if (progress.phase != ScanPhase.DONE) {
                            InfoRow("Step", "${progress.completed} / ${progress.total}")
                            progress.current?.let { InfoRow("Current", it) }
                        }
                        InfoRow("Modules found", scan.found.size.toString())
                        if (scan.reports.isNotEmpty()) InfoRow("Modules read", "${scan.reports.size} / ${scan.found.size}")
                        val start = scan.startedAtMs
                        val end = scan.finishedAtMs
                        if (start != null && end != null) InfoRow("Duration", "%.1f s".format((end - start) / 1000.0))
                    }
                }
            }

            if (!scan.running && scan.startedAtMs != null) {
                item {
                    SectionCard("Result") {
                        when {
                            scan.error != null -> Text("Scan stopped: ${scan.error}", color = MaterialTheme.colorScheme.error)
                            scan.cancelled -> Text("Scan cancelled.")
                            else -> Text("Scan complete: ${scan.reports.size} modules.")
                        }
                        if (scan.saved) {
                            Text("Saved to the vehicle profile for ${state.savedProfile?.vin}.", style = MaterialTheme.typography.bodySmall)
                        }
                        scan.notSavedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = onOpenModules, enabled = state.modules.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("View modules")
                        }
                        RawLogActions(rawLogText, "raw log", enabled = state.rawLog.isNotEmpty())
                    }
                }
            }

            if (scan.found.isNotEmpty()) {
                item { Text("Found", style = MaterialTheme.typography.titleMedium) }
                items(scan.found, key = { it.address.key }) { module ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(module.address.display, fontFamily = FontFamily.Monospace)
                            val read = scan.reports.firstOrNull { it.address.key == module.address.key }
                            Text(
                                listOfNotNull(
                                    read?.displayName,
                                    module.address.bus.label,
                                    module.detectedBy.label,
                                    "negative reply".takeIf { module.negativeReply },
                                    read?.dtcs?.display,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
