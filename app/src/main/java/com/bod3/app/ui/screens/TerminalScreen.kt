package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bod3.app.obd.terminal.TerminalLine
import com.bod3.app.ui.UiState

/**
 * Expert-mode raw terminal. Commands go to the adapter as typed; [com.bod3.app.obd.terminal.TerminalPolicy]
 * blocks link-breaking AT commands, unknown ST/VT commands and every non-read diagnostic service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: UiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    terminalText: () -> String,
    onClear: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.terminal.size) {
        if (state.terminal.isNotEmpty()) listState.animateScrollToItem(state.terminal.size - 1)
    }
    val send = {
        if (input.isNotBlank() && !state.terminalBusy) {
            onSend(input)
            input = ""
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expert terminal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close terminal") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Raw adapter access. AT commands are sent as typed (except baud-rate, memory and monitor commands); " +
                        "ST/VT only for identification; OBD/UDS requests must be reads (01, 09, 22, 19 …). " +
                        "Writes, clears and actuator tests are blocked here. Adapter settings are restored when you leave.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.terminal) { line ->
                        val color = when (line.kind) {
                            TerminalLine.Kind.INPUT -> MaterialTheme.colorScheme.primary
                            TerminalLine.Kind.ERROR -> MaterialTheme.colorScheme.error
                            TerminalLine.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            TerminalLine.Kind.OUTPUT -> MaterialTheme.colorScheme.onSurface
                        }
                        val prefix = when (line.kind) {
                            TerminalLine.Kind.INPUT -> "> "
                            TerminalLine.Kind.ERROR -> "! "
                            TerminalLine.Kind.INFO -> "# "
                            TerminalLine.Kind.OUTPUT -> ""
                        }
                        Text(prefix + line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
                    }
                }
            }
            RawLogActions(terminalText, "terminal", enabled = state.terminal.isNotEmpty(), onClear = onClear)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("ATRV, 0100, 22F190 …") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                IconButton(onClick = { send() }, enabled = !state.terminalBusy && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
