package com.bod3.app.diagnostics

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.live.LiveHistory
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecordResult
import com.bod3.app.obd.uds.UdsClearResult
import com.bod3.app.obd.uds.UdsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuWorkbenchTest {

    private var faults = listOf(0x90, 0x12, 0x13, 0x09)
    private val bcm = SimModule('6', "70E", "778", respond = SimulatedCanAdapter.didResponder(
        values = mapOf(0xF187 to "5QA937084B ", 0xF190 to "WVGZZZ5NZJW000001"),
        other = { request ->
            when {
                request == listOf(0x19, 0x02, 0xFF) -> listOf(listOf(0x59, 0x02, 0xFF) + faults)
                request.take(2) == listOf(0x19, 0x06) -> listOf(listOf(0x59, 0x06) + faults + listOf(0x01, 0x07))
                request.take(2) == listOf(0x19, 0x04) -> listOf(listOf(0x59, 0x04) + faults + listOf(0x01, 0x01, 0xF4, 0x0C, 0x0B, 0xB8))
                request == listOf(0x14, 0xFF, 0xFF, 0xFF) -> listOf(listOf(0x54)).also { faults = emptyList() }
                else -> emptyList()
            }
        },
    ))
    private val adapter = SimulatedCanAdapter(listOf(bcm), '6')
    private val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
    private val workbench = EcuWorkbench(session, UdsClient(session, retryDelayMs = 1))
    private val ecu = EcuDefinition("09", "Central electronics", "70E", "778")

    @Test
    fun identificationRecordsAndClear() = runBlocking<Unit> {
        val seen = mutableListOf<Int>()
        val ids = workbench.readIdentification(ecu) { seen += it.did }
        assertEquals(seen, ids.map { it.did })
        assertEquals("5QA937084B", ids.first { it.did == 0xF187 }.display.trim())
        assertTrue(ids.first { it.did == 0xF18C }.result is DidResult.Negative)

        val dtc = (workbench.readDtcs(ecu) as DtcReadResult.Read).records.single()
        assertEquals("B1012-13", dtc.code)
        val records = workbench.readRecords(ecu, dtc)
        assertEquals("01 07", (records.extended as DtcRecordResult.Data).display)
        assertEquals(listOf(0x01, 0x01, 0xF4, 0x0C, 0x0B, 0xB8), (records.snapshot as DtcRecordResult.Data).records)

        assertEquals(UdsClearResult.Cleared, workbench.clear(ecu, ClearConfirmation.udsClear(ecu.target)))
        assertTrue((workbench.readDtcs(ecu) as DtcReadResult.Read).records.isEmpty())
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun absentModuleCostsOneRequestAndObdEcusCannotBeClearedHere() = runBlocking<Unit> {
        val absent = EcuDefinition("44", "Steering assist", "712", "77C")
        val ids = workbench.readIdentification(absent)
        assertEquals(1, ids.size)
        assertEquals(DidResult.NoResponse, ids.single().result)
        val obd = ecu.copy(protocol = EcuProtocol.OBD)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { workbench.clear(obd, ClearConfirmation.udsClear(obd.target)) } }
        assertTrue(adapter.clears.isEmpty())
    }

    @Test
    fun kwpIsNeverMisroutedThroughUdsServicesOrWritePermissions() = runBlocking<Unit> {
        val kwp = ecu.copy(protocol = EcuProtocol.KWP)
        assertTrue(workbench.readIdentification(kwp).isEmpty())
        val result = workbench.readDtcs(kwp)
        assertTrue(result is DtcReadResult.Failed)
        assertTrue((result as DtcReadResult.Failed).message.contains("no UDS request"))
        assertTrue(adapter.requests.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { workbench.clear(kwp, ClearConfirmation.udsClear(kwp.target)) } }
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun historyKeepsTheNewestSamples() {
        val history = LiveHistory(capacity = 3)
        (1..5).forEach { history.add("010C", it.toDouble()) }
        assertEquals(listOf(3.0, 4.0, 5.0), history.snapshot()["010C"])
    }
}
