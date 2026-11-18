package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private class IntroPage(val title: String, val paragraphs: List<String>)

private val PAGES = listOf(
    IntroPage(
        "Welcome to bod3",
        listOf(
            "bod3 reads and diagnoses any OBD2 car through a vLinker MC+ (or another ELM327/STN compatible) Bluetooth adapter, " +
                "with extended module access, tests and coding for VW group cars (UDS over CAN).",
            "These four pages explain how to connect and how bod3 keeps your car safe. You can open them again from App settings.",
        ),
    ),
    IntroPage(
        "1 · Pair the adapter",
        listOf(
            "Plug the adapter into the OBD port (under the dashboard, driver's side) and switch the ignition on. The engine can stay off.",
            "Pair it in Android's Bluetooth settings – the PIN is usually 1234 or 0000. It is a classic Bluetooth device, so it is paired in Android, not in the app.",
            "Then pick it from the device list in bod3. The adapter type, firmware and the car's protocol are detected automatically; " +
                "you can choose a protocol manually in Connection settings if detection fails.",
        ),
    ),
    IntroPage(
        "2 · Permissions",
        listOf(
            "Android asks for the 'Nearby devices' (Bluetooth) permission. It is only used to talk to the adapter – bod3 does not use your location and needs no internet for diagnostics.",
            "The internet is only used, if available, to decode a VIN into make and model (NHTSA vPIC).",
            "Files you export are shared through Android's share sheet; files you import are opened only when you choose them.",
        ),
    ),
    IntroPage(
        "3 · Read-only by default",
        listOf(
            "Out of the box bod3 only reads: live data, fault codes, freeze frames, readiness, module identification. " +
                "Everything that could change the car is blocked at the lowest level.",
            "Clearing fault codes always asks for confirmation first.",
            "Bidirectional tests and coding need Expert mode, which is off until you switch it on in Connection settings and acknowledge the risks.",
        ),
    ),
    IntroPage(
        "4 · Safety for tests and coding",
        listOf(
            "Before every write bod3 checks battery voltage (at least 12 V – use a charger), that the engine is off, and the module's part number and software version.",
            "The current value is backed up, you confirm the exact change, the new value is read back and verified, and a one-tap rollback is offered. Every write is logged.",
            "Recipes from the internet can be wrong for your car. Only use recipes you trust, and never code while driving.",
        ),
    ),
)

/** First-run introduction: pairing, permissions, read-only vs Expert mode, and safety. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = PAGES[page.coerceIn(0, PAGES.lastIndex)]
    val last = page >= PAGES.lastIndex
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LinearProgressIndicator(progress = { (page + 1f) / PAGES.size }, modifier = Modifier.fillMaxWidth())
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(current.title, style = MaterialTheme.typography.headlineSmall)
                current.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!last) TextButton(onClick = onFinish) { Text("Skip") }
                Spacer(modifier = Modifier.weight(1f))
                if (page > 0) OutlinedButton(onClick = { page -= 1 }) { Text("Back") }
                Button(onClick = { if (last) onFinish() else page += 1 }) { Text(if (last) "Get started" else "Next") }
            }
        }
    }
}
