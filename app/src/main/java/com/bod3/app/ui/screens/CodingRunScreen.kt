package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bod3.app.actuator.CheckStatus
import com.bod3.app.coding.CodingDiff
import com.bod3.app.coding.CodingMethod
import com.bod3.app.coding.CodingReading
import com.bod3.app.coding.CodingRecipe
import com.bod3.app.coding.CodingResult
import com.bod3.app.coding.CodingUiState
import com.bod3.app.recipe.Hex
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One coding recipe: read + backup, choose the new value, diff, confirmed write, verify, rollback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingRunScreen(
    state: CodingUiState,
    expertMode: Boolean,
    connected: Boolean,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onOverride: (Boolean) -> Unit,
    onValue: (Long?) -> Unit,
    onWrite: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onRollbackLast: () -> Unit,
    onRequestRollback: (String) -> Unit,
    onConfirmRollback: () -> Unit,
    onCancelRollback: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    writeExport: suspend () -> File,
    onDismissMessage: () -> Unit,
) {
    val entry = state.selected
    val share = rememberShare("application/json", "bod3 coding recipe", "Share recipe", writeExport)
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.recipe?.name ?: "Coding") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (entry != null) TextButton(onClick = share) { Text("Share") } },
            )
        },
    ) { padding ->
        if (entry == null) {
            Text("No recipe selected", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val recipe = entry.recipe
        val canWrite = expertMode && connected && !state.busy
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.step?.let { step ->
                SectionCard("Writing – do not disconnect") {
                    Text(step.label + "…", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Keep the ignition on and the adapter connected until this finishes.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.message?.let { MessageCard(it, onDismissMessage) }
            state.lastResult?.let { ResultCard(it, canWrite, onRollbackLast) }
            if (!recipe.verified) {
                WarningCard("Unverified recipe: the byte/bit position has not been confirmed for your module. Check it against the module's long-coding helper or documentation first. A backup is always saved, so the change can be rolled back.") {}
            }
            RecipeDetailsCard(recipe, entry.packName)

            SectionCard("1 · Current value") {
                if (recipe.applicability.isRestricted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.overrideVersion, onCheckedChange = onOverride, enabled = !state.busy)
                        Text("Continue even if the module's part number or software does not match", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = onRead, enabled = connected && !state.busy) {
                    Text(if (state.reading == null) "Read and back up" else "Read again")
                }
                if (state.readingInProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (!connected) Text("Needs a connection to a CAN vehicle.", style = MaterialTheme.typography.bodySmall)
                state.readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                state.reading?.let { ReadingInfo(it, dateFormat) }
            }

            state.reading?.let { reading ->
                SectionCard("2 · New value") {
                    ValueChooser(recipe, reading, state.newValue, onValue, enabled = !state.busy)
                    state.valueError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                state.diff?.let { diff ->
                    SectionCard("3 · Change") {
                        DiffView(recipe, diff)
                        Button(onClick = onWrite, enabled = canWrite && diff.isChange, modifier = Modifier.fillMaxWidth()) { Text("Write…") }
                        if (!expertMode) Text("Writing needs Expert mode (Settings).", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            val backups = state.backupsForSelected
            if (backups.isNotEmpty()) {
                Text("Backups for this recipe", style = MaterialTheme.typography.titleSmall)
                backups.take(10).forEach { backup -> BackupCard(backup, dateFormat, enabled = canWrite) { onRequestRollback(backup.id) } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEdit(recipe.id) }, enabled = !state.busy) { Text(if (entry.isUser) "Edit recipe" else "Copy & edit") }
                if (entry.isUser) TextButton(onClick = { onDelete(recipe.id) }, enabled = !state.busy) { Text("Delete my recipe") }
            }
        }
    }

    val reading = state.reading
    val diff = state.diff
    if (state.confirming && entry != null && reading != null && diff != null) {
        WriteConfirmDialog(entry.recipe, reading, diff, onConfirm, onCancelConfirm)
    }
    state.rollbackCandidate?.let { RollbackDialog(it, onConfirmRollback, onCancelRollback) }
}

@Composable
private fun ResultCard(result: CodingResult, canWrite: Boolean, onRollback: () -> Unit) {
    SectionCard("Result: ${result.outcome.label}") {
        Text(
            result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.outcome.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        result.before?.let { InfoRow("Before", Hex.format(it)) }
        result.intended?.let { InfoRow("Intended", Hex.format(it)) }
        result.readBack?.let { InfoRow("Read back", Hex.format(it)) }
        Text("Recorded in the audit log.", style = MaterialTheme.typography.bodySmall)
        if (result.canRollback) {
            Button(onClick = onRollback, enabled = canWrite, modifier = Modifier.fillMaxWidth()) { Text("Roll back to the backup") }
        }
    }
}

@Composable
private fun RecipeDetailsCard(recipe: CodingRecipe, packName: String) {
    SectionCard("Details") {
        if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodyMedium)
        InfoRow("Module", recipe.module.display)
        InfoRow("Method", recipe.method.label)
        InfoRow("Identifier", "%04X".format(recipe.did))
        InfoRow(
            "Position",
            "byte ${recipe.byteIndex}" + (recipe.mask?.let { " · mask %02X (bits %s)".format(it, bits(it)) } ?: if (recipe.length > 1) " · ${recipe.length} bytes" else ""),
        )
        if (recipe.options.isEmpty()) {
            InfoRow("Range", "${recipe.min ?: 0} – ${recipe.max ?: recipe.fieldMax}" + (recipe.unit?.let { " (raw, shown in $it)" } ?: ""))
        }
        InfoRow("Session", "%02X".format(recipe.session) + if (recipe.session == 0x03) " (extended)" else " (default)")
        InfoRow("Security", recipe.security?.let { "Level %02X · %s".format(it.level, it.algorithm.label) } ?: "None")
        InfoRow(
            "Conditions",
            "Battery ≥ %.1f V · %s".format(recipe.preconditions.minVoltage, if (recipe.preconditions.allowEngineRunning) "engine may run" else "engine off"),
        )
        val a = recipe.applicability
        if (a.isRestricted) {
            InfoRow("For", (a.makes + a.models).joinToString(", ").ifEmpty { "Any vehicle" })
            if (a.partNumbers.isNotEmpty()) InfoRow("Part numbers", a.partNumbers.joinToString(", "))
            if (a.softwareVersions.isNotEmpty()) InfoRow("Software", a.softwareVersions.joinToString(", "))
        }
        InfoRow("From", packName)
        recipe.source?.takeIf { it.isNotBlank() }?.let { InfoRow("Source", it) }
        recipe.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun bits(mask: Int): String = (7 downTo 0).filter { mask and (1 shl it) != 0 }.joinToString(",")

@Composable
private fun ReadingInfo(reading: CodingReading, dateFormat: DateFormat) {
    InfoRow("Value", reading.recipe.describe(reading.value))
    InfoRow("%04X".format(reading.recipe.did), Hex.format(reading.data))
    InfoRow("Part number", reading.partNumber ?: "?")
    InfoRow("Software", reading.softwareVersion ?: "?")
    if (reading.versionOverridden) Text("Version check overridden.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Text("Backup saved " + dateFormat.format(Date(reading.backup.createdAtMs)), style = MaterialTheme.typography.bodySmall)
    reading.preconditions.items.filterNot { it.overridable }.forEach { item ->
        val mark = when (item.status) {
            CheckStatus.PASS -> "✓"
            CheckStatus.FAIL -> "✗"
            CheckStatus.UNKNOWN -> "?"
        }
        InfoRow("$mark ${item.label}", item.detail)
    }
}

@Composable
private fun ValueChooser(recipe: CodingRecipe, reading: CodingReading, selected: Long?, onValue: (Long?) -> Unit, enabled: Boolean) {
    if (recipe.options.isNotEmpty()) {
        recipe.options.forEach { option ->
            val isSelected = selected == option.value
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = isSelected, enabled = enabled, onClick = { onValue(option.value) }),
            ) {
                RadioButton(selected = isSelected, onClick = { onValue(option.value) }, enabled = enabled)
                Text(option.label + if (option.value == reading.value) " (current)" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        var text by remember(reading.backup.id) { mutableStateOf(selected?.toString() ?: reading.value.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onValue(input.trim().toLongOrNull())
            },
            label = { Text("Raw value (${recipe.min ?: 0}–${recipe.max ?: recipe.fieldMax})") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        text.trim().toLongOrNull()?.let { if (recipe.valueProblem(it) == null) Text("= " + recipe.describe(it), style = MaterialTheme.typography.bodySmall) }
        if (recipe.method == CodingMethod.ADAPTATION && recipe.unit != null) {
            Text("Displayed value = raw × ${recipe.factor} + ${recipe.offset} ${recipe.unit}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiffView(recipe: CodingRecipe, diff: CodingDiff) {
    InfoRow("Value", "${diff.beforeText} → ${diff.afterText}")
    if (!diff.isChange) {
        Text("The module already has this value.", style = MaterialTheme.typography.bodySmall)
        return
    }
    diff.changedBytes.forEach { i ->
        InfoRow("Byte $i", "%02X → %02X  (%s → %s)".format(diff.before[i], diff.after[i], binary(diff.before[i]), binary(diff.after[i])))
    }
    Text("Before  " + Hex.format(diff.before), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    Text("After   " + Hex.format(diff.after), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    Text("Writes %04X with 2E; every other byte stays as read.".format(recipe.did), style = MaterialTheme.typography.bodySmall)
}

private fun binary(b: Int): String = Integer.toBinaryString(b or 0x100).drop(1)

@Composable
private fun WriteConfirmDialog(recipe: CodingRecipe, reading: CodingReading, diff: CodingDiff, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Write \"${recipe.name}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recipe.module.display, style = MaterialTheme.typography.bodyMedium)
                Text("${diff.beforeText} → ${diff.afterText}", style = MaterialTheme.typography.bodyLarge)
                Text("%04X: %s → %s".format(recipe.did, Hex.format(diff.before), Hex.format(diff.after)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("Backup saved: ${Hex.format(reading.backup.data)}. The value is read back after writing.", style = MaterialTheme.typography.bodySmall)
                if (!recipe.verified) Text("This recipe is unverified.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sure, onCheckedChange = { sure = it })
                    Text("Ignition on, engine off, battery support connected; I accept the risk", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = sure) { Text("Write") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
