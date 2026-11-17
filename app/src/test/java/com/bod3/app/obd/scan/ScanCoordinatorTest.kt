package com.bod3.app.obd.scan

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.UdsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoordinatorTest {

    @Test
    fun scansThenReadsEveryModuleOnItsOwnBus() = runBlocking<Unit> {
        val adapter = SimulatedCanAdapter(TestVehicle.all(), '6')
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val coordinator = ScanCoordinator(session, adapter, reader = ModuleReader(session, UdsClient(session, retryDelayMs = 1)))
        val read = mutableListOf<ModuleReport>()
        val progress = mutableListOf<ScanProgress>()
        fun record(p: ScanProgress) {
            progress += p
        }

        val reports = coordinator.run(
            listener = object : ScanListener {
                override fun onProgress(progress: ScanProgress) = record(progress)
                override fun onModuleRead(report: ModuleReport) {
                    read += report
                }
            },
        )

        assertEquals(5, reports.size)
        assertEquals(reports, read)
        // Native bus first, then the 29-bit module.
        assertEquals(listOf("70E", "713", "7E0", "7E1", "18DA19F1"), reports.map { it.address.requestHeader })
        val gateway = reports.last()
        assertEquals(DtcReadResult.Read("UDS 19 02", emptyList(), 0xFF), gateway.dtcs)
        assertEquals("BCM", reports.first().name)
        assertTrue(progress.any { it.phase == ScanPhase.READING_MODULES && it.current?.contains("22 F187") == true })
        assertEquals(ScanPhase.DONE, progress.last().phase)
        assertEquals('6', adapter.protocolCode)
        // The gateway was read on 29-bit CAN.
        assertTrue(adapter.requests.any { it.protocolCode == '7' && it.header == "18DA19F1" && it.payload == listOf(0x19, 0x02, 0xFF) })
        assertTrue(adapter.violations.isEmpty())
    }
}
