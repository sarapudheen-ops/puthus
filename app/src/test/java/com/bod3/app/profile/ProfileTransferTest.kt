package com.bod3.app.profile

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.connection.InMemoryKeyValueStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTransferTest {

    private val vin = "WVGZZZ5NZJW000001"
    private val describer = VinDescriber { VehicleDescription("VOLKSWAGEN", "Tiguan", 2018, DescriptionSource.VPIC, mapOf("Trim" to "Highline")) }
    private val extrasStore = ProfileExtrasStore(InMemoryKeyValueStore())
    private val repository = InMemoryProfileRepository()
    private val service = VehicleProfileService(repository, describer, clock = { 5_000L }, extras = extrasStore)

    private val bcm = ModuleReport(
        module = DiscoveredModule(ModuleAddress(Bus.CAN_11, "70E", "778"), DetectionMethod.VIN_READ, obd = false, negativeReply = false, firstReply = "77810146..."),
        name = "BCM",
        identifiers = listOf(DidReading(0xF187, DidResult.Value("5Q0937084".map { it.code })), DidReading(0x0600, DidResult.Value(listOf(0x01, 0x02, 0xA4)))),
        dtcs = DtcReadResult.Read("UDS 19 02", listOf(DtcRecord("B1234-11", 0x09, listOf(0x92, 0x34, 0x11, 0x09))), 0xFF),
    )
    private val log = listOf(RawExchange(1, 4_000L, "70E", "22F187", "77810B62F187...", 40), RawExchange(2, 4_050L, "70E", "190AFF", "", 900, "NO DATA"))
    private val extras = ProfileExtras(
        manualEcus = listOf(EcuDefinition("5F", "Infotainment", "773", "7DD", EcuProtocol.UDS, "not found by scan")),
        moduleNames = mapOf("70E" to "Body control"),
        vehicleKey = "Volkswagen|Tiguan (AD1)",
        notes = "Highline, LED headlights",
    )

    private suspend fun seed() {
        service.onConnected(vin, "ISO 15765-4 (CAN 11/500)")
        service.saveScan(vin, "ISO 15765-4 (CAN 11/500)", listOf(bcm), log)
        extrasStore.save(vin, extras)
    }

    @Test
    fun exportImportRoundTripRestoresEverything() = runBlocking<Unit> {
        seed()
        val json = ProfileTransferCodec.encode(service.export(vin)!!, 9_000L)
        assertTrue(json.contains("\"format\":\"bod3-vehicle-profile\""))

        // Import into a fresh install.
        val otherExtras = ProfileExtrasStore(InMemoryKeyValueStore())
        val other = VehicleProfileService(InMemoryProfileRepository(), describer, extras = otherExtras)
        val decoded = ProfileTransferCodec.decode(json)
        assertTrue(decoded.errors.toString(), decoded.isValid)
        val imported = other.import(decoded.bundle!!, overwrite = false)

        assertEquals(vin, imported.vin)
        assertEquals("Tiguan", imported.description!!.model)
        assertEquals("Highline", imported.description!!.extra["Trim"])
        val module = imported.modules.single()
        assertEquals(bcm.identifiers, module.identifiers)
        assertEquals(bcm.dtcs, module.dtcs)
        assertEquals(bcm.module, module.module)
        assertEquals(log, other.rawLog(vin))
        assertEquals(extras, otherExtras.get(vin))
    }

    @Test
    fun exportWithoutRawLogAndUnknownVin() = runBlocking<Unit> {
        seed()
        assertTrue(service.export(vin, includeRawLog = false)!!.rawLog.isEmpty())
        assertNull(service.export("WVGZZZ5NZJW999999"))
    }

    @Test
    fun importRefusesToOverwriteUnlessAsked() = runBlocking<Unit> {
        seed()
        val bundle = service.export(vin)!!
        val changed = bundle.copy(extras = bundle.extras.withName("70E", "Renamed"))
        assertThrows(VehicleProfileService.ProfileExistsException::class.java) { runBlocking { service.import(changed, overwrite = false) } }
        assertEquals("Body control", extrasStore.get(vin).moduleNames["70E"])
        service.import(changed, overwrite = true)
        assertEquals("Renamed", extrasStore.get(vin).moduleNames["70E"])
    }

    @Test
    fun deleteRemovesProfileAndExtras() = runBlocking<Unit> {
        seed()
        service.delete(vin)
        assertNull(service.load(vin))
        assertTrue(extrasStore.get(vin).isEmpty)
        assertTrue(service.rawLog(vin).isEmpty())
    }

    @Test
    fun decodeReportsEveryProblemWithItsPath() {
        assertEquals(listOf("The file must contain a JSON object"), ProfileTransferCodec.decode("[]").errors)
        assertTrue(ProfileTransferCodec.decode("{oops").errors.single().startsWith("Not valid JSON"))
        assertTrue(ProfileTransferCodec.decode("""{"format":"bod3-datapack","id":"x"}""").errors.single().contains("data pack"))

        val bad = ProfileTransferCodec.decode(
            """{"format":"bod3-vehicle-profile","formatVersion":7,
               "profile":{"vin":"WVGZZZ5NZJW00000I","createdAtMs":1.5,"descriptionSource":"MAGIC"},
               "modules":[{"bus":"CAN_11","requestHeader":"70E","responseHeader":"18DAF119"},
                          {"bus":"LIN","requestHeader":"70E","responseHeader":"778","identifiersJson":"{}","detectedBy":"GUESS"},
                          "x"],
               "extras":{"manualEcus":[{"name":"","requestId":"773","responseId":"773"}],"moduleNames":{"70E":""}},
               "rawLog":[{"sequence":1}]}""",
        )
        assertNull(bad.bundle)
        val e = bad.errors
        listOf(
            "formatVersion: 7 is newer",
            "profile.vin:",
            "profile.createdAtMs: must be a whole number",
            "profile.descriptionSource: unknown value \"MAGIC\"",
            "modules[0].responseHeader: must be a 3-digit hex CAN ID",
            "modules[1].bus: must be CAN_11 or CAN_29",
            "modules[1].detectedBy: unknown value \"GUESS\"",
            "modules[1].identifiersJson: must be a JSON list",
            "modules[2]: must be an object",
            "extras.manualEcus[0].name: must not be empty",
            "extras.manualEcus[0].responseId: must differ from requestId",
            "extras.moduleNames.70E: name must not be empty",
            "rawLog[0].command: missing",
        ).forEach { expected -> assertTrue("missing \"$expected\" in $e", e.any { it.startsWith(expected) || it.contains(expected) }) }
    }

    @Test
    fun extrasValidationAndEditing() {
        val store = ProfileExtrasStore(InMemoryKeyValueStore())
        val ecu = EcuDefinition("44", "Steering assist", "712", "77C")
        store.update(vin) { it.withEcu(ecu) }
        store.update(vin) { it.withEcu(ecu.copy(name = "Power steering", requestId = "713", responseId = "77D"), replacingKey = "712") }
        assertEquals(listOf("713"), store.get(vin).manualEcus.map { it.key })
        store.update(vin) { it.withName("713", "EPS") }
        assertEquals("EPS", store.get(vin).moduleNames["713"])
        store.update(vin) { it.withName("713", "") }
        assertTrue(store.get(vin).moduleNames.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.update(vin) { it.withEcu(EcuDefinition(null, "Dup", "7E0", "7E0")) } }
        store.update(vin) { it.withoutEcu("713") }
        assertTrue(store.get(vin).isEmpty)
        assertNotNull(EcuDefinition(null, "x", "18DA19F1", "18DAF119").moduleAddress().takeIf { it.bus == Bus.CAN_29 })
    }
}
