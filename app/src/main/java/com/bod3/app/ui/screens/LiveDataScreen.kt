package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bod3.app.R
import com.bod3.app.ui.LiveItem
import com.bod3.app.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDataScreen(
    state: UiState,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.live_data_title))
                        state.liveEcu?.let { Text("ECU $it", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.liveRunning) {
                        TextButton(onClick = onPause) { Text("Pause") }
                    } else {
                        TextButton(onClick = onResume) { Text("Resume") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.live.isEmpty()) {
            Text("This vehicle reports none of the supported live PIDs.", modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LiveGrid(state.live, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun LiveGrid(liveItems: List<LiveItem>, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(liveItems, key = { it.definition.pid }) { item -> LiveTile(item) }
    }
}

@Composable
private fun LiveTile(item: LiveItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.definition.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val reading = item.reading
            Text(
                text = reading?.formatted ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (reading != null) item.definition.unit else item.status ?: "Waiting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
