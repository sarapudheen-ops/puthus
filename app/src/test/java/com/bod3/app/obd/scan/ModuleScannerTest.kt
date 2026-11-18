package com.bod3.app.obd.scan

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModuleScannerTest {

    private fun setup(modules: List<SimModule> = TestVehicle.all(), protocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500): Triple<SimulatedCanAdapter, Session, ModuleScanner> {
        val adapter = SimulatedCanAdapter(modules, protocol.dpn!!)
        val session = Session(adapter, protocol)
        return Triple(adapter, session, ModuleScanner(session, adapter))
    }

    @Test
    fun findsModulesOnBothBusesWithEveryDetectionMethod() = runBlocking<Unit> {
        val (adapter, _, scanner) = setup()
        val found = scanner.scan().associateBy { it.address.requestHeader }

        assertEquals(setOf("7E0", "7E1", "713", "70E", "18DA19F1"), found.keys)
        assertEquals(DetectionMethod.OBD_BROADCAST, found.getValue("7E0").detectedBy)
        assertEquals("7E8", found.getValue("7E0").address.responseHeader)
        assertTrue(found.getValue("7E1").obd)

        // A negative response (7F 3E 11) still counts as a module, with its real reply ID.
        val abs = found.getValue("713")
        assertEquals(DetectionMethod.TESTER_PRESENT, abs.detectedBy)
        assertTrue(abs.negativeReply)
        assertEquals("77D", abs.address.responseHeader)

        // Silent to 3E 00, found by the VIN read.
        assertEquals(DetectionMethod.VIN_READ, found.getValue("70E").detectedBy)
        assertFalse(found.getValue("70E").negativeReply)

        val gateway = found.getValue("18DA19F1")
        assertEquals(Bus.CAN_29, gateway.address.bus)
        assertEquals("18DAF119", gateway.address.responseHeader)

        assertTrue("Only read-only requests: ${adapter.violations}", adapter.violations.isEmpty())
    }

    @Test
    fun probesOnlyReadOnlyServicesAndSkipsFunctionalAndReplyIds() = runBlocking<Unit> {
        val (adapter, _, scanner) = setup()
        scanner.scan()
        val services = adapter.requests.map { it.payload.take(1) + it.payload.drop(1).take(2) }.toSet()
        assertEquals(setOf(listOf(0x01, 0x00), listOf(0x3E, 0x00), listOf(0x22, 0xF1, 0x90)), services)

        val probed11 = adapter.requests.filter { it.protocolCode == '6' }.map { it.header }
        assertEquals(1, probed11.count { it == "7DF" }) // only the OBD broadcast
        assertTrue((0x7E8..0x7EF).none { "%03X".format(it) in probed11 })
        // Each 11-bit address is probed with 3E 00 at most once.
        val testerPresent = adapter.requests.filter { it.protocolCode == '6' && it.payload == listOf(0x3E, 0x00) }.map { it.header }
        assertEquals(testerPresent.size, testerPresent.toSet().size)
        // 11-bit: 7E0-7E7 already found by broadcast (7E0, 7E1) are not probed again.
        assertFalse(testerPresent.contains("7E0"))
        assertTrue(testerPresent.contains("7E2"))
        assertTrue(testerPresent.contains("7FF"))
        // The 29-bit tester address F1 is never probed.
        assertFalse(adapter.requests.any { it.header == "18DAF1F1" })
    }

    @Test
    fun usesShortTimeoutAndRestoresAdapterAfterwards() = runBlocking<Unit> {
        val (adapter, session, scanner) = setup()
        scanner.scan(ScanOptions(adapterTimeoutMs = 100))
        assertTrue(adapter.commands.contains("ATST19")) // 100 ms / 4.096 ms = 25 = 0x19
        assertEquals("32", adapter.adapterTimeout)
        assertEquals('6', adapter.protocolCode)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, session.protocol)
    }

    @Test
    fun scansOtherBusOnlyWhenAsked() = runBlocking<Unit> {
        val (adapter, _, scanner) = setup()
        val found = scanner.scan(ScanOptions(includeOtherBus = false))
        assertTrue(found.none { it.address.bus == Bus.CAN_29 })
        assertTrue(adapter.requests.none { it.protocolCode == '7' })
    }

    @Test
    fun scanStartsOnTheVehiclesOwnBus() = runBlocking<Unit> {
        val engine29 = SimModule('7', "18DA10F1", "18DAF110", obd = true) { request ->
            if (request == listOf(0x01, 0x00)) listOf(listOf(0x41, 0x00, 0x80, 0, 0, 1)) else emptyList()
        }
        val (adapter, _, scanner) = setup(listOf(engine29), ObdProtocol.ISO_15765_4_CAN_29_500)
        val found = scanner.scan()
        assertEquals('7', adapter.requests.first().protocolCode)
        assertEquals(listOf("18DA10F1"), found.map { it.address.requestHeader })
        assertEquals(DetectionMethod.OBD_BROADCAST, found.single().detectedBy)
        assertEquals('7', adapter.protocolCode)
    }

    @Test
    fun reportsProgressUpToDone() = runBlocking<Unit> {
        val (_, _, scanner) = setup()
        val progress = mutableListOf<ScanProgress>()
        val modules = mutableListOf<DiscoveredModule>()
        scanner.scan(onProgress = { progress += it }, onModuleFound = { modules += it })

        assertEquals(ScanPhase.PREPARING, progress.first().phase)
        val last = progress.last()
        assertEquals(ScanPhase.DONE, last.phase)
        assertEquals(last.total, last.completed)
        assertEquals(5, last.modulesFound)
        assertEquals(5, modules.size)
        // 11-bit: broadcast + 8 OBD + 239 UDS (256 minus 7DF and 7E0-7EF); 29-bit: broadcast + 8 OBD + 247 UDS.
        assertEquals(1 + 8 + 239 + 1 + 8 + 247, last.total)
        val completed = progress.map { it.completed }
        assertEquals(completed.sorted(), completed)
        assertTrue(progress.any { it.phase == ScanPhase.UDS_ADDRESSES && it.bus == Bus.CAN_29 })
    }

    @Test
    fun cancellationStopsQuicklyAndRestoresAdapter() = runBlocking<Unit> {
        val (adapter, session, scanner) = setup()
        var job: Job? = null
        adapter.onRequest = { if (adapter.requests.size == 40) job?.cancel() }
        val found = mutableListOf<DiscoveredModule>()
        coroutineScope {
            job = launch {
                scanner.scan(onModuleFound = { found += it })
                fail("Scan should have been cancelled")
            }
        }
        assertTrue(job!!.isCancelled)
        assertTrue("stopped after ${adapter.requests.size} requests", adapter.requests.size <= 42)
        assertEquals(setOf("7E0", "7E1"), found.map { it.address.requestHeader }.toSet())
        assertEquals("32", adapter.adapterTimeout)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, session.protocol)
    }

    @Test
    fun abortsWhenAdapterKeepsFailing() = runBlocking<Unit> {
        val (adapter, _, scanner) = setup()
        adapter.failWith = "CAN ERROR"
        val error = assertThrows(ScanAbortedException::class.java) { runBlocking { scanner.scan() } }
        assertNotNull(error.message)
        assertTrue(adapter.requests.size <= ScanOptions().maxConsecutiveFailures)
    }

    @Test
    fun refusesNonCanVehicles() = runBlocking<Unit> {
        val adapter = SimulatedCanAdapter(emptyList(), '3')
        val scanner = ModuleScanner(Session(adapter, ObdProtocol.ISO_9141_2), adapter)
        assertThrows(ScanAbortedException::class.java) { runBlocking { scanner.scan() } }
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun addressRanges() {
        val uds11 = ModuleScanner.udsRequestHeaders(Bus.CAN_11)
        assertEquals("700", uds11.first())
        assertEquals("7FF", uds11.last())
        assertFalse("7DF" in uds11)
        assertFalse("7E0" in uds11)
        val uds29 = ModuleScanner.udsRequestHeaders(Bus.CAN_29)
        assertEquals("18DA00F1", uds29.first())
        assertEquals("18DAFFF1", uds29.last())
        assertFalse("18DAF1F1" in uds29)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_29_250, ModuleScanner.counterpart(ObdProtocol.ISO_15765_4_CAN_11_250))
    }
}
