package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.FlowControlMode
import com.bod3.app.obd.protocol.ObdProtocol

/** Edits a connection profile. Values are validated by [ConnectionProfile] itself when saving. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(profile: ConnectionProfile, onSave: (ConnectionProfile) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var protocolCode by rememberSaveable(profile.id) { mutableStateOf(profile.forcedProtocol?.code ?: "") }
    var fallbacks by rememberSaveable(profile.id) { mutableStateOf(profile.tryFallbacks) }
    var requestTimeout by rememberSaveable(profile.id) { mutableStateOf(profile.requestTimeoutMs.toString()) }
    var searchTimeout by rememberSaveable(profile.id) { mutableStateOf(profile.searchTimeoutMs.toString()) }
    var adapterTimeout by rememberSaveable(profile.id) { mutableStateOf(profile.adapterTimeoutMs?.toString() ?: "") }
    var adaptive by rememberSaveable(profile.id) { mutableStateOf(profile.adaptiveTiming?.toString() ?: "") }
    var vtTimeout by rememberSaveable(profile.id) { mutableStateOf(profile.useVtTimeout) }
    var filter by rememberSaveable(profile.id) { mutableStateOf(profile.broadcastFilter ?: "") }
    var mask by rememberSaveable(profile.id) { mutableStateOf(profile.broadcastMask ?: "") }
    var customFc by rememberSaveable(profile.id) { mutableStateOf(profile.flowControlMode == FlowControlMode.CUSTOM_DATA) }
    var fcData by rememberSaveable(profile.id) { mutableStateOf(profile.flowControlData) }
    var extAddress by rememberSaveable(profile.id) { mutableStateOf(profile.extendedAddress ?: "") }
    var canBaud by rememberSaveable(profile.id) { mutableStateOf(profile.canBaudKbps?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var extraCommands by rememberSaveable(profile.id) { mutableStateOf(profile.extraInitCommands.joinToString("\n")) }
    var error by rememberSaveable(profile.id) { mutableStateOf<String?>(null) }

    fun build(): ConnectionProfile = ConnectionProfile(
        id = profile.id,
        name = name.trim(),
        forcedProtocol = protocolCode.takeIf { it.isNotEmpty() }?.let { code -> ObdProtocol.byCode(code) ?: throw IllegalArgumentException("Unknown protocol $code") },
        tryFallbacks = fallbacks,
        requestTimeoutMs = requestTimeout.trim().toLongOrNull() ?: throw IllegalArgumentException("Request timeout must be a number"),
        searchTimeoutMs = searchTimeout.trim().toLongOrNull() ?: throw IllegalArgumentException("Search timeout must be a number"),
        adapterTimeoutMs = adapterTimeout.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Adapter timeout must be a number") },
        adaptiveTiming = adaptive.toIntOrNull(),
        useVtTimeout = vtTimeout,
        broadcastFilter = filter.trim().uppercase().ifEmpty { null },
        broadcastMask = mask.trim().uppercase().ifEmpty { null },
        flowControlMode = if (customFc) FlowControlMode.CUSTOM_DATA else FlowControlMode.AUTO,
        flowControlData = fcData.trim().uppercase().filterNot { it == ' ' },
        extendedAddress = extAddress.trim().uppercase().ifEmpty { null },
        canBaudKbps = canBaud.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("CAN bit rate must be a number") },
        extraInitCommands = extraCommands.lines().map { it.trim().uppercase() }.filter { it.isNotEmpty() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit profile") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") } },
                actions = {
                    TextButton(onClick = {
                        try {
                            onSave(build())
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionCard("Protocol") {
                ProtocolDropdown(protocolCode) { protocolCode = it }
                SwitchRow(
                    "Try fallbacks",
                    if (protocolCode.isEmpty()) {
                        "When automatic search fails, try every protocol: K-line, CAN, J1850, J1939, user CAN B/C, MS-CAN, SW-CAN, raw CAN, KWP1281"
                    } else {
                        "Also try the other protocols if this one fails"
                    },
                    fallbacks,
                ) { fallbacks = it }
                OutlinedTextField(
                    value = canBaud,
                    onValueChange = { text -> canBaud = text.filter { it.isDigit() || it == '.' } },
                    label = { Text("CAN bit rate kbit/s for user CAN B/C and STN CAN (empty = default)") },
                    supportingText = { Text("10-1000, e.g. 125, 33.3, 95.2, 1000. Uses ATPB/ATPP, or STPBR for rates that are no whole divisor of 500.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard("Timeouts") {
                NumberField("Request timeout (ms)", requestTimeout) { requestTimeout = it }
                NumberField("Protocol search timeout (ms)", searchTimeout) { searchTimeout = it }
                NumberField("Adapter reply timeout (ms, empty = default)", adapterTimeout) { adapterTimeout = it }
                Text("Adaptive timing", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Default", "0" to "Off (ATAT0)", "1" to "Normal (ATAT1)", "2" to "Aggressive (ATAT2)").forEach { (value, label) ->
                        FilterChip(selected = adaptive == value, onClick = { adaptive = value }, label = { Text(label) })
                    }
                }
                SwitchRow("Use VTTOST", "Set the reply timeout with the vLinker VTTOST command instead of ATST", vtTimeout) { vtTimeout = it }
            }

            SectionCard("CAN options") {
                Text("Receive filter for broadcast requests (empty = automatic)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HexField("Filter (ATCF)", filter, Modifier.weight(1f)) { filter = it }
                    HexField("Mask (ATCM)", mask, Modifier.weight(1f)) { mask = it }
                }
                SwitchRow("Custom flow control", "Send ATFCSH/ATFCSD/ATFCSM1 on every physical request", customFc) { customFc = it }
                if (customFc) HexField("Flow-control data (e.g. 300000)", fcData) { fcData = it }
                HexField("Extended address (ATCEA, empty = normal addressing)", extAddress) { extAddress = it }
            }

            SectionCard("Extra adapter commands") {
                OutlinedTextField(
                    value = extraCommands,
                    onValueChange = { extraCommands = it },
                    label = { Text("One AT/ST/VT command per line, sent after setup") },
                    supportingText = { Text("e.g. STCSEGT1 or VTVLCW. Commands the adapter does not know (?) are logged and skipped.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolDropdown(code: String, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = code.takeIf { it.isNotEmpty() }?.let { c -> ObdProtocol.byCode(c)?.label } ?: "Automatic (ATSP0)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Protocol") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Automatic (ATSP0)") }, onClick = { onChange(""); expanded = false })
            ObdProtocol.all.forEach { protocol ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(protocol.label)
                            Text(
                                "${protocol.family.label} · ${protocol.bus.label}" + (protocol.defaultBaudKbps?.let { " · custom baud" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onChange(protocol.code); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HexField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier = modifier,
    )
}
