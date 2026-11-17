package com.bod3.app.obd.scan

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.UdsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleReaderTest {

    private val adapter = SimulatedCanAdapter(TestVehicle.all(), '6')
    private val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
    private val reader = ModuleReader(session, UdsClient(session, retryDelayMs = 1))

    private fun module(request: String, response: String, obd: Boolean = false) = DiscoveredModule(
        ModuleAddress(Bus.CAN_11, request, response), DetectionMethod.TESTER_PRESENT, obd, negativeReply = false, firstReply = null,
    )

    @Test
    fun readsIdentificationCodingAndDtcs() = runBlocking<Unit> {
        val report = reader.read(module("7E0", "7E8", obd = true))

        assertEquals("R4 2.0L TDI", report.name)
        assertEquals("03L906018JJ", report.identifierText(KnownDids.SPARE_PART_NUMBER.id))
        assertEquals("1234", report.identifierText(KnownDids.SOFTWARE_VERSION.id))
        assertEquals("03L906018", report.identifierText(KnownDids.HARDWARE_NUMBER.id)) // padding trimmed
        assertEquals(TestVehicle.VIN, report.identifierText(KnownDids.VIN.id)) // multi-frame reply
        assertEquals("01 02 03 A4", report.identifierText(KnownDids.VW_CODING.id)) // binary shown as hex

        val unsupported = report.identifier(KnownDids.ODX_FILE.id)!!
        assertEquals(DidResult.Negative(0x31), unsupported.result)
        assertEquals("Not supported", unsupported.display)

        val dtcs = report.dtcs as DtcReadResult.Read
        assertEquals("UDS 19 02", dtcs.source)
        assertEquals(listOf("P0420-13", "U0100-00"), dtcs.records.map { it.code })
        assertTrue(dtcs.records[0].confirmed)
        assertTrue(dtcs.records[0].testFailed)
        assertTrue(!dtcs.records[0].warningLamp)
        assertEquals(2, report.dtcs.faultCount)
        assertEquals("ECM-EngineControl", report.obdEcuName)
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun obdOnlyModuleFallsBackToService03AndShowsNotSupported() = runBlocking<Unit> {
        val report = reader.read(module("7E1", "7E9", obd = true))
        assertTrue(report.identifiers.all { it.result == DidResult.Negative(0x11) })
        assertTrue(report.identifiers.all { it.display == "Not supported" })
        val dtcs = report.dtcs as DtcReadResult.Read
        assertEquals("OBD 03", dtcs.source)
        assertEquals(listOf("P0700"), dtcs.records.map { it.code })
        assertEquals("Transmission (TCM)", report.name)
    }

    @Test
    fun responsePendingIsRetriedAndOtherNegativesAreReported() = runBlocking<Unit> {
        val report = reader.read(module("713", "77D"))
        assertEquals("1K0907379", report.identifierText(KnownDids.SPARE_PART_NUMBER.id))
        assertEquals(2, adapter.requests.count { it.payload == listOf(0x22, 0xF1, 0x87) })
        assertEquals(DtcReadResult.Negative(0x22), report.dtcs)
        assertTrue(report.dtcs.display.startsWith("Not available: NRC 0x22"))
        assertNull(report.dtcs.faultCount)
        assertNull(report.name) // no system name, unknown address
        assertEquals("Module 713", report.displayName)
    }

    @Test
    fun nonStandardPairGetsFlowControlOnRequestId() = runBlocking<Unit> {
        reader.read(module("70E", "778"))
        assertTrue(adapter.commands.contains("ATFCSH70E"))
        assertEquals("1", adapter.flowControlMode)
        assertTrue(adapter.commands.contains("ATCRA778"))
    }

    @Test
    fun silentModuleGivesNoResponseValues() = runBlocking<Unit> {
        val report = reader.read(module("750", "7BA"))
        assertTrue(report.identifiers.all { it.result == DidResult.NoResponse })
        assertEquals(DtcReadResult.NoResponse, report.dtcs)
        assertEquals("Not supported (no response)", report.dtcs.display)
    }
}
