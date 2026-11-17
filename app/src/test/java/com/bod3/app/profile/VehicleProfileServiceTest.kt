package com.bod3.app.profile

import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.scan.Bus
import com.bod3.app.obd.scan.DetectionMethod
import com.bod3.app.obd.scan.DiscoveredModule
import com.bod3.app.obd.scan.ModuleAddress
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileServiceTest {

    private val vin = "1D4GP24R45B123456"
    private var now = 1_000L
    private val repository = InMemoryProfileRepository()
    private var online = true
    private var lookups = 0
    private val describer = VinDescriber { v ->
        lookups++
        if (online) {
            VehicleDescription("DODGE", "Durango", 2005, DescriptionSource.VPIC, mapOf("Trim" to "SLT"))
        } else {
            OfflineVinDecoder.decode(v, 2026, "offline")
        }
    }
    private val service = VehicleProfileService(repository, describer, clock = { now })

    private val engine = ModuleReport(
        module = DiscoveredModule(ModuleAddress(Bus.CAN_11, "7E0", "7E8"), DetectionMethod.OBD_BROADCAST, obd = true, negativeReply = false, firstReply = "7E8064100BE3EA813"),
        name = "Engine (ECM)",
        identifiers = listOf(
            DidReading(0xF187, DidResult.Value("56044691AC".map { it.code })),
            DidReading(0xF189, DidResult.Negative(0x31)),
            DidReading(0xF191, DidResult.NoResponse),
            DidReading(0x0600, DidResult.Value(listOf(0x01, 0xA4))),
            DidReading(0xF19E, DidResult.Failed("CAN ERROR")),
        ),
        dtcs = DtcReadResult.Read("UDS 19 02", listOf(DtcRecord("P0420-13", 0x2F, listOf(0x04, 0x20, 0x13, 0x2F))), 0xFF),
        obdEcuName = "ECM",
        calibrationId = "CAL123",
    )
    private val abs = ModuleReport(
        module = DiscoveredModule(ModuleAddress(Bus.CAN_29, "18DA28F1", "18DAF128"), DetectionMethod.TESTER_PRESENT, obd = false, negativeReply = true, firstReply = null),
        name = null,
        identifiers = emptyList(),
        dtcs = DtcReadResult.Negative(0x11),
    )

    @Test
    fun codecRoundTripKeepsEverything() {
        for (report in listOf(engine, abs)) {
            val stored = ModuleCodec.encode(vin, report, 5L)
            assertEquals(report, ModuleCodec.decode(stored))
        }
        val stored = ModuleCodec.encode(vin, engine, 5L)
        assertEquals("56044691AC", stored.partNumber)
        assertEquals("CAL123", stored.softwareVersion) // F189 unsupported: falls back to the calibration ID
        assertNull(stored.hardwareVersion)
        assertEquals("01 A4", stored.coding)
        assertEquals(1, stored.dtcCount)
        assertNull(ModuleCodec.encode(vin, abs, 5L).dtcCount)
    }

    @Test
    fun firstConnectionCreatesAndDescribesProfile() = runBlocking<Unit> {
        val result = service.onConnected(vin, "ISO 15765-4 CAN (11 bit, 500 kbaud)")
        assertTrue(result.isNew)
        assertEquals("2005 Dodge Durango", result.profile.title)
        assertEquals("SLT", result.profile.description!!.extra["Trim"])
        assertTrue(result.profile.modules.isEmpty())
        assertNull(result.profile.lastScanAtMs)
    }

    @Test
    fun reconnectingLoadsSavedProfileWithModulesAndLog() = runBlocking<Unit> {
        service.onConnected(vin, "CAN 11")
        now = 2_000L
        val log = listOf(RawExchange(1, 1_500L, "7E0", "3E00", "7E8027E00", 12L))
        service.saveScan(vin, "CAN 11", listOf(engine, abs), log)

        now = 9_000L
        val again = service.onConnected(vin, "CAN 11")
        assertFalse(again.isNew)
        assertEquals(listOf(engine, abs), again.profile.modules)
        assertEquals(2_000L, again.profile.lastScanAtMs)
        assertEquals(1_000L, again.profile.createdAtMs)
        assertEquals(9_000L, again.profile.updatedAtMs)
        assertEquals(1, again.profile.totalFaults)
        assertEquals(log, service.rawLog(vin))
        assertEquals(1, lookups) // already decoded by vPIC: no second lookup
    }

    @Test
    fun offlineDescriptionIsUpgradedWhenOnlineLater() = runBlocking<Unit> {
        online = false
        val first = service.onConnected(vin, null).profile
        assertEquals(DescriptionSource.OFFLINE, first.description!!.source)
        assertEquals("2005 Dodge", first.title)
        assertEquals("offline", first.description!!.note)

        online = true
        val second = service.onConnected(vin, null).profile
        assertEquals(DescriptionSource.VPIC, second.description!!.source)
        assertEquals("2005 Dodge Durango", second.title)
    }

    @Test
    fun onlineDescriptionIsNotReplacedByOfflineOne() = runBlocking<Unit> {
        repository.saveProfile(
            StoredProfile(vin, "DODGE", "Durango", 2005, DescriptionSource.OFFLINE.name, null, null, 1L, 1L, null),
        )
        online = false
        val profile = service.onConnected(vin, null).profile
        assertEquals("Durango", profile.description!!.model)
    }

    @Test
    fun newScanReplacesOldModules() = runBlocking<Unit> {
        service.saveScan(vin, null, listOf(engine, abs), emptyList())
        service.saveScan(vin, null, listOf(abs), emptyList())
        assertEquals(listOf(abs), service.load(vin)!!.modules)
        assertEquals(1, service.allProfiles().size)
    }
}

class VinRulesTest {
    @Test
    fun validation() {
        assertTrue(VinRules.isValid("1D4GP24R45B123456"))
        assertFalse(VinRules.isValid("1D4GP24R45B12345"))
        assertFalse(VinRules.isValid("1D4GP24R45BI23456")) // I is not allowed
        assertFalse(VinRules.isValid(null))
    }

    @Test
    fun vinFromModulesUsesMostCommonValidValue() {
        fun report(header: String, vin: String?) = ModuleReport(
            DiscoveredModule(ModuleAddress(Bus.CAN_11, header, header), DetectionMethod.VIN_READ, false, false, null),
            null,
            listOfNotNull(vin?.let { DidReading(0xF190, DidResult.Value(it.map { c -> c.code })) }),
            DtcReadResult.NoResponse,
        )
        val reports = listOf(report("700", "WVWZZZ1KZAW000001"), report("710", "WVWZZZ1KZAW000001"), report("720", "WVWZZZ1KZAW999999"), report("730", "garbage"), report("740", null))
        assertEquals("WVWZZZ1KZAW000001", VinRules.fromModules(reports))
        assertNull(VinRules.fromModules(listOf(report("730", "garbage"))))
    }
}
