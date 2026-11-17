package com.bod3.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.live.LiveValueDef

/** Chooses which values are polled (fewer values = faster updates) and their order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSelectScreen(available: List<LiveValueDef>, selected: List<String>, onApply: (List<String>) -> Unit, onCancel: () -> Unit) {
    val chosen = remember(selected) { mutableStateListOf<String>().apply { addAll(selected) } }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = available.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live values (${chosen.size})") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") } },
                actions = {
                    TextButton(onClick = { chosen.clear() }) { Text("None") }
                    TextButton(onClick = { onApply(chosen.toList()) }) { Text("Apply") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Every value is a separate request: 6–10 values update about twice a second, 30 values much slower. Selected values are shown in the order ticked; ↑ moves one up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.key }) { def ->
                    val index = chosen.indexOf(def.key)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { if (index >= 0) chosen.remove(def.key) else chosen.add(def.key) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = index >= 0, onCheckedChange = { if (it) chosen.add(def.key) else chosen.remove(def.key) })
                        Column(Modifier.weight(1f)) {
                            Text(def.name)
                            val source = when (def) {
                                is LiveValueDef.Standard -> "PID %02X".format(def.definition.pid)
                                is LiveValueDef.Custom -> "Custom · ${def.pid.request}"
                            }
                            Text(listOf(source, def.unit).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (index > 0) {
                            IconButton(onClick = { chosen.removeAt(index); chosen.add(index - 1, def.key) }) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                            }
                        }
                        if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
}
