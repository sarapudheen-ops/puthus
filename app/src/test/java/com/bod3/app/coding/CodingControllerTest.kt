package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorEnvironment
import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.VehicleContext
import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimUdsEcu
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingControllerTest {

    private val original = listOf(0x00, 0x1B, 0x0C, 0x01)

    private val closing = CodingRecipe(
        id = "comfort", name = "Comfort closing", module = ActuatorModule("Central electronics", "09", "70E", "778"),
        method = CodingMethod.LONG_CODING, did = 0x0600, byteIndex = 3, mask = 0x04,
        options = listOf(CodingOption(0, "Off"), CodingOption(1, "On")),
    )

    private class Rig(scope: CoroutineScope, recipe: CodingRecipe, data: List<Int>) {
        val ecu = SimUdsEcu(dids = mutableMapOf(0x0600 to data), writableDids = setOf(0x0600))
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "70E", "778", respond = ecu::respond)))
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        var expert = false
        var connected = true
        val audit = AuditStore(InMemoryKeyValueStore())
        val backups = CodingBackupStore(InMemoryKeyValueStore())
        val library = CodingLibrary(listOf(CodingPack("b", "Bundled", recipes = listOf(recipe))), InMemoryKeyValueStore())
        val controller = CodingController(
            scope, library, audit, backups,
            expertMode = { expert },
            environment = { if (connected) ActuatorEnvironment(session, CodingEngineTest.FakeProbe(), VehicleContext("Volkswagen", "Tiguan")) else null },
        )

        suspend fun idle() {
            while (controller.state.value.busy) yield()
        }
    }

    @Test
    fun readChooseConfirmWriteAndRollBack() = runBlocking<Unit> {
        val rig = Rig(this, closing, original)
        val c = rig.controller
        c.refresh()
        c.select("comfort")
        assertEquals("Comfort closing", c.state.value.selected?.recipe?.name)

        c.read()
        rig.idle()
        val reading = c.state.value.reading
        assertNotNull(c.state.value.readError, reading)
        assertEquals(0L, reading!!.value)
        assertEquals(1, c.state.value.backupsForSelected.size)

        c.setNewValue(2)
        assertNotNull(c.state.value.valueError)
        assertNull(c.state.value.diff)
        c.setNewValue(1)
        assertNull(c.state.value.valueError)
        assertEquals(listOf(3), c.state.value.diff!!.changedBytes)

        c.requestWrite()
        assertFalse("Expert mode is required", c.state.value.confirming)
        assertTrue(c.state.value.message!!.contains("Expert mode"))
        assertTrue(rig.adapter.writes.isEmpty())

        rig.expert = true
        c.requestWrite()
        assertTrue(c.state.value.confirming)
        c.confirmWrite()
        rig.idle()
        val result = c.state.value.lastResult!!
        assertEquals(result.message, CodingOutcome.VERIFIED, result.outcome)
        assertEquals(0x05, rig.ecu.dids.getValue(0x0600)[3])
        assertTrue(result.canRollback)
        assertNull("a verified write needs a fresh read before the next change", c.state.value.reading)

        // Confirming again without a new request does nothing.
        c.confirmWrite()
        assertEquals(1, rig.adapter.writes.count { it.payload[0] == 0x2E })

        c.rollbackLast()
        rig.idle()
        assertEquals(CodingOutcome.VERIFIED, c.state.value.lastResult!!.outcome)
        assertEquals(original, rig.ecu.dids.getValue(0x0600))
        assertEquals(listOf(AuditKind.CODING_ROLLBACK, AuditKind.CODING_WRITE), c.state.value.history.map { it.kind })
        assertEquals(BackupStatus.ROLLED_BACK, c.state.value.backups.single().status)
        assertTrue(rig.adapter.violations.isEmpty())
    }

    @Test
    fun rollbackFromBackupListNeedsConfirmationAndExpertMode() = runBlocking<Unit> {
        val rig = Rig(this, closing, original)
        val c = rig.controller
        rig.expert = true
        c.refresh()
        c.select("comfort")
        c.read()
        rig.idle()
        c.setNewValue(1)
        c.requestWrite()
        c.confirmWrite()
        rig.idle()
        val backupId = c.state.value.backups.single().id

        c.requestRollback(backupId)
        assertEquals(backupId, c.state.value.rollbackCandidate?.id)
        c.cancelRollback()
        assertNull(c.state.value.rollbackCandidate)

        rig.expert = false
        c.requestRollback(backupId)
        c.confirmRollback()
        assertTrue(c.state.value.message!!.contains("Expert mode"))
        assertEquals(0x05, rig.ecu.dids.getValue(0x0600)[3])

        rig.expert = true
        c.requestRollback(backupId)
        c.confirmRollback()
        rig.idle()
        assertEquals(original, rig.ecu.dids.getValue(0x0600))
    }

    @Test
    fun readNeedsConnectionAndReportsRefusals() = runBlocking<Unit> {
        val rig = Rig(this, closing.copy(did = 0x0601), original)
        val c = rig.controller
        c.refresh()
        c.select("comfort")
        rig.connected = false
        c.read()
        assertTrue(c.state.value.message!!.contains("Connect"))
        rig.connected = true
        c.read()
        rig.idle()
        assertNull(c.state.value.reading)
        assertTrue(c.state.value.readError!!.contains("0601"))
    }

    @Test
    fun editorValidatesAndSavesUserRecipes() = runBlocking<Unit> {
        val rig = Rig(this, closing, original)
        val c = rig.controller
        c.refresh()

        c.openEditor(null)
        assertTrue(c.state.value.editor!!.text.contains("my-recipe"))
        assertNull(c.state.value.editor!!.editingId)
        val decoded = CodingRecipeCodec.decode(c.state.value.editor!!.text)
        assertTrue("template is valid: ${decoded.errors}", decoded.isValid)

        c.updateEditor("""{"id":"x","name":"X","module":{"name":"M","requestId":"7E0","responseId":"7E8"},"method":"long_coding","byte":0}""")
        assertNull(c.saveEditor())
        assertTrue(c.state.value.editor!!.errors.toString(), c.state.value.editor!!.errors.any { it.startsWith("mask:") })

        c.updateEditor("{ not json")
        assertNull(c.saveEditor())
        assertTrue(c.state.value.editor!!.errors.single().startsWith("Not valid JSON"))

        // Editing a bundled recipe creates an unverified copy.
        c.openEditor("comfort")
        assertNull(c.state.value.editor!!.editingId)
        assertEquals("comfort-mine", c.saveEditor())
        assertNull(c.state.value.editor)
        val copy = c.state.value.entries.single { it.recipe.id == "comfort-mine" }
        assertTrue(copy.isUser)
        assertFalse(copy.recipe.verified)

        // Renaming a user recipe replaces it.
        c.openEditor("comfort-mine")
        assertEquals("comfort-mine", c.state.value.editor!!.editingId)
        c.updateEditor(c.state.value.editor!!.text.replace("\"comfort-mine\"", "\"comfort-renamed\""))
        assertEquals("comfort-renamed", c.saveEditor())
        assertEquals(listOf("comfort", "comfort-renamed"), c.state.value.entries.map { it.recipe.id }.sorted())

        val exported = CodingRecipeCodec.decode(c.exportJson())
        assertEquals(listOf("comfort-renamed"), exported.pack!!.recipes.map { it.id })
        c.deleteUserRecipe("comfort-renamed")
        assertEquals(listOf("comfort"), c.state.value.entries.map { it.recipe.id })
    }

    @Test
    fun importReportsErrorsOrAddsRecipes() = runBlocking<Unit> {
        val rig = Rig(this, closing, original)
        val c = rig.controller
        c.importJson("""{"format":"bod3-coding","id":"p","name":"P","recipes":[]}""")
        assertTrue(c.state.value.importErrors.any { it.contains("no recipes") })
        c.importJson(CodingRecipeCodec.encode(CodingPack("p", "P", recipes = listOf(closing.copy(id = "new-one")))))
        assertTrue(c.state.value.importErrors.isEmpty())
        assertTrue(c.state.value.message!!.contains("1 new"))
        assertNotNull(c.state.value.entries.firstOrNull { it.recipe.id == "new-one" })
    }

    @Test
    fun prettyPrinterKeepsJsonValid() {
        val json = CodingRecipeCodec.encodeRecipeJson(closing)
        val pretty = JsonPretty.format(json)
        assertTrue(pretty.contains("\n  \"id\": \"comfort\""))
        assertEquals(listOf(closing), CodingRecipeCodec.decode(pretty).pack!!.recipes)
        assertEquals("{ broken", JsonPretty.format("{ broken"))
    }
}
