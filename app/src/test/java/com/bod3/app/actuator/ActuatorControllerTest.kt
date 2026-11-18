package com.bod3.app.actuator

import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimUdsEcu
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActuatorControllerTest {

    private val horn = ActuatorRecipe(
        id = "bcm-horn", name = "Horn", description = "",
        module = ActuatorModule("Central electronics", "09", "70E", "778"),
        start = listOf(0x2F, 0x05, 0x10, 0x03, 0x01), stop = listOf(0x2F, 0x05, 0x10, 0x00),
        durationSeconds = 1, pollIntervalMs = 200, safetyNotes = listOf("Loud"),
    )

    @Test
    fun flowFromSelectionToFinishedRun() = runBlocking<Unit> {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510))
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "70E", "778", respond = ecu::respond)))
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        var expert = false
        var connected = true
        val audit = AuditStore(InMemoryKeyValueStore())
        val controller = ActuatorController(
            this, ActuatorLibrary(listOf(ActuatorPack("b", "B", recipes = listOf(horn))), InMemoryKeyValueStore()), audit,
            expertMode = { expert },
            environment = { if (connected) ActuatorEnvironment(session, ActuatorRunnerTest.FakeProbe(), VehicleContext("Volkswagen", "Tiguan")) else null },
        )
        controller.refresh()
        controller.select("bcm-horn")
        assertEquals("Horn", controller.state.value.selected?.recipe?.name)

        controller.requestStart()
        assertFalse(controller.state.value.confirming)
        assertTrue(controller.state.value.message!!.contains("Expert mode"))

        expert = true
        connected = false
        controller.requestStart()
        assertTrue(controller.state.value.message!!.contains("Connect"))

        connected = true
        controller.checkPreconditions()
        controller.state.value.let { assertTrue(it.checking || it.preconditions != null) }
        while (controller.state.value.checking) kotlinx.coroutines.yield()
        assertTrue(controller.state.value.preconditions!!.passed(false))

        controller.requestStart()
        assertTrue(controller.state.value.confirming)
        controller.confirmAndStart()
        assertTrue(controller.state.value.running)
        while (controller.state.value.running) kotlinx.coroutines.delay(20)
        val result = controller.state.value.lastResult
        assertNotNull(result)
        assertEquals(result!!.message, RunOutcome.COMPLETED, result.outcome)
        assertEquals(1, controller.state.value.history.size)
        assertTrue(controller.auditText().contains("2F 05 10 03 01"))
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun importShowsErrorsOrAddsRecipes() = runBlocking<Unit> {
        val library = ActuatorLibrary(emptyList(), InMemoryKeyValueStore())
        val controller = ActuatorController(this, library, AuditStore(InMemoryKeyValueStore()), { true }, { null })
        controller.importJson("""{"format":"bod3-actuators","id":"x","name":"X","recipes":[{"id":"a"}]}""")
        assertTrue(controller.state.value.importErrors.any { it.contains("recipes[0].name: missing") })

        controller.importJson(ActuatorRecipeCodec.encode(ActuatorPack("x", "X", recipes = listOf(horn))))
        assertTrue(controller.state.value.importErrors.isEmpty())
        assertTrue(controller.state.value.message!!.contains("1 new"))
        assertEquals(listOf("bcm-horn"), controller.state.value.entries.map { it.recipe.id })
        assertEquals(listOf("bcm-horn"), ActuatorRecipeCodec.decodeOrThrow(controller.exportJson()).recipes.map { it.id })

        controller.select("bcm-horn")
        controller.deleteUserRecipe("bcm-horn")
        assertNull(controller.state.value.selectedId)
        assertTrue(controller.state.value.entries.isEmpty())
    }
}
