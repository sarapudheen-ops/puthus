package com.bod3.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.ui.UiState
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleListScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpenModule: (String) -> Unit,
    onRescan: () -> Unit,
    rawLogText: () -> String,
    onClearLog: () -> Unit,
    onOpenEcus: () -> Unit = {},
    writeExport: (suspend () -> java.io.File)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val profile = state.savedProfile
    val modules = state.modules
    val showingUnsaved = !state.scan.saved && state.scan.reports.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Modules")
                        Text(profile?.title ?: "Unsaved scan", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (profile != null) TextButton(onClick = onOpenEcus) { Text("ECUs") }
                    if (profile != null && writeExport != null) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    com.bod3.app.android.ReportSharer.share(context, writeExport(), "application/json", "bod3 vehicle profile ${profile.vin}", "Share vehicle profile")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("Export") }
                    }
                    if (!state.offline) TextButton(onClick = onRescan, enabled = state.canScan) { Text("Rescan") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard("Vehicle profile") {
                    if (profile != null) {
                        InfoRow("VIN", profile.vin)
                        profile.description?.let { d ->
                            InfoRow("Vehicle", d.title ?: "Unknown")
                            InfoRow("Decoded by", if (d.source.name == "VPIC") "NHTSA vPIC" else "VIN only (offline)" + (d.note?.let { " – $it" } ?: ""))
                        }
                        profile.protocol?.let { InfoRow("Protocol", it) }
                        profile.lastScanAtMs?.let { InfoRow("Last scan", formatTime(it)) }
                    }
                    if (showingUnsaved) {
                        Text(
                            state.scan.notSavedReason ?: "These results are not saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (profile != null) {
                        val extras = state.profileExtras
                        InfoRow("Manual ECUs", extras.manualEcus.size.toString())
                        if (extras.moduleNames.isNotEmpty()) InfoRow("Your module names", extras.moduleNames.size.toString())
                    }
                    InfoRow("Modules", modules.size.toString())
                    InfoRow("Fault codes", modules.sumOf { it.dtcs.faultCount ?: 0 }.toString())
                    RawLogActions(rawLogText, "raw log", enabled = state.rawLog.isNotEmpty(), onClear = onClearLog)
                }
            }
            if (modules.isEmpty()) {
                item { Text(if (state.offline) "No modules saved for this vehicle." else "No modules yet. Run a module scan from the vehicle screen.") }
            }
            items(modules, key = { it.address.key }) { module ->
                ModuleCard(module, onClick = { onOpenModule(module.address.key) })
            }
        }
    }
}

@Composable
private fun ModuleCard(module: ModuleReport, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(module.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    module.nameSource?.let { source ->
                        Text("Name: ${source.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${module.address.display} · ${module.address.bus.label}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FaultChip(module)
            }
            module.partNumber?.let { InfoRow("Part number", it) }
            InfoRow("Software", module.softwareVersion ?: "Not supported")
            InfoRow("Hardware", module.hardwareVersion ?: "Not supported")
        }
    }
}

@Composable
private fun FaultChip(module: ModuleReport) {
    val count = module.dtcs.faultCount
    val label = when (count) {
        null -> "Faults: n/a"
        0 -> "No faults"
        1 -> "1 fault"
        else -> "$count faults"
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = if (count != null && count > 0) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

internal fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
