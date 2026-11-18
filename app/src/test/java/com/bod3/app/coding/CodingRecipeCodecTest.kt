package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingRecipeCodecTest {

    private val bcm = ActuatorModule("Central electronics", "09", "70E", "778")

    private val comfort = CodingRecipe(
        id = "vw-09-comfort-closing", name = "Comfort closing", module = bcm, method = CodingMethod.LONG_CODING,
        did = 0x0600, byteIndex = 3, mask = 0x04, options = listOf(CodingOption(0, "Off"), CodingOption(1, "On")),
        security = SecurityAccessSpec(0x03, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x00, 0x01, 0x23, 0x45)),
    )

    private val packJson = """
        {"format":"bod3-coding","formatVersion":1,"id":"test","name":"Test pack","recipes":[
          {"id":"vw-09-comfort-closing","name":"Comfort closing","description":"Close windows with the key",
           "module":{"name":"Central electronics","address":"09","requestId":"70e","responseId":"778"},
           "method":"long_coding","byte":3,"mask":"04",
           "values":[{"value":0,"label":"Off"},{"value":1,"label":"On"}],
           "securityAccess":{"level":"03","algorithm":"SEED_PLUS_CONSTANT","constant":"00 01 23 45"},
           "preconditions":{"minVoltage":12.5},
           "applicability":{"makes":["Volkswagen"],"models":["Tiguan"],"partNumbers":["5Q0937084"],"softwareVersions":[]},
           "source":"Test","notes":"n","verified":false},
          {"id":"interval","name":"Service interval","module":{"name":"Dashboard","requestId":"714","responseId":"77E"},
           "method":"adaptation","did":"2203","byte":0,"length":2,"min":0,"max":"0x7530","factor":1,"unit":"km",
           "preconditions":{"allowEngineRunning":true}}
        ]}
    """.trimIndent()

    // ---- field bit math ----

    @Test
    fun maskedFieldReadsAndWritesOnlyItsBits() {
        val field = CodingField(byteIndex = 3, mask = 0x0C)
        val data = listOf(0x00, 0x11, 0x22, 0xF3, 0x44)
        assertEquals(0L, field.read(data))
        assertEquals(3L, field.maxValue)
        val written = field.write(data, 2)
        assertEquals(listOf(0x00, 0x11, 0x22, 0xFB, 0x44), written)
        assertEquals(2L, field.read(written))
        assertEquals(listOf(0x00, 0x11, 0x22, 0xFF, 0x44), field.write(data, 3))
        assertEquals(data, field.write(field.write(data, 3), 0))
    }

    @Test
    fun singleBitFieldTogglesOnlyThatBit() {
        val field = CodingField(byteIndex = 0, mask = 0x80)
        assertEquals(listOf(0x81, 0x00), field.write(listOf(0x01, 0x00), 1))
        assertEquals(listOf(0x7F), field.write(listOf(0xFF), 0))
        assertEquals(1L, field.read(listOf(0x80)))
        assertEquals(1L, field.maxValue)
    }

    @Test
    fun multiByteFieldIsBigEndian() {
        val field = CodingField(byteIndex = 1, length = 2)
        val data = listOf(0xAA, 0x01, 0x2C, 0xBB)
        assertEquals(300L, field.read(data))
        assertEquals(listOf(0xAA, 0x75, 0x30, 0xBB), field.write(data, 30000))
        assertEquals(65535L, field.maxValue)
        assertEquals(0xFFFFFFFFL, CodingField(0, length = 4).maxValue)
        assertEquals(0x01020304L, CodingField(0, length = 4).read(listOf(1, 2, 3, 4)))
    }

    @Test
    fun fieldRejectsShortDataAndOutOfRangeValues() {
        val field = CodingField(byteIndex = 4, mask = 0x01)
        assertFalse(field.fits(listOf(0, 0, 0, 0)))
        assertThrows(IllegalArgumentException::class.java) { field.read(listOf(0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { field.write(listOf(0, 0, 0, 0, 0), 2) }
    }

    // ---- recipe validation ----

    @Test
    fun valueProblemsFollowOptionsAndRange() {
        assertNull(comfort.valueProblem(1))
        assertNotNull(comfort.valueProblem(2))
        val adaptation = comfort.copy(method = CodingMethod.ADAPTATION, mask = null, length = 2, options = emptyList(), min = 10, max = 500)
        assertNull(adaptation.valueProblem(10))
        assertTrue(adaptation.valueProblem(9)!!.contains("minimum"))
        assertTrue(adaptation.valueProblem(501)!!.contains("maximum"))
        assertTrue(adaptation.valueProblem(-1)!!.contains("does not fit"))
    }

    @Test
    fun describeUsesLabelsOrScaling() {
        assertEquals("On (1)", comfort.describe(1))
        val scaled = comfort.copy(method = CodingMethod.ADAPTATION, mask = null, options = emptyList(), factor = 0.5, offset = -10.0, unit = "°C")
        assertEquals("40 °C (raw 100)", scaled.describe(100))
        assertEquals("-9.5 °C (raw 1)", scaled.describe(1))
    }

    @Test
    fun validatorListsEveryProblem() {
        val bad = comfort.copy(
            id = "Bad Id", name = " ", module = bcm.copy(requestId = "70", responseId = "778"),
            did = 0xF190, byteIndex = 300, mask = 0x00, length = 2, session = 0x02,
            security = SecurityAccessSpec(0x02, SecurityAlgorithm.STATIC_KEY, emptyList()),
            preconditions = CodingPreconditions(minVoltage = 5.0), factor = 0.0,
        )
        val problems = bad.problems()
        listOf("id:", "name:", "module:", "did: F190", "byte:", "mask: must be", "mask: only allowed", "session:", "securityAccess:", "minVoltage", "factor:")
            .forEach { p -> assertTrue("missing '$p' in $problems", problems.any { it.contains(p) }) }
    }

    @Test
    fun methodSpecificRules() {
        assertTrue(comfort.copy(mask = null).problems().any { it.startsWith("mask: long coding needs") })
        assertTrue(comfort.copy(method = CodingMethod.DID_VALUE, options = emptyList()).problems().any { it.startsWith("values:") })
        assertTrue(comfort.copy(options = listOf(CodingOption(2, "Two"))).problems().any { it.contains("does not fit") })
        assertTrue(comfort.copy(options = listOf(CodingOption(1, "A"), CodingOption(1, "B"))).problems().any { it.contains("more than once") })
        assertTrue(comfort.copy(did = 0xF198).problems().isEmpty())
        assertTrue(comfort.problems().isEmpty())
    }

    // ---- codec ----

    @Test
    fun decodesPackWithDefaults() {
        val result = CodingRecipeCodec.decode(packJson)
        assertTrue(result.errors.toString(), result.isValid)
        val (closing, interval) = result.pack!!.recipes
        assertEquals("70E", closing.module.requestId)
        assertEquals(0x0600, closing.did)
        assertEquals(3, closing.byteIndex)
        assertEquals(0x04, closing.mask)
        assertEquals(0x03, closing.session)
        assertEquals(12.5, closing.preconditions.minVoltage, 0.0)
        assertFalse(closing.preconditions.allowEngineRunning)
        assertEquals(listOf("5Q0937084"), closing.applicability.partNumbers)
        assertEquals(listOf(0x00, 0x01, 0x23, 0x45), closing.security!!.constant)
        assertEquals(CodingMethod.ADAPTATION, interval.method)
        assertEquals(0x2203, interval.did)
        assertEquals(30000L, interval.max)
        assertTrue(interval.preconditions.allowEngineRunning)
        assertEquals(12.0, interval.preconditions.minVoltage, 0.0)
    }

    @Test
    fun roundTripsThroughJson() {
        val pack = CodingRecipeCodec.decode(packJson).pack!!
        val again = CodingRecipeCodec.decode(CodingRecipeCodec.encode(pack))
        assertTrue(again.errors.toString(), again.isValid)
        assertEquals(pack.recipes, again.pack!!.recipes)
        val single = CodingRecipeCodec.decode(CodingRecipeCodec.encodeRecipeJson(comfort))
        assertEquals(listOf(comfort), single.pack!!.recipes)
    }

    @Test
    fun reportsErrorsWithPaths() {
        assertTrue(CodingRecipeCodec.decode("{nope").errors.single().startsWith("Not valid JSON"))
        assertEquals(listOf("The file must contain a JSON object"), CodingRecipeCodec.decode("[1]").errors)
        val errors = CodingRecipeCodec.decode(
            """{"format":"bod3-actuators","formatVersion":9,"id":"x","name":"x","recipes":[
                {"id":"a","name":"A","module":{"name":"M","requestId":"7E0"},"method":"bits","byte":"x",
                 "values":[{"value":-1,"label":"neg"}],"securityAccess":{"level":"03","algorithm":"MAGIC","constant":"zz"}}]}""",
        ).errors
        listOf(
            "format:", "formatVersion:", "recipes[0].module.responseId: missing", "recipes[0].method:", "recipes[0].byte:",
            "recipes[0].values[0].value:", "recipes[0].securityAccess.algorithm:", "recipes[0].securityAccess.constant:",
        ).forEach { p -> assertTrue("missing '$p' in $errors", errors.any { it.startsWith(p) }) }
    }

    @Test
    fun validatorErrorsCarryRecipePath() {
        val json = packJson.replace("\"byte\":3", "\"byte\":3,\"did\":\"F187\"")
        val errors = CodingRecipeCodec.decode(json).errors
        assertTrue(errors.toString(), errors.any { it.startsWith("recipes[0] (vw-09-comfort-closing).did: F187") })
        val dup = CodingRecipeCodec.decode(packJson.replace("\"id\":\"interval\"", "\"id\":\"vw-09-comfort-closing\"")).errors
        assertTrue(dup.any { it.contains("used more than once") })
    }

    @Test
    fun bundledPacksAreValidAndUnverified() {
        val dir = listOf("src/main/assets/coding", "app/src/main/assets/coding").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 2)
        val ids = mutableSetOf<String>()
        for (file in files) {
            val result = CodingRecipeCodec.decode(file.readText())
            assertTrue("${file.name}: ${result.errors}", result.isValid)
            result.pack!!.recipes.forEach { r ->
                assertTrue("duplicate ${r.id}", ids.add(r.id))
                assertFalse("${r.id} ships as verified", r.verified)
            }
        }
        assertTrue("Tiguan comfort closing example", "vw-mqb-09-comfort-closing" in ids)
    }

    // ---- library ----

    @Test
    fun libraryImportsSavesAndDeletesUserRecipes() {
        val bundled = CodingRecipeCodec.decode(packJson).pack!!
        val store = InMemoryKeyValueStore()
        val library = CodingLibrary(listOf(bundled), store)
        assertEquals(2, library.entries().size)

        val mine = comfort.copy(id = "mine", name = "Mine")
        val result = library.import(CodingPack("p", "P", recipes = listOf(mine, comfort.copy(name = "Override"))))
        assertEquals(listOf("mine"), result.added)
        assertEquals(listOf("vw-09-comfort-closing"), result.replaced)
        assertEquals(3, library.entries().size)
        assertEquals("Override", library.entry("vw-09-comfort-closing")!!.recipe.name)
        assertTrue(library.entry("vw-09-comfort-closing")!!.isUser)

        library.save(mine.copy(id = "mine2"), replacingId = "mine")
        assertNull(library.entry("mine"))
        assertNotNull(library.entry("mine2"))
        assertThrows(IllegalArgumentException::class.java) { library.save(mine.copy(id = "BAD ID")) }

        library.deleteUserRecipe("vw-09-comfort-closing")
        assertEquals("Comfort closing", library.entry("vw-09-comfort-closing")!!.recipe.name)
        assertFalse(library.entry("vw-09-comfort-closing")!!.isUser)

        // Survives a restart (same store).
        assertNotNull(CodingLibrary(listOf(bundled), store).entry("mine2"))
        val exported = CodingRecipeCodec.decode(library.export())
        assertEquals(listOf("mine2"), exported.pack!!.recipes.map { it.id })
    }

    @Test
    fun backupStoreRoundTrips() {
        val store = CodingBackupStore(InMemoryKeyValueStore(), maxBackups = 2)
        val backup = CodingBackup(
            "b1", 1000, comfort.id, comfort.name, "WVG", comfort.module, 0x03, comfort.security, CodingPreconditions(12.2, true),
            0x0600, listOf(0x01, 0xFF), "5Q0937084AB", "0123",
        )
        store.add(backup)
        assertEquals(backup, store.get("b1"))
        store.updateStatus("b1", BackupStatus.WRITTEN, 2000)
        assertEquals(BackupStatus.WRITTEN, store.get("b1")!!.status)
        assertEquals(2000L, store.get("b1")!!.updatedAtMs)
        store.add(backup.copy(id = "b2"))
        store.add(backup.copy(id = "b3"))
        assertEquals(listOf("b2", "b3"), store.all().map { it.id })
    }
}
