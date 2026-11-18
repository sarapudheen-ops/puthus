package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.packs.BundledPack
import com.bod3.app.packs.DataExport
import com.bod3.app.packs.ImportPreview
import com.bod3.app.packs.PackFiles
import com.bod3.app.packs.PackKind
import com.bod3.app.ui.PacksState
import java.io.File

/** Everything the data packs screen can ask for. */
class DataPacksActions(
    val onBack: () -> Unit,
    val onImportText: (String) -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelImport: () -> Unit,
    val onDismissMessage: () -> Unit,
    val writeData: suspend (DataExport) -> File,
    val writeMyActuators: suspend () -> File,
    val writeMyCoding: suspend () -> File,
    val writeBundled: suspend (String, PackKind) -> File,
    val writeSample: suspend (String) -> File,
)

private const val JSON_MIME = "application/json"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPacksScreen(state: PacksState, actions: DataPacksActions) {
    val pickFile = rememberJsonImport(PackFiles.MAX_BYTES, actions.onImportText)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data packs") },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.message?.let { MessageCard(it, actions.onDismissMessage) }

            SectionCard("Import") {
                Text(
                    "Import a manual catalog (.json or .csv), legacy PIDs/ECU lists/DTC packs, test/coding recipes or a saved profile. " +
                        "The entire file is checked first; preview counts and errors before confirming. One invalid item rejects the whole file. " +
                        "Disconnect before confirming catalog imports. Imports never select a vehicle; write/security items remain unverified.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = pickFile, enabled = !state.importing) { Text("Choose file…") }
            }

            SectionCard("Export catalog data") {
                val c = state.counts
                InfoRow("Custom PIDs", c.pids.toString())
                InfoRow("Catalog levels", c.vehicles.toString())
                InfoRow("Module names", c.moduleNames.toString())
                InfoRow("DTC descriptions", c.dtcs.toString())
                InfoRow("Test recipes", c.actuatorRecipes.toString())
                InfoRow("Coding recipes", c.codingRecipes.toString())
                HorizontalDivider()
                ShareButton("Catalog PID definitions (with hierarchy)", c.pids > 0) { actions.writeData(DataExport.PIDS) }
                ShareButton("Manual vehicle catalog (JSON)", c.vehicles > 0) { actions.writeData(DataExport.VEHICLES) }
                ShareButton("Complete catalog (all definitions)", c.pids + c.vehicles + c.moduleNames + c.dtcs > 0) { actions.writeData(DataExport.ALL) }
                ShareButton("My test recipes", c.actuatorRecipes > 0) { actions.writeMyActuators() }
                ShareButton("My coding recipes", c.codingRecipes > 0) { actions.writeMyCoding() }
                Text(
                    "Export a single make, model, generation or variant from Vehicle catalog. JSON keeps all data; ECU CSV contains hierarchy and ECU declarations only. Saved VIN profiles are exported from Saved vehicles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.samples.isNotEmpty()) {
                SectionCard("Templates") {
                    Text(
                        "Annotated example files showing every field. Share one to a computer, edit it, and import it here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.samples.forEach { name -> ShareButton(name.removeSuffix(".json"), true) { actions.writeSample(name) } }
                }
            }

            SectionCard("Bundled packs") {
                Text(
                    "Built into the app. Coding and test recipes in them are unverified until checked on your car.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.bundled.forEach { pack -> BundledRow(pack, actions) }
            }
        }
    }

    state.preview?.let { preview -> PreviewDialog(preview, state.importing, actions) }
}

@Composable
private fun ShareButton(label: String, enabled: Boolean, write: suspend () -> File) {
    val share = rememberShare(JSON_MIME, "bod3: $label", "Share $label", write)
    OutlinedButton(onClick = share, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Share: $label") }
}

@Composable
private fun BundledRow(pack: BundledPack, actions: DataPacksActions) {
    val share = rememberShare(JSON_MIME, "bod3: ${pack.name}", "Share ${pack.name}") { actions.writeBundled(pack.id, pack.kind) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${pack.name} (v${pack.version})", style = MaterialTheme.typography.bodyLarge)
            Text("${pack.kind.label} · ${pack.contents}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = share) { Text("Share") }
    }
}

@Composable
private fun PreviewDialog(preview: ImportPreview, busy: Boolean, actions: DataPacksActions) {
    AlertDialog(
        onDismissRequest = { if (!busy) actions.onCancelImport() },
        title = { Text(if (preview.canImport) "Import ${preview.title}?" else preview.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                preview.kind?.let { Text(it.label, style = MaterialTheme.typography.labelLarge) }
                preview.summary.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (preview.warnings.isNotEmpty()) {
                    Text("Warnings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    preview.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (preview.errors.isNotEmpty()) {
                    preview.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Fix the file and import it again. Nothing was changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (preview.canImport) {
                Button(onClick = actions.onConfirmImport, enabled = !busy) { Text("Import") }
            } else {
                TextButton(onClick = actions.onCancelImport) { Text("Close") }
            }
        },
        dismissButton = {
            if (preview.canImport) TextButton(onClick = actions.onCancelImport, enabled = !busy) { Text("Cancel") }
        },
    )
}
