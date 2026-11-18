package com.bod3.app.actuator

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

class ActuatorRecipeCodecTest {

    private fun recipe(id: String = "bcm-horn", start: List<Int> = listOf(0x2F, 0x05, 0x10, 0x03, 0x01), stop: List<Int> = listOf(0x2F, 0x05, 0x10, 0x00)) =
        ActuatorRecipe(
            id = id, name = "Horn", description = "Sounds the horn",
            module = ActuatorModule("Central electronics", "09", "70E", "778"),
            security = SecurityAccessSpec(0x03, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x00, 0x01, 0x23, 0x45)),
            start = start, stop = stop, status = listOf(0x22, 0x05, 0x10), durationSeconds = 3,
            preconditions = ActuatorPreconditions(minVoltage = 12.2, engine = EngineRequirement.ANY, speedZero = true),
            safetyNotes = listOf("Loud"), applicability = Applicability(makes = listOf("Volkswagen"), partNumbers = listOf("5Q0937084")),
            source = "test", notes = "n",
        )

    @Test
    fun roundTrip() {
        val pack = ActuatorPack("p", "Pack", recipes = listOf(recipe(), recipe("routine", listOf(0x31, 0x01, 0x02, 0x00), listOf(0x31, 0x02, 0x02, 0x00)).copy(status = listOf(0x31, 0x03, 0x02, 0x00))))
        assertTrue(pack.problems().toString(), pack.problems().isEmpty())
        val decoded = ActuatorRecipeCodec.decode(ActuatorRecipeCodec.encode(pack))
        assertTrue(decoded.errors.toString(), decoded.isValid)
        assertEquals(pack, decoded.pack)
    }

    @Test
    fun validatorRejectsUnsafeOrInconsistentRecipes() {
        fun problemsOf(r: ActuatorRecipe) = r.problems().joinToString("\n")
        assertTrue(problemsOf(recipe(start = listOf(0x2E, 0x06, 0x00, 0x01))).contains("start:"))
        assertTrue(problemsOf(recipe(start = listOf(0x11, 0x01))).contains("start:"))
        assertTrue(problemsOf(recipe(start = listOf(0x2F, 0x05, 0x10, 0x00))).contains("returns control"))
        assertTrue(problemsOf(recipe(stop = listOf(0x2F, 0x05, 0x11, 0x00))).contains("stop:"))
        assertTrue(problemsOf(recipe(stop = listOf(0x2F, 0x05, 0x10, 0x03))).contains("stop:"))
        assertTrue(problemsOf(recipe("routine", listOf(0x31, 0x01, 0x02, 0x00), listOf(0x31, 0x02, 0x02, 0x01)).copy(status = null)).contains("stop:"))
        assertTrue(problemsOf(recipe().copy(session = 0x02)).contains("session"))
        assertTrue(problemsOf(recipe().copy(safetyNotes = emptyList())).contains("safety"))
        assertTrue(problemsOf(recipe().copy(durationSeconds = 600)).contains("durationSeconds"))
        assertTrue(problemsOf(recipe().copy(module = ActuatorModule("x", null, "70E", "18DAF10E"))).contains("module"))
        assertTrue(problemsOf(recipe().copy(security = SecurityAccessSpec(0x04, SecurityAlgorithm.STATIC_KEY, listOf(1)))).contains("securityAccess"))
        assertTrue(problemsOf(recipe().copy(id = "Bad Id")).contains("id:"))
    }

    @Test
    fun decodeReportsEveryProblemWithItsPath() {
        val json = """
            {"format":"bod3-actuators","formatVersion":1,"id":"p","name":"P","recipes":[
              {"id":"a","name":"A","module":{"name":"M","requestId":"70E","responseId":"778"},"start":"2F 05 10 03 01","stop":"2F 05 10 00","safety":["s"]},
              {"id":"b","module":{"name":"M","requestId":"70E"},"start":"zz","stop":"2F 05 10 00","safety":["s"],"preconditions":{"engine":"idle"}},
              {"id":"c","name":"C","module":{"name":"M","requestId":"70E","responseId":"778"},"start":"10 02","stop":"10 01","safety":["s"],
               "securityAccess":{"level":"03","algorithm":"MAGIC","constant":"01"}}
            ]}
        """.trimIndent()
        val result = ActuatorRecipeCodec.decode(json)
        assertNull(result.pack)
        val text = result.errors.joinToString("\n")
        assertTrue(text, text.contains("recipes[1].name: missing"))
        assertTrue(text, text.contains("recipes[1].module.responseId: missing"))
        assertTrue(text, text.contains("recipes[1].start: \"zz\" is not a hex byte string"))
        assertTrue(text, text.contains("recipes[1].preconditions.engine"))
        assertTrue(text, text.contains("recipes[2].securityAccess.algorithm"))
    }

    @Test
    fun decodeRejectsWrongFormatAndNewerVersion() {
        assertTrue(ActuatorRecipeCodec.decode("[1]").errors.single().contains("JSON object"))
        assertTrue(ActuatorRecipeCodec.decode("{").errors.single().startsWith("Not valid JSON"))
        val errors = ActuatorRecipeCodec.decode("""{"format":"bod3-datapack","formatVersion":9,"id":"x","name":"x","recipes":[]}""").errors
        assertTrue(errors.any { it.startsWith("format:") })
        assertTrue(errors.any { it.startsWith("formatVersion:") })
        assertTrue(errors.any { it.contains("no recipes") })
    }

    @Test
    fun bundledPacksAreValid() {
        val dir = listOf("src/main/assets/actuators", "app/src/main/assets/actuators").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 2)
        val ids = mutableSetOf<String>()
        for (file in files) {
            val result = ActuatorRecipeCodec.decode(file.readText())
            assertTrue("${file.name}: ${result.errors}", result.isValid)
            result.pack!!.recipes.forEach { r ->
                assertTrue("duplicate ${r.id}", ids.add(r.id))
                assertFalse("${r.id} ships as verified", r.verified)
            }
        }
    }

    @Test
    fun libraryImportExportAndOverride() {
        val bundled = ActuatorPack("b", "Bundled", recipes = listOf(recipe("bcm-horn"), recipe("bcm-wiper")))
        val library = ActuatorLibrary(listOf(bundled), InMemoryKeyValueStore())
        assertEquals(2, library.entries().size)

        val mine = recipe("bcm-horn").copy(name = "My horn", durationSeconds = 1)
        val result = library.import(ActuatorPack("x", "X", recipes = listOf(mine, recipe("new-one"))))
        assertEquals(listOf("new-one"), result.added)
        assertEquals(listOf("bcm-horn"), result.replaced)
        assertEquals(3, library.entries().size)
        val horn = library.entry("bcm-horn")!!
        assertTrue(horn.isUser)
        assertEquals("My horn", horn.recipe.name)

        val exported = ActuatorRecipeCodec.decodeOrThrow(library.export())
        assertEquals(setOf("bcm-horn", "new-one"), exported.recipes.map { it.id }.toSet())

        library.deleteUserRecipe("bcm-horn")
        assertFalse(library.entry("bcm-horn")!!.isUser)
        library.deleteUserRecipe("new-one")
        assertNull(library.entry("new-one"))
        assertNotNull(library.entry("bcm-wiper"))

        assertThrows(IllegalArgumentException::class.java) { library.importJson("""{"format":"bod3-actuators","id":"x","name":"x","recipes":[{}]}""") }
    }
}
