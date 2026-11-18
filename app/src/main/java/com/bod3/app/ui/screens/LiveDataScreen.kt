package com.bod3.app.ui.screens

import com.bod3.app.settings.UnitPreferences
import com.bod3.app.settings.Units
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bod3.app.R
import com.bod3.app.android.ReportSharer
import com.bod3.app.datapack.LiveDisplay
import com.bod3.app.ui.LiveItem
import com.bod3.app.ui.UiState
import java.io.File
import kotlinx.coroutines.launch

data class LiveActions(
    val onBack: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onSelect: () -> Unit,
    val onDisplay: (key: String, display: LiveDisplay) -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    val writeCsv: suspend () -> File?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDataScreen(state: UiState, actions: LiveActions, units: UnitPreferences = UnitPreferences()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }
    val recording = state.liveConfig.recording
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.live_data_title))
                        val subtitle = listOfNotNull(state.liveEcu?.let { "ECU $it" }, "${state.live.size} values").joinToString(" · ")
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = actions.onSelect) { Icon(Icons.Filled.Edit, contentDescription = "Choose values") }
                    if (state.liveRunning) {
                        TextButton(onClick = actions.onPause) { Text("Pause") }
                    } else {
                        TextButton(onClick = actions.onResume) { Text("Resume") }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val status = when {
                            recording.active -> "● Recording · ${recording.rows} rows"
                            recording.lastRows != null -> "Recorded ${recording.lastRows} rows" + if (recording.full) " (limit reached)" else ""
                            else -> "Not recording"
                        }
                        Text(
                            status,
                            modifier = Modifier.weight(1f),
                            color = if (recording.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (recording.active) {
                            TextButton(onClick = actions.onStopRecording) { Text("Stop") }
                        } else {
                            TextButton(onClick = actions.onStartRecording, enabled = state.live.isNotEmpty()) { Text("Record") }
                        }
                        if (!recording.active && recording.lastRows != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    exportError = null
                                    runCatching { actions.writeCsv() }
                                        .onSuccess { file ->
                                            if (file != null) ReportSharer.share(context, file, "text/csv", "bod3 live data", "Share CSV")
                                        }
                                        .onFailure { exportError = "Export failed: ${it.message}" }
                                }
                            }) { Icon(Icons.Filled.Share, contentDescription = "Export CSV") }
                        }
                    }
                    exportError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) }
                }
            }
            if (state.live.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No values selected, or the vehicle reports none of them. Tap the pencil to choose values.", modifier = Modifier.padding(4.dp))
                }
            }
            items(state.live, key = { it.def.key }, span = { item ->
                GridItemSpan(if (state.liveConfig.displayOf(item.def.key) == LiveDisplay.GRAPH) maxLineSpan else 1)
            }) { item ->
                LiveTile(
                    item = item,
                    display = state.liveConfig.displayOf(item.def.key),
                    history = state.liveConfig.history[item.def.key].orEmpty(),
                    units = units,
                    onDisplay = { actions.onDisplay(item.def.key, it) },
                )
            }
        }
    }
}

@Composable
private fun LiveTile(item: LiveItem, display: LiveDisplay, history: List<Double>, units: UnitPreferences, onDisplay: (LiveDisplay) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Values are recorded in SI units and converted for display only.
    val shown = remember(item.def, units) { Units.display(item.def, units) }
    val value = item.value?.let(shown::convert)
    val shownHistory = remember(history, shown) { if (shown.conversion.isIdentity) history else history.map(shown::convert) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.def.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { menu = true }) { Text(display.label, style = MaterialTheme.typography.labelSmall) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        LiveDisplay.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.label) }, onClick = { menu = false; onDisplay(mode) })
                        }
                    }
                }
            }
            when (display) {
                LiveDisplay.GAUGE -> Gauge(value, shown.min, shown.max, Modifier.fillMaxWidth().aspectRatio(2f).padding(end = 12.dp))
                LiveDisplay.GRAPH -> Graph(shownHistory, shown.min, shown.max, Modifier.fillMaxWidth().height(120.dp).padding(end = 12.dp))
                LiveDisplay.VALUE -> {}
            }
            Text(text = value?.let(shown::format) ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (value != null) shown.unit else item.status ?: "Waiting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun fraction(value: Double, min: Double, max: Double): Float {
    val span = max - min
    if (span <= 0.0) return 0f
    return ((value - min) / span).coerceIn(0.0, 1.0).toFloat()
}

/** Half-circle gauge from [min] to [max]. */
@Composable
private fun Gauge(value: Double?, min: Double, max: Double, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = size.height * 0.18f
        val diameter = minOf(size.width, size.height * 2f) - stroke
        val topLeft = Offset((size.width - diameter) / 2f, stroke / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(track, 180f, 180f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (value != null) {
            drawArc(bar, 180f, 180f * fraction(value, min, max), useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Line graph of the recent samples, scaled to min/max (widened if values fall outside). */
@Composable
private fun Graph(history: List<Double>, min: Double, max: Double, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f))
        if (history.size < 2) return@Canvas
        val low = minOf(min, history.min())
        val high = maxOf(max, history.max())
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val step = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - ((v - low) / span).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}
