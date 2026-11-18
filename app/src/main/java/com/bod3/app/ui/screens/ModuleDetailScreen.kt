package com.bod3.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.toHex
import com.bod3.app.profile.RawLogFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailScreen(
    module: ModuleReport,
    rawLog: List<RawExchange>,
    onBack: () -> Unit,
    moduleLogText: () -> String,
    fullLogText: () -> String,
    onClearLog: () -> Unit,
    descriptions: DtcDescriptions,
    onSaveDescription: (String, String) -> Unit,
    /** Renames the module for this vehicle; null when no profile is loaded. Blank resets the name. */
    onRename: ((String) -> Unit)? = null,
) {
    var editingDtc by rememberSaveable(module.address.key) { mutableStateOf<String?>(null) }
    editingDtc?.let { code ->
        DtcDescriptionDialog(code, descriptions, onSave = { onSaveDescription(code, it); editingDtc = null }, onDismiss = { editingDtc = null })
    }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (renaming && onRename != null) {
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (module.nameSource == com.bod3.app.profile.NameSource.USER) module.displayName else "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename module") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your name for ${module.address.requestHeader} on this vehicle. Leave empty to use the module's own or data-pack name.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { renaming = false; onRename(text) }) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(module.displayName)
                        Text(module.address.display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (onRename != null) androidx.compose.material3.TextButton(onClick = { renaming = true }) { Text("Rename") }
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
            SectionCard("Module") {
                module.nameSource?.let { InfoRow("Name from", it.label) }
                InfoRow("Request ID", module.address.requestHeader)
                InfoRow("Response ID", module.address.responseHeader)
                InfoRow("Bus", module.address.bus.label)
                InfoRow("Detected by", module.module.detectedBy.label + if (module.module.negativeReply) " (negative reply)" else "")
                InfoRow("OBD", if (module.module.obd) "Yes" else "No")
                module.obdEcuName?.let { InfoRow("OBD ECU name", it) }
                module.calibrationId?.let { InfoRow("Calibration ID", it) }
                InfoRow("Coding", module.coding ?: "Not supported")
            }

            SectionCard("Identification (UDS 22)") {
                module.identifiers.forEach { reading ->
                    InfoRow("${reading.label}\n${"%04X".format(reading.did)}", reading.display)
                    val value = reading.result
                    if (value is DidResult.Value && value.text != null) {
                        Text(
                            value.hex,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (module.identifiers.isEmpty()) Text("Not read")
            }

            SectionCard("Fault codes") {
                val dtcs = module.dtcs
                Text(dtcs.display + ((dtcs as? DtcReadResult.Read)?.let { " · ${it.source}" } ?: ""))
                if (dtcs is DtcReadResult.Read) {
                    dtcs.records.forEach { record ->
                        HorizontalDivider()
                        DtcRow(record.code, descriptions, detail = record.statusText, onEditDescription = { editingDtc = it })
                        Text(
                            record.raw.toHex(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Read-only: fault codes are displayed, never cleared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val entries = remember(rawLog, module) { RawLogFormatter.forModule(rawLog, module.address.requestHeader) }
            var showAll by rememberSaveable { mutableStateOf(false) }
            SectionCard("Raw data (${entries.size} requests)") {
                module.module.firstReply?.let { InfoRow("First reply", it) }
                RawLogActions(moduleLogText, "module log", enabled = entries.isNotEmpty())
                RawLogActions(fullLogText, "full log", enabled = rawLog.isNotEmpty(), onClear = onClearLog)
                if (entries.isEmpty()) {
                    Text("No raw log saved for this module.", style = MaterialTheme.typography.bodySmall)
                } else {
                    val shown = if (showAll) entries else entries.takeLast(PREVIEW_ENTRIES)
                    if (entries.size > PREVIEW_ENTRIES) {
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "Show last $PREVIEW_ENTRIES" else "Show all ${entries.size}")
                        }
                    }
                    SelectionContainer {
                        Text(
                            RawLogFormatter.format(shown),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private const val PREVIEW_ENTRIES = 40
