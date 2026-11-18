package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.datapack.VehicleSource

/**
 * Edits a vehicle and its ECU list: name, address, request/response IDs (3 hex digits = 11-bit,
 * 8 = 29-bit) and protocol. Saving a bundled vehicle stores a user copy that replaces it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditorScreen(
    vehicle: VehicleDefinition,
    source: VehicleSource?,
    error: String?,
    onSave: (VehicleDefinition) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var make by rememberSaveable(vehicle.key) { mutableStateOf(vehicle.make) }
    var model by rememberSaveable(vehicle.key) { mutableStateOf(vehicle.model) }
    var years by rememberSaveable(vehicle.key) { mutableStateOf(vehicle.years.orEmpty()) }
    val ecus = remember(vehicle.key) { mutableStateListOf<EcuDefinition>().apply { addAll(vehicle.ecus) } }
    var confirmDelete by remember { mutableStateOf(false) }
    val bundledLocked = source == VehicleSource.BUNDLED || source == VehicleSource.USER_MODIFIED

    fun build() = VehicleDefinition(make, model, years.ifBlank { null }, ecus.toList(), vehicle.notes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (source == null) "New vehicle" else "Edit vehicle") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") } },
                actions = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(make, { make = it }, label = { Text("Make") }, singleLine = true, enabled = !bundledLocked, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, enabled = !bundledLocked, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(years, { years = it }, label = { Text("Years (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (bundledLocked) {
                Text(
                    "Bundled vehicle: your changes are saved as a copy that replaces the bundled ECU list. Make and model stay fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("ECUs (${ecus.size})", style = MaterialTheme.typography.titleMedium)
            ecus.forEachIndexed { index, ecu ->
                EcuEditor(ecu, onChange = { ecus[index] = it }, onDelete = { ecus.removeAt(index) })
            }
            OutlinedButton(onClick = {
                ecus.add(EcuDefinition(address = null, name = "", requestId = "", responseId = ""))
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add ECU")
            }
            when (source) {
                VehicleSource.USER -> OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete vehicle") }
                VehicleSource.USER_MODIFIED -> OutlinedButton(onClick = { confirmDelete = true }) { Text("Restore bundled ECU list") }
                else -> {}
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (source == VehicleSource.USER) "Delete ${vehicle.title}?" else "Restore bundled ECU list?") },
            text = { Text(if (source == VehicleSource.USER) "The vehicle and its ECU list are removed." else "Your changes to this vehicle are discarded.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EcuEditor(ecu: EcuDefinition, onChange: (EcuDefinition) -> Unit, onDelete: () -> Unit) {
    val hex = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
    SectionCard(ecu.display.ifBlank { "New ECU" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                ecu.address.orEmpty(), { onChange(ecu.copy(address = it.ifBlank { null })) },
                label = { Text("Addr") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.weight(0.3f),
            )
            OutlinedTextField(ecu.name, { onChange(ecu.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(0.7f))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ECU") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                ecu.requestId, { onChange(ecu.copy(requestId = it.trim().uppercase())) },
                label = { Text("Request ID") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                ecu.responseId, { onChange(ecu.copy(responseId = it.trim().uppercase())) },
                label = { Text("Response ID") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EcuProtocol.entries.forEach { protocol ->
                FilterChip(selected = ecu.protocol == protocol, onClick = { onChange(ecu.copy(protocol = protocol)) }, label = { Text(protocol.label) })
            }
            val bits = when (ecu.requestId.length) {
                3 -> "11-bit"
                8 -> "29-bit"
                else -> "3 or 8 hex digits"
            }
            Text(bits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val problems = ecu.problems()
        if (problems.isNotEmpty() && (ecu.requestId.isNotEmpty() || ecu.name.isNotEmpty())) {
            problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
