package com.bod3.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bod3.app.actuator.ActuatorEntry
import com.bod3.app.actuator.ActuatorUiState
import com.bod3.app.android.ReportSharer
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads a picked file as UTF-8 text (size-limited) and hands it to [onText]; errors become a toast. */
@Composable
fun rememberJsonImport(maxBytes: Int = com.bod3.app.packs.PackFiles.MAX_BYTES, onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        com.bod3.app.packs.PackTextReader.read(input, maxBytes)
                    } ?: throw IllegalStateException("The file could not be opened")
                }
                onText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    return { launcher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "application/vnd.ms-excel")) }
}

/** Shares a file produced by [write] through the share sheet; failures become a toast. */
@Composable
fun rememberShare(mimeType: String, subject: String, chooserTitle: String, write: suspend () -> File): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            try {
                ReportSharer.share(context, write(), mimeType, subject, chooserTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Bidirectional tests: recipes grouped by module, import/export, run history and audit log export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActuatorsScreen(
    state: ActuatorUiState,
    expertMode: Boolean,
    connected: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onImportText: (String) -> Unit,
    writeExport: suspend () -> File,
    writeAuditLog: suspend () -> File,
    onDismissMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val startImport = rememberJsonImport(onText = onImportText)
    val exportMine = rememberShare("application/json", "bod3 actuator recipes", "Share recipes", writeExport)
    val shareAudit = rememberShare("text/plain", "bod3 audit log", "Share audit log", writeAuditLog)
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bidirectional tests") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = startImport) { Text("Import") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!expertMode) {
                item {
                    WarningCard("Expert mode is off. Tests can be browsed but not run. Turn on Expert mode in Settings (after acknowledging the risks) to run them.") {
                        TextButton(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
            } else if (!connected) {
                item { WarningCard("Not connected to a CAN vehicle. Connect to run tests.") {} }
            }
            state.message?.let { message -> item { MessageCard(message, onDismissMessage) } }
            if (state.importErrors.isNotEmpty()) {
                item {
                    SectionCard("Import failed") {
                        state.importErrors.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        if (state.importErrors.size > 20) Text("… and ${state.importErrors.size - 20} more", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDismissMessage) { Text("OK") }
                    }
                }
            }
            item {
                Text(
                    "Each test opens a diagnostic session on one module, starts an output or routine for a limited time and " +
                        "always sends the stop request. Battery voltage, ignition, engine state and vehicle speed are checked " +
                        "first, and every run is written to the audit log.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val groups = state.entries.groupBy { it.recipe.module.display }
            groups.forEach { (module, entries) ->
                item { Text(module, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(entries, key = { it.recipe.id }) { entry -> RecipeRow(entry) { onOpen(entry.recipe.id) } }
            }
            if (state.entries.isEmpty()) item { Text("No recipes. Import a bod3-actuators JSON file.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = exportMine, enabled = state.entries.any { it.isUser }) { Text("Export my recipes") }
                    OutlinedButton(onClick = shareAudit) { Text("Share audit log") }
                }
            }
            if (state.packErrors.isNotEmpty()) {
                item {
                    SectionCard("Problems in bundled recipes") {
                        state.packErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (state.history.isNotEmpty()) {
                item { Text("Recent runs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(state.history, key = { it.id }) { record ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${if (record.success) "✓" else "✗"} ${record.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(dateFormat.format(Date(record.startedAtMs)) + " · " + record.module, style = MaterialTheme.typography.bodySmall)
                            Text(record.outcome, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(entry: ActuatorEntry, onClick: () -> Unit) {
    val recipe = entry.recipe
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
            if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    recipe.kind?.label,
                    "max ${recipe.durationSeconds} s",
                    if (recipe.security != null) "security access" else null,
                    if (!recipe.verified) "unverified" else null,
                    entry.packName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun WarningCard(text: String, action: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            action()
        }
    }
}

@Composable
fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
