package com.bod3.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.coding.BackupStatus
import com.bod3.app.coding.CodingBackup
import com.bod3.app.coding.CodingEntry
import com.bod3.app.coding.CodingUiState
import com.bod3.app.recipe.Hex
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Coding: recipes grouped by module, import/export, new recipe, backups with rollback, write history. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CodingScreen(
    state: CodingUiState,
    expertMode: Boolean,
    connected: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNewRecipe: () -> Unit,
    onImportText: (String) -> Unit,
    writeExport: suspend () -> File,
    writeAuditLog: suspend () -> File,
    onRequestRollback: (String) -> Unit,
    onConfirmRollback: () -> Unit,
    onCancelRollback: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val startImport = rememberJsonImport(onText = onImportText)
    val exportMine = rememberShare("application/json", "bod3 coding recipes", "Share recipes", writeExport)
    val shareAudit = rememberShare("text/plain", "bod3 audit log", "Share audit log", writeAuditLog)
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coding") },
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
                    WarningCard("Expert mode is off. Values can be read and backed up, but nothing can be written. Turn on Expert mode in Settings (after acknowledging the risks) to code modules.") {
                        TextButton(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
            } else if (!connected) {
                item { WarningCard("Not connected to a CAN vehicle. Connect to read or write coding.") {} }
            }
            if (state.busy) item { WarningCard("Coding in progress – keep the ignition on and the adapter connected.") {} }
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
                    "Each change reads the module's current value and saves a backup first, shows the difference, and " +
                        "writes only after you confirm. The value is read back to verify it, and any write can be rolled back " +
                        "from its backup. Battery ≥ 12 V and engine off are required unless a recipe says otherwise, and every " +
                        "write is recorded in the audit log.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val groups = state.entries.groupBy { it.recipe.module.display }
            groups.forEach { (module, entries) ->
                item { Text(module, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(entries, key = { it.recipe.id }) { entry -> CodingRecipeRow(entry) { onOpen(entry.recipe.id) } }
            }
            if (state.entries.isEmpty()) item { Text("No recipes. Import a bod3-coding JSON file or create one.") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onNewRecipe, enabled = !state.busy) { Text("New recipe") }
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
            if (state.backups.isNotEmpty()) {
                item { Text("Backups", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(state.backups.take(30), key = { "backup-" + it.id }) { backup ->
                    BackupCard(backup, dateFormat, enabled = expertMode && connected && !state.busy) { onRequestRollback(backup.id) }
                }
            }
            if (state.history.isNotEmpty()) {
                item { Text("Recent writes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
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

    state.rollbackCandidate?.let { RollbackDialog(it, onConfirmRollback, onCancelRollback) }
}

@Composable
private fun CodingRecipeRow(entry: CodingEntry, onClick: () -> Unit) {
    val recipe = entry.recipe
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
            if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    recipe.method.label,
                    "%04X".format(recipe.did),
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

/** One saved backup with its status and a rollback button. */
@Composable
fun BackupCard(backup: CodingBackup, dateFormat: DateFormat, enabled: Boolean, onRollback: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(backup.recipeName, style = MaterialTheme.typography.bodyMedium)
            Text(
                dateFormat.format(Date(backup.createdAtMs)) + " · " + backup.module.display + (backup.vin?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("%04X = %s".format(backup.did, Hex.format(backup.data)), style = MaterialTheme.typography.bodySmall)
            Text(
                backup.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (backup.status == BackupStatus.WRITE_FAILED || backup.status == BackupStatus.WRITE_UNKNOWN) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (backup.status != BackupStatus.SAVED) {
                TextButton(onClick = onRollback, enabled = enabled) { Text("Roll back to this value…") }
            }
        }
    }
}

/** Confirmation before writing a backup back to its module. */
@Composable
fun RollbackDialog(backup: CodingBackup, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Roll back \"${backup.recipeName}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(backup.module.display, style = MaterialTheme.typography.bodyMedium)
                Text("Writes %04X = %s (the value saved before the change), then reads it back.".format(backup.did, Hex.format(backup.data)), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sure, onCheckedChange = { sure = it })
                    Text("Ignition on, engine off, battery support connected", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = sure) { Text("Roll back") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
