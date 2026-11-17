package com.bod3.app.obd.connection

import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.dtc.DtcKind
import com.bod3.app.obd.dtc.ObdDtcReader
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.FakeAdapterTransport
import com.bod3.app.obd.testing.SimulatedVehicleElm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Basic OBD over K-line (ISO 9141-2, KWP2000) end to end through the driver and session. */
class KLineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun connected(protocol: Char): Pair<Session, ConnectionResult> {
        val manager = ConnectionManager(scope, ConnectionStore(InMemoryKeyValueStore()), Dispatchers.Default)
        val result = manager.connect(FakeAdapterTransport(SimulatedVehicleElm(vehicleProtocol = protocol, autoSearchWorks = false)::respond), "K", ConnectionProfile.DEFAULT)
        return Session(result.driver, result.negotiation.protocol!!.protocol) to result
    }

    @Test
    fun iso9141LiveDataVinAndDtcs() = runBlocking<Unit> {
        val (session, result) = connected('3')
        assertEquals(ObdProtocol.ISO_9141_2, session.protocol)
        val reader = VehicleReader(session, result.driver)
        assertEquals("WVGZZZ5NZJW000001", reader.readVin())
        val rpm = reader.readPid(Pids.byPid(0x0C)!!)!!
        assertEquals(1726.0, rpm.value, 0.01)
        assertEquals("10", rpm.ecu.takeLast(2)) // K-line header 48 6B 10

        val dtcs = ObdDtcReader(session)
        assertEquals(listOf("P0420", "P0171", "P0300", "U0100"), dtcs.read(DtcKind.STORED).codes)
        val pending = dtcs.read(DtcKind.PENDING)
        assertTrue(pending.codes.isEmpty())
        assertEquals(listOf("P0420"), dtcs.read(DtcKind.PERMANENT).codes)
        result.driver.close()
    }

    @Test
    fun kwp2000DtcsAndPhysicalAddressingRefused() = runBlocking<Unit> {
        val (session, result) = connected('5')
        assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, session.protocol)
        assertEquals(listOf("P0420", "P0171", "P0300", "U0100"), ObdDtcReader(session).read(DtcKind.STORED).codes)
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { session.request(EcuTarget.Physical("7E0", "7E8"), 0x01, 0x00) }
        }
        result.driver.close()
    }

    @Test
    fun canDtcsWithCountByte() = runBlocking<Unit> {
        val (session, result) = connected('6')
        val report = ObdDtcReader(session).read(DtcKind.STORED)
        assertEquals(listOf("P0420", "P0171", "P0300", "U0100"), report.codes) // multi-frame on CAN (10 bytes)
        assertEquals("7E8", report.ecus.single().ecu)
        result.driver.close()
    }
}
