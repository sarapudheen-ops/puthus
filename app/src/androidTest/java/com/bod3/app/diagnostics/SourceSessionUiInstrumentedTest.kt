package com.bod3.app.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.DiagnosticLink
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.ui.EcuDetailState
import com.bod3.app.ui.screens.EcuDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Compose UI input on the minSdk emulator, SYNTHETIC selected-ECU fixture only.
 * Callbacks just count UI events: no Session, Bluetooth, writes, source pack,
 * calibration/serial proof, vehicle selection or executable-Toyota acceptance.
 * JVM scenarios separately test the capability, lease and post-bus guards.
 */
@RunWith(AndroidJUnit4::class)
class SourceSessionUiInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private val fixture: EcuDefinition get() {
        val refs = listOf("synthetic:consent-ui")
        val frame = ToyotaReadFrame(1, "105F", "0040", "505F", refs)
        val query = ToyotaReadFrame(2, "13B0", "", "53", refs)
        val workflow = ToyotaFaultWorkflow(1, 0, 3,
            listOf(ToyotaFunctionDetail(1, 0, null, 0, 0, refs)), listOf(frame),
            listOf(ToyotaFaultQuery(2, 1, 1, emptyList(), listOf(query), refs)), refs)
        val faults = FaultRead(DiagnosticRead("synthetic-fault", "Synthetic faults", "13B0", "53",
            minBytes = 1, maxBytes = 255, sourceRecords = refs),
            ToyotaFaultLayout(0, 1, 2, 2, false, 0, 1, 0,
                listOf(ToyotaFaultLabel("00001234", "P1234", "Synthetic only", refs)), sourceRecords = refs), workflow = workflow)
        return EcuDefinition(name = "Synthetic consent ECU", requestId = "7E0", responseId = "7E8", protocol = EcuProtocol.KWP,
            link = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500, DiagnosticDialect.KWP2000),
            sourceRecords = refs, diagnostics = EcuReadDefinitions(faults = faults))
    }

    private inner class Host {
        val detail = mutableStateOf(EcuDetailState(fixture))
        val connected = mutableStateOf(true)
        val expert = mutableStateOf(false)
        var requested = 0
        var confirmed = 0
        var dismissed = 0
        var refreshed = 0
        fun install() = rule.setContent {
            MaterialTheme {
                EcuDetailScreen(detail.value, DtcDescriptions(emptyMap(), emptyList()), connected.value, expert.value,
                    onRequestSourceSession = { requested++; detail.value = detail.value.copy(sourceSessionPrompt = true) },
                    onConfirmSourceSession = { confirmed++; detail.value = detail.value.copy(sourceSessionPrompt = false) },
                    onDismissSourceSession = { dismissed++; detail.value = detail.value.copy(sourceSessionPrompt = false) },
                    onBack = { detail.value = detail.value.copy(sourceSessionPrompt = false) },
                    onRefresh = { refreshed++ }, onReadRecords = {}, onClear = {}, onDismissClear = {}, onSaveDescription = { _, _ -> })
            }
        }
        fun open() {
            rule.runOnIdle { expert.value = true }
            rule.onNodeWithText("Confirm source DTC session 10 5F…").performScrollTo().performClick()
            rule.onNodeWithText("Enter source diagnostic session 10 5F?").assertExists()
        }
    }

    private fun checkbox() = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    private fun confirm() = rule.onNodeWithText("Enter session and read faults")

    @Test fun refreshNeverRequestsSessionAndConfirmationNeedsExplicitCheckboxOnEveryOpen() {
        val host = Host(); host.install()
        rule.onNodeWithText("Confirm source DTC session 10 5F…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Read again").performClick()
        rule.runOnIdle { assertEquals(1, host.refreshed); assertEquals(0, host.requested); assertEquals(0, host.confirmed) }
        host.open(); confirm().assertIsNotEnabled()
        checkbox().performClick(); confirm().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, host.confirmed) }
        rule.onNodeWithText("Enter source diagnostic session 10 5F?").assertDoesNotExist()
        host.open(); confirm().assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(2, host.requested); assertEquals(1, host.confirmed); assertEquals(1, host.dismissed) }
    }

    @Test fun checkedDialogCannotSubmitWhileDisconnectedExpertDisabledOrBusy() {
        val host = Host(); host.install(); host.open()
        checkbox().performClick(); confirm().assertIsEnabled()
        rule.runOnIdle { host.connected.value = false }; confirm().assertIsNotEnabled()
        rule.runOnIdle { host.connected.value = true; host.expert.value = false }; confirm().assertIsNotEnabled()
        rule.runOnIdle { host.expert.value = true; host.detail.value = host.detail.value.copy(identificationLoading = true) }
        confirm().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, host.confirmed) }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, host.dismissed) }
    }

    @Test fun displayedEcuDefinitionChangeResetsCheckboxEvenWhenPhysicalIdsAreTheSame() {
        val host = Host(); host.install(); host.open()
        checkbox().performClick(); confirm().assertIsEnabled()
        rule.runOnIdle { host.detail.value = host.detail.value.copy(ecu = host.detail.value.ecu.copy(name = "Different synthetic ECU")) }
        rule.onNodeWithText("Different synthetic ECU · 7E0 → 7E8").assertExists()
        confirm().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, host.confirmed) }
        rule.onNodeWithText("Cancel").performClick()
    }
}
