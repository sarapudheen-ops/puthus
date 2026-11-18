package com.bod3.app.obd.scan

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual / data-pack ECU lists are probed directly, with their exact reply IDs. */
class ListedModulesScanTest {

    /** Outside the 700-7FF scan range, answers only 22 F190 on its own pair. */
    private val hidden = SimModule('6', "6F1", "6F9") { request ->
        if (request == listOf(0x22, 0xF1, 0x90)) listOf(listOf(0x62, 0xF1, 0x90) + "WVWZZZ1KZAW000001".map { it.code }) else emptyList()
    }

    /** 29-bit module on a vehicle whose native bus is 11-bit. */
    private val gateway29 = SimModule('7', "18DA19F1", "18DAF119") { request ->
        if (request == listOf(0x3E, 0x00)) listOf(listOf(0x7E, 0x00)) else emptyList()
    }

    private fun scan(options: ScanOptions, modules: List<SimModule> = TestVehicle.all() + hidden): Pair<SimulatedCanAdapter, List<DiscoveredModule>> {
        val adapter = SimulatedCanAdapter(modules, '6')
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        return adapter to runBlocking { ModuleScanner(session, adapter).scan(options) }
    }

    @Test
    fun rangeScanMissesHiddenModuleButListedProbeFindsIt() {
        val (_, plain) = scan(ScanOptions(includeOtherBus = false))
        assertFalse(plain.any { it.address.requestHeader == "6F1" })

        val (adapter, listed) = scan(ScanOptions(includeOtherBus = false, listedModules = listOf(ModuleAddress(Bus.CAN_11, "6F1", "6F9"))))
        val module = listed.single { it.address.requestHeader == "6F1" }
        assertEquals(DetectionMethod.MANUAL_LIST, module.detectedBy)
        assertEquals("6F9", module.address.responseHeader)
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun quickScanProbesOnlyListedModules() {
        val listed = listOf(ModuleAddress(Bus.CAN_11, "7E0", "7E8"), ModuleAddress(Bus.CAN_11, "6F1", "6F9"), ModuleAddress(Bus.CAN_11, "755", "7BF"))
        val (adapter, found) = scan(ScanOptions(includeOtherBus = false, rangeScan = false, listedModules = listed))
        assertEquals(setOf("7E0", "6F1"), found.map { it.address.requestHeader }.toSet())
        // 3 addresses × at most 3 probes, no range sweep.
        assertTrue("requests: ${adapter.requests.size}", adapter.requests.size <= 9)
        assertTrue(adapter.requests.none { it.header == "7DF" })
    }

    @Test
    fun alreadyFoundModulesAreNotProbedAgain() {
        val (adapter, found) = scan(ScanOptions(includeOtherBus = false, listedModules = listOf(ModuleAddress(Bus.CAN_11, "7E0", "7E8"))))
        assertEquals(1, found.count { it.address.requestHeader == "7E0" })
        assertEquals(DetectionMethod.OBD_BROADCAST, found.single { it.address.requestHeader == "7E0" }.detectedBy)
        // Found by the broadcast, so neither the OBD-address phase nor the listed step probes it directly.
        assertEquals(0, adapter.requests.count { it.header == "7E0" })
    }

    @Test
    fun listedModuleOnOtherBusAddsThatPassEvenWithoutOtherBusScan() {
        val (adapter, found) = scan(
            ScanOptions(includeOtherBus = false, listedModules = listOf(ModuleAddress(Bus.CAN_29, "18DA19F1", "18DAF119"))),
            modules = listOf(TestVehicle.engine, gateway29),
        )
        assertTrue(found.any { it.address.requestHeader == "18DA19F1" && it.detectedBy == DetectionMethod.MANUAL_LIST })
        // No 29-bit range sweep: only the listed address was probed on 29-bit.
        assertEquals(setOf("18DA19F1"), adapter.requests.filter { it.protocolCode == '7' }.map { it.header }.toSet())
        assertEquals('6', adapter.protocolCode) // back on the native protocol
    }
}
