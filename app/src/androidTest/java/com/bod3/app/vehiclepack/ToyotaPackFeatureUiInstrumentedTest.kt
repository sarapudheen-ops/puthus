package com.bod3.app.vehiclepack

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bod3.app.android.AssetVehiclePacks
import com.bod3.app.android.SharedPrefsKeyValueStore
import com.bod3.app.bringup.AttemptOutcome
import com.bod3.app.bringup.BringUpAttempt
import com.bod3.app.bringup.BringUpController
import com.bod3.app.bringup.BringUpOutcome
import com.bod3.app.bringup.BringUpReport
import com.bod3.app.bringup.BringUpScope
import com.bod3.app.bringup.BringUpState
import com.bod3.app.bringup.VerifiedSetupStore
import com.bod3.app.ui.screens.ToyotaVehiclePacksScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real 2007 pack UI with simulated verification state: no vehicle or adapter is contacted. */
@RunWith(AndroidJUnit4::class)
class ToyotaPackFeatureUiInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun enabled2007ExplainsBringUpAndVerifiedStateUnlocksReadButtons() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(app.noBackupFilesDir, "pack-ui-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val catalog = VehiclePackCatalog(AssetVehiclePacks.load(app).index, PackObjectStore(cache, PackHttpSource()))
            var page = catalog.root()
            for (name in listOf("Toyota", "Kijang Innova", "TGN · -2009.07")) {
                page = catalog.open(page.children.single { it.name == name })
            }
            val vehicle = catalog.vehicle(page.children.single { it.name == "MINI VAN · w/o VSC (Vehicle Stability Control)" })
            val system = catalog.system(vehicle, vehicle.systems.single { it.id == "SYSLIST_EU.BIN@00023a75" })
            val candidate = catalog.bringUpCandidates(system).single()
            assertTrue(system.enabled && system.readOnly && candidate.runnable)
            assertEquals("1 ECU · 30 PIDs · 1,414 DTCs · 1 ECU info read", system.coverage)
            val packState = VehiclePacksState(view = PackView.FEATURES, vehicle = vehicle, system = system,
                coverage = system.coverage, executableDefinitions = 2890)
            val bringUp = mutableStateOf(BringUpState(candidates = listOf(candidate)))
            val controller = VehiclePacksController(scope, { catalog }, { vehicle.variantId }, {}, {}, {})
            val bringController = BringUpController(scope, VerifiedSetupStore(File(cache, "setups")), SharedPrefsKeyValueStore(app), { null })
            rule.setContent {
                MaterialTheme {
                    ToyotaVehiclePacksScreen(packState, controller, offline = false, connected = true,
                        onBack = {}, onLive = {}, onTests = {}, onCoding = {}, onDiagnostics = {},
                        bringUp = bringUp.value, bringUpController = bringController, onStartBringUp = {},
                        writeBringUpReport = { File(cache, "report") }, writeVerifiedSetups = { File(cache, "setups.json") },
                        onImportSetups = {})
                }
            }
            fun scroll(text: String) = rule.onNodeWithTag("toyotaPackContent").performScrollToNode(hasText(text))
            fun selectTab(tab: String) {
                rule.onNodeWithTag("toyotaPackContent").performScrollToNode(hasTestTag("categoryTabs"))
                rule.onNodeWithTag("category-$tab").performScrollTo().performClick()
            }
            fun gate(tab: String, button: String, prompt: String, notConverted: String) {
                selectTab(tab)
                scroll(prompt)
                rule.onNodeWithText(prompt).assertExists()
                rule.onNodeWithText(notConverted, substring = true).assertDoesNotExist()
                rule.onNodeWithText(button).assertIsNotEnabled()
            }
            scroll("Start is disabled: enter the required vehicle instance above.")
            rule.onNodeWithText("Unique vehicle instance (garage label or VIN) · required").assertExists()
            rule.onNodeWithText("Try ranked read-only candidates").assertIsNotEnabled()
            gate("pids", "Select values, graph and record", "Run the read-only ECU bring-up above on this connection to unlock live data.", "No executable PID request")
            gate("dtcs", "Read fault codes", "Run the read-only ECU bring-up above on this connection to unlock fault codes.", "have not been converted")
            gate("actuators", "View this system's tests", "Run the read-only ECU bring-up above on this connection to check available tests. Read-only restrictions still apply.", "Start/stop/status and safety mappings remain unresolved")
            gate("coding", "View this system's customizations", "Run the read-only ECU bring-up above on this connection to check available customizations. Read-only restrictions still apply.", "Read/write identifiers and value maps remain unresolved")
            gate("ecuInfo", "ECU information", "Run the read-only ECU bring-up above on this connection to unlock ECU information.", "have not been converted")

            // Only UI state is simulated; no proof is persisted and no ECU request is made.
            val attempt = BringUpAttempt(candidate.id, candidate.fingerprint, 1, 2, AttemptOutcome.WORKING, "Simulated UI state")
            val report = BringUpReport(BringUpScope(listOf("a", "b", "c", "d"), system.id, "UI fixture"),
                1, 2, BringUpOutcome.WORKING, listOf(candidate), listOf(attempt), emptyList(), true)
            rule.runOnIdle { bringUp.value = BringUpState(vehicleInstance = "UI fixture", candidates = listOf(candidate), report = report, verifiedHere = true) }
            for ((tab, button) in listOf("pids" to "Select values, graph and record", "dtcs" to "Read fault codes", "ecuInfo" to "ECU information")) {
                selectTab(tab); scroll(button); rule.onNodeWithText(button).assertIsEnabled()
            }
            for ((tab, button) in listOf("actuators" to "View this system's tests", "coding" to "View this system's customizations")) {
                selectTab(tab); scroll(button); rule.onNodeWithText(button).assertIsNotEnabled()
            }
            Log.i("ToyotaPackUiEvidence", "2007 UI: unverified bring-up prompts shown; unconverted claims absent; simulated verified read buttons enabled; read-only write buttons disabled")
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }
}
