package com.bod3.app.obd.uds

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ReadOnlyViolationException
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsDiagnosticsTest {

    private val bcm = EcuTarget.Physical("70E", "778")
    private var faults = mutableListOf(listOf(0x90, 0x12, 0x13, 0x09), listOf(0xC1, 0x00, 0x00, 0x08))
    private var clearReply: List<List<Int>> = listOf(listOf(0x54))

    private val module = SimModule('6', "70E", "778") { request ->
        when {
            request == listOf(0x19, 0x02, 0xFF) -> listOf(listOf(0x59, 0x02, 0xFF) + faults.flatten())
            request.take(2) == listOf(0x19, 0x06) && request.size == 6 ->
                faults.firstOrNull { it.take(3) == request.subList(2, 5) }
                    ?.let { listOf(listOf(0x59, 0x06) + it + listOf(0x01, 0x05, 0x02, 0x28)) } ?: listOf(listOf(0x7F, 0x19, 0x31))
            request.take(2) == listOf(0x19, 0x04) -> listOf(listOf(0x7F, 0x19, 0x12))
            request == listOf(0x14, 0xFF, 0xFF, 0xFF) -> clearReply.also { if (it.lastOrNull() == listOf(0x54)) faults.clear() }
            request.size == 3 && request[0] == 0x22 -> listOf(listOf(0x7F, 0x22, 0x31))
            else -> emptyList()
        }
    }
    private val adapter = SimulatedCanAdapter(listOf(module), '6')
    private val client = UdsClient(Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500), retryDelayMs = 1)

    @Test
    fun readClearAndVerify() = runBlocking<Unit> {
        val before = client.readDtcs(bcm) as DtcReadResult.Read
        assertEquals(listOf("B1012-13", "U0100-00"), before.records.map { it.code })

        assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500).request(bcm, listOf(0x14, 0xFF, 0xFF, 0xFF)) }
        }
        assertTrue(adapter.clears.isEmpty())

        assertEquals(UdsClearResult.Cleared, client.clearDtcs(bcm, ClearConfirmation.udsClear(bcm)))
        assertEquals(1, adapter.clears.size)
        assertEquals("70E", adapter.clears.single().header)
        assertTrue((client.readDtcs(bcm) as DtcReadResult.Read).records.isEmpty())
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun clearRefusedOrPending() = runBlocking<Unit> {
        clearReply = listOf(listOf(0x7F, 0x14, 0x22))
        val refused = client.clearDtcs(bcm, ClearConfirmation.udsClear(bcm))
        assertEquals(UdsClearResult.Negative(0x22), refused)
        assertTrue(refused.display.contains("engine off"))
        clearReply = listOf(listOf(0x7F, 0x14, 0x78))
        assertEquals(UdsClearResult.Pending, client.clearDtcs(bcm, ClearConfirmation.udsClear(bcm)))
        assertEquals(2, adapter.clears.size) // never re-sent automatically
        clearReply = emptyList()
        assertEquals(UdsClearResult.NoResponse, client.clearDtcs(bcm, ClearConfirmation.udsClear(bcm)))
    }

    @Test
    fun extendedDataAndSnapshot() = runBlocking<Unit> {
        val dtc = (client.readDtcs(bcm) as DtcReadResult.Read).records.first()
        val extended = client.readExtendedData(bcm, dtc) as DtcRecordResult.Data
        assertEquals(0x09, extended.status)
        assertEquals("01 05 02 28", extended.display)
        assertEquals(listOf(0x19, 0x06, 0x90, 0x12, 0x13, 0xFF), adapter.requests.last().payload)
        assertEquals(DtcRecordResult.Negative(0x12), client.readSnapshot(bcm, dtc))
        assertEquals("Not supported", client.readSnapshot(bcm, dtc).display)
    }

    @Test
    fun identificationLabels() {
        assertEquals("Programming date", KnownDids.label(0xF199))
        assertEquals("Spare part number", KnownDids.label(0xF187))
        assertTrue(KnownDids.identification.size >= 25)
    }
}
