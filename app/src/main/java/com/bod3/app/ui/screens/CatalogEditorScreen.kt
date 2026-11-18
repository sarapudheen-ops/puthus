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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogNode
import com.bod3.app.catalog.CatalogPlatform

/** Metadata only: editing a name does not change its stable ID or copy inherited definitions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogEditorScreen(node: CatalogNode, isNew: Boolean, parentTitle: String, platforms: List<CatalogPlatform>,
    onSave: (CatalogNode) -> String?, onCancel: () -> Unit,
) {
    var name by rememberSaveable(node.id) { mutableStateOf(node.name) }
    var from by rememberSaveable(node.id) { mutableStateOf(node.yearFrom?.toString().orEmpty()) }
    var to by rememberSaveable(node.id) { mutableStateOf(node.yearTo?.toString().orEmpty()) }
    var engine by rememberSaveable(node.id) { mutableStateOf(node.engine.orEmpty()) }
    var fuel by rememberSaveable(node.id) { mutableStateOf(node.fuel.orEmpty()) }
    var transmission by rememberSaveable(node.id) { mutableStateOf(node.transmission.orEmpty()) }
    var notes by rememberSaveable(node.id) { mutableStateOf(node.notes.orEmpty()) }
    var platformIds by rememberSaveable(node.id) { mutableStateOf(node.platformIds.toList()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("${if (isNew) "Add" else "Edit"} ${node.level.label.lowercase()}") },
        navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
        actions = { TextButton(onClick = {
            error = when {
                from.isNotBlank() && from.toIntOrNull() == null -> "Year from must be a whole year"
                to.isNotBlank() && to.toIntOrNull() == null -> "Year to must be a whole year"
                else -> onSave(node.copy(name = name.trim(), yearFrom = from.toIntOrNull(), yearTo = to.toIntOrNull(),
                    engine = engine.trim().ifBlank { null }, fuel = fuel.trim().ifBlank { null }, transmission = transmission.trim().ifBlank { null },
                    platformIds = platformIds, notes = notes.trim().ifBlank { null }))
            }
        }, enabled = name.isNotBlank()) { Text("Save") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(parentTitle.ifBlank { "Root of the manual catalog" }, style = MaterialTheme.typography.labelLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text("${node.level.label} name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (node.level == CatalogLevel.GENERATION) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("Year from") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(to, { to = it }, label = { Text("Year to (blank = open)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("Use documented generation years. Open ranges are not a diagnostic coverage promise.", style = MaterialTheme.typography.bodySmall)
            }
            if (node.level == CatalogLevel.VARIANT) {
                OutlinedTextField(engine, { engine = it }, label = { Text("Engine (code / displacement)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fuel, { fuel = it }, label = { Text("Fuel") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(transmission, { transmission = it }, label = { Text("Transmission") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes / source") }, modifier = Modifier.fillMaxWidth())
            SectionCard("Reusable platform data") {
                Text("Only attach a platform when its documented definitions apply. No platform is chosen automatically. Your local declarations override it, and children inherit it.", style = MaterialTheme.typography.bodySmall)
                platforms.forEach { platform ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = platform.id in platformIds, onCheckedChange = { checked -> platformIds = if (checked) platformIds + platform.id else platformIds - platform.id })
                        Column { Text(platform.name); Text(platform.data.summary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Text("Save the level, then use ECUs, PID editor, Tests, Coding or Connection to add data here. Empty children inherit their parent's data. Saving never selects a vehicle or executes a diagnostic command.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
