package com.bod3.app.datapack

import com.bod3.app.obd.connection.InMemoryKeyValueStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPackTest {

    private fun asset(name: String): String {
        val candidates = listOf("src/main/assets/datapacks/$name", "app/src/main/assets/datapacks/$name")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    @Test
    fun bundledPacksAreValid() {
        val generic = DataPackCodec.decode(asset("generic-obd.json"))
        assertTrue(generic.errors.joinToString(), generic.isValid)
        assertEquals("Catalyst system efficiency below threshold (bank 1)", generic.pack!!.dtcs["P0420"])
        assertTrue(generic.pack!!.dtcs.size > 150)
        val vw = DataPackCodec.decode(asset("vw-group-mqb.json"))
        assertTrue(vw.errors.joinToString(), vw.isValid)
        val tiguan = vw.pack!!.vehicles.first { it.model.startsWith("Tiguan") }
        assertEquals("778", tiguan.ecus.first { it.address == "09" }.responseId)
    }

    @Test
    fun roundTrip() {
        val pack = DataPack(
            id = "test", name = "Test", version = 2, author = "me",
            pids = listOf(CustomPid("boost", "Boost", "22F40C", "(A*256+B)/100", "bar", 0.0, 3.0, 2, "7E0", "7E8", "note \"quoted\"")),
            vehicles = listOf(VehicleDefinition("Volkswagen", "Tiguan", "2018", listOf(EcuDefinition("09", "BCM", "70E", "778")))),
            dtcs = mapOf("P1234" to "Custom code", "P0420-13" to "Catalyst, open circuit"),
        )
        val decoded = DataPackCodec.decode(DataPackCodec.encode(pack))
        assertTrue(decoded.errors.joinToString(), decoded.isValid)
        assertEquals(pack, decoded.pack)
    }

    @Test
    fun invalidPacksListEveryProblem() {
        assertEquals(listOf("Not valid JSON: "), DataPackCodec.decode("{oops").errors.map { it.substringBefore("Not valid JSON: ") + "Not valid JSON: " })
        assertEquals(listOf("The file must contain a JSON object"), DataPackCodec.decode("[1,2]").errors)
        val json = """
            {"format":"bod3-datapack","formatVersion":1,"id":"bad","name":"Bad",
             "pids":[{"id":"w","name":"Write","request":"2EF19000","formula":"A"},
                     {"id":"f","name":"Formula","request":"010C","formula":"(A*256+"},
                     {"id":"f","name":"Dup","request":"0105","formula":"A-40","min":10,"max":5},
                     {"name":"No id","request":"0105","formula":"A"}],
             "vehicles":[{"make":"VW","model":"Golf","ecus":[{"name":"BCM","requestId":"70E","responseId":"77"},
                                                            {"name":"Same","requestId":"70E","responseId":"778"}]}],
             "dtcs":{"P0420":"ok","X123":"bad key","P0300":5}}
        """.trimIndent()
        val result = DataPackCodec.decode(json)
        assertNull(result.pack)
        val text = result.errors.joinToString("\n")
        listOf(
            "pids[0] (Write).request: 2E is not a read service",
            "pids[1] (Formula).formula:",
            "pids[2] (Dup).min/max",
            "pids[3].id: missing",
            "pids: id \"f\" is used twice",
            "vehicles[0] (VW Golf).ecus[0].responseId: must be a 3-digit",
            "vehicles[0] (VW Golf).ecus: request ID 70E is used twice",
            "dtcs: \"X123\" is not a DTC",
            "dtcs.P0300: description must be text",
        ).forEach { assertTrue("missing '$it' in\n$text", text.contains(it)) }

        val newer = DataPackCodec.decode("""{"formatVersion":9,"id":"x","name":"X"}""")
        assertTrue(newer.errors.single().contains("newer than this app supports"))
        assertThrows(IllegalArgumentException::class.java) { DataPackCodec.decodeOrThrow("""{"id":"x"}""") }
    }

    @Test
    fun storeValidatesAndRepositoryMerges() {
        val store = DataPackStore(InMemoryKeyValueStore())
        val bundled = DataPackCodec.decodeOrThrow(asset("vw-group-mqb.json"))
        val generic = DataPackCodec.decodeOrThrow(asset("generic-obd.json"))
        val repo = DataRepository(listOf(generic, bundled), store)

        // Custom PIDs: user PIDs replace bundled ones with the same id.
        store.savePid(CustomPid("map-bar", "My MAP", "010B", "A/100", "bar"))
        assertEquals("My MAP", repo.customPids().first { it.id == "map-bar" }.name)
        assertTrue(repo.isUserPid("map-bar"))
        assertThrows(IllegalArgumentException::class.java) { store.savePid(CustomPid("x", "Write", "2E0600", "A")) }
        assertThrows(IllegalArgumentException::class.java) { store.savePid(CustomPid("x", "Clear", "04", "A")) }
        store.deletePid("map-bar")
        assertEquals("Intake manifold pressure", repo.customPids().first { it.id == "map-bar" }.name)

        // Vehicles: editing a bundled vehicle stores an override; deleting it restores the bundled list.
        val tiguan = repo.vehicles().first { it.vehicle.model.startsWith("Tiguan") }
        assertEquals(VehicleSource.BUNDLED, tiguan.source)
        val edited = tiguan.vehicle.copy(ecus = tiguan.vehicle.ecus + EcuDefinition("42", "Door electronics driver", "74A", "7B4"))
        store.saveVehicle(edited)
        val after = repo.vehicle(tiguan.vehicle.key)!!
        assertEquals(VehicleSource.USER_MODIFIED, after.source)
        assertEquals(tiguan.vehicle.ecus.size + 1, after.vehicle.ecus.size)
        store.saveVehicle(VehicleDefinition("Opel", "Astra K", ecus = listOf(EcuDefinition(name = "Engine", requestId = "7E0", responseId = "7E8"))))
        assertEquals(VehicleSource.USER, repo.vehicles().first { it.vehicle.make == "Opel" }.source)
        store.deleteVehicle(tiguan.vehicle.key)
        assertEquals(VehicleSource.BUNDLED, repo.vehicle(tiguan.vehicle.key)!!.source)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveVehicle(VehicleDefinition("X", "Y", ecus = listOf(EcuDefinition(name = "Bad", requestId = "7E0", responseId = "7E0"))))
        }

        // Selection and live selection persist.
        store.selectedVehicleKey = tiguan.vehicle.key
        assertEquals(tiguan.vehicle.key, store.selectedVehicleKey)
        store.saveLiveSelection(LiveSelection(listOf("010C", "custom:boost"), mapOf("010C" to LiveDisplay.GAUGE)))
        assertEquals(LiveDisplay.GAUGE, store.liveSelection()!!.displayOf("010C"))
        assertEquals(LiveDisplay.VALUE, store.liveSelection()!!.displayOf("custom:boost"))
    }

    @Test
    fun dtcDescriptionsLookupOrder() {
        val store = DataPackStore(InMemoryKeyValueStore())
        val repo = DataRepository(listOf(DataPackCodec.decodeOrThrow(asset("generic-obd.json"))), store)
        assertEquals("Catalyst system efficiency below threshold (bank 1)", repo.dtcDescriptions().describe("P0420").text)
        assertEquals("Generic OBD-II (SAE J2012)", repo.dtcDescriptions().describe("P0420-13").source) // base code
        store.setDtcDescription("p0420", "My note on the catalyst")
        val user = repo.dtcDescriptions().describe("P0420")
        assertTrue(user.isUserDefined)
        assertEquals("My note on the catalyst", user.text)
        store.setDtcDescription("P0420", " ")
        assertFalse(repo.dtcDescriptions().describe("P0420").isUserDefined)
        assertThrows(IllegalArgumentException::class.java) { store.setDtcDescription("hello", "x") }

        val descriptions = repo.dtcDescriptions()
        assertTrue(descriptions.describe("P1570").text.contains("manufacturer-specific"))
        assertTrue(descriptions.describe("P0699").text.contains("computer and output circuits"))
        assertTrue(descriptions.describe("U3000").text.contains("generic"))
        assertEquals("circuit open", descriptions.failureType("B1234-13"))
        assertNull(descriptions.failureType("P0420"))
    }
}
