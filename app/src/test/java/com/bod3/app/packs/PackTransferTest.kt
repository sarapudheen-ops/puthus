package com.bod3.app.packs

import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.actuator.ActuatorRecipeCodec
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.coding.CodingRecipeCodec
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTransferTest {

    private val boost = CustomPid("boost", "Boost", "22F40C", "(A*256+B)/100", unit = "bar", min = 0.0, max = 3.0, decimals = 2, header = "7E0", responseHeader = "7E8")
    private val tiguan = VehicleDefinition("Volkswagen", "Tiguan", ecus = listOf(EcuDefinition("09", "Central electronics", "70E", "778")))
    private val bundled = DataPack("bundled", "Bundled", pids = listOf(boost), vehicles = listOf(tiguan))

    private fun assetDir(kind: String) = listOf("src/main/assets/$kind", "app/src/main/assets/$kind").map(::File).first { it.isDirectory }

    private inner class Rig {
        val dataStore = DataPackStore(InMemoryKeyValueStore())
        val actuators = ActuatorLibrary(emptyList(), InMemoryKeyValueStore())
        val coding = CodingLibrary(emptyList(), InMemoryKeyValueStore())
        val transfer = PackTransfer(listOf(bundled), dataStore, actuators, coding)
    }

    // ---- detection and clear errors ----

    @Test
    fun detectsEveryKind() {
        fun kind(json: String) = (PackFiles.detect(json) as Detection.Known).kind
        assertEquals(PackKind.DATA, kind("""{"format":"bod3-datapack","id":"a","name":"A"}"""))
        assertEquals(PackKind.ACTUATORS, kind("""{"format":"bod3-actuators"}"""))
        assertEquals(PackKind.CODING, kind("""{"format":"bod3-coding"}"""))
        assertEquals(PackKind.PROFILE, kind("""{"format":"bod3-vehicle-profile"}"""))
        assertEquals("single coding recipe", PackKind.CODING, kind("""{"id":"x","module":{},"method":"long_coding"}"""))
        assertEquals("legacy data pack without format", PackKind.DATA, kind("""{"id":"x","name":"X","pids":[]}"""))
        assertEquals("BOM is ignored", PackKind.CODING, kind("\uFEFF{\"format\":\"bod3-coding\"}"))
    }

    @Test
    fun unreadableFilesExplainWhy() {
        fun message(text: String) = (PackFiles.detect(text) as Detection.Unreadable).message
        assertTrue(message("   ").contains("empty"))
        assertTrue(message("[1,2]").contains("JSON list"))
        assertTrue(message("42").contains("JSON object"))
        assertTrue(message("""{"name":"x"}""").contains("no \"format\""))
        val other = message("""{"format":"carscanner-profile"}""")
        assertTrue(other, other.contains("not supported") && other.contains("bod3-datapack"))
        assertTrue(message("""{"format":"bod3-future"}""").contains("newer app version"))
        assertTrue(message("""{"format":3}""").contains("must be text"))
        val broken = message("{\n  \"format\": \"bod3-datapack\",\n  \"id\": x\n}")
        assertTrue(broken, broken.contains("line 3, column 9"))
        assertTrue(broken.contains("missing comma"))
        assertTrue(message("x".repeat(PackFiles.MAX_BYTES + 1)).contains("larger than"))
    }

    @Test
    fun invalidPacksListEveryProblemAndImportNothing() {
        val rig = Rig()
        val preview = rig.transfer.preview(
            """{"format":"bod3-datapack","id":"p","name":"P","pids":[{"id":"bad","name":"Bad","request":"2E0600","formula":"A"}],
               "vehicles":[{"make":"VW","model":"X","ecus":[{"name":"E","requestId":"7E0","responseId":"7E0"}]}]}""",
        )
        assertFalse(preview.canImport)
        assertTrue(preview.errors.first(), preview.errors.first().contains("problems – nothing was imported"))
        assertTrue(preview.errors.any { it.contains("pids[0]") && it.contains("not a read service") })
        assertTrue(preview.errors.any { it.contains("vehicles[0]") && it.contains("must differ") })
        assertThrows(IllegalArgumentException::class.java) { rig.transfer.apply(preview) }
        assertTrue(rig.dataStore.userPack().pids.isEmpty())
    }

    // ---- data packs ----

    @Test
    fun dataPackPreviewThenMerge() {
        val rig = Rig()
        rig.dataStore.savePid(boost.copy(name = "Old boost"))
        val incoming = DataPack(
            "shared", "Shared PIDs", version = 3,
            pids = listOf(boost.copy(name = "New boost"), boost.copy(id = "oil", name = "Oil", request = "22F40D")),
            vehicles = listOf(tiguan.copy(years = "2016-2024"), VehicleDefinition("Audi", "Q3")),
            dtcs = mapOf("P1234" to "Example"),
        )
        val preview = rig.transfer.preview(DataPackCodec.encode(incoming))
        assertTrue(preview.errors.toString(), preview.canImport)
        assertEquals(PackKind.DATA, preview.kind)
        assertEquals("Shared PIDs (v3)", preview.title)
        assertTrue(preview.summary.toString(), preview.summary.any { it.startsWith("2 custom PID(s), 1 replacing") })
        assertTrue(preview.summary.any { it.startsWith("2 vehicle(s) with 1 ECU definitions, 1 replacing") })
        assertTrue(preview.summary.any { it == "1 DTC description(s)" })
        assertTrue("nothing saved before apply", rig.dataStore.userPack().pids.single().name == "Old boost")

        val message = rig.transfer.apply(preview)
        assertTrue(message, message.startsWith("Imported 2 PID(s), 2 vehicle ECU list(s), 1 DTC description(s)"))
        val user = rig.dataStore.userPack()
        assertEquals(listOf("New boost", "Oil"), user.pids.map { it.name }.sorted())
        assertEquals(2, user.vehicles.size)
        assertEquals("Example", user.dtcs["P1234"])
    }

    @Test
    fun emptyDataPackIsRefused() {
        val preview = Rig().transfer.preview("""{"format":"bod3-datapack","id":"e","name":"Empty"}""")
        assertFalse(preview.canImport)
        assertTrue(preview.errors.single().contains("contains no"))
    }

    @Test
    fun exportsUserDataByScope() {
        val rig = Rig()
        assertNull(rig.transfer.exportData(DataExport.PIDS))
        assertNull(rig.transfer.exportData(DataExport.ALL))
        rig.dataStore.savePid(boost)
        rig.dataStore.saveVehicle(tiguan)
        rig.dataStore.setDtcDescription("P1234", "Mine")

        val pids = DataPackCodec.decode(rig.transfer.exportData(DataExport.PIDS)!!).pack!!
        assertEquals(listOf("boost"), pids.pids.map { it.id })
        assertTrue(pids.vehicles.isEmpty())

        val ecus = DataPackCodec.decode(rig.transfer.exportData(DataExport.VEHICLES)!!).pack!!
        assertEquals(listOf(tiguan), ecus.vehicles)
        assertTrue(ecus.pids.isEmpty())

        val all = DataPackCodec.decode(rig.transfer.exportData(DataExport.ALL)!!).pack!!
        assertEquals("my-data", all.id)
        assertEquals("Mine", all.dtcs["P1234"])

        val one = DataPackCodec.decode(rig.transfer.exportVehicle(tiguan)).pack!!
        assertEquals("ecus-volkswagen-tiguan", one.id)
        assertEquals(listOf(tiguan), one.vehicles)

        // Round trip into a fresh install.
        val other = Rig()
        other.transfer.apply(other.transfer.preview(rig.transfer.exportData(DataExport.ALL)!!))
        assertEquals(rig.dataStore.userPack().copy(id = "x", name = "x"), other.dataStore.userPack().copy(id = "x", name = "x"))
    }

    // ---- recipes ----

    @Test
    fun recipesImportThroughTheSameEntryPoint() {
        val rig = Rig()
        val coding = rig.transfer.preview(assetDir("samples").resolve("coding-template.json").readText())
        assertTrue(coding.errors.toString(), coding.canImport)
        assertEquals(PackKind.CODING, coding.kind)
        assertTrue(coding.summary.single().startsWith("2 coding recipe(s)"))
        assertTrue(coding.warnings.any { it.contains("unverified") })
        assertTrue(rig.transfer.apply(coding).contains("2 new"))
        assertEquals(2, rig.coding.userPack().recipes.size)
        assertTrue("re-import replaces", rig.transfer.preview(rig.transfer.exportCoding()!!).summary.single().contains("2 replacing"))

        val tests = rig.transfer.preview(assetDir("samples").resolve("actuators-template.json").readText())
        assertTrue(tests.errors.toString(), tests.canImport)
        rig.transfer.apply(tests)
        assertEquals(1, rig.actuators.userPack().recipes.size)
        assertNotNull(rig.transfer.exportActuators())

        val badCoding = rig.transfer.preview("""{"format":"bod3-coding","id":"c","name":"C","recipes":[{"id":"x","name":"X","module":{"name":"M","requestId":"70E","responseId":"778"},"method":"long_coding","byte":0}]}""")
        assertFalse(badCoding.canImport)
        assertTrue(badCoding.errors.toString(), badCoding.errors.any { it.contains("mask") })

        val counts = rig.transfer.userCounts()
        assertEquals(2, counts.codingRecipes)
        assertEquals(1, counts.actuatorRecipes)
    }

    @Test
    fun profileFilesAreRecognised() {
        val preview = Rig().transfer.preview("""{"format":"bod3-vehicle-profile","formatVersion":1}""")
        assertEquals(PackKind.PROFILE, preview.kind)
        assertTrue(preview.errors.isEmpty())
        assertFalse("handled by the garage import", preview.canImport)
    }

    @Test
    fun bundledPacksAreListedAndExportable() {
        val rig = Rig()
        val list = rig.transfer.bundled()
        assertEquals(1, list.size)
        assertEquals("1 PIDs · 1 vehicles", list.single().contents)
        assertEquals(bundled, DataPackCodec.decode(rig.transfer.exportBundled(list.single())!!).pack)
    }

    @Test
    fun sampleTemplatesAreValid() {
        val dir = assetDir("samples")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals(3, files.size)
        for (file in files) {
            val text = file.readText()
            val ok = when ((PackFiles.detect(text) as Detection.Known).kind) {
                PackKind.DATA -> DataPackCodec.decode(text).let { r -> assertTrue("${file.name}: ${r.errors}", r.isValid); true }
                PackKind.ACTUATORS -> ActuatorRecipeCodec.decode(text).let { r -> assertTrue("${file.name}: ${r.errors}", r.isValid); true }
                PackKind.CODING -> CodingRecipeCodec.decode(text).let { r -> assertTrue("${file.name}: ${r.errors}", r.isValid); true }
                PackKind.PROFILE, PackKind.CATALOG, PackKind.VERIFIED_SETUPS -> false
            }
            assertTrue(file.name, ok)
        }
    }
}
