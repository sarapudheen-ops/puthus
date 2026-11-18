package com.bod3.app.obd.connection

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.FakeAdapterTransport
import com.bod3.app.obd.testing.SimulatedVehicleElm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ConnectionStore(InMemoryKeyValueStore(), clock = { 42L })
    private val log = mutableListOf<String>()
    private val manager = ConnectionManager(scope, store, Dispatchers.Default) { synchronized(log) { log += it } }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun connect(elm: SimulatedVehicleElm, profile: ConnectionProfile = ConnectionProfile.DEFAULT): ConnectionResult =
        manager.connect(FakeAdapterTransport(elm::respond), "00:11:22:33:44:55", profile)

    @Test
    fun automaticSearchOnCanWorksFirstTime() = runBlocking<Unit> {
        val result = connect(SimulatedVehicleElm(vehicleProtocol = "6"))
        val protocol = result.negotiation.protocol!!
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, protocol.protocol)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", protocol.description)
        assertEquals(1, result.negotiation.attempts.size)
        assertEquals("ATSP0", result.negotiation.attempts[0].selectCommand)
        assertEquals("vLinker MC+ V2.2.11", result.adapter.capabilities.vtVersion)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, store.rememberedProtocol("00:11:22:33:44:55")!!.protocol)
        result.driver.close()
    }

    @Test
    fun failedSearchFallsBackThroughKLineProtocolsAndLogsEachAttempt() = runBlocking<Unit> {
        val elm = SimulatedVehicleElm(vehicleProtocol = "3", autoSearchWorks = false)
        val result = connect(elm)
        val attempts = result.negotiation.attempts
        assertEquals(listOf("ATSP0", "ATSP5", "ATSP4", "ATSP3"), attempts.map { it.selectCommand })
        assertEquals(listOf(false, false, false, true), attempts.map { it.success })
        assertTrue(attempts[0].result, attempts[0].result.startsWith("UNABLE TO CONNECT"))
        assertTrue(attempts[1].result, attempts[1].result.startsWith("BUS INIT: ...ERROR"))
        assertTrue(attempts[4 - 1].result.startsWith("486B104100"))
        assertEquals("ISO 9141-2", attempts[3].atdp)
        assertTrue(attempts.all { it.atdp != null }) // ATDP logged after every attempt
        assertEquals(ObdProtocol.ISO_9141_2, result.negotiation.protocol!!.protocol)
        assertTrue(log.any { it.startsWith("Protocol 5 ·") && it.contains("failed") })
        result.driver.close()

        // Next connection tries the remembered protocol first and succeeds immediately.
        val again = connect(SimulatedVehicleElm(vehicleProtocol = "3", autoSearchWorks = false))
        assertEquals(listOf("ATSP3"), again.negotiation.attempts.map { it.selectCommand })
        again.driver.close()
    }

    @Test
    fun fallsBackToCan29WhenOnlyExplicitSelectionWorks() = runBlocking<Unit> {
        val result = connect(SimulatedVehicleElm(vehicleProtocol = "7", autoSearchWorks = false))
        assertEquals(listOf("ATSP0", "ATSP5", "ATSP4", "ATSP3", "ATSP6", "ATSP7"), result.negotiation.attempts.map { it.selectCommand })
        assertEquals(ObdProtocol.ISO_15765_4_CAN_29_500, result.negotiation.protocol!!.protocol)
        result.driver.close()
    }

    @Test
    fun forcedProtocolWithoutFallbacksTriesOnlyThatOne() = runBlocking<Unit> {
        val profile = ConnectionProfile(id = "p", name = "KWP only", forcedProtocol = ObdProtocol.ISO_14230_4_KWP_FAST, tryFallbacks = false)
        val result = connect(SimulatedVehicleElm(vehicleProtocol = "6"), profile)
        assertEquals(listOf("ATSP5"), result.negotiation.attempts.map { it.selectCommand })
        assertFalse(result.negotiation.succeeded)
        // The adapter stays connected even though no protocol answered.
        assertTrue(result.driver.isOpen)
        assertNull(store.rememberedProtocol("00:11:22:33:44:55"))
        result.driver.close()
    }

    @Test
    fun appliesProfileSettingsAndSendsVtCommandsWithoutGating() = runBlocking<Unit> {
        val profile = ConnectionProfile(id = "p", name = "Tuned", adapterTimeoutMs = 200, adaptiveTiming = 2, useVtTimeout = true, extendedAddress = "F1")
        val vtElm = SimulatedVehicleElm()
        val withVt = connect(vtElm, profile)
        assertEquals(listOf("ATAT2", "VTTOST31", "ATCEAF1"), withVt.setup.map { it.command.replace(" ", "") })
        assertTrue(withVt.setup.all { it.accepted })
        withVt.driver.close()

        // An adapter without VT firmware gets VTTOST anyway; its "?" is logged and skipped, and the
        // connection still succeeds.
        val plainElm = SimulatedVehicleElm(vt = false)
        val withoutVt = connect(plainElm, profile)
        assertEquals("VTTOST 31", withoutVt.setup[1].command)
        assertFalse(withoutVt.setup[1].accepted)
        assertTrue(plainElm.commands.contains("VTTOST31"))
        assertTrue(withoutVt.negotiation.succeeded)
        withoutVt.driver.close()
    }

    @Test
    fun rejectedSetupCommandsAreNotFatalAndPlainAtceaIsQuiet() = runBlocking<Unit> {
        val elm = SimulatedVehicleElm(rejected = mutableSetOf("ATCEA", "ATAT2"))
        val result = connect(elm, ConnectionProfile(id = "p", name = "p", adaptiveTiming = 2))
        assertEquals(listOf(false, false), result.setup.map { it.accepted })
        assertTrue(result.negotiation.succeeded) // the connection carries on
        val warnings = synchronized(log) { log.filter { it.startsWith("Warning") } }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("ATAT2"))
        result.driver.close()
    }

    @Test
    fun linkLossIsReportedButDeliberateCloseIsNot() = runBlocking<Unit> {
        val transport = FakeAdapterTransport(SimulatedVehicleElm()::respond)
        val result = manager.connect(transport, "AA", ConnectionProfile.DEFAULT)
        assertNull(result.driver.linkLost.value)
        transport.close() // the adapter goes away
        var waited = 0
        while (result.driver.linkLost.value == null && waited < 100) {
            kotlinx.coroutines.delay(10); waited++
        }
        assertNotNull(result.driver.linkLost.value)

        val second = manager.connect(FakeAdapterTransport(SimulatedVehicleElm()::respond), "AA", ConnectionProfile.DEFAULT)
        second.driver.close()
        kotlinx.coroutines.delay(50)
        assertNull(second.driver.linkLost.value)
    }

    @Test
    fun retryReconnectsWithinPolicyAndRethrowsWhenExhausted() = runBlocking<Unit> {
        val policy = ReconnectPolicy(listOf(1, 1, 1))
        val attempts = mutableListOf<Int>()
        val value = policy.retry(onAttempt = { a, _ -> attempts += a }) { attempt ->
            if (attempt < 2) throw com.bod3.app.obd.transport.TransportException("socket closed") else "connected"
        }
        assertEquals("connected", value)
        assertEquals(listOf(1, 2), attempts)

        val failures = mutableListOf<Int>()
        val error = runCatching {
            policy.retry(onFailure = { a, _ -> failures += a }) { throw com.bod3.app.obd.transport.TransportException("gone") }
        }.exceptionOrNull()
        assertEquals("gone", error!!.message)
        assertEquals(listOf(1, 2, 3), failures)
        assertNull(policy.delayBefore(4))
    }

    @Test
    fun candidateOrder() {
        val all = ProtocolNegotiator.candidates(ConnectionProfile.DEFAULT, null)
        assertEquals(
            listOf(null, ObdProtocol.ISO_14230_4_KWP_FAST, ObdProtocol.ISO_14230_4_KWP_SLOW, ObdProtocol.ISO_9141_2, ObdProtocol.ISO_15765_4_CAN_11_500, ObdProtocol.ISO_15765_4_CAN_29_500, ObdProtocol.ISO_15765_4_CAN_11_250, ObdProtocol.ISO_15765_4_CAN_29_250),
            all.take(8),
        )
        // Auto-detect covers every protocol the MIC3313 supports.
        assertEquals(ObdProtocol.entries.toSet(), all.filterNotNull().toSet())
        assertEquals(ObdProtocol.VT_KWP1281, all.last())
        val remembered = ProtocolNegotiator.candidates(ConnectionProfile.DEFAULT, ObdProtocol.ISO_15765_4_CAN_11_500)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, remembered.first())
        assertEquals(ObdProtocol.entries.size + 1, remembered.size) // no duplicate
        assertEquals(listOf(null), ProtocolNegotiator.candidates(ConnectionProfile.DEFAULT.copy(tryFallbacks = false), null))
    }
}
