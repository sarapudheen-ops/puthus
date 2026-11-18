package com.bod3.app.ui.screens

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bod3.app.R
import com.bod3.app.bluetooth.BluetoothPermissions
import com.bod3.app.bluetooth.PairedAdapter
import com.bod3.app.ui.ConnectionState
import com.bod3.app.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    state: UiState,
    onPermissionResult: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onConnect: (PairedAdapter) -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVehicles: () -> Unit = {},
    onOpenGarage: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenDataPacks: () -> Unit = {},
    onShowIntro: () -> Unit = {},
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onPermissionResult(results.values.all { it })
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_adapter_title)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh paired devices")
                    }
                    TextButton(onClick = onOpenGarage) { Text("Saved") }
                    TextButton(onClick = onOpenVehicles) { Text("Catalog") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Connection settings")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("App settings") }, onClick = { menu = false; onOpenAppSettings() })
                            DropdownMenuItem(text = { Text("Data packs (import / export)") }, onClick = { menu = false; onOpenDataPacks() })
                            DropdownMenuItem(text = { Text("Introduction") }, onClick = { menu = false; onShowIntro() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.bluetoothAvailable -> Message("This device has no Bluetooth support.")

                !state.permissionGranted -> PermissionRequest(
                    denied = state.permissionDenied,
                    onRequest = { permissionLauncher.launch(BluetoothPermissions.runtimePermissions) },
                    onOpenSettings = { openAppSettings(context) },
                )

                !state.bluetoothEnabled -> Message(
                    text = "Bluetooth is turned off.",
                    action = "Turn on Bluetooth",
                    onAction = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                )

                state.pairedDevices.isEmpty() -> Message(
                    text = "No paired devices.\n\nPair your OBD adapter in the system Bluetooth settings " +
                        "(the PIN is usually 1234 or 0000), then come back here.",
                    action = "Open Bluetooth settings",
                    onAction = { startActivitySafely(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                )

                else -> DeviceList(state, onConnect)
            }
        }
    }

    val failed = state.connection as? ConnectionState.Failed
    if (failed != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Could not connect to ${failed.device.name}") },
            text = { Text(failed.message, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { onDismissError(); onConnect(failed.device) }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onDismissError) { Text("Close") } },
        )
    }
}

@Composable
private fun DeviceList(state: UiState, onConnect: (PairedAdapter) -> Unit) {
    val connecting = state.connection as? ConnectionState.Connecting
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "Connection profile: ${state.settings.activeProfile.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.catalog.selected?.title ?: "No vehicle selected manually", style = MaterialTheme.typography.titleSmall)
                Text(if (state.catalog.selectedId == null) "Standard OBD reads are available, without a fallback ECU list or vehicle connection settings. Choose a vehicle in Catalog to apply its definitions."
                    else "This explicit selection supplies the ECU list and inherited connection overrides. Reading a VIN never changes it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.catalog.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Text(
                "Paired devices",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(state.pairedDevices, key = { it.address }) { device ->
            val isConnecting = connecting?.device?.address == device.address
            ListItem(
                headlineContent = { Text(device.name) },
                supportingContent = {
                    Text(if (isConnecting) connecting?.step.orEmpty() else device.address)
                },
                leadingContent = {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        tint = if (device.looksLikeObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                },
                trailingContent = {
                    if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.clickable(enabled = connecting == null) { onConnect(device) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PermissionRequest(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bluetooth permission needed", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(8.dp))
        Text(
            "bod3 needs the \"Nearby devices\" permission to list your paired OBD adapter and connect to it. " +
                "It does not scan for devices or use your location.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.padding(12.dp))
        Button(onClick = onRequest) { Text(if (denied) "Try again" else "Grant permission") }
        if (denied) {
            Spacer(Modifier.padding(4.dp))
            Text(
                "If Android no longer shows the prompt, allow \"Nearby devices\" in the app settings.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.padding(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    startActivitySafely(context, intent)
}

private fun startActivitySafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some customised Android builds lack these settings screens; nothing else we can do.
    }
}
