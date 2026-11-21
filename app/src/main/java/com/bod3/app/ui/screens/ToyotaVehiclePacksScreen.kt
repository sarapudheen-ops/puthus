package com.bod3.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bod3.app.bringup.BringUpController
import com.bod3.app.bringup.BringUpState
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogNode
import com.bod3.app.diagnostics.EcuWorkbench
import java.io.File
import com.bod3.app.vehiclepack.PackView
import com.bod3.app.vehiclepack.VehiclePacksController
import com.bod3.app.vehiclepack.VehiclePacksState

/** Only selected-vehicle/system content. No legacy templates, inferred vehicle or generic fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToyotaVehiclePacksScreen(
    state: VehiclePacksState, controller: VehiclePacksController, offline: Boolean, connected: Boolean,
    onBack: () -> Unit, onLive: () -> Unit, onTests: () -> Unit, onCoding: () -> Unit,
    onDiagnostics: () -> Unit, bringUp: BringUpState, bringUpController: BringUpController,
    onStartBringUp: () -> Unit, writeBringUpReport: suspend () -> File, writeVerifiedSetups: suspend () -> File,
    onImportSetups: () -> Unit, onConnect: () -> Unit = {}, onSettings: () -> Unit = {},
    names: List<CatalogNode> = emptyList(),
) {
    var localBrandId by rememberSaveable { mutableStateOf<String?>(null) }
    var localModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val localBrand = names.firstOrNull { it.id == localBrandId && it.level == CatalogLevel.MAKE }
    val localModel = names.firstOrNull { it.id == localModelId && it.parentId == localBrandId && it.level == CatalogLevel.MODEL }
    val atBrands = state.view == PackView.PICKER && state.page?.level in listOf(null, "root")
    val localBack = {
        if (localModelId != null) localModelId = null else localBrandId = null
    }
    BackHandler(enabled = atBrands && localBrandId != null, onBack = localBack)
    val back = { if (atBrands && localBrandId != null) localBack() else onBack() }
    var query by rememberSaveable(state.page?.id, localBrandId, localModelId) { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (state.view) { PackView.PICKER -> "Cars"; PackView.SYSTEMS -> "Car"; PackView.FEATURES -> state.vehicle?.title ?: "Car" }) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (!connected) TextButton(onClick = onConnect) { Text("Connect") }
                TextButton(onClick = onSettings) { Text("Settings") }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("toyotaPackContent"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.view != PackView.PICKER) item {
                SectionCard(state.vehicle?.title ?: "Car") {
                    Text("Read-only engine data for this car.", style = MaterialTheme.typography.bodySmall)
                    state.coverage?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                }
            }
            state.error?.let { message -> item { SectionCard("Cars unavailable") { Text(message, color = MaterialTheme.colorScheme.error) } } }
            when (state.view) {
                PackView.PICKER -> {
                    if (atBrands) {
                        val level = when {
                            localModel != null -> CatalogLevel.GENERATION
                            localBrand != null -> CatalogLevel.MODEL
                            else -> CatalogLevel.MAKE
                        }
                        val parent = localModel?.id ?: localBrand?.id
                        val choices = names.filter { it.level == level && it.parentId == parent &&
                            it.label.contains(query, ignoreCase = true) }.sortedBy { it.name.lowercase() }
                        item {
                            Text(when (level) { CatalogLevel.MAKE -> "Choose a brand"; CatalogLevel.MODEL -> "Choose a model"; else -> "Choose your car" },
                                style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(localBrand?.name, localModel?.name).joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                            if (localModel == null) OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (localModel != null) item {
                            Text("Names and model years only. Read-only diagnostic data is not available for this car.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        items(choices, key = { it.id }) { choice ->
                            OutlinedButton(onClick = {
                                when (level) {
                                    CatalogLevel.MAKE -> if (choice.name.equals("Toyota", ignoreCase = true)) controller.openBrand(choice.name)
                                        else localBrandId = choice.id
                                    CatalogLevel.MODEL -> localModelId = choice.id
                                    else -> Unit
                                }
                            }, enabled = !state.busy && level != CatalogLevel.GENERATION, modifier = Modifier.fillMaxWidth()) { Text(choice.label) }
                        }
                        if (choices.isEmpty() && !state.busy) item { Text("No matches.") }
                    } else {
                        item {
                            Text(if (state.page?.level == "make") "Choose a model" else "Choose your car",
                                style = MaterialTheme.typography.titleMedium)
                            Text((state.trail + listOfNotNull(state.page)).filter { it.level != "root" }.joinToString(" / ") { it.name },
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        val generations = when (state.page?.level) {
                            "model" -> state.page
                            "generation" -> state.trail.lastOrNull { it.level == "model" }
                            else -> null
                        }
                        if (generations != null && state.page?.level == "generation") {
                            item {
                                Text("Generation", style = MaterialTheme.typography.titleSmall)
                                generations.children.forEach { generation ->
                                    OutlinedButton(onClick = { controller.changeGeneration(generation) },
                                        enabled = !state.busy && generation.id != state.page?.id,
                                        modifier = Modifier.fillMaxWidth()) { Text(generation.name) }
                                }
                                Text("Choose your car", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        val children = state.page?.children.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
                        items(children, key = { it.id }) { choice ->
                            OutlinedButton(onClick = { if (choice.level == "variant") controller.selectCar(choice) else controller.browse(choice) },
                                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(choice.name) }
                        }
                        if (!state.busy && children.isEmpty()) item { Text("No matches.") }
                    }
                }
                PackView.SYSTEMS -> {
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        item { Text(vehicle.title, style = MaterialTheme.typography.titleSmall) }
                        val enabled = vehicle.systems.filter { it.enabled }
                        if (enabled.isEmpty()) item { Text("This car has no converted read-only system in this build.") }
                        items(enabled, key = { it.id }) { system ->
                            SectionCard(system.name) {
                                Text("Read-only", color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(onClick = { controller.openSystem(system) }, enabled = !state.busy) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
                PackView.FEATURES -> {
                    val system = state.system
                    if (system != null) {
                        val verified = bringUp.verifiedHere
                        val runtime = bringUp.report?.workingCandidate?.runtime
                        val rawData = if (verified) runtime?.data ?: CatalogData() else CatalogData()
                        val data = if (runtime?.identityKind == com.bod3.app.bringup.EcuIdentityKind.ECU_SERIAL) rawData else rawData.copy(actuators = emptyList(), coding = emptyList())
                        item { CarReadSection(bringUp, connected, onStartBringUp) }
                        item {
                            SectionCard(system.name) {
                                Text(state.vehicle?.title.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                state.coverage?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                                if (system.enabled) {
                                    Text("Read-only: clearing, coding, actuators, security access and flashing are excluded from this system.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            SectionCard("Live data") {
                                Button(onClick = onLive, enabled = connected && verified && data.pids.isNotEmpty()) { Text("Select values, graph and record") }
                                when {
                                    !system.enabled -> Text("No executable PID request/byte-position/formula mapping is available for this system.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Waiting for a successful read on this connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    data.pids.isEmpty() -> Text("No executable PID request/byte-position/formula mapping is available for this system.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            SectionCard("Fault codes") {
                                val available = verified && data.ecus.singleOrNull()?.let(EcuWorkbench::canReadDtcs) == true
                                OutlinedButton(onClick = onDiagnostics, enabled = connected && available) { Text("Read fault codes") }
                                when {
                                    !system.enabled -> Text("Fault code reading is unavailable for this car.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Waiting for a successful read on this connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !available -> Text("Fault code reading is unavailable for this car.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Clearing requires a documented group and a separate confirmation. Freeze frames remain disabled without an exact record mapping.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            SectionCard("Information") {
                                val available = verified && data.ecus.singleOrNull()?.let(EcuWorkbench::canReadIdentification) == true
                                OutlinedButton(onClick = onDiagnostics, enabled = connected && available) { Text("ECU information") }
                                when {
                                    !system.enabled -> Text("ECU information is unavailable for this car.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Waiting for a successful read on this connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !available -> Text("ECU information is unavailable for this car.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One compact, consumer-facing read status. No candidate list, no instance label, no source IDs. */
@Composable
private fun CarReadSection(bringUp: BringUpState, connected: Boolean, onStart: () -> Unit) {
    SectionCard("Engine") {
        when {
            bringUp.running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading this car…", style = MaterialTheme.typography.bodySmall)
            }
            bringUp.verifiedHere -> Text("Read-only data verified on this connection.", color = MaterialTheme.colorScheme.primary)
            bringUp.report != null -> {
                Text("The last read did not finish.", color = MaterialTheme.colorScheme.error)
                bringUp.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onStart, enabled = connected && !bringUp.running) { Text("Try again") }
            }
            else -> {
                Text(if (connected) "Ready to read." else "Connect the adapter to read this car.")
                bringUp.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onStart, enabled = connected && bringUp.vehicleInstance.isNotBlank()) { Text("Read now") }
            }
        }
    }
}
