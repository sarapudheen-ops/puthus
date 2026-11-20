package com.bod3.app.ui.screens

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.diagnostics.EcuWorkbench
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecordResult
import com.bod3.app.ui.EcuDetailState
import com.bod3.app.ui.Readout

/** One selected ECU's explicit reads (KWP/UDS), or the existing legacy UDS/OBD workbench. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcuDetailScreen(
    detail: EcuDetailState,
    descriptions: DtcDescriptions,
    connected: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReadRecords: (String) -> Unit,
    onClear: () -> Unit,
    onDismissClear: () -> Unit,
    onSaveDescription: (code: String, text: String) -> Unit,
) {
    val ecu = detail.ecu
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = detail.identificationLoading || detail.dtcs is Readout.Loading || detail.records.values.any { it is Readout.Loading }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ecu.display)
                        Text(
                            "${ecu.requestId} → ${ecu.responseId} · ${if (ecu.link?.kLine != null) ecu.link.protocol.displayName else if (ecu.is29Bit) "29-bit CAN" else "11-bit CAN"} · ${ecu.protocol.label}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = onRefresh, enabled = connected && !busy) { Icon(Icons.Filled.Refresh, contentDescription = "Read again") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Fault codes") {
                when (val readout = detail.dtcs) {
                    Readout.NotRead -> Text("Not read")
                    Readout.Loading -> Text("Reading…")
                    is Readout.Unavailable -> Text(readout.reason, color = MaterialTheme.colorScheme.error)
                    is Readout.Value -> {
                        val result = readout.value
                        Text(result.display + ((result as? DtcReadResult.Read)?.let { " · ${it.source}" } ?: ""))
                        if (result is DtcReadResult.Read) {
                            result.records.forEach { record ->
                                HorizontalDivider()
                                DtcRow(record.code, descriptions, detail = record.statusText, sourceDescription = record.sourceDescription,
                                    exactSource = record.statusMeaning == com.bod3.app.obd.uds.DtcStatusMeaning.TOYOTA, onEditDescription = { editing = it })
                                if (EcuWorkbench.canReadRecords(ecu)) {
                                    when (val records = detail.records[record.code]) {
                                        null -> TextButton(onClick = { onReadRecords(record.code) }, enabled = connected && !busy) {
                                            Text("Read extended data and snapshot")
                                        }
                                        Readout.Loading -> Text("Reading records…", style = MaterialTheme.typography.bodySmall)
                                        Readout.NotRead -> {}
                                        is Readout.Unavailable -> Text(records.reason, color = MaterialTheme.colorScheme.error)
                                        is Readout.Value -> {
                                            RecordRow("Extended data (19 06)", records.value.extended)
                                            RecordRow("Snapshot (19 04)", records.value.snapshot)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (EcuWorkbench.canClear(ecu)) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = connected && !busy && detail.clear == null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear fault memory of this ECU…") }
                } else {
                    Text(if (ecu.protocol == EcuProtocol.OBD) "OBD-only ECU: clear its codes from the OBD fault code screen."
                        else "No documented clear request for this ECU. Clearing is disabled; no generic group is substituted.", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard("Identification") {
                if (detail.identification.isEmpty()) Text(if (!EcuWorkbench.canReadIdentification(ecu)) "No resolved identification definitions; no generic identifiers were requested."
                    else if (detail.identificationLoading) "Reading…" else "Not read")
                val first = detail.identification.firstOrNull()
                if (first?.result == DidResult.NoResponse && detail.identification.size == 1) {
                    Text("No reply. Check the IDs and that the ignition is on; the module may not be fitted.", color = MaterialTheme.colorScheme.error)
                }
                detail.identification.forEach { reading ->
                    InfoRow("${reading.label}\n${reading.request ?: "%04X".format(reading.did)}", reading.display)
                }
                if (detail.identificationLoading && detail.identification.isNotEmpty()) Text("Reading…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmClear && EcuWorkbench.canClear(ecu)) {
        ClearConfirmDialog(
            title = "Clear fault memory?",
            target = "${ecu.display} (${ecu.requestId}) · ${ecu.protocol.label} (${EcuWorkbench.clearRequestLabel(ecu)})",
            consequences = listOf("All stored fault codes of this module", "Their extended data and snapshots"),
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
        )
    }
    detail.clear?.let { ClearStateDialog(it, onDismissClear) }
    editing?.let { code ->
        DtcDescriptionDialog(code, descriptions, onSave = { onSaveDescription(code, it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun RecordRow(label: String, result: DtcRecordResult) {
    InfoRow(label, result.display)
}
