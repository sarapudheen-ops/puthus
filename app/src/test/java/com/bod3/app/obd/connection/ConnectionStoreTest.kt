package com.bod3.app.obd.connection

import com.bod3.app.obd.protocol.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStoreTest {

    private val kv = InMemoryKeyValueStore()
    private val store = ConnectionStore(kv, clock = { 1_000L })

    @Test
    fun builtInProfilesAndActiveSelection() {
        assertEquals(listOf(ConnectionProfile.DEFAULT_ID, "kline"), store.profiles().map { it.id })
        assertEquals(ConnectionProfile.DEFAULT, store.activeProfile())
        val custom = ConnectionProfile(id = "tiguan", name = "Tiguan", forcedProtocol = ObdProtocol.ISO_15765_4_CAN_11_500, adapterTimeoutMs = 100)
        store.saveProfile(custom)
        store.setActiveProfile("tiguan")
        assertEquals(custom, ConnectionStore(kv).activeProfile()) // persisted
        store.deleteProfile("tiguan")
        assertEquals(ConnectionProfile.DEFAULT_ID, store.activeProfileId())
        assertThrows(IllegalArgumentException::class.java) { store.deleteProfile(ConnectionProfile.DEFAULT_ID) }
        assertThrows(IllegalArgumentException::class.java) { store.setActiveProfile("missing") }
    }

    @Test
    fun codecRoundTripAndValidation() {
        val profile = ConnectionProfile(
            id = "x", name = "All options", forcedProtocol = ObdProtocol.ISO_15765_4_CAN_29_250, tryFallbacks = false,
            requestTimeoutMs = 3_000, searchTimeoutMs = 15_000, adapterTimeoutMs = 96, adaptiveTiming = 0, useVtTimeout = true,
            broadcastFilter = "7E8", broadcastMask = "7F8", flowControlMode = FlowControlMode.CUSTOM_DATA, flowControlData = "300804",
            extendedAddress = "F1",
        )
        val decoded = ConnectionProfileCodec.decodeList(ConnectionProfileCodec.encodeList(listOf(profile)))
        assertEquals(listOf(profile), decoded)
        assertEquals(24, profile.adapterTimeoutUnits) // 96 ms / 4.096
        val options = profile.sessionOptions()
        assertTrue(options.extendedAddressing)
        assertTrue(options.customFlowControl)

        assertThrows(IllegalArgumentException::class.java) { ConnectionProfile(id = "b", name = " ") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionProfile(id = "b", name = "b", adaptiveTiming = 3) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionProfile(id = "b", name = "b", extendedAddress = "F") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionProfile(id = "b", name = "b", broadcastFilter = "7E8") }
        val errors = mutableListOf<String>()
        val partial = ConnectionProfileCodec.decodeList("""[{"id":"ok","name":"OK"},{"id":"bad","name":"Bad","adaptiveTiming":9},{"name":"no id"}]""") { errors += it }
        assertEquals(listOf("ok"), partial.map { it.id })
        assertEquals(2, errors.size)
    }

    @Test
    fun rememberedProtocolPerAdapter() {
        store.rememberProtocol("A", ObdProtocol.ISO_9141_2, "ISO 9141-2")
        store.rememberProtocol("B", ObdProtocol.ISO_15765_4_CAN_11_500, null)
        store.rememberProtocol("A", ObdProtocol.ISO_14230_4_KWP_FAST, "KWP")
        assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, store.rememberedProtocol("A")!!.protocol)
        assertEquals(2, store.remembered().size)
        store.forgetProtocol("A")
        assertNull(store.rememberedProtocol("A"))
    }

    @Test
    fun expertModeIsOffByDefaultAndNeedsAcknowledgement() {
        assertFalse(store.expertModeEnabled)
        assertThrows(IllegalArgumentException::class.java) { store.enableExpertMode(riskAcknowledged = false) }
        assertFalse(store.expertModeEnabled)
        store.enableExpertMode(riskAcknowledged = true)
        assertTrue(store.expertModeEnabled)
        assertEquals(1_000L, store.expertRiskAcknowledgedAtMs)
        store.disableExpertMode()
        assertFalse(store.expertModeEnabled)
    }

    @Test
    fun voltageClassificationAndHistory() = kotlinx.coroutines.runBlocking<Unit> {
        assertEquals(VoltageStatus.CRITICAL, VoltageMonitor.classify(11.2))
        assertEquals(VoltageStatus.LOW, VoltageMonitor.classify(11.9))
        assertEquals(VoltageStatus.OK, VoltageMonitor.classify(12.6))
        assertEquals(VoltageStatus.CHARGING, VoltageMonitor.classify(14.1))
        assertEquals(VoltageStatus.HIGH, VoltageMonitor.classify(15.8))
        assertEquals(VoltageStatus.UNKNOWN, VoltageMonitor.classify(null))

        val readings = ArrayDeque(listOf("12.6V", "?", "12.4V", "14.2V"))
        val exec = com.bod3.app.obd.elm.CommandExecutor { command, _ -> com.bod3.app.obd.elm.ElmResponseParser.parse(command, readings.removeFirst()) }
        var t = 0L
        val monitor = VoltageMonitor(exec, clock = { t++ }, maxSamples = 2)
        repeat(4) { monitor.sample() }
        assertEquals(listOf(12.4, 14.2), monitor.history.map { it.volts })
        assertEquals(VoltageStatus.CHARGING, monitor.status)
        assertEquals(12.4, monitor.min!!, 0.001)
    }
}
