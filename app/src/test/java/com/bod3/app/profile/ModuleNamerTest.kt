package com.bod3.app.profile

import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.ModuleNameEntry
import com.bod3.app.obd.scan.Bus
import com.bod3.app.obd.scan.ModuleAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleNamerTest {

    private val bcm = ModuleAddress(Bus.CAN_11, "70E", "778")
    private val engine = ModuleAddress(Bus.CAN_11, "7E0", "7E8")
    private val vwNames = listOf(
        ModuleNameEntry("70E", "Central electronics", "778", "09", listOf("Volkswagen", "Audi")) to "VW group",
        ModuleNameEntry("7E0", "Engine electronics", null, "01", listOf("Volkswagen")) to "VW group",
    )

    @Test
    fun priorityOrder() {
        val all = ModuleNamer(
            userNames = mapOf("70E" to "My BCM"),
            manualEcus = listOf(EcuDefinition("09", "Manual BCM", "70E", "778")),
            vehicleEcus = listOf(EcuDefinition("09", "Pack BCM", "70E", "778")),
            vehicleTitle = "Volkswagen Tiguan",
            packNames = vwNames,
            make = "VOLKSWAGEN",
        )
        assertEquals(ModuleName("My BCM", NameSource.USER), all.name(bcm, "BCM PQ", "x"))
        assertEquals(NameSource.MANUAL, ModuleNamer(manualEcus = listOf(EcuDefinition("09", "Manual BCM", "70E", "778")), vehicleEcus = listOf(EcuDefinition("09", "Pack BCM", "70E", "778"))).name(bcm, "BCM", null)!!.source)
        val pack = ModuleNamer(vehicleEcus = listOf(EcuDefinition("09", "Pack BCM", "70E", "778")), vehicleTitle = "VW Tiguan").name(bcm, "BCM", null)!!
        assertEquals(ModuleName("09 · Pack BCM", NameSource.VEHICLE_PACK, "VW Tiguan"), pack)
        assertEquals(ModuleName("BCM PQ", NameSource.MODULE), ModuleNamer(packNames = vwNames, make = "Volkswagen").name(bcm, " BCM PQ ", null))
        assertEquals(ModuleName("09 · Central electronics", NameSource.PACK_MAP, "VW group"), ModuleNamer(packNames = vwNames, make = "audi").name(bcm, null, null))
    }

    @Test
    fun packNamesRespectMakeAndResponseId() {
        // Wrong make → falls through to the OBD name, then the generic name.
        assertEquals(ModuleName("ECM-Engine", NameSource.OBD), ModuleNamer(packNames = vwNames, make = "Ford").name(engine, null, "ECM-Engine"))
        assertEquals(ModuleName("Engine (ECM)", NameSource.GENERIC), ModuleNamer(packNames = vwNames, make = null).name(engine, null, null))
        // Response ID mismatch.
        assertNull(ModuleNamer(packNames = vwNames, make = "Audi").name(ModuleAddress(Bus.CAN_11, "70E", "779"), null, null))
        // An entry without makes applies to any vehicle.
        val any = listOf(ModuleNameEntry("710", "Gateway") to "Generic")
        assertEquals("Gateway", ModuleNamer(packNames = any).name(ModuleAddress(Bus.CAN_11, "710", "77A"), null, null)!!.name)
    }

    @Test
    fun userNameIsCaseInsensitiveAndBlankIgnored() {
        assertEquals(NameSource.USER, ModuleNamer(userNames = mapOf("18da19f1" to "Gateway")).name(ModuleAddress(Bus.CAN_29, "18DA19F1", "18DAF119"), null, null)!!.source)
        assertEquals(NameSource.MODULE, ModuleNamer(userNames = mapOf("70E" to " ")).name(bcm, "BCM", null)!!.source)
    }

    @Test
    fun dataPackModuleNamesRoundTripAndValidate() {
        val json = """{"format":"bod3-datapack","id":"x","name":"X","moduleNames":[
            {"requestId":"70e","responseId":"778","address":"09","name":"Central electronics","makes":["Volkswagen"]},
            {"requestId":"7G0","name":""},
            {"requestId":"710","name":"Gw","makes":[5]}]}"""
        val bad = DataPackCodec.decode(json)
        assertNull(bad.pack)
        assertTrue(bad.errors.toString(), bad.errors.any { it.startsWith("moduleNames[1]") && it.contains("requestId") })
        assertTrue(bad.errors.toString(), bad.errors.any { it.startsWith("moduleNames[1]") && it.contains("name") })
        assertTrue(bad.errors.toString(), bad.errors.any { it == "moduleNames[2].makes[0]: must be text" })

        val good = DataPackCodec.decodeOrThrow("""{"id":"x","name":"X","moduleNames":[{"requestId":"70e","responseId":"778","address":"09","name":"Central electronics","makes":["Volkswagen"]}]}""")
        assertEquals(ModuleNameEntry("70E", "Central electronics", "778", "09", listOf("Volkswagen")), good.moduleNames.single())
        assertEquals(good, DataPackCodec.decodeOrThrow(DataPackCodec.encode(good)))
    }
}
