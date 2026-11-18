package com.bod3.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bod3.app.source.SourceLocation
import com.bod3.app.source.SourceRow
import com.bod3.app.ui.ToyotaSourceState
import com.bod3.app.ui.ToyotaSourceViewModel
import java.io.File

@Composable
fun ToyotaSourceRoute(entry: Long, initialQuery: String?, onExit: () -> Unit, model: ToyotaSourceViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val back = { if (!model.back()) onExit() }
    BackHandler(onBack = back)
    LaunchedEffect(entry) { model.open(initialQuery) }
    DisposableEffect(model) { onDispose { model.suspendLoading() } }
    ToyotaSourceScreen(state, onBack = back, onNavigate = model::navigate, onSearch = model::search,
        onPage = model::page, onRetry = model::retry, writePage = model::writePage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToyotaSourceScreen(
    state: ToyotaSourceState, onBack: () -> Unit, onNavigate: (SourceLocation) -> Unit,
    onSearch: (String) -> Unit, onPage: (Boolean) -> Unit, onRetry: () -> Unit, writePage: suspend () -> File,
) {
    val page = state.page
    val scroll = rememberLazyListState()
    LaunchedEffect(state.request.location, state.request.offset) { scroll.scrollToItem(0) }
    val share = rememberShare("text/plain", "Toyota source evidence", "Share this source page") { writePage() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Toyota data library") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { onNavigate(SourceLocation.Overview) }, enabled = !state.loading) { Text("Library") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("OFFLINE SOURCE DATA · NOT EXECUTABLE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
            if (state.loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (page == null) "Preparing the bundled source library… First open installs about 240 MiB in private storage. No network download or adapter is used." else "Reading source records…", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error -> item {
                SectionCard("Source library unavailable — no partial data used") {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Text("Your vehicle catalog, profiles and diagnostic configuration are unchanged. Free storage if needed, then retry. A missing or corrupt bundled library is never treated as an empty successful import.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRetry) { Text("Retry library") }
                }
            } }
            if (page != null) {
                item {
                    SectionCard(page.title) {
                        Text(page.explanation, style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                page.fields.forEach { field ->
                                    Text(field.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(field.value, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        page.links.forEach { link ->
                            OutlinedButton(onClick = { onNavigate(link.location) }, enabled = !state.loading) { Text(link.title) }
                        }
                        TextButton(onClick = share, enabled = !state.loading) { Text("Share displayed page (source evidence)") }
                    }
                }
                page.searchHint?.let { hint -> item {
                    OutlinedTextField(value = state.request.query, onValueChange = onSearch, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (state.request.query.isNotEmpty()) TextButton(onClick = { onSearch("") }) { Text("Clear search") }
                } }
                page.rangeLabel?.let { label -> item { Text(label, style = MaterialTheme.typography.labelLarge) } }
                items(page.rows, key = { it.key }) { row -> SourceRecordCard(row, enabled = !state.loading, onNavigate) }
                if (page.rows.isEmpty() && !state.loading) item { Text("No records on this page. Use the source links above, clear the search, or go back.", style = MaterialTheme.typography.bodySmall) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onPage(false) }, enabled = state.request.offset > 0 && !state.loading) { Text("Previous page") }
                        Button(onClick = { onPage(true) }, enabled = page.hasNext && !state.loading) { Text("Next page") }
                    }
                }
            }
            item {
                Text("No Send, Run or automatic vehicle selection. Source bytes/expressions never bypass the read-only policy, Expert mode, confirmations or backup → write → verify safeguards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceRecordCard(row: SourceRow, enabled: Boolean, onNavigate: (SourceLocation) -> Unit) {
    SectionCard(row.title, modifier = if (row.destination == null) Modifier else Modifier.clickable(enabled = enabled) { onNavigate(row.destination) }) {
        SelectionContainer {
            Text(row.detail, style = MaterialTheme.typography.bodySmall,
                color = if (row.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                fontFamily = if (row.monospace) FontFamily.Monospace else FontFamily.Default)
        }
        row.destination?.let { location -> TextButton(onClick = { onNavigate(location) }, enabled = enabled) { Text("Open source record ›") } }
        row.links.forEach { link -> OutlinedButton(onClick = { onNavigate(link.location) }, enabled = enabled) { Text(link.title) } }
    }
}
