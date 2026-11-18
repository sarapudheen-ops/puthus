package com.bod3.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.ui.ConnectionSettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    settings: ConnectionSettingsState,
    connected: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onForgetProtocol: (String) -> Unit,
    onExpertMode: (enabled: Boolean, acknowledged: Boolean) -> Unit,
) {
    var showRiskDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Connection profile") {
                if (connected) {
                    Text(
                        "Changes apply to the next connection or reconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                settings.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == settings.activeProfileId,
                        builtIn = ConnectionProfile.BUILT_IN.any { it.id == profile.id },
                        onSelect = { onSelect(profile.id) },
                        onEdit = { onEdit(profile.id) },
                        onDelete = { onDelete(profile.id) },
                    )
                }
                OutlinedButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" New profile")
                }
            }

            SectionCard("Remembered protocols") {
                if (settings.remembered.isEmpty()) {
                    Text("None yet. The protocol that works is remembered per adapter and tried first next time.", style = MaterialTheme.typography.bodySmall)
                }
                settings.remembered.forEach { remembered ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(remembered.adapterAddress, style = MaterialTheme.typography.bodyMedium)
                            Text(remembered.atdp ?: remembered.protocol.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onForgetProtocol(remembered.adapterAddress) }) { Text("Forget") }
                    }
                }
            }

            SectionCard("Supported protocols (vLinker MC+)") {
                var showAll by rememberSaveable { mutableStateOf(false) }
                Text(
                    "Automatic search tries ATSP0 and then every protocol below. Force one in a profile to skip the search.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val shown = if (showAll) ObdProtocol.all else ObdProtocol.all.take(6)
                shown.forEach { protocol ->
                    Column {
                        Text(protocol.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${protocol.family.label} · ${protocol.bus.label} · " + protocol.selectCommands().joinToString("; "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${ObdProtocol.all.size} protocols")
                }
            }

            SectionCard("Expert mode") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (settings.expertMode) "On – terminal and bidirectional tests available" else "Off",
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.expertMode,
                        onCheckedChange = { on -> if (on) showRiskDialog = true else onExpertMode(false, false) },
                    )
                }
                Text(
                    "Expert mode unlocks the raw AT/OBD/UDS terminal and bidirectional (actuator) tests. The terminal stays " +
                        "read-only. Tests may only use their whitelisted services (session 01/03, security access, 2F, 31) " +
                        "on one module, after a confirmation and precondition checks, and every run is audit-logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showRiskDialog) {
        RiskDialog(
            onConfirm = {
                showRiskDialog = false
                onExpertMode(true, true)
            },
            onDismiss = { showRiskDialog = false },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ConnectionProfile,
    selected: Boolean,
    builtIn: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
            Text(profile.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${profile.name}") }
        if (!builtIn) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${profile.name}") }
    }
}

internal fun ConnectionProfile.summary(): String = buildList {
    add(forcedProtocol?.let { "Protocol ${it.code}" } ?: "Automatic")
    if (tryFallbacks) add("fallbacks")
    adapterTimeoutMs?.let { add("timeout $it ms") }
    adaptiveTiming?.let { add("ATAT$it") }
    if (broadcastFilter != null) add("filter $broadcastFilter/$broadcastMask")
    if (flowControlMode != com.bod3.app.obd.connection.FlowControlMode.AUTO) add("custom FC")
    extendedAddress?.let { add("ext. addr $it") }
}.joinToString(" · ")

@Composable
private fun RiskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on Expert mode?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Expert mode sends commands you type straight to the adapter and lets you run bidirectional tests " +
                        "that switch outputs and routines in control units. Wrong adapter settings can make the connection " +
                        "stop working until the adapter is reset; actuator tests move real parts (horn, wipers, pumps, " +
                        "fans) and are your responsibility. Only use it if you know the ELM327/UDS command set and the " +
                        "vehicle is parked safely.",
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { understood = !understood }) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand the risks")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = understood) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
