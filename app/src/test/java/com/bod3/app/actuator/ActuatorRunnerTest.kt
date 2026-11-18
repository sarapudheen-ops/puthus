package com.bod3.app.actuator

import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimUdsEcu
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActuatorRunnerTest {

    class FakeProbe(
        var volts: Double? = 12.6,
        var rpm: Int? = 0,
        var speed: Int? = 0,
        var ids: Map<Int, String> = emptyMap(),
    ) : VehicleStateProbe {
        override suspend fun batteryVoltage() = volts
        override suspend fun engineRpm() = rpm
        override suspend fun vehicleSpeedKmh() = speed
        override suspend fun readIdentification(target: EcuTarget.Physical, did: Int) = ids[did]
    }

    private val key = SecurityAccessSpec(0x03, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x00, 0x01, 0x23, 0x45))

    private val horn = ActuatorRecipe(
        id = "bcm-horn", name = "Horn", description = "",
        module = ActuatorModule("Central electronics", "09", "70E", "778"),
        start = listOf(0x2F, 0x05, 0x10, 0x03, 0x01), stop = listOf(0x2F, 0x05, 0x10, 0x00),
        durationSeconds = 5, safetyNotes = listOf("Loud"),
    )

    private val routine = horn.copy(
        id = "cluster-test", start = listOf(0x31, 0x01, 0x03, 0x10), stop = listOf(0x31, 0x02, 0x03, 0x10),
        status = listOf(0x31, 0x03, 0x03, 0x10), durationSeconds = 4,
    )

    private inner class Bench(
        val ecu: SimUdsEcu = SimUdsEcu(ioDids = setOf(0x0510), routines = setOf(0x0310)),
        var expert: Boolean = true,
        val probe: FakeProbe = FakeProbe(),
    ) {
        var now = 1_000_000L
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "70E", "778", respond = ecu::respond)))
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val audit = AuditStore(InMemoryKeyValueStore())
        val phases = mutableListOf<RunPhase>()
        var onSleep: (Long) -> Unit = {}
        val runner = ActuatorRunner(session, probe, { expert }, audit, { now }, { ms -> now += ms; onSleep(now) }, { "run-${audit.records().size + 1}" })

        fun run(
            recipe: ActuatorRecipe,
            control: RunControl = RunControl(),
            confirmation: ActuatorConfirmation = ActuatorConfirmation.forRecipe(recipe) { now },
            vehicle: VehicleContext = VehicleContext("Volkswagen", "Tiguan", "WVGZZZ5NZJW000001"),
            override: Boolean = false,
            onPhase: (RunPhase) -> Unit = {},
        ): RunResult = runBlocking { runner.run(recipe, confirmation, control, vehicle, override) { phases += it; onPhase(it) } }

        /** Every request that reached the module (writes and reads, e.g. tester present). */
        fun sent(): List<List<Int>> = adapter.requests.filter { it.header == "70E" }.map { it.payload }
    }

    @Test
    fun ioControlRunsUntilTimeoutThenStopsAndReturnsToDefaultSession() {
        val bench = Bench()
        var activeWhileRunning = false
        val result = bench.run(horn, onPhase = { if (it is RunPhase.Running) activeWhileRunning = bench.ecu.activeOutputs.containsKey(0x0510) })
        assertEquals(result.message, RunOutcome.COMPLETED, result.outcome)
        assertEquals(StopReason.TIMEOUT, result.stopReason)
        assertTrue(result.stopConfirmed)
        assertTrue(activeWhileRunning)
        assertTrue(bench.ecu.activeOutputs.isEmpty())
        assertEquals(0x01, bench.ecu.session)
        val sent = bench.sent()
        assertEquals(listOf(0x10, 0x03), sent.first())
        assertEquals(horn.start, sent[1])
        assertEquals(listOf(horn.stop, listOf(0x10, 0x01)), sent.takeLast(2))
        // Tester present keeps the session alive at least every 2 s while running.
        assertTrue(sent.count { it == listOf(0x3E, 0x00) } >= 2)
        assertTrue(bench.adapter.violations.isEmpty())
        assertTrue(bench.phases.any { it is RunPhase.Running })
        assertTrue(bench.phases.last() is RunPhase.Finished)
        // Ran for the recipe's duration, not longer.
        val record = bench.audit.records().single()
        assertTrue(record.success)
        assertTrue(record.endedAtMs - record.startedAtMs in 5_000L..5_500L)
        assertTrue(record.steps.any { it.label == "Start" && it.request == "2F 05 10 03 01" })
    }

    @Test
    fun userStopIsHonouredImmediately() {
        val bench = Bench()
        val control = RunControl()
        bench.onSleep = { now -> if (now - 1_000_000L >= 1_500) control.requestStop() }
        val result = bench.run(horn, control)
        assertEquals(RunOutcome.COMPLETED, result.outcome)
        assertEquals(StopReason.USER, result.stopReason)
        val record = bench.audit.records().single()
        assertTrue(record.endedAtMs - record.startedAtMs < 2_000)
        assertEquals(horn.stop, bench.sent()[bench.sent().size - 2])
    }

    @Test
    fun stopBeforeStartSendsNoStartRequest() {
        val bench = Bench()
        val control = RunControl().apply { requestStop() }
        val result = bench.run(horn, control)
        assertEquals(RunOutcome.REFUSED, result.outcome)
        assertTrue(bench.sent().isEmpty())
    }

    @Test
    fun routinePollsStatusAndStops() {
        val bench = Bench()
        val result = bench.run(routine)
        assertEquals(result.message, RunOutcome.COMPLETED, result.outcome)
        assertTrue(bench.sent().count { it == routine.status } >= 3)
        assertFalse(bench.sent().contains(listOf(0x3E, 0x00)))
        assertTrue(bench.ecu.runningRoutines.isEmpty())
        assertTrue(result.message.contains("last status 01"))
    }

    @Test
    fun securityAccessIsPerformedBeforeStart() {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510), security = mapOf(0x03 to key), requiredLevel = 0x03)
        val bench = Bench(ecu)
        val result = bench.run(horn.copy(security = key))
        assertEquals(result.message, RunOutcome.COMPLETED, result.outcome)
        val sent = bench.sent()
        assertEquals(listOf(0x27, 0x03), sent[1])
        assertEquals(listOf(0x27, 0x04, 0x12, 0x35, 0x79, 0xBD), sent[2])
        assertEquals(horn.start, sent[3])
    }

    @Test
    fun wrongKeyAbortsWithoutStarting() {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510), security = mapOf(0x03 to key), requiredLevel = 0x03)
        val bench = Bench(ecu)
        val result = bench.run(horn.copy(security = key.copy(constant = listOf(9, 9, 9, 9))))
        assertEquals(RunOutcome.FAILED, result.outcome)
        assertTrue(result.message, result.message.contains("NRC 0x35"))
        assertFalse(bench.sent().contains(horn.start))
        assertFalse(bench.sent().contains(horn.stop))
        assertEquals(listOf(0x10, 0x01), bench.sent().last())
        assertFalse(bench.audit.records().single().success)
    }

    @Test
    fun negativeResponseToStartIsReportedAndCleanedUp() {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510)).apply { nrcFor[0x2F] = 0x22; nrcTimes = 1 }
        val bench = Bench(ecu)
        val result = bench.run(horn)
        assertEquals(RunOutcome.START_REJECTED, result.outcome)
        assertTrue(result.message, result.message.contains("conditions not correct"))
        // The stop is still sent (harmless) and the session is closed.
        assertEquals(listOf(horn.stop, listOf(0x10, 0x01)), bench.sent().takeLast(2))
    }

    @Test
    fun unknownIdentifierIsRejected() {
        val bench = Bench(SimUdsEcu(ioDids = emptySet()))
        val result = bench.run(horn)
        assertEquals(RunOutcome.START_REJECTED, result.outcome)
        assertTrue(result.message.contains("request out of range"))
    }

    @Test
    fun responsePendingThenPositiveStartsNormally() {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510)).apply { pendingFirst += 0x2F }
        val result = Bench(ecu).run(horn)
        assertEquals(result.message, RunOutcome.COMPLETED, result.outcome)
    }

    @Test
    fun connectionLossMidRunStopsAndReports() {
        val bench = Bench()
        bench.onSleep = { now -> if (now - 1_000_000L >= 2_500) bench.adapter.disconnected = true }
        val result = bench.run(horn)
        assertEquals(RunOutcome.CONNECTION_LOST, result.outcome)
        assertEquals(StopReason.CONNECTION_LOST, result.stopReason)
        assertFalse(result.stopConfirmed)
        assertTrue(result.message, result.message.contains("diagnostic session"))
        val record = bench.audit.records().single()
        assertFalse(record.success)
        assertTrue(record.steps.any { it.label == "Stop" && !it.ok })
        assertEquals("NO", record.details["Stop confirmed"])
    }

    @Test
    fun silentModuleTriggersAutoStop() {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510))
        val bench = Bench(ecu)
        bench.onSleep = { now -> if (now - 1_000_000L >= 1_000) ecu.silentFor += 0x3E }
        val result = bench.run(horn)
        assertEquals(RunOutcome.FAILED, result.outcome)
        assertEquals(StopReason.MODULE_SILENT, result.stopReason)
        assertTrue(result.stopConfirmed) // the stop itself was still acknowledged
        assertTrue(ecu.activeOutputs.isEmpty())
    }

    @Test
    fun cancellationStillSendsStop() {
        val bench = Bench()
        runBlocking {
            var cancel: () -> Unit = {}
            bench.onSleep = { now -> if (now - 1_000_000L >= 1_000) cancel() }
            val job = async(start = CoroutineStart.LAZY) {
                bench.runner.run(horn, ActuatorConfirmation.forRecipe(horn) { bench.now }, RunControl())
            }
            cancel = { job.cancel() }
            try {
                job.await()
                fail("expected cancellation")
            } catch (e: CancellationException) {
                yield()
            }
        }
        assertEquals(listOf(horn.stop, listOf(0x10, 0x01)), bench.sent().takeLast(2))
        assertTrue(bench.ecu.activeOutputs.isEmpty())
        assertEquals("Cancelled", bench.audit.records().single().details["Stop reason"])
    }

    @Test
    fun refusedOutsideExpertMode() {
        val bench = Bench(expert = false)
        val result = bench.run(horn)
        assertEquals(RunOutcome.REFUSED, result.outcome)
        assertTrue(result.message.contains("Expert mode"))
        assertTrue(bench.adapter.requests.isEmpty())
        assertTrue(bench.adapter.writes.isEmpty())
        assertFalse(bench.audit.records().single().success)
    }

    @Test
    fun preconditionsBlockTheRun() {
        for ((probe, text) in listOf(
            FakeProbe(volts = 11.6) to "Battery voltage",
            FakeProbe(rpm = 800) to "switch the engine off",
            FakeProbe(rpm = null) to "Ignition",
            FakeProbe(speed = 12) to "stop the vehicle",
            FakeProbe(speed = null) to "Vehicle speed",
            FakeProbe(volts = null) to "No reading",
        )) {
            val bench = Bench(probe = probe)
            val result = bench.run(horn)
            assertEquals(text, RunOutcome.REFUSED, result.outcome)
            assertTrue("$text / ${result.message}", result.message.contains(text))
            assertTrue(bench.adapter.writes.isEmpty())
        }
        // A recipe that needs the engine running refuses when it is off and runs when it is on.
        val running = horn.copy(preconditions = ActuatorPreconditions(engine = EngineRequirement.RUNNING))
        assertEquals(RunOutcome.REFUSED, Bench().run(running).outcome)
        assertEquals(RunOutcome.COMPLETED, Bench(probe = FakeProbe(rpm = 750, volts = 14.1)).run(running).outcome)
    }

    @Test
    fun applicabilityMismatchNeedsOverride() {
        val restricted = horn.copy(applicability = Applicability(makes = listOf("Volkswagen"), partNumbers = listOf("5Q0937084")))
        val probe = FakeProbe(ids = mapOf(0xF187 to "5Q0 937 085 AB"))
        val refused = Bench(probe = probe).run(restricted)
        assertEquals(RunOutcome.REFUSED, refused.outcome)
        assertTrue(refused.message.contains("part number"))
        assertTrue(refused.preconditions!!.blocking.isEmpty())

        val overridden = Bench(probe = probe)
        val result = overridden.run(restricted, override = true)
        assertEquals(RunOutcome.COMPLETED, result.outcome)
        assertEquals("yes", overridden.audit.records().single().details["Applicability override"])

        val matching = Bench(probe = FakeProbe(ids = mapOf(0xF187 to "5Q0 937 084 AB"))).run(restricted)
        assertEquals(RunOutcome.COMPLETED, matching.outcome)
        val otherMake = Bench(probe = FakeProbe(ids = mapOf(0xF187 to "5Q0937084"))).run(restricted, vehicle = VehicleContext("Ford", "Focus"))
        assertEquals(RunOutcome.REFUSED, otherMake.outcome)
    }

    @Test
    fun confirmationIsSingleUseAndBoundToTheRecipe() {
        val bench = Bench()
        val confirmation = ActuatorConfirmation.forRecipe(horn) { bench.now }
        assertEquals(RunOutcome.COMPLETED, bench.run(horn, confirmation = confirmation).outcome)
        val again = bench.run(horn, confirmation = confirmation)
        assertEquals(RunOutcome.REFUSED, again.outcome)
        assertTrue(again.message.contains("already used"))

        val other = bench.run(horn.copy(durationSeconds = 9), confirmation = ActuatorConfirmation.forRecipe(horn) { bench.now })
        assertTrue(other.message.contains("different test"))

        val stale = ActuatorConfirmation.forRecipe(horn) { bench.now }
        bench.now += ActuatorConfirmation.VALID_FOR_MS + 1
        assertTrue(bench.run(horn, confirmation = stale).message.contains("expired"))
        assertEquals(4, bench.audit.records().size)
    }

    @Test
    fun invalidRecipeIsNeverSent() {
        val bench = Bench()
        val bad = horn.copy(start = listOf(0x10, 0x02))
        val result = bench.run(bad)
        assertEquals(RunOutcome.REFUSED, result.outcome)
        assertTrue(bench.adapter.requests.isEmpty())
    }

    @Test
    fun sessionStateProbeReadsObdValues() = runBlocking<Unit> {
        val engine = SimModule('6', "7E0", "7E8", obd = true) { r ->
            when (r) {
                listOf(0x01, 0x0C) -> listOf(listOf(0x41, 0x0C, 0x0C, 0x80))
                listOf(0x01, 0x0D) -> listOf(listOf(0x41, 0x0D, 0x00))
                listOf(0x22, 0xF1, 0x87) -> listOf(listOf(0x62, 0xF1, 0x87) + "04E906016".map { it.code })
                else -> emptyList()
            }
        }
        val adapter = SimulatedCanAdapter(listOf(engine))
        val probe = SessionStateProbe(Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500), adapter)
        assertEquals(800, probe.engineRpm())
        assertEquals(0, probe.vehicleSpeedKmh())
        assertEquals("04E906016", probe.readIdentification(EcuTarget.Physical("7E0", "7E8"), 0xF187))
        assertTrue(adapter.violations.isEmpty())
        assertTrue(adapter.writes.isEmpty())
    }
}
