package com.bod3.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.android.ReportSharer
import com.bod3.app.profile.VehicleProfile
import com.bod3.app.ui.UiState
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Largest profile file accepted for import. */
private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/** Saved vehicle profiles: open (offline too), export through the share sheet, import from a file, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportText: (String) -> Unit,
    writeExport: suspend (vin: String, includeRawLog: Boolean) -> File,
    onConfirmOverwrite: () -> Unit,
    onCancelOverwrite: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val garage = state.garage
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= MAX_IMPORT_BYTES) { "The file is larger than 20 MB" }
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalStateException("The file could not be opened")
                }
                onImportText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun export(vin: String) {
        exporting = vin
        scope.launch {
            try {
                ReportSharer.share(context, writeExport(vin, true), "application/json", "bod3 vehicle profile $vin", "Share vehicle profile")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                exporting = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved vehicles") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            garage.message?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDismissMessage) { Text("OK") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Every vehicle you connect to gets a profile keyed by its VIN, with the latest module scan, raw log, " +
                        "your manual ECU list and module names. Open a profile to browse it without the car; export it as a " +
                        "JSON file to back it up or share it, and import it on another phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (garage.loading && garage.profiles.isEmpty()) item { Text("Loading…") }
            if (!garage.loading && garage.profiles.isEmpty()) {
                item { Text("No saved vehicles yet. Connect to a car (or import a profile file) to create one.") }
            }
            items(garage.profiles, key = { it.vin }) { profile ->
                ProfileCard(
                    profile = profile,
                    current = state.savedProfile?.vin == profile.vin,
                    exporting = exporting == profile.vin,
                    onOpen = { onOpen(profile.vin) },
                    onExport = { export(profile.vin) },
                    onDelete = { deleting = profile.vin },
                )
            }
        }
    }

    deleting?.let { vin ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $vin?") },
            text = { Text("The profile, its module scan, raw log, manual ECUs and module names are removed from this phone. Export it first if you want to keep a copy.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(vin) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    garage.pendingOverwrite?.let { bundle ->
        AlertDialog(
            onDismissRequest = onCancelOverwrite,
            title = { Text("Replace ${bundle.profile.vin}?") },
            text = {
                Text(
                    "A profile for this VIN already exists. Importing replaces its module scan, raw log, manual ECUs and " +
                        "module names with the file's (${bundle.modules.size} modules).",
                )
            },
            confirmButton = { TextButton(onClick = onConfirmOverwrite) { Text("Replace") } },
            dismissButton = { TextButton(onClick = onCancelOverwrite) { Text("Cancel") } },
        )
    }

    if (garage.importErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Cannot import this file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Nothing was changed. Problems found:", style = MaterialTheme.typography.bodyMedium)
                    garage.importErrors.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    if (garage.importErrors.size > 12) Text("… and ${garage.importErrors.size - 12} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun ProfileCard(profile: VehicleProfile, current: Boolean, exporting: Boolean, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.title + if (current) " (loaded)" else "", style = MaterialTheme.typography.titleSmall)
            Text(profile.vin, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            profile.protocol?.let { InfoRow("Protocol", it) }
            InfoRow("Last scan", profile.lastScanAtMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Never")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Open") }
                OutlinedButton(onClick = onExport, enabled = !exporting) { Text(if (exporting) "Exporting…" else "Export") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
