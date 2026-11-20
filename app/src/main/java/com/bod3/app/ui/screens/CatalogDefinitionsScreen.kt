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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogCoverage
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogEditing
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.catalog.CatalogTree
import com.bod3.app.coding.JsonPretty
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.ConnectionProfileCodec
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.ui.CatalogDataTarget
import com.bod3.app.catalog.CatalogSection
import java.util.UUID

/** ECU forms and structured definition editors reuse the same validation as whole-file imports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDefinitionsScreen(target: CatalogDataTarget, pack: CatalogPack, adapter: ConnectionProfile,
    onSave: (String) -> String?, onCancel: () -> Unit,
) {
    val tree = remember(pack) { CatalogTree(pack) }
    val node = tree.node(target.nodeId)
    val local = node?.data ?: pack.library
    val section = target.section
    val title = node?.let { tree.path(it.id).joinToString(" / ") { p -> p.name } } ?: "Unscoped legacy library"
    val inherited = remember(pack, target) {
        if (node == null) CatalogData() else CatalogTree(pack.copy(nodes = pack.nodes.map { if (it.id == node.id) it.copy(data = CatalogData()) else it })).resolve(node.id)?.data ?: CatalogData()
    }
    // What this level plus its ancestors actually provide: names/years alone are not coverage.
    val effective = remember(inherited, local) { inherited.overlay(local) }
    val sectionMap = CatalogCodec.dataMap(local)
    val exclusion = (sectionMap["exclude"] as? Map<*, *>)?.get(section.key)
    var text by rememberSaveable(target) { mutableStateOf(JsonPretty.format(JsonWriter.write(
        if (section == CatalogSection.CONNECTION) mapOf(section.key to sectionMap[section.key])
        else linkedMapOf(section.key to sectionMap[section.key], "exclude" to mapOf(section.key to (exclusion ?: emptyList<String>())))
    ))) }
    var ecus by remember(target) { mutableStateOf(local.ecus) }
    var hiddenEcus by remember(target) { mutableStateOf(local.exclude.ecus) }
    var error by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var copying by rememberSaveable(target) { mutableStateOf(false) }
    var copyQuery by rememberSaveable(target) { mutableStateOf("") }
    var protocolMenu by remember { mutableStateOf(false) }
    val candidates = remember(pack, section) { candidates(pack, section) }

    fun append(raw: Map<String, Any?>, duplicate: Boolean) {
        error = runCatching {
            if (section == CatalogSection.ECUS) {
                val errors = mutableListOf<String>()
                var ecu = DataPackCodec.decodeEcu(raw, "ecu", errors) ?: throw IllegalArgumentException(errors.joinToString("\n"))
                if (duplicate) ecu = ecu.copy(name = ecu.name + " copy", requestId = "", responseId = "")
                ecus = ecus.filterNot { it.key == ecu.key } + ecu
            } else {
                val root = (MiniJson.parse(text) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
                val item = if (duplicate) raw + mapOf("id" to "copy-${UUID.randomUUID()}", "name" to "${raw["name"] ?: "Definition"} copy", "verified" to false) else raw
                val list = (root[section.key] as? List<*>).orEmpty()
                val id = item["id"]
                val updated = list.filterNot { id != null && (it as? Map<*, *>)?.get("id") == id } + item
                text = JsonPretty.format(JsonWriter.write(root + (section.key to updated)))
            }
            copying = false
        }.exceptionOrNull()?.message
    }

    Scaffold(topBar = { TopAppBar(title = { Text(section.label) },
        navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
        actions = { TextButton(onClick = {
            val json = if (section == CatalogSection.ECUS) JsonWriter.write(mapOf("ecus" to ecus.map(DataPackCodec::encodeEcu), "exclude" to mapOf("ecus" to hiddenEcus.sorted()))) else text
            error = onSave(json)
        }) { Text("Validate & save") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val effectiveCoverage = effective.coverage
            Text(
                if (effectiveCoverage.executable) "Effective coverage of this level and its ancestors: ${effectiveCoverage.counts}."
                else "Effective coverage of this level and its ancestors. ${CatalogCoverage.NO_ENHANCED_DATA}",
                style = MaterialTheme.typography.bodySmall,
                color = if (effectiveCoverage.executable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
            Text("Local declarations only. The same stable key overrides an inherited item. Removing a local override restores the parent; exclude suppresses a parent item here. Data is validated together before a single save.", style = MaterialTheme.typography.bodySmall)
            if (section in listOf(CatalogSection.ACTUATORS, CatalogSection.CODING, CatalogSection.SECURITY)) Text("Always unverified. Definitions never bypass Expert mode, preconditions, confirmation, backup or write verification. Security access runs only inside a guarded test/coding recipe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (section == CatalogSection.ECUS) {
                ecus.forEachIndexed { i, ecu -> EcuEditor(ecu, { updated -> ecus = ecus.toMutableList().also { it[i] = updated } }, { ecus = ecus.filterIndexed { index, _ -> index != i } }) }
                OutlinedButton(onClick = { ecus = ecus + EcuDefinition(name = "", requestId = "", responseId = "") }) { Text("Add ECU (documented IDs required)") }
                if (inherited.ecus.isNotEmpty()) SectionCard("Inherited / platform ECUs") {
                    inherited.ecus.forEach { ecu ->
                        Text("${ecu.display} · ${ecu.requestId} → ${ecu.responseId}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { ecus = ecus.filterNot { it.key == ecu.key } + ecu }) { Text("Override here") }
                            TextButton(onClick = { hiddenEcus = if (ecu.key in hiddenEcus) hiddenEcus - ecu.key else hiddenEcus + ecu.key }) { Text(if (ecu.key in hiddenEcus) "Restore inheritance" else "Exclude here") }
                        }
                    }
                }
            } else {
                if (section == CatalogSection.CONNECTION) {
                    Text("Sparse patch: omit a key to inherit; null clears a nullable option. Adapter settings not specified by the catalog are preserved. Payload macros, writes and security requests are forbidden in init commands.", style = MaterialTheme.typography.bodySmall)
                    val inheritedOverrides = inherited.connection.values
                    Text(
                        if (inheritedOverrides.isEmpty()) "No inherited connection overrides from parent levels or platforms."
                        else "${inheritedOverrides.size} inherited connection ${if (inheritedOverrides.size == 1) "override" else "overrides"}: " +
                            inheritedOverrides.keys.sorted().joinToString(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        text = JsonPretty.format(JsonWriter.write(mapOf("connection" to (ConnectionProfileCodec.encode(adapter) - setOf("id", "name")))))
                    }) { Text("Copy current adapter settings (explicit overrides)") }
                    Column {
                        TextButton(onClick = { protocolMenu = true }) { Text("Set protocol override…") }
                        DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            (listOf<ObdProtocol?>(null) + ObdProtocol.entries).forEach { protocol ->
                                DropdownMenuItem(text = { Text(protocol?.displayName ?: "Automatic protocol detection") }, onClick = {
                                    protocolMenu = false
                                    error = runCatching {
                                        val map = (MiniJson.parse(text) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
                                        val connection = (map["connection"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
                                        text = JsonPretty.format(JsonWriter.write(map + ("connection" to (connection + ("forcedProtocol" to protocol?.name)))))
                                    }.exceptionOrNull()?.message
                                })
                            }
                        }
                    }
                    Text("Fields: forcedProtocol, tryFallbacks, requestTimeoutMs, searchTimeoutMs, adapterTimeoutMs, adaptiveTiming, useVtTimeout, canBaudKbps, broadcastFilter, broadcastMask, flowControlMode, flowControlData, extendedAddress, extraInitCommands.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(text, { text = it; error = null }, label = { Text("Local ${section.label.lowercase()} JSON") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp), minLines = 10)
                if (section in listOf(CatalogSection.ACTUATORS, CatalogSection.CODING, CatalogSection.SECURITY, CatalogSection.PIDS, CatalogSection.MODULE_NAMES)) {
                    OutlinedButton(onClick = { append(CatalogEditing.blank(section), false) }) { Text("Add blank definition (fill from documentation)") }
                }
                if (section == CatalogSection.DTCS) {
                    Text("DTC text is a map such as code → documented meaning. Manufacturer reference groups keep their restrictions and source; local text can override them without changing those originals.", style = MaterialTheme.typography.bodySmall)
                    val groups = inherited.dtcGroups
                    groups.forEach { Text("${it.dtcs.size} inherited reference meanings · ${it.source ?: it.id} · ${it.makes.joinToString()}", style = MaterialTheme.typography.bodySmall) }
                }
                if (inherited.count > 0) Text("Inherited (not copied): ${inherited.summary}", style = MaterialTheme.typography.bodySmall)
            }
            if (candidates.isNotEmpty()) {
                TextButton(onClick = { copying = !copying }) { Text(if (copying) "Close copy list" else "Copy / duplicate from existing…") }
                if (copying) {
                    OutlinedTextField(copyQuery, { copyQuery = it }, label = { Text("Search definitions from all levels & platforms") }, modifier = Modifier.fillMaxWidth())
                    val matches = candidates.filter { it.first.contains(copyQuery, true) }
                    Text("${matches.size} matches", style = MaterialTheme.typography.bodySmall)
                    matches.take(60).forEach { (name, raw) ->
                        SectionCard(name) {
                            Row {
                                TextButton(onClick = { append(raw, false) }) { Text("Copy key / override") }
                                if (section != CatalogSection.MODULE_NAMES) TextButton(onClick = { append(raw, true) }) { Text("Duplicate") }
                            }
                        }
                    }
                    if (matches.size > 60) Text("Refine the search to see more definitions.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = {
                val json = if (section == CatalogSection.ECUS) JsonWriter.write(mapOf("ecus" to ecus.map(DataPackCodec::encodeEcu), "exclude" to mapOf("ecus" to hiddenEcus.sorted()))) else text
                error = onSave(json)
            }) { Text("Validate & save at this level") }
        }
    }
}

private fun candidates(pack: CatalogPack, section: CatalogSection): List<Pair<String, Map<String, Any?>>> {
    if (section in listOf(CatalogSection.DTCS, CatalogSection.CONNECTION)) return emptyList()
    val tree = CatalogTree(pack)
    val data = pack.nodes.map { tree.path(it.id).joinToString(" / ") { n -> n.name } to it.data } +
        pack.platforms.map { "Platform: ${it.name}" to it.data } + ("Legacy library" to pack.library)
    return data.flatMap { (scope, d) ->
        (CatalogCodec.dataMap(d)[section.key] as? List<*>).orEmpty().mapNotNull { raw ->
            val map = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
            "$scope · ${map["name"] ?: map["id"]}" to map
        }
    }.distinctBy { JsonWriter.write(it.second) }
}

