package com.bod3.app.obd

import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ProtocolDetectionException
import com.bod3.app.obd.session.ProtocolDetector
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.ScriptedExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleReaderTest {

    private fun reader(exec: ScriptedExecutor, protocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500) =
        VehicleReader(Session(exec, protocol), exec)

    @Test
    fun followsTheSupportedPidChainPerEcu() = runBlocking<Unit> {
        val exec = ScriptedExecutor(
            mapOf(
                // 7E8 supports 0x20 (next range); 7E9 does not.
                "0100" to "7E8064100BE3EA813\r7E906410098180000",
                "0120" to "7E806412080000001",
                "0140" to "7E806414040000000",
            ),
        )
        val supported = reader(exec).supportedPids()

        assertEquals(listOf("0100", "0120", "0140"), exec.sent)
        assertEquals(setOf("7E8", "7E9"), supported.keys)
        val engine = supported.getValue("7E8")
        assertEquals(true, 0x0C in engine)
        assertEquals(true, 0x21 in engine)
        assertEquals(true, 0x40 in engine)
        assertEquals(true, 0x42 in engine)
        assertEquals(setOf(0x01, 0x04, 0x05, 0x0C, 0x0D), supported.getValue("7E9"))
    }

    @Test
    fun readsVinAndVoltage() = runBlocking<Unit> {
        val exec = ScriptedExecutor(
            mapOf(
                "0902" to "7E81014490201314434\r7E82147503030523535\r7E82242313233343536",
                "ATRV" to "12.4V",
            ),
        )
        val reader = reader(exec)
        assertEquals("1D4GP00R55B123456", reader.readVin())
        assertEquals(12.4, reader.readBatteryVoltage()!!, 0.001)
    }

    @Test
    fun readPidPrefersRequestedEcu() = runBlocking<Unit> {
        val exec = ScriptedExecutor(mapOf("0105" to "7E90341057B\r7E803410564"))
        val reading = reader(exec).readPid(Pids.COOLANT_TEMP, EcuTarget.Broadcast, preferredEcu = "7E8")!!
        assertEquals("7E8", reading.ecu)
        assertEquals(60.0, reading.value, 0.001)
        assertEquals("60", reading.formatted)
    }

    @Test
    fun readPidReturnsNullOnNoData() = runBlocking<Unit> {
        val exec = ScriptedExecutor(mapOf("015C" to "NO DATA"))
        assertNull(reader(exec).readPid(Pids.OIL_TEMP))
    }

    @Test
    fun protocolDetection() = runBlocking<Unit> {
        val exec = ScriptedExecutor(
            mapOf(
                "0100" to "SEARCHING...\r7E8064100BE3EA813",
                "ATDPN" to "A6",
                "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            ),
        )
        val info = ProtocolDetector.detect(exec)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, info.protocol)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", info.description)
    }

    @Test
    fun protocolDetectionFailsWhenIgnitionIsOff() {
        val exec = ScriptedExecutor(mapOf("0100" to "SEARCHING...\rUNABLE TO CONNECT"))
        assertThrows(ProtocolDetectionException::class.java) { runBlocking { ProtocolDetector.detect(exec) } }
        assertEquals(listOf("0100"), exec.sent)
    }
}
