package com.bod3.app.vehiclepack

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogNode
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

    @Test fun offlineBrandsAndModelsNeedNoPackDownload() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(app.noBackupFilesDir, "offline-ui-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val names = listOf(
            CatalogNode("honda", null, CatalogLevel.MAKE, "Honda"),
            CatalogNode("civic", "honda", CatalogLevel.MODEL, "Civic"),
            CatalogNode("civic-gen", "civic", CatalogLevel.GENERATION, "Example generation"),
            CatalogNode("toyota", null, CatalogLevel.MAKE, "Toyota"),
        )
        try {
            val controller = VehiclePacksController(scope, { error("Offline names must not load a pack") }, { null }, {}, {}, {})
            val bringController = BringUpController(scope, VerifiedSetupStore(File(cache, "setups")), SharedPrefsKeyValueStore(app), { null })
            rule.setContent {
                MaterialTheme {
                    ToyotaVehiclePacksScreen(VehiclePacksState(), controller, offline = true, connected = false,
                        onBack = {}, onLive = {}, onTests = {}, onCoding = {}, onDiagnostics = {},
                        bringUp = BringUpState(), bringUpController = bringController, onStartBringUp = {},
                        writeBringUpReport = { File(cache, "report") }, writeVerifiedSetups = { File(cache, "setups.json") },
                        onImportSetups = {}, names = names)
                }
            }
            rule.onNodeWithText("Choose a brand").assertExists()
            rule.onNodeWithText("Search").performTextInput("Honda")
            rule.onNodeWithText("Toyota").assertDoesNotExist()
            rule.onNodeWithText("Honda").performClick()
            rule.onNodeWithText("Choose a model").assertExists()
            rule.onNodeWithText("Civic").performClick()
            rule.onNodeWithText("Choose your car").assertExists()
            rule.onNodeWithText("Example generation").assertIsNotEnabled()
            rule.onNodeWithText("Names and model years only.", substring = true).assertExists()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Choose a model").assertExists()
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }

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
            scroll("Read now")
            rule.onNodeWithText("Vehicle label or VIN").assertDoesNotExist()
            rule.onNodeWithText("View this system's tests").assertDoesNotExist()
            rule.onNodeWithText("View this system's customizations").assertDoesNotExist()
            for (button in listOf("Select values, graph and record", "Read fault codes", "ECU information")) {
                scroll(button); rule.onNodeWithText(button).assertIsNotEnabled()
            }
            rule.onNodeWithText("have not been converted", substring = true).assertDoesNotExist()

            // Only UI state is simulated; no proof is persisted and no ECU request is made.
            val attempt = BringUpAttempt(candidate.id, candidate.fingerprint, 1, 2, AttemptOutcome.WORKING, "Simulated UI state")
            val report = BringUpReport(BringUpScope(listOf("a", "b", "c", "d"), system.id, "UI fixture"),
                1, 2, BringUpOutcome.WORKING, listOf(candidate), listOf(attempt), emptyList(), true)
            rule.runOnIdle { bringUp.value = BringUpState(vehicleInstance = "UI fixture", candidates = listOf(candidate), report = report, verifiedHere = true) }
            for (button in listOf("Select values, graph and record", "Read fault codes", "ECU information")) {
                scroll(button); rule.onNodeWithText(button).assertIsEnabled()
            }
            Log.i("ToyotaPackUiEvidence", "2007 UI: unverified bring-up prompts shown; unconverted claims absent; simulated verified read buttons enabled; read-only write buttons disabled")
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }
}
