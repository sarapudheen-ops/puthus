package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bod3.app.coding.CodingEditorState

/** Adds or changes a user coding recipe as JSON; problems are listed with their JSON paths. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingEditorScreen(
    editor: CodingEditorState,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onTemplate: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editor.editingId != null) "Edit ${editor.editingId}" else "New coding recipe") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "One recipe in bod3-coding JSON. Required: id, name, module (name, requestId, responseId), method " +
                    "(long_coding, adaptation or did_value), byte, and mask for long coding or values for did_value. " +
                    "Saved recipes are stored on this phone as your own and are never marked verified.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (editor.errors.isNotEmpty()) {
                SectionCard("Not saved – fix these") {
                    editor.errors.take(30).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            OutlinedTextField(
                value = editor.text,
                onValueChange = onChange,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Validate & save") }
                OutlinedButton(onClick = onTemplate) { Text("Start from template") }
            }
        }
    }
}
