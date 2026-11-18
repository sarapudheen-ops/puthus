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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.CustomPid
import com.bod3.app.obd.pid.Formula
import com.bod3.app.obd.pid.FormulaException
import com.bod3.app.ui.UiState

/** Custom PIDs from data packs and the user's own; they appear in the live data value list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPidsScreen(state: UiState, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom PIDs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, contentDescription = "Add PID") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "A custom PID is a read request (service 01, 09 or UDS 22), a formula over the reply data bytes A, B, C… and a unit. Add them to live data with the pencil on the live data screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(state.data.customPids, key = { it.id }) { pid ->
                Row(Modifier.fillMaxWidth().clickable { onEdit(pid.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pid.name)
                        Text(
                            listOfNotNull(pid.header?.let { "$it:" }, pid.request, "→", pid.formula, pid.unit.ifBlank { null }).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(if (pid.id in state.data.userPidIds) "Yours" else "Bundled", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
            if (state.data.customPids.isEmpty()) item { Text("No custom PIDs yet. Tap + to add one.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PidEditorScreen(
    pid: CustomPid,
    isUserPid: Boolean,
    isNew: Boolean,
    connected: Boolean,
    error: String?,
    testResult: String?,
    onSave: (CustomPid) -> Unit,
    onTest: (CustomPid) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var id by rememberSaveable(pid.id) { mutableStateOf(pid.id) }
    var name by rememberSaveable(pid.id) { mutableStateOf(pid.name) }
    var request by rememberSaveable(pid.id) { mutableStateOf(pid.request) }
    var formula by rememberSaveable(pid.id) { mutableStateOf(pid.formula) }
    var unit by rememberSaveable(pid.id) { mutableStateOf(pid.unit) }
    var min by rememberSaveable(pid.id) { mutableStateOf(pid.min.toString()) }
    var max by rememberSaveable(pid.id) { mutableStateOf(pid.max.toString()) }
    var decimals by rememberSaveable(pid.id) { mutableStateOf(pid.decimals.toString()) }
    var header by rememberSaveable(pid.id) { mutableStateOf(pid.header.orEmpty()) }
    var responseHeader by rememberSaveable(pid.id) { mutableStateOf(pid.responseHeader.orEmpty()) }
    var notes by rememberSaveable(pid.id) { mutableStateOf(pid.notes.orEmpty()) }

    fun build() = CustomPid(
        id = id.trim(),
        name = name.trim(),
        request = request.filterNot { it.isWhitespace() }.uppercase(),
        formula = formula.trim(),
        unit = unit.trim(),
        min = min.trim().toDoubleOrNull() ?: 0.0,
        max = max.trim().toDoubleOrNull() ?: 100.0,
        decimals = decimals.trim().toIntOrNull() ?: 1,
        header = header.trim().uppercase().ifEmpty { null },
        responseHeader = responseHeader.trim().uppercase().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
    )

    val current = build()
    val problems = current.problems()
    val formulaHint = try {
        val parsed = Formula.parse(formula)
        "Uses ${parsed.bytesNeeded} data byte(s) after the echoed request"
    } catch (e: FormulaException) {
        "Formula: ${e.message}"
    }
    val hex = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
    val number = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New custom PID" else "Edit custom PID") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") } },
                actions = { TextButton(onClick = { onSave(current) }, enabled = problems.isEmpty()) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isNew && !isUserPid) {
                Text("Bundled PID: saving stores your copy, which replaces it.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(id, { id = it }, label = { Text("ID (letters, digits, - _)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(request, { request = it }, label = { Text("Request (hex, e.g. 010B or 22F40C)") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(formula, { formula = it }, label = { Text("Formula, e.g. (A*256+B)/4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(formulaHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(decimals, { decimals = it }, label = { Text("Decimals") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(min, { min = it }, label = { Text("Gauge min") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
                OutlinedTextField(max, { max = it }, label = { Text("Gauge max") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(header, { header = it }, label = { Text("ECU request ID") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.weight(1f))
                OutlinedTextField(responseHeader, { responseHeader = it }, label = { Text("Response ID") }, singleLine = true, keyboardOptions = hex, modifier = Modifier.weight(1f))
            }
            Text("Leave the IDs empty to send the request to all OBD ECUs (7DF).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onTest(current) }, enabled = connected && problems.isEmpty()) { Text("Test read") }
                if (isUserPid) OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
            testResult?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
