package com.bod3.app.obd.kwp

import com.bod3.app.actuator.*
import com.bod3.app.audit.AuditStore
import com.bod3.app.coding.*
import com.bod3.app.datapack.CustomPid
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.*
import com.bod3.app.obd.testing.*
import com.bod3.app.obd.uds.*
import com.bod3.app.recipe.Hex
import java.io.IOException
import kotlinx.coroutines.*

/** Shared with a standalone compiler harness; no Android, wall-clock sleeps or real vehicle claims. */
object KwpScenarios {
    val link = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
        kLine = KLineConfig(KLineInit.FAST, 0x10))
    val target = EcuTarget.Physical("8010F1", "80F110", link = link)
    private val key = SecurityAccessSpec(1, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(1))
    private val probe = object : VehicleStateProbe {
        override suspend fun batteryVoltage() = 12.8
        override suspend fun engineRpm() = 0
        override suspend fun vehicleSpeedKmh() = 0
        override suspend fun readIdentification(target: EcuTarget.Physical, did: Int): String? = null
    }
    private class Bench {
        var now = 1000L
        val adapter = SimulatedKwpAdapter()
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500, clock = { now }, wait = { now += it })
        val client = KwpClient(session, target)
        fun grant(p: WritePurpose, id: List<Int>) = WriteGate.issue(p, target, true, { now },
            scope = WriteScope(id, 0x81, 1, 1))
    }
    private suspend inline fun <reified T : Throwable> fails(crossinline block: suspend () -> Unit) {
        val e = try { block(); null } catch (e: Throwable) { e }
        check(e is T) { "Expected ${T::class.simpleName}, got $e" }
    }

    fun framing() {
        for (size in listOf(1, 2, 63, 64, 255)) {
            val p = List(size) { it and 255 }
            val encoded = KLineFrames.encode(listOf(0x80, 0xF1, 0x10), p)
            check(KLineFrames.decode(Hex.format(encoded), listOf(0x80, 0xF1, 0x10))?.payload == p)
            check(KLineFrames.decode(Hex.format(encoded.dropLast(1)), listOf(0x80, 0xF1, 0x10)) == null)
            check(KLineFrames.decode(Hex.format(encoded.dropLast(1) + (encoded.last() xor 1)), listOf(0x80, 0xF1, 0x10)) == null)
            check(KLineFrames.decode(Hex.format(encoded), listOf(0x80, 0xF1, 0x11)) == null)
        }
        val iso = listOf(0x48, 0x6B, 0x10)
        val payload = listOf(0x61, 0x20, 0x11)
        val e = KLineFrames.encode(iso, payload, true)
        check(KLineFrames.decode(Hex.format(e), iso, true)?.payload == payload)
        check(KLineFrames.decode("NO DATA", iso, true) == null)
        check(KLineFrames.decode("8000", iso) == null)
        check(KLineFrames.decode("C1F11081" + "43", listOf(0x80, 0xF1, 0x10)) == null)
    }

    suspend fun reads(service: Int) {
        val b = Bench()
        val response = when (service) {
            0x81 -> b.client.startCommunication()
            0x1A -> b.client.readIdentification(0x90)
            0x21 -> b.client.readLocal(0x20)
            0x22 -> b.client.readIdentifier(0x1234)
            0x18 -> b.client.readDtcs(0xFF)
            0x19 -> b.session.request(target, listOf(0x19, 0x80))
            0x3E -> b.client.testerPresent()
            else -> error("Unknown scenario")
        }
        check(response.messages.single().payload.first() == service + 0x40)
        check(b.adapter.commands.any { it.startsWith("STIFI25,25,8110F181") })
        check("ATIIA10" in b.adapter.commands)
        check(b.adapter.commands.none { it == "ATI" || it == "STI" || it == "VTI" })
        check(b.ecuRequests().single().first() == service)
        b.adapter.ecu.pending = true
        check(b.client.readLocal(0x20).negativeResponses.single().payload == listOf(0x7F, 0x21, 0x78))
        b.adapter.ecu.pending = false
        check(b.client.readLocal(0x7F).negativeResponses.single().payload == listOf(0x7F, 0x21, 0x31))
    }
    private fun Bench.ecuRequests() = adapter.ecu.requests

    suspend fun writes(service: Int) {
        val b = Bench()
        b.client.readLocal(0x20) // establish the exact physical link with a read, never by a write
        val grant = b.grant(if (service in setOf(0x10, 0x27, 0x3B, 0x2E)) WritePurpose.CODING else WritePurpose.ACTUATOR_TEST,
            if (service == 0x2E) listOf(0x12, 0x34) else listOf(0x20))
        val out = when (service) {
            0x10 -> b.client.enterSession(0x81, grant)
            0x27 -> b.client.unlock(key, grant)
            0x30 -> b.client.inputOutput(0x20, 3, listOf(1), grant)
            0x31, 0x32, 0x33 -> b.client.routine(service, 0x20, emptyList(), grant)
            0x3B -> b.client.writeLocal(0x20, listOf(1, 2), grant)
            0x2E -> UdsWriteClient(b.session, grant).send(listOf(0x2E, 0x12, 0x34, 1))
            else -> error("Unknown service")
        }
        check(out.isPositive) { out.display }
        if (service == 0x27) check(b.adapter.ecu.unlocked)
        grant.revoke()
        fails<WriteNotPermittedException> { b.session.request(target, listOf(0x10, 0x81), grant = grant) }
    }

    suspend fun scopeAndClear() {
        val b = Bench()
        val grant = b.grant(WritePurpose.CODING, listOf(0x20))
        for (r in listOf(listOf(0x3B, 0x21, 1), listOf(0x2E, 0, 0x20, 1), listOf(0x27, 3), listOf(0x10, 2), listOf(0x11, 1))) {
            fails<IllegalArgumentException> { b.session.request(target, r, grant = grant) }
        }
        val other = target.copy(requestHeader = "8020F1", responseHeader = "80F120")
        fails<WriteNotPermittedException> { b.session.request(other, listOf(0x3B, 0x20, 1), grant = grant) }
        fails<ReadOnlyViolationException> { b.session.request(target, listOf(0x3B, 0x20, 1)) }
        fails<ReadOnlyViolationException> { b.session.request(target, listOf(0x14, 0xFF, 0xFF)) }
        check(b.adapter.commands.isEmpty())
        check(b.client.clear(ClearConfirmation.kwpClear(target)).messages.single().payload == listOf(0x54))
        check(!b.adapter.ecu.dtcs)
        val c = ClearConfirmation.kwpClear(target)
        b.client.clear(c)
        fails<ClearNotConfirmedException> { b.client.clear(c) }
        fails<IllegalArgumentException> { ClearConfirmation.udsClear(target) }
    }

    suspend fun initExpiryAndRejection() {
        val b = Bench()
        b.adapter.rejected += "STIP1X20"
        b.client.readLocal(0x20)
        check("STIP1X20" in b.adapter.commands && "STIP45" in b.adapter.commands)
        check(b.now >= 1055)
        b.now += 6000
        val before = b.adapter.commands.size
        check(!b.session.keepAliveCurrent(target))
        check(b.adapter.commands.size == before) // no reconnect or bus initialization by keep-alive
        fails<IOException> { b.session.request(target, listOf(0x3B, 0x20, 1), grant = b.grant(WritePurpose.CODING, listOf(0x20))) }
        check(b.adapter.commands.size == before)
        b.client.readLocal(0x20)
        check(b.adapter.commands.count { it.startsWith("STIFI") } == 2)
        b.adapter.corruptChecksum = true
        check(b.client.readLocal(0x20).isEmpty)
        b.adapter.corruptChecksum = false; b.adapter.wrongEcu = true
        check(b.client.readLocal(0x20).isEmpty)
        val rejected = Bench()
        rejected.adapter.rejected += KLineAdapter.initCommand(target).replace(" ", "").uppercase()
        fails<ObdRequestException> { rejected.client.readLocal(0x20) }
        check(rejected.ecuRequests().isEmpty())
    }

    suspend fun fiveBaudAndIso9141() {
        for (protocol in listOf(ObdProtocol.ISO_9141_2, ObdProtocol.ISO_14230_4_KWP_SLOW)) {
            val t = target.copy(link = link.copy(protocol = protocol, kLine = link.kLine!!.copy(init = KLineInit.FIVE_BAUD)))
            val a = SimulatedKwpAdapter(iso9141 = protocol == ObdProtocol.ISO_9141_2)
            var clock = 0L
            val s = Session(a, protocol, clock = { clock }, wait = { clock += it })
            check(KwpClient(s, t).readLocal(0x20).messages.single().payload.take(2) == listOf(0x61, 0x20))
            check("ATSI" in a.commands && a.commands.none { it.startsWith("STIFI") })
        }
    }

    suspend fun kwpOverCan() {
        val ecu = SimKwpEcu()
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", respond = ecu::respond)))
        val t = EcuTarget.Physical("7E0", "7E8", link = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500, DiagnosticDialect.KWP2000))
        val s = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val c = KwpClient(s, t)
        check(c.readLocal(0x20).messages.single().payload == listOf(0x61, 0x20, 0, 0x55))
        val grant = WriteGate.issue(WritePurpose.CODING, t, true, scope = WriteScope(listOf(0x20)))
        check(c.writeLocal(0x20, listOf(1, 0x55), grant).isPositive)
        check(adapter.commands.none { it.startsWith("STIFI") || it == "ATSI" })
    }

    suspend fun lease(): Unit = coroutineScope {
        val b = Bench()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = launch {
            b.session.withEcuOperation(target) {
                b.client.readLocal(0x20)
                entered.complete(Unit); release.await()
                fails<IllegalArgumentException> { b.session.request(EcuTarget.Broadcast, 1, 0x0C) }
                b.client.testerPresent()
            }
        }
        entered.await()
        val queued = launch { b.session.withEcuOperation(target) { b.client.readIdentification(0x90) } }
        yield()
        check(b.ecuRequests().size == 1)
        release.complete(Unit); owner.join(); queued.join()
        check(b.ecuRequests().map { it[0] } == listOf(0x21, 0x3E, 0x1A))
        fails<CancellationException> {
            b.session.withEcuOperation(target) { throw CancellationException("synthetic cancellation") }
        }
        b.client.readLocal(0x20) // lock released even on cancellation
    }

    suspend fun coding() {
        val b = Bench(); val store = CodingBackupStore(InMemoryKeyValueStore()); val audit = AuditStore(InMemoryKeyValueStore())
        val module = ActuatorModule("Synthetic body", requestId = "8010F1", responseId = "80F110", link = link)
        val recipe = CodingRecipe("synthetic-local", "Synthetic setting", module = module, method = CodingMethod.DID_VALUE,
            did = 0x20, byteIndex = 0, options = listOf(CodingOption(0, "Off"), CodingOption(1, "On")),
            session = 0x81, security = key, readService = 0x21, writeService = 0x3B, sourceRecords = listOf("synthetic:row1"))
        val engine = CodingEngine(b.session, probe, { true }, audit, store, { b.now })
        val reading = (engine.read(recipe) as ReadResult.Ready).reading
        check(reading.data == listOf(0, 0x55))
        val reloaded = store.get(reading.backup.id)!!
        check(reloaded.module.link == link && reloaded.readService == 0x21 && reloaded.writeService == 0x3B)
        check(reloaded.sourceRecords == recipe.sourceRecords)
        check(engine.diff(reading, 1).changedBytes == listOf(0))
        val confirmation = CodingConfirmation.forWrite(reading, 1) { b.now }
        check(engine.write(reading, 1, confirmation).outcome == CodingOutcome.VERIFIED)
        check(b.adapter.ecu.locals[0x20] == listOf(1, 0x55))
        check(b.adapter.ecu.session == 1 && audit.records().single().success)
        check(engine.write(reading, 1, confirmation).outcome == CodingOutcome.REFUSED)
        check(engine.rollback(reloaded, CodingConfirmation.forRollback(reloaded) { b.now }).outcome == CodingOutcome.VERIFIED)
        check(b.adapter.ecu.locals[0x20] == listOf(0, 0x55))
        check(b.ecuRequests().none { it.first() == 0x22 || it.first() == 0x2E })
        val again = (engine.read(recipe) as ReadResult.Ready).reading
        b.adapter.ecu.locals[0x20] = listOf(1, 0x55)
        check(engine.write(again, 1, CodingConfirmation.forWrite(again, 1) { b.now }).outcome == CodingOutcome.ABORTED)
    }

    suspend fun actuator() {
        for (localRoutine in listOf(false, true)) {
            val b = Bench()
            val recipe = ActuatorRecipe("synthetic-output", "Synthetic output", "Not vehicle data",
                ActuatorModule("Synthetic ECU", requestId = "8010F1", responseId = "80F110", link = link),
                session = 0x81, start = if (localRoutine) listOf(0x31, 0x20) else listOf(0x30, 0x20, 3, 1),
                stop = if (localRoutine) listOf(0x32, 0x20) else listOf(0x30, 0x20, 0),
                status = if (localRoutine) listOf(0x33, 0x20) else listOf(0x21, 0x20),
                durationSeconds = 1, safetyNotes = listOf("Synthetic test only"))
            check(recipe.problems().isEmpty()) { recipe.problems().joinToString() }
            b.client.readLocal(0x20)
            val runner = ActuatorRunner(b.session, probe, { true }, clock = { b.now }, sleep = { b.now += it })
            val r = runner.run(recipe, ActuatorConfirmation.forRecipe(recipe) { b.now })
            check(r.outcome == RunOutcome.COMPLETED && r.stopConfirmed) { r.toString() }
            check(!b.adapter.ecu.output && !b.adapter.ecu.routine && b.adapter.ecu.session == 1)
        }
    }

    suspend fun actuatorCancellation() = coroutineScope {
        val b = Bench(); val audit = AuditStore(InMemoryKeyValueStore())
        val recipe = ActuatorRecipe("synthetic-cancel", "Synthetic cancellation", "Not vehicle data",
            ActuatorModule("Synthetic ECU", requestId = "8010F1", responseId = "80F110", link = link),
            session = 0x81, start = listOf(0x30, 0x20, 3, 1), stop = listOf(0x30, 0x20, 0),
            durationSeconds = 2, safetyNotes = listOf("Synthetic test only"))
        b.client.readLocal(0x20)
        var run: Deferred<RunResult>? = null
        val runner = ActuatorRunner(b.session, probe, { true }, audit, clock = { b.now }, sleep = {
            b.now += it
            if (b.now > 1500) run!!.cancel()
        })
        run = async(start = CoroutineStart.LAZY) { runner.run(recipe, ActuatorConfirmation.forRecipe(recipe) { b.now }) }
        fails<CancellationException> { run.await() }
        check(!b.adapter.ecu.output && b.adapter.ecu.session == 1)
        check(audit.records().single().details["Stop reason"] == "Cancelled")
    }

    fun formulaOffset() {
        val p = CustomPid("synthetic", "Synthetic", "2120", "(A*256+B)/4", header = "8010F1", responseHeader = "80F110",
            link = link, dataOffset = 2, sourceRecords = listOf("synthetic:formula"))
        check(p.problems().isEmpty())
        check(p.decode(listOf(0x61, 0x20, 0xEE, 0xDD, 0x10, 0x00)) == 1024.0)
        check(p.decode(listOf(0x61, 0x21, 0, 0, 0x10, 0)) == null)
        check(p.decode(listOf(0x61, 0x20, 1)) == null)
    }
}
