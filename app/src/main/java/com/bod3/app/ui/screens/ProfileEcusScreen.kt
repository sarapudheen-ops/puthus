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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.ui.UiState

/**
 * The loaded vehicle profile's manual ECU list (probed directly by every scan) and the data-pack
 * vehicle used to name its modules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEcusScreen(
    state: UiState,
    onBack: () -> Unit,
    onSave: (ecu: EcuDefinition, originalKey: String?) -> String?,
    onDelete: (String) -> Unit,
    onCopyFromPack: () -> Unit,
    onSelectVehicle: (String?) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val profile = state.savedProfile
    val extras = state.profileExtras
    val editable = profile != null && state.profileExtrasError == null
    var editing by remember { mutableStateOf<EcuDefinition?>(null) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val tree = state.catalog.tree
    val packVehicle = tree.resolve(extras.catalogNodeId ?: state.catalog.selectedId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ECU list")
                        Text(profile?.title ?: "No profile", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { editing = EcuDefinition(null, "", "", ""); editingKey = null }, enabled = editable) {
                        Icon(Icons.Filled.Add, contentDescription = "Add ECU")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.profileExtrasError?.let { error -> item {
                SectionCard("Saved additions need recovery") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } }
            item {
                SectionCard("Saved catalog link (manual)") {
                    Text("A link is metadata, not a VIN-driven selection. Loading this profile never applies its connection settings or ECU probe list. Select a catalog vehicle explicitly before connecting.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(packVehicle?.title ?: if (extras.catalogNodeId != null) "Linked catalog level is missing; the saved link is retained" else "No catalog link or manual selection")
                    TextButton(onClick = onOpenCatalog, enabled = editable) { Text("Browse / choose a catalog link…") }
                    if (state.catalog.selectedId != null) TextButton(onClick = { onSelectVehicle(state.catalog.selectedId) }, enabled = editable) { Text("Link the current manual selection") }
                    if (extras.catalogNodeId != null || extras.vehicleKey != null) TextButton(onClick = { onSelectVehicle(null) }, enabled = editable) { Text("Remove saved link") }
                    if (packVehicle != null && packVehicle.data.ecus.isNotEmpty()) OutlinedButton(onClick = onCopyFromPack, enabled = editable) { Text("Copy its ${packVehicle.data.ecus.size} ECUs into this profile's manual list") }
                }
            }
            item {
                Text(
                    "Manual ECUs are probed directly by every scan with their exact reply ID, so modules outside the scanned " +
                        "ranges or with unusual reply IDs are found. Turn on Quick scan to probe only this list. " +
                        "KWP entries are retained but excluded from the direct CAN/UDS scan; enhanced KWP requests are not guessed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (extras.manualEcus.isEmpty() && state.profileExtrasError == null) item { Text("No manual ECUs. Tap + to add one.") }
            items(extras.manualEcus, key = { it.key }) { ecu ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ecu.display, style = MaterialTheme.typography.titleSmall)
                            Text("${ecu.requestId} → ${ecu.responseId} · ${ecu.protocol.label}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            ecu.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = { editing = ecu; editingKey = ecu.key }) { Text("Edit") }
                        TextButton(onClick = { deleting = ecu.key }) { Text("Delete") }
                    }
                }
            }
        }
    }

    editing?.let { ecu ->
        ManualEcuDialog(
            initial = ecu,
            isNew = editingKey == null,
            onDismiss = { editing = null },
            onSave = { updated -> onSave(updated, editingKey).also { error -> if (error == null) editing = null } },
        )
    }

    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ECU $key?") },
            text = { Text("It is no longer probed by scans or used for naming. Scan results are not changed.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(key) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ManualEcuDialog(initial: EcuDefinition, isNew: Boolean, onDismiss: () -> Unit, onSave: (EcuDefinition) -> String?) {
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address ?: "") }
    var request by remember { mutableStateOf(initial.requestId) }
    var response by remember { mutableStateOf(initial.responseId) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var notes by remember { mutableStateOf(initial.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val hexOnly = { text: String -> text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(8) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add ECU" else "Edit ECU") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it.take(6) }, label = { Text("Address / short code (optional, e.g. 09)") }, singleLine = true)
                OutlinedTextField(
                    value = request, onValueChange = { request = hexOnly(it) }, label = { Text("Request ID (e.g. 70E or 18DA19F1)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = response, onValueChange = { response = hexOnly(it) }, label = { Text("Response ID (e.g. 778 or 18DAF119)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EcuProtocol.entries.forEach { p -> FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p.label) }) }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ecu = EcuDefinition(address.trim().ifEmpty { null }, name.trim(), request, response, protocol, notes.trim().ifEmpty { null })
                error = onSave(ecu)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
