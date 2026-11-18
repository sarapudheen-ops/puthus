package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.settings.AppSettings
import com.bod3.app.settings.DistanceUnit
import com.bod3.app.settings.PressureUnit
import com.bod3.app.settings.TemperatureUnit
import com.bod3.app.settings.ThemeMode
import com.bod3.app.settings.UnitPreferences
import com.bod3.app.settings.Units

/** App-wide preferences: theme, keep screen on, display units. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenDataPacks: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onShowIntro: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Choices(ThemeMode.entries, settings.theme, { it.label }) { mode -> onChange { it.copy(theme = mode) } }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "While the app is open, so live data, tests and coding stay visible. Uses more battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.keepScreenOn, onCheckedChange = { on -> onChange { it.copy(keepScreenOn = on) } })
                }
            }

            SectionCard("Units") {
                Text(
                    "Used for live data and freeze frames. Recordings (CSV), data packs and coding values always keep the vehicle's original metric units.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Temperature", style = MaterialTheme.typography.labelLarge)
                Choices(TemperatureUnit.entries, settings.units.temperature, { it.label }) { t -> onChange { it.copy(units = it.units.copy(temperature = t)) } }
                Text("Distance, speed, fuel, torque", style = MaterialTheme.typography.labelLarge)
                Choices(DistanceUnit.entries, settings.units.distance, { it.label }) { d -> onChange { it.copy(units = it.units.copy(distance = d)) } }
                Text("Pressure", style = MaterialTheme.typography.labelLarge)
                Choices(PressureUnit.entries, settings.units.pressure, { it.label }) { p -> onChange { it.copy(units = it.units.copy(pressure = p)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChange { it.copy(units = UnitPreferences()) } }, enabled = !settings.units.isDefault) { Text("Metric") }
                    OutlinedButton(onClick = { onChange { it.copy(units = UnitPreferences.IMPERIAL) } }, enabled = settings.units != UnitPreferences.IMPERIAL) { Text("US") }
                }
                Text(
                    "Example: " + listOf(
                        Units.format(90.0, "°C", 0, settings.units),
                        Units.format(100.0, "km/h", 0, settings.units),
                        Units.format(101.0, "kPa", 0, settings.units),
                        Units.format(250.0, "Nm", 0, settings.units),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard("More") {
                OutlinedButton(onClick = onOpenDataPacks, modifier = Modifier.fillMaxWidth()) { Text("Data packs: import / export") }
                OutlinedButton(onClick = onOpenConnectionSettings, modifier = Modifier.fillMaxWidth()) { Text("Connection settings and Expert mode") }
                OutlinedButton(onClick = onShowIntro, modifier = Modifier.fillMaxWidth()) { Text("Show the introduction again") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}
