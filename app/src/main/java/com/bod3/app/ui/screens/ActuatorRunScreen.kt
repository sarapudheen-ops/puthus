package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.bod3.app.actuator.ActuatorRecipe
import com.bod3.app.actuator.ActuatorUiState
import com.bod3.app.actuator.CheckStatus
import com.bod3.app.actuator.RunOutcome
import com.bod3.app.actuator.RunPhase
import com.bod3.app.recipe.Hex
import java.io.File

/** One recipe: details, precondition check, confirmation, live status with an always-visible STOP. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActuatorRunScreen(
    state: ActuatorUiState,
    expertMode: Boolean,
    connected: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onOverride: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDelete: (String) -> Unit,
    writeExport: suspend () -> File,
    onDismissMessage: () -> Unit,
) {
    val entry = state.selected
    val share = rememberShare("application/json", "bod3 actuator recipe", "Share recipe", writeExport)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.recipe?.name ?: "Test") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (entry != null) TextButton(onClick = share) { Text("Share") } },
            )
        },
    ) { padding ->
        if (entry == null) {
            Text("No recipe selected", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val recipe = entry.recipe
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.running) RunningCard(state.phase, onStop)
            state.message?.let { MessageCard(it, onDismissMessage) }
            state.lastResult?.let { result ->
                SectionCard("Result: ${result.outcome.label}") {
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                    if (!result.stopConfirmed) {
                        Text("Stop not confirmed by the module.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (result.outcome == RunOutcome.COMPLETED) Text("Logged in the audit log.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!recipe.verified) {
                WarningCard("Unverified recipe: it has not been confirmed on a real vehicle. Check the identifiers against your module's documentation before running it.") {}
            }
            SafetyCard(recipe)
            DetailsCard(recipe, entry.packName)
            SectionCard("Preconditions") {
                val report = state.preconditions
                if (report == null) {
                    Text(
                        "Battery ≥ %.1f V · %s%s%s".format(
                            recipe.preconditions.minVoltage, recipe.preconditions.engine.label,
                            if (recipe.preconditions.ignitionOn) " · ignition on" else "",
                            if (recipe.preconditions.speedZero) " · vehicle stationary" else "",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    report.items.forEach { item ->
                        val mark = when (item.status) {
                            CheckStatus.PASS -> "✓"
                            CheckStatus.FAIL -> "✗"
                            CheckStatus.UNKNOWN -> "?"
                        }
                        InfoRow("$mark ${item.label}", item.detail)
                    }
                }
                if (state.checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCheck, enabled = connected && !state.running && !state.checking) { Text("Check now") }
            }
            if (!state.running) {
                Button(
                    onClick = onStart,
                    enabled = expertMode && connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start test…") }
                if (!expertMode) Text("Needs Expert mode (Settings).", style = MaterialTheme.typography.bodySmall)
                if (!connected) Text("Needs a connection to a CAN vehicle.", style = MaterialTheme.typography.bodySmall)
                if (entry.isUser) {
                    TextButton(onClick = { onDelete(recipe.id) }) { Text("Delete my recipe") }
                }
            }
        }
    }

    if (state.confirming && entry != null) {
        ConfirmDialog(
            recipe = entry.recipe,
            needsOverride = state.preconditions?.overridableFailures?.isNotEmpty() == true || entry.recipe.applicability.isRestricted,
            override = state.overrideApplicability,
            onOverride = onOverride,
            onConfirm = onConfirm,
            onCancel = onCancelConfirm,
        )
    }
}

@Composable
private fun RunningCard(phase: RunPhase?, onStop: () -> Unit) {
    SectionCard("Running") {
        val status = when (phase) {
            RunPhase.CheckingPreconditions -> "Checking preconditions…"
            is RunPhase.Preparing -> phase.step + "…"
            is RunPhase.Running -> "Active · ${phase.remainingSeconds} s left" + (phase.lastStatus?.let { " · status $it" } ?: "")
            is RunPhase.Stopping -> "Stopping (${phase.reason.label.lowercase()})…"
            else -> ""
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (phase is RunPhase.Running && phase.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (phase.elapsedMs.toFloat() / phase.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = onStop,
            enabled = phase !is RunPhase.Stopping,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("STOP", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun SafetyCard(recipe: ActuatorRecipe) {
    SectionCard("Safety") {
        recipe.safetyNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(
            "The test stops automatically after ${recipe.durationSeconds} s, when the module stops answering or the connection drops. STOP is always available.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DetailsCard(recipe: ActuatorRecipe, packName: String) {
    SectionCard("Details") {
        if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodyMedium)
        InfoRow("Module", recipe.module.display)
        InfoRow("Type", recipe.kind?.label ?: "?")
        InfoRow("Session", "%02X".format(recipe.session) + if (recipe.session == 0x03) " (extended)" else " (default)")
        InfoRow("Security", recipe.security?.let { "Level %02X · %s".format(it.level, it.algorithm.label) } ?: "None")
        InfoRow("Start", Hex.format(recipe.start))
        InfoRow("Stop", Hex.format(recipe.stop))
        recipe.status?.let { InfoRow("Status", Hex.format(it)) }
        InfoRow("Time limit", "${recipe.durationSeconds} s")
        val a = recipe.applicability
        if (a.isRestricted) {
            InfoRow("For", (a.makes + a.models).joinToString(", ").ifEmpty { "Any vehicle" })
            if (a.partNumbers.isNotEmpty()) InfoRow("Part numbers", a.partNumbers.joinToString(", "))
            if (a.softwareVersions.isNotEmpty()) InfoRow("Software", a.softwareVersions.joinToString(", "))
        }
        InfoRow("From", packName)
        recipe.source?.let { InfoRow("Source", it) }
        recipe.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default) }
    }
}

@Composable
private fun ConfirmDialog(
    recipe: ActuatorRecipe,
    needsOverride: Boolean,
    override: Boolean,
    onOverride: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var read by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Run \"${recipe.name}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${recipe.module.display} · up to ${recipe.durationSeconds} s", style = MaterialTheme.typography.bodyMedium)
                recipe.safetyNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = read, onCheckedChange = { read = it })
                    Text("I have read the safety notes and the vehicle is safe to test", style = MaterialTheme.typography.bodySmall)
                }
                if (needsOverride) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = override, onCheckedChange = onOverride)
                        Text("Run even if the vehicle or module does not match the recipe", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = read) { Text("Start") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
