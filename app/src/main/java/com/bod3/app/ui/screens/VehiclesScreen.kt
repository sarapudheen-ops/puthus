package com.bod3.app.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogNode
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.catalog.CatalogSection
import com.bod3.app.ui.UiState
import java.io.File

class CatalogActions(
    val onBack: () -> Unit, val onBrowse: (String?) -> Unit, val onSelect: (String?) -> Unit,
    val onFavorite: (String) -> Unit, val onNew: (String?) -> Unit, val onEdit: (String) -> Unit,
    val onDuplicate: (String, String) -> Unit, val onDelete: (String) -> Unit,
    val onData: (String?, CatalogSection) -> Unit, val onPids: (String?) -> Unit,
    val onOpenEcu: (EcuDefinition) -> Unit, val onImport: () -> Unit,
    val writeExport: suspend (String?, Boolean) -> File,
    val onLinkProfile: (String?) -> Unit, val onDismissMessage: () -> Unit,
    val onToyotaSource: (String?) -> Unit,
)

/** Hierarchical manual browsing, cross-level search, favorites and recents. Never driven by a VIN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(state: UiState, actions: CatalogActions) {
    val catalog = state.catalog
    val tree = remember(catalog.pack) { catalog.tree }
    val focused = tree.node(catalog.browseId)
    val path = tree.path(catalog.browseId)
    val resolved = remember(catalog.pack, catalog.browseId) { tree.resolve(catalog.browseId) }
    val active = remember(catalog.pack, catalog.selectedId) { tree.resolve(catalog.selectedId) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(catalog.pack, query) { tree.search(query) }
    var duplicate by rememberSaveable { mutableStateOf(false) }
    var duplicateName by rememberSaveable(focused?.id) { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val jsonShare = rememberShare("application/json", "bod3 vehicle catalog", "Export catalog JSON") { actions.writeExport(focused?.id, false) }
    val csvShare = rememberShare("text/csv", "bod3 ECU catalog", "Export ECU CSV") { actions.writeExport(focused?.id, true) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (catalog.linkingProfile) "Link catalog to saved profile" else "Vehicle catalog") },
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { actions.onNew(focused?.id) }, enabled = focused?.level != CatalogLevel.VARIANT && state.offline) { Icon(Icons.Filled.Add, "Add ${focused?.level?.child?.label ?: "make"}") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("Manual selection") {
                    Text(active?.title ?: "No vehicle selected", style = MaterialTheme.typography.titleSmall)
                    Text("Make → Model → Generation → Variant. Browsing, importing and reading a VIN never select a vehicle. No generic vehicle fallback.", style = MaterialTheme.typography.bodySmall)
                    if (!state.offline) Text("Disconnect before changing vehicle or editing catalog configuration. ECU reads still use the current connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    if (active != null) TextButton(onClick = { actions.onSelect(null) }, enabled = state.offline) { Text("Clear manual selection") }
                }
            }
            if (focused == null || path.firstOrNull()?.name in setOf("Toyota", "Lexus", "Toyota Thailand")) item {
                SectionCard("Recovered Toyota diagnostic data") {
                    Text("The complete recovered corpus is bundled separately: manual source menus, regional ECU relationships, typed tables, all eight text libraries and frame dictionaries. Loaded on demand, without changing your selected configuration.", style = MaterialTheme.typography.bodySmall)
                    Text("Unresolved applicability, ECU addressing and formula mappings stay source-only; this is not automatic enhanced Toyota support.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { actions.onToyotaSource(path.firstOrNull { it.level == CatalogLevel.MODEL }?.name) }) {
                        Text(if (path.any { it.level == CatalogLevel.MODEL }) "Find this model in the source menu" else "Open complete Toyota data library")
                    }
                }
            }
            catalog.message?.let { message -> item { MessageCard(message, actions.onDismissMessage) } }
            if (catalog.errors.isNotEmpty()) item { SectionCard("Catalog problems — original data preserved") { catalog.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Search makes, models, generations, years, engines…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (query.isNotBlank()) {
                item { Text("${matches.size} results across all levels", style = MaterialTheme.typography.labelLarge) }
                items(matches, key = { "search-${it.id}" }) { n -> CatalogRow(n, tree.path(n.id).joinToString(" / ") { it.label }, n.id in catalog.favorites,
                    { query = ""; actions.onBrowse(n.id) }, { actions.onFavorite(n.id) }) }
                if (matches.isEmpty()) item { Text("No matches. Add a make/model/generation/variant manually, or import a reviewed pack.") }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { actions.onBrowse(null) }) { Text("All makes") }
                        if (focused != null) TextButton(onClick = { actions.onBrowse(focused.parentId) }) { Text("↑ ${path.dropLast(1).lastOrNull()?.name ?: "Makes"}") }
                    }
                    if (path.isNotEmpty()) Text(path.joinToString(" / ") { it.label }, style = MaterialTheme.typography.titleMedium)
                }
                if (focused == null) {
                    if (catalog.favorites.isNotEmpty()) {
                        item { Text("Favorites", style = MaterialTheme.typography.titleSmall) }
                        items(catalog.favorites.mapNotNull(tree::node), key = { "fav-${it.id}" }) { n -> CatalogRow(n, tree.path(n.id).joinToString(" / ") { it.label }, true,
                            { actions.onBrowse(n.id) }, { actions.onFavorite(n.id) }) }
                    }
                    if (catalog.recents.isNotEmpty()) {
                        item { Text("Recently selected", style = MaterialTheme.typography.titleSmall) }
                        items(catalog.recents.mapNotNull(tree::node), key = { "recent-${it.id}" }) { n -> CatalogRow(n, tree.path(n.id).joinToString(" / ") { it.label }, n.id in catalog.favorites,
                            { actions.onBrowse(n.id) }, { actions.onFavorite(n.id) }) }
                    }
                    item { Text("Makes", style = MaterialTheme.typography.titleSmall) }
                } else {
                    item {
                        SectionCard("${focused.level.label} · ${focused.label}") {
                            Text(resolved?.data?.summary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Text("Local: ${focused.data.summary}", style = MaterialTheme.typography.bodySmall)
                            focused.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (focused.platformIds.isNotEmpty()) Text("Platforms: " + focused.platformIds.mapNotNull { id -> catalog.pack.platforms.firstOrNull { it.id == id }?.name }.joinToString(), style = MaterialTheme.typography.bodySmall)
                            if (focused.level != CatalogLevel.MAKE) Button(onClick = { actions.onSelect(focused.id) }, enabled = state.offline && focused.id != catalog.selectedId && catalog.errors.isEmpty()) {
                                Text(if (focused.id == catalog.selectedId) "Selected manually" else "Select this ${focused.level.label.lowercase()}")
                            }
                            if (catalog.linkingProfile) {
                                OutlinedButton(onClick = { actions.onLinkProfile(focused.id) }) { Text("Save link to this profile (do not select)") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { actions.onEdit(focused.id) }, enabled = state.offline) { Text("Edit level") }
                                TextButton(onClick = { duplicate = true; duplicateName = "${focused.name} copy" }, enabled = state.offline) { Text("Duplicate branch") }
                                TextButton(onClick = { deleting = true }, enabled = state.offline) { Text("Remove") }
                            }
                            if (focused.level.child != null) OutlinedButton(onClick = { actions.onNew(focused.id) }, enabled = state.offline) { Text("Add ${focused.level.child!!.label.lowercase()}") }
                        }
                    }
                    item {
                        SectionCard("Definitions at this level") {
                            Text("Children inherit shared data. Edit local declarations or override an inherited item using the same key. Nothing here runs a write.", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.ECUS) }, enabled = state.offline) { Text("ECUs") }
                                TextButton(onClick = { actions.onPids(focused.id) }) { Text("PID editor") }
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.DTCS) }, enabled = state.offline) { Text("DTC text") }
                            }
                            TextButton(onClick = { actions.onData(focused.id, CatalogSection.PIDS) }, enabled = state.offline) { Text("Advanced PID JSON / copy from another level") }
                            Row {
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.ACTUATORS) }, enabled = state.offline) { Text("Tests") }
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.CODING) }, enabled = state.offline) { Text("Coding") }
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.SECURITY) }, enabled = state.offline) { Text("Security") }
                            }
                            Row {
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.CONNECTION) }, enabled = state.offline) { Text("Connection") }
                                TextButton(onClick = { actions.onData(focused.id, CatalogSection.MODULE_NAMES) }, enabled = state.offline) { Text("Module names") }
                            }
                        }
                    }
                    if (resolved != null && resolved.data.ecus.isNotEmpty()) {
                        item { Text("Resolved ECU list", style = MaterialTheme.typography.titleSmall) }
                        if (resolved.data.ecus.any { it.protocol == EcuProtocol.KWP }) item {
                            Text("KWP entries are preserved but excluded from the direct CAN/UDS scan. Enhanced KWP reads need documented definitions; supported K-line OBD reads remain available.", style = MaterialTheme.typography.bodySmall)
                        }
                        items(resolved.data.ecus, key = { "ecu-${it.key}" }) { ecu ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = state.isConnected && focused.id == catalog.selectedId) { actions.onOpenEcu(ecu) }.padding(8.dp)) {
                                Text(ecu.display)
                                Text("${ecu.requestId} → ${ecu.responseId} · ${if (ecu.is29Bit) "29-bit" else "11-bit"} · ${ecu.protocol.label}", style = MaterialTheme.typography.bodySmall)
                                Text(resolved.origins["ecus:${ecu.key}"].orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                        if (focused.id != catalog.selectedId) item { Text("Select this level before connecting to read its ECUs.", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (resolved?.data?.connection?.values?.isNotEmpty() == true) item {
                        SectionCard("Resolved connection settings") { resolved.data.connection.values.forEach { (key, value) -> InfoRow(key, value?.toString() ?: "clear / automatic") } }
                    }
                    if (focused.level.child != null) item { Text("${focused.level.child!!.label}s", style = MaterialTheme.typography.titleSmall) }
                }
                val children = tree.children(focused?.id)
                items(children, key = { "child-${it.id}" }) { n -> CatalogRow(n, n.level.label, n.id in catalog.favorites, { actions.onBrowse(n.id) }, { actions.onFavorite(n.id) }) }
                if (focused?.level?.child != null && children.isEmpty()) item { Text("No ${focused.level.child!!.label.lowercase()}s yet. Shared data at this level remains usable without inventing variant information.", style = MaterialTheme.typography.bodySmall) }
                item {
                    SectionCard("Packs & data library") {
                        Row {
                            TextButton(onClick = jsonShare) { Text("Export ${focused?.level?.label?.lowercase() ?: "catalog"} JSON") }
                            TextButton(onClick = csvShare) { Text("ECU CSV") }
                        }
                        Text("JSON keeps all data and inheritance, including needed ancestors. CSV exports names/years and ECU declarations only; platform ECUs are materialized.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = actions.onImport) { Text("Import JSON / CSV…") }
                        if (focused == null) {
                            Text("Unscoped legacy PIDs, notes and templates were preserved. They never supply a default vehicle, ECU list or connection settings.", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { actions.onPids(null) }) { Text("Library PIDs") }
                                TextButton(onClick = { actions.onData(null, CatalogSection.ACTUATORS) }, enabled = state.offline) { Text("Library tests") }
                                TextButton(onClick = { actions.onData(null, CatalogSection.CODING) }, enabled = state.offline) { Text("Library coding") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (duplicate && focused != null) AlertDialog(onDismissRequest = { duplicate = false }, title = { Text("Duplicate ${focused.name}") },
        text = { Column { Text("Copies this branch with new IDs and unverified write definitions. It does not change your selection."); OutlinedTextField(duplicateName, { duplicateName = it }, label = { Text("New name") }) } },
        confirmButton = { TextButton(onClick = { duplicate = false; actions.onDuplicate(focused.id, duplicateName) }, enabled = duplicateName.isNotBlank()) { Text("Duplicate") } },
        dismissButton = { TextButton(onClick = { duplicate = false }) { Text("Cancel") } })
    if (deleting && focused != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Remove ${focused.name} and its descendants?") },
        text = { Text("Local data in this branch will be removed (bundled originals stay in the app). Export JSON first if you need a copy. Saved VIN profiles, scans, logs and manual ECUs are not deleted.") },
        confirmButton = { TextButton(onClick = { deleting = false; actions.onDelete(focused.id) }) { Text("Remove branch") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
private fun CatalogRow(node: CatalogNode, subtitle: String, favorite: Boolean, onBrowse: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBrowse).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(node.label); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.titleLarge) }
        Text("›", modifier = Modifier.padding(horizontal = 6.dp))
    }
}
