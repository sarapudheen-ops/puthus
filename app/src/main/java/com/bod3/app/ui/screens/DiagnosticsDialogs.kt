package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.ui.ClearState

/**
 * Asks before clearing fault memory. The user must tick that they understand what is lost; only then
 * does the confirm button call [onConfirm], which creates the one-shot clear confirmation.
 */
@Composable
fun ClearConfirmDialog(title: String, target: String, consequences: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target, style = MaterialTheme.typography.bodyMedium)
                consequences.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Ignition on, engine off. Codes come back if the fault is still present.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand that this data is deleted", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = understood) { Text("Clear codes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows clearing in progress, then the outcome. */
@Composable
fun ClearStateDialog(state: ClearState, onDismiss: () -> Unit) {
    when (state) {
        ClearState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Clearing fault codes…") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Keep the ignition on and the app open.")
                }
            },
            confirmButton = {},
        )
        is ClearState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (state.success) "Fault codes cleared" else "Not cleared") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}

/** Edits the user's own description of a code; blank removes it (the bundled text is used again). */
@Composable
fun DtcDescriptionDialog(code: String, descriptions: DtcDescriptions, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val current = descriptions.describe(code)
    var text by rememberSaveable(code) { mutableStateOf(if (current.isUserDefined) current.text else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Description of $code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!current.isUserDefined) {
                    Text("${current.text}\n(${current.source})", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Your description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Your note is saved on this phone for all vehicles, for $code${if (code.contains('-')) "" else " and its sub-types"}. Leave empty to restore the applicable reference text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A fault code with its description; tapping "Edit" edits the description. */
@Composable
fun DtcRow(code: String, descriptions: DtcDescriptions, detail: String? = null, onEditDescription: (String) -> Unit) {
    val description = descriptions.describe(code)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(code, style = MaterialTheme.typography.titleSmall)
            Text(description.text, style = MaterialTheme.typography.bodyMedium)
            val extra = listOfNotNull(
                descriptions.failureType(code)?.let { "Failure type: $it" },
                detail,
                if (description.isUserDefined) "Your description" else "Source: ${description.source}",
            )
            if (extra.isNotEmpty()) {
                Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { onEditDescription(code) }) { Text("Edit") }
    }
}
