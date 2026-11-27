package com.bod3.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.bod3.app.diagnostics.EcuWorkbench
import java.io.File
import com.bod3.app.vehiclepack.PackSystemAction
import com.bod3.app.vehiclepack.PackSystemStatusUi
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
    onImportSetups: () -> Unit,
) {
    fun reason(key: String) = state.reasons[key] ?: key
    // Local UI-only filter state; resets when another variant is selected. The pure logic lives in
    // PackSystemStatusUi and runs over the selected vehicle's already-loaded descriptors only.
    var systemSearch by rememberSaveable(state.vehicle?.variantId) { mutableStateOf("") }
    var linkVerifiedOnly by rememberSaveable(state.vehicle?.variantId) { mutableStateOf(false) }
    var systemPage by rememberSaveable(state.vehicle?.variantId, systemSearch, linkVerifiedOnly) { mutableStateOf(0) }
    var category by rememberSaveable(state.system?.id) { mutableStateOf("") }
    val visibleSystems = PackSystemStatusUi.filter(state.vehicle?.systems ?: emptyList(), systemSearch, linkVerifiedOnly)
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (state.view) { PackView.PICKER -> "Toyota vehicle packs"; PackView.SYSTEMS -> "Vehicle systems"; PackView.FEATURES -> state.system?.name ?: "System features" }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("toyotaPackContent"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SectionCard(if (state.complete) "Source-verified conversion" else "Conversion incomplete — unresolved features disabled") {
                    Text("${state.executableDefinitions} executable recovered definitions in this build.", style = MaterialTheme.typography.bodySmall)
                    Text("MIC3313 adapter equivalence and physical validation are unproven; source-exact values are not adapter-verified.", style = MaterialTheme.typography.bodySmall)
                    Text("Only the chosen vehicle path and system are downloaded. Cached objects work offline. Browsing and downloading never send ECU requests.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let { message -> item { SectionCard("Pack not loaded") { Text(message, color = MaterialTheme.colorScheme.error); Text("No generic ECU, PID or write template was substituted.", style = MaterialTheme.typography.bodySmall) } } }
            when (state.view) {
                PackView.PICKER -> {
                    val preview = state.preview
                    if (preview == null) {
                        item {
                            Text("Make → Model → Generation → Variant", style = MaterialTheme.typography.titleSmall)
                            Text((state.trail + listOfNotNull(state.page)).filter { it.level != "root" }.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall)
                            Text("Browse source-derived vehicle paths. Unresolved hierarchy choices and feature mappings are recorded in the conversion report; they are not guessed.", style = MaterialTheme.typography.bodySmall)
                        }
                        items(state.page?.children.orEmpty(), key = { it.id }) { choice ->
                            OutlinedButton(onClick = { controller.browse(choice) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) { Text(choice.name); Text(choice.level, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        if (!state.busy && state.page?.children?.isEmpty() == true) item { Text("No reviewed vehicle choices are available in this conversion.") }
                        if (!state.busy && state.page == null) item { OutlinedButton(onClick = controller::picker) { Text("Retry loading catalog") } }
                    } else {
                        item {
                            SectionCard("Review exact source-bound vehicle") {
                                Text(preview.title, style = MaterialTheme.typography.titleSmall)
                                val variant = preview.path.nodes.last()
                                InfoRow("Engine", variant.engine ?: "Not specified by this source branch")
                                InfoRow("Fuel", variant.fuel ?: "Not specified by this source branch")
                                InfoRow("Transmission", variant.transmission ?: "Not specified by this source branch")
                                variant.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text("Exact source selection: " + preview.sourceSelection.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                                Text("${preview.systems.size} source systems · ${preview.systems.count { it.enabled }} resolved systems", style = MaterialTheme.typography.labelLarge)
                                preview.reasons.forEach { Text(reason(it), style = MaterialTheme.typography.bodySmall) }
                                Button(onClick = controller::selectPreview, enabled = offline && !state.busy && preview.hierarchyResolved) { Text("Select this variant and open systems") }
                                if (!offline) Text("Disconnect before changing the manually selected vehicle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                Text("This is an explicit manual choice. VIN/profile loading never makes this choice for you.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                PackView.SYSTEMS -> {
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        val visible = visibleSystems
                        item { Text(vehicle.title, style = MaterialTheme.typography.titleSmall) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = systemSearch, onValueChange = { systemSearch = it },
                                    label = { Text("Search systems by name") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = linkVerifiedOnly, onClick = { linkVerifiedOnly = !linkVerifiedOnly },
                                        label = { Text("Link verified") })
                                    Text("${visible.size} of ${vehicle.systems.size} systems", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { systemPage-- }, enabled = systemPage > 0) { Text("Previous") }
                                Text("Page ${systemPage + 1} / ${maxOf(1, (visible.size + PackSystemStatusUi.SYSTEM_PAGE_SIZE - 1) / PackSystemStatusUi.SYSTEM_PAGE_SIZE)}")
                                TextButton(onClick = { systemPage++ }, enabled = (systemPage + 1) * PackSystemStatusUi.SYSTEM_PAGE_SIZE < visible.size) { Text("Next") }
                            }
                        }
                        items(PackSystemStatusUi.page(visible, systemPage), key = { it.id }) { system ->
                            SectionCard(system.name) {
                                Text(PackSystemStatusUi.linkBadge(system.linkStatus, system.linkReasons),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (system.linkStatus == "proven") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                Text(if (system.enabled) "Enabled · read-only · source verified" else "Disabled · mapping incomplete",
                                    color = if (system.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                PackSystemStatusUi.categoryLines(system.categoryStatus).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                PackSystemStatusUi.dtcOnlyLines(system.categoryStatus).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                                system.counts.forEach { (kind, count) -> InfoRow(kind, count.label) }
                                if (system.reasons.isNotEmpty()) Text("Reason: " + system.reasons.joinToString(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                system.reasons.forEach { Text(reason(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                OutlinedButton(onClick = { controller.openSystem(system) }, enabled = !state.busy) {
                                    Text(if (system.enabled) "Open this system" else "Inspect disabled features and source IDs")
                                }
                            }
                        }
                        if (visible.isEmpty()) item { Text("No systems match this search and filter.") }
                    }
                }
                PackView.FEATURES -> {
                    val system = state.system
                    if (system != null) {
                        val verified = bringUp.verifiedHere
                        val runtime = bringUp.report?.workingCandidate?.runtime
                        val rawData = if (verified) runtime?.data ?: CatalogData() else CatalogData()
                        val data = if (runtime?.identityKind == com.bod3.app.bringup.EcuIdentityKind.ECU_SERIAL) rawData else rawData.copy(actuators = emptyList(), coding = emptyList())
                        val choice = state.vehicle?.systems?.firstOrNull { it.id == system.id }
                        val actions = PackSystemStatusUi.executableActions(choice)
                        val categories = choice?.let(PackSystemStatusUi::presentCategories).orEmpty()
                        val selectedCategory = category.takeIf { it in categories } ?: categories.firstOrNull()
                        item { BringUpSection(bringUp, bringUpController, connected, onStartBringUp, writeBringUpReport, writeVerifiedSetups, onImportSetups) }
                        item {
                            SectionCard(system.name) {
                                choice?.let { c ->
                                    Text(PackSystemStatusUi.linkBadge(c.linkStatus, c.linkReasons),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (c.linkStatus == "proven") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                }
                                Text(state.vehicle?.title.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                state.coverage?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                                if (system.enabled) {
                                    Text("Read-only: clearing, coding, actuators, security access and flashing are excluded from this system.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                choice?.let { c ->
                                    PackSystemStatusUi.categoryLines(c.categoryStatus).forEach {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    PackSystemStatusUi.dtcOnlyLines(c.categoryStatus).forEach {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                                system.counts.forEach { (kind, count) -> InfoRow(kind, count.label) }
                                if (system.reasons.isNotEmpty()) Text("Reason: " + system.reasons.joinToString(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                system.reasons.forEach { Text(reason(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                                Text("Source records: " + system.sourceRecords.joinToString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (categories.isNotEmpty()) item {
                            Column {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    categories.forEach { key ->
                                        FilterChip(selected = selectedCategory == key, onClick = { category = key }, label = { Text(when (key) {
                                            "pids" -> "Live data"; "dtcs" -> "Fault codes"; "ecuInfo" -> "ECU information"
                                            "actuators" -> "Active tests"; "coding" -> "Coding"; else -> "Special functions"
                                        }) })
                                    }
                                }
                                Text("$selectedCategory: ${choice?.categoryStatus?.get(selectedCategory) ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                                if (choice?.categoryStatus?.get(selectedCategory) != "executable") {
                                    system.reasons.forEach { Text(reason(it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        if (selectedCategory == "pids") item {
                            SectionCard("Live data") {
                                Button(onClick = onLive, enabled = PackSystemAction.LIVE in actions && connected && verified && data.pids.isNotEmpty()) { Text("Select values, graph and record") }
                                when {
                                    !system.enabled -> Text("No executable PID request/byte-position/formula mapping is available for this system.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Run the read-only ECU bring-up above on this connection to unlock live data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    data.pids.isEmpty() -> Text("No executable PID request/byte-position/formula mapping is available for this system.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (selectedCategory == "dtcs") item {
                            SectionCard("Fault codes") {
                                val available = verified && data.ecus.singleOrNull()?.let(EcuWorkbench::canReadDtcs) == true
                                OutlinedButton(onClick = onDiagnostics, enabled = PackSystemAction.FAULTS in actions && connected && available) { Text("Read fault codes") }
                                when {
                                    !system.enabled -> Text("The selected system's DTC read request and record decoder have not been converted. No generic UDS or emissions request will be substituted here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Run the read-only ECU bring-up above on this connection to unlock fault codes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !available -> Text("The selected system's DTC read request and record decoder have not been converted. No generic UDS or emissions request will be substituted here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Clearing requires a documented group and a separate confirmation. Freeze frames remain disabled without an exact record mapping.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (selectedCategory == "actuators") item {
                            SectionCard("Active tests") {
                                Button(onClick = onTests, enabled = PackSystemAction.TESTS in actions && verified && data.actuators.isNotEmpty()) { Text("View this system's tests") }
                                if (system.enabled && !verified) Text("Run the read-only ECU bring-up above on this connection to check available tests. Read-only restrictions still apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else if (data.actuators.isEmpty()) Text("Start/stop/status and safety mappings remain unresolved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Execution still requires Expert mode, one-time confirmation and passing safety checks. Output-off cleanup and audit remain mandatory.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selectedCategory == "coding") item {
                            SectionCard("Customize / Coding") {
                                Button(onClick = onCoding, enabled = PackSystemAction.CODING in actions && verified && data.coding.isNotEmpty()) { Text("View this system's customizations") }
                                if (system.enabled && !verified) Text("Run the read-only ECU bring-up above on this connection to check available customizations. Read-only restrictions still apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else if (data.coding.isEmpty()) Text("Read/write identifiers and value maps remain unresolved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Read → backup → diff → one-time confirmation → write → read-back. Rollback and audit use the existing guarded coding engine.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selectedCategory == "ecuInfo" || selectedCategory == "specialFunctions") item {
                            SectionCard("ECU information & special functions") {
                                val available = verified && data.ecus.singleOrNull()?.let(EcuWorkbench::canReadIdentification) == true
                                OutlinedButton(onClick = onDiagnostics, enabled = PackSystemAction.ECU_INFO in actions && connected && available) { Text("ECU information") }
                                when {
                                    !system.enabled -> Text("The exact identification definitions for this system have not been converted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !verified -> Text("Run the read-only ECU bring-up above on this connection to unlock ECU information.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    !available -> Text("The exact identification definitions for this system have not been converted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = {}, enabled = false) { Text("Special functions") }
                                Text("Special-function mappings remain unresolved. Unknown is not zero source support.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (system.unresolvedPages.isNotEmpty()) {
                            item {
                                SectionCard("Per-item unresolved accounting") {
                                    Text("Stable source IDs and explicit disabling reasons. At most 128 source rows are loaded at once; these are not executable definitions.", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { controller.unresolved((state.unresolvedPage ?: 1) - 1) }, enabled = !state.busy && (state.unresolvedPage == null || state.unresolvedPage!! > 0)) { Text(if (state.unresolvedPage == null) "Load first page" else "Previous") }
                                        TextButton(onClick = { controller.unresolved((state.unresolvedPage ?: -1) + 1) }, enabled = !state.busy && (state.unresolvedPage ?: -1) < system.unresolvedPages.lastIndex) { Text("Next") }
                                    }
                                    state.unresolvedPage?.let { Text("Page ${it + 1} / ${system.unresolvedPages.size}", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                            items(state.unresolvedItems, key = { it.id }) { row ->
                                Column { Text(row.id, style = MaterialTheme.typography.labelSmall); Text(row.kind, style = MaterialTheme.typography.bodySmall); row.reasons.forEach { Text(reason(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                            }
                        }
                    }
                }
            }
        }
    }
}
