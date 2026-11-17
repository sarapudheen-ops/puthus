package com.bod3.app.obd.dtc

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimulatedVehicleElm
import com.bod3.app.obd.testing.executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDiagnosticsTest {

    private fun session(elm: SimulatedVehicleElm, protocol: ObdProtocol) = Session(elm.executor(), protocol)

    @Test
    fun freezeFrameOnCanAndKLine() = runBlocking<Unit> {
        for ((code, protocol) in listOf('6' to ObdProtocol.ISO_15765_4_CAN_11_500, '3' to ObdProtocol.ISO_9141_2)) {
            val frames = FreezeFrameReader(session(SimulatedVehicleElm(vehicleProtocol = code), protocol)).read()
            val frame = frames.single()
            assertEquals("P0420", frame.dtc)
            val values = frame.values.associate { it.definition.pid to it.formatted }
            assertEquals(mapOf(0x04 to "50", 0x05 to "83", 0x0C to "750", 0x0D to "0"), values)
        }
        assertTrue(FreezeFrameReader(session(SimulatedVehicleElm(freezeFrameDtc = null), ObdProtocol.ISO_15765_4_CAN_11_500)).read().isEmpty())
    }

    @Test
    fun readinessDecoding() = runBlocking<Unit> {
        val readiness = ReadinessDecoder.read(session(SimulatedVehicleElm(), ObdProtocol.ISO_15765_4_CAN_11_500)).single()
        assertTrue(readiness.milOn)
        assertEquals(4, readiness.dtcCount)
        assertFalse(readiness.compressionIgnition)
        assertEquals(listOf("Catalyst", "Oxygen sensor"), readiness.incomplete.map { it.name })
        assertEquals(
            listOf("Misfire", "Fuel system", "Comprehensive components", "Catalyst", "Evaporative system", "Oxygen sensor", "Oxygen sensor heater"),
            readiness.supported.map { it.name },
        )
        val diesel = ReadinessDecoder.decode("7E8", listOf(0x00, 0x0F, 0xC8, 0x40))!!
        assertTrue(diesel.compressionIgnition)
        assertEquals(listOf("PM filter"), diesel.incomplete.map { it.name })
        assertTrue(diesel.supported.any { it.name == "Boost pressure" })
    }

    @Test
    fun confirmedClearOnCanAndKLine() = runBlocking<Unit> {
        for ((code, protocol) in listOf('6' to ObdProtocol.ISO_15765_4_CAN_11_500, '5' to ObdProtocol.ISO_14230_4_KWP_FAST)) {
            val elm = SimulatedVehicleElm(vehicleProtocol = code)
            val reader = ObdDtcReader(session(elm, protocol))
            assertEquals(4, reader.read(DtcKind.STORED).codes.size)
            val result = reader.clear(ClearConfirmation.obdClear())
            assertEquals(1, result.cleared.size)
            assertTrue(reader.read(DtcKind.STORED).codes.isEmpty())
            assertEquals(listOf("P0420"), reader.read(DtcKind.PERMANENT).codes) // permanent codes stay
        }
    }

    @Test
    fun clearRefusedWhileEngineRuns() = runBlocking<Unit> {
        val elm = SimulatedVehicleElm(engineRunning = true)
        val reader = ObdDtcReader(session(elm, ObdProtocol.ISO_15765_4_CAN_11_500))
        val result = reader.clear(ClearConfirmation.obdClear())
        assertTrue(result.cleared.isEmpty())
        assertEquals(mapOf("7E8" to 0x22), result.refused)
        assertEquals(4, reader.read(DtcKind.STORED).codes.size)
    }
}
