package com.bod3.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.VehicleSource
import com.bod3.app.ui.UiState

/** Make/model selection (bundled and user vehicles) and the ECU list of the selected vehicle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(
    state: UiState,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
    onEdit: (String?) -> Unit,
    onOpenEcu: (EcuDefinition) -> Unit,
) {
    val data = state.data
    var query by rememberSaveable { mutableStateOf("") }
    var showList by rememberSaveable { mutableStateOf(data.selectedVehicle == null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vehicle & ECUs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, contentDescription = "Add vehicle") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val selected = data.selectedVehicle
            if (selected != null) {
                item {
                    SectionCard(selected.vehicle.title) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val source = when (selected.source) {
                                VehicleSource.BUNDLED -> selected.packName
                                VehicleSource.USER -> "Your vehicle"
                                VehicleSource.USER_MODIFIED -> "${selected.packName} · edited"
                            }
                            Text("${selected.vehicle.ecus.size} ECUs · $source", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onEdit(selected.vehicle.key) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ECU list") }
                        }
                        if (!state.isConnected) {
                            Text("Connect to the adapter to read an ECU.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        selected.vehicle.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                items(selected.vehicle.ecus, key = { "ecu-" + it.key }) { ecu ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = state.isConnected) { onOpenEcu(ecu) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ecu.address ?: "–", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ecu.name)
                            Text(
                                "${ecu.requestId} → ${ecu.responseId} · ${if (ecu.is29Bit) "29-bit" else "11-bit"} · ${ecu.protocol.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    TextButton(onClick = { showList = !showList }) { Text(if (showList) "Hide vehicle list" else "Change vehicle") }
                }
            }
            if (showList || selected == null) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search make or model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val shown = data.vehicles.filter { query.isBlank() || it.vehicle.title.contains(query, ignoreCase = true) }
                    .sortedWith(compareBy({ it.vehicle.make.lowercase() }, { it.vehicle.model.lowercase() }))
                items(shown, key = { "vehicle-" + it.vehicle.key }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(entry.vehicle.key); showList = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = entry.vehicle.key == selected?.vehicle?.key, onClick = { onSelect(entry.vehicle.key); showList = false })
                        Column(Modifier.weight(1f)) {
                            Text(entry.vehicle.title)
                            val source = when (entry.source) {
                                VehicleSource.BUNDLED -> entry.packName
                                VehicleSource.USER -> "Your vehicle"
                                VehicleSource.USER_MODIFIED -> "Edited"
                            }
                            Text("${entry.vehicle.ecus.size} ECUs · $source", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("No vehicle matches. Tap + to add your own.") }
                if (selected != null) item { TextButton(onClick = { onSelect(null) }) { Text("No vehicle selected") } }
            }
            if (data.packErrors.isNotEmpty()) {
                item {
                    SectionCard("Data pack problems") {
                        data.packErrors.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
