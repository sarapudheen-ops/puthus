package com.bod3.app.bringup

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.ActuatorRecipe
import com.bod3.app.catalog.CatalogData
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.diagnostics.*
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.elm.RecordingExecutor
import com.bod3.app.obd.kwp.*
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.*
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedBringUpAdapter
import com.bod3.app.obd.testing.SimulatedBringUpAdapter.Mode
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.packs.*
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.profile.JsonWriter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** ALL ECU definitions, addresses, serials, timings and formulas below are SYNTHETIC. No real ECU. */
object BringUpScenarios {
    val selected = BringUpScope(listOf("make", "model", "generation", "variant"), "system", "Garage car 1")
    fun candidate(baud: Int = 10400, support: Int = 1, runtimeChange: (BringUpRuntime) -> BringUpRuntime = { it }): BringUpCandidate {
        val sources = listOf("synthetic:entry:$baud")
        val link = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
            kLine = KLineConfig(KLineInit.FAST, 0x10, baud = baud))
        val id = DiagnosticRead("serial", "Synthetic ECU serial", "1A90", "5A90", minBytes = 1, maxBytes = 64, sourceRecords = listOf("synthetic:serial"))
        val dtc = DiagnosticRead("dtcs", "Synthetic DTC records", "18FFFFFF", "58", minBytes = 1, maxBytes = 64, sourceRecords = listOf("synthetic:dtcs"))
        val ecu = EcuDefinition(name = "Synthetic ECU", requestId = "8010F1", responseId = "80F110", protocol = EcuProtocol.KWP,
            link = link, sourceRecords = sources, diagnostics = EcuReadDefinitions(listOf(IdentificationRead(id)),
                FaultRead(dtc, FaultLayout(3, 0, 2, statusOffset = 2, countBytes = 1))))
        val pid = CustomPid("live", "Synthetic value", "2120", "A", header = ecu.requestId, responseHeader = ecu.responseId,
            link = link, sourceRecords = listOf("synthetic:live"))
        val test = ActuatorRecipe("test", "Synthetic output", "Not vehicle data", ActuatorModule("Synthetic ECU", requestId = ecu.requestId,
            responseId = ecu.responseId, link = link), start = listOf(0x30, 0x20, 3, 1), stop = listOf(0x30, 0x20, 0),
            safetyNotes = listOf("Synthetic only"), sourceRecords = listOf("synthetic:test"), verified = true)
        val runtime = runtimeChange(BringUpRuntime(CatalogData(ecus = listOf(ecu), pids = listOf(pid), actuators = listOf(test)), "serial", "live", identityKind = EcuIdentityKind.ECU_SERIAL))
        return BringUpCandidate("entry:$baud", "Synthetic $baud", sources, List(support) { "synthetic:support:$baud:$it" },
            mapOf("notice" to "Synthetic, not recovered Toyota mappings"), emptyList(), runtime, pinned = true)
    }
    private class Bench(modes: Map<Int, Mode> = mapOf(10400 to Mode.WORKING)) {
        var now = 1000000L
        val adapter = SimulatedBringUpAdapter(modes)
        val recorder = RecordingExecutor(adapter, clock = { now++ }, maxEntries = 1)
        val session = Session(recorder, ObdProtocol.ISO_15765_4_CAN_11_500, clock = { now }, wait = { now += it })
        fun engine(maxEntries: Int = BringUpEngine.MAX_EXCHANGES, maxBytes: Int = BringUpEngine.MAX_LOG_BYTES) =
            BringUpEngine(session, recorder, clock = { now++ }, maxExchanges = maxEntries, maxLogBytes = maxBytes)
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (e: IllegalArgumentException) { rejected = true } catch (e: IllegalStateException) { rejected = true }
        check(rejected) { "Invalid or unsafe bring-up input was accepted" }
    }
    private suspend fun temporary(block: suspend (File) -> Unit) {
        val folder = Files.createTempDirectory("bring-up-test").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }

    suspend fun working() {
        val b = Bench(); b.recorder.startRecording()
        val report = b.engine().run(selected, listOf(candidate()))
        check(report.outcome == BringUpOutcome.WORKING && report.logComplete && report.identity != null)
        check(report.attempts.single().probes.map { it.role } == ProbeRole.entries)
        check(b.adapter.requests.map { it.bytes } == listOf(listOf(0x1A, 0x90), listOf(0x21, 0x20), listOf(0x18, 0xFF, 0xFF, 0xFF)))
        check(b.adapter.commands.all { it.second <= 6000 })
        check(b.adapter.commands.filter { !it.first.startsWith("AT") && !it.first.startsWith("ST") }.all { it.second == 1000L })
        check(report.exchanges.size > 20 && b.recorder.stopRecording().size == 1) // Full evidence does NOT use the rolling log.
        check(report.exchanges.any { it.rawResponse.endsWith("\r") })
        report.validate()
    }
    suspend fun modeFails(mode: Mode) {
        val b = Bench(mapOf(10400 to mode)); val r = b.engine().run(selected, listOf(candidate()))
        check(r.outcome == BringUpOutcome.NO_MATCH && r.workingCandidate == null && r.attempts.single().outcome == AttemptOutcome.FAILED)
        check(r.exchanges.isNotEmpty() && r.logComplete)
        check(b.adapter.requests.size == when (mode) { Mode.SHORT_LIVE -> 2; Mode.BAD_DTC -> 3; else -> 1 })
        r.validate()
    }
    suspend fun rankAndStop() {
        val b = Bench(mapOf(9600 to Mode.SILENT, 10400 to Mode.WRONG_FRAMING, 19200 to Mode.WRONG_CHECKSUM, 38400 to Mode.WORKING, 57600 to Mode.WORKING))
        val candidates = listOf(candidate(57600, 1), candidate(19200, 3), candidate(10400, 4), candidate(38400, 2), candidate(9600, 5))
        val r = b.engine().run(selected, candidates)
        check(r.candidates.map { it.supportCount } == listOf(5, 4, 3, 2, 1))
        check(r.attempts.map { it.candidateId } == listOf("entry:9600", "entry:10400", "entry:19200", "entry:38400"))
        check(r.workingCandidate?.id == "entry:38400" && b.adapter.requests.none { it.baud == 57600 })
        check(b.adapter.requests.none { it.bytes.first() in setOf(0x04, 0x14, 0x10, 0x27, 0x30, 0x31, 0x3B, 0x2E, 0x2F) })
    }
    suspend fun manualSelection() {
        val b = Bench(mapOf(9600 to Mode.SILENT, 10400 to Mode.WORKING))
        val r = b.engine().run(selected, listOf(candidate(9600, 10), candidate()), "entry:10400")
        check(r.manualCandidateId == "entry:10400" && r.attempts.single().outcome == AttemptOutcome.WORKING)
        check(b.adapter.requests.all { it.baud == 10400 })
        rejects { b.engine().run(selected, listOf(candidate()), "another-system-entry") }
    }
    suspend fun blockedNoIo() {
        val c = BringUpCandidate("raw", "Untranslated recovered record", listOf("synthetic:raw"), listOf("synthetic:raw"),
            mapOf("baudRaw" to "10416", "addresses" to "unmapped"), listOf("Unresolved timing and read definitions"), null, true)
        val b = Bench(); val r = b.engine().run(selected, listOf(c))
        check(b.adapter.commands.isEmpty() && r.attempts.single().outcome == AttemptOutcome.BLOCKED)
        check(BringUpCodec.readReport(BringUpCodec.reportText(r)).candidates.single().metadata["baudRaw"] == "10416")
    }
    suspend fun strictSetup() {
        val b = Bench(); b.adapter.rejected += "STPBR10400"
        val r = b.engine().run(selected, listOf(candidate()))
        check(r.outcome == BringUpOutcome.NO_MATCH && b.adapter.requests.isEmpty())
        check(r.exchanges.last().rawResponse.contains('?'))
        check(b.adapter.commands.none { it.first.startsWith("STIFI") })
    }
    suspend fun emptyDtcIsValid() {
        val b = Bench(); b.adapter.emptyDtcs = true
        check(b.engine().run(selected, listOf(candidate())).outcome == BringUpOutcome.WORKING)
    }
    suspend fun fiveBaudInitHasOwnBudget() {
        val b = Bench()
        val c = candidate(runtimeChange = { r ->
            val link = r.ecu.link!!.copy(protocol = ObdProtocol.ISO_14230_4_KWP_SLOW,
                kLine = r.ecu.link!!.kLine!!.copy(init = KLineInit.FIVE_BAUD))
            r.copy(data = r.data.copy(ecus = listOf(r.ecu.copy(link = link)), pids = listOf(r.live.copy(link = link)),
                actuators = r.data.actuators.map { it.copy(module = it.module.copy(link = link)) }))
        })
        b.adapter.beforeInit = { delay(1700) } // Longer than a diagnostic read's outer budget.
        val report = b.engine().run(selected, listOf(c))
        check(report.outcome == BringUpOutcome.WORKING)
        check(b.adapter.commands.single { it.first == "ATSI" }.second == 6000L)
        check(b.adapter.commands.filter { it.first in setOf("1A90", "2120", "18FFFFFF") }.all { it.second == 1000L })
    }
    suspend fun interruptedExchangeCannotHaveCompleteEvidence() {
        val b = Bench(mapOf(10400 to Mode.WORKING, 19200 to Mode.WORKING))
        b.adapter.beforeRead = { throw com.bod3.app.obd.elm.CommandTimeoutException("1A90", 1000, "83 F1") }
        val report = b.engine().run(selected, listOf(candidate(10400, 2), candidate(19200)))
        check(report.outcome == BringUpOutcome.ERROR && !report.logComplete && report.workingCandidate == null)
        check(report.attempts.size == 1 && report.exchanges.last().rawResponse == "83 F1")
        check(b.adapter.commands.none { it.first == "STPBR19200" })
        report.validate()
    }
    suspend fun malformedCandidates() {
        val b = Bench()
        val unsafe = candidate(runtimeChange = { r -> r.copy(data = r.data.copy(pids = listOf(r.live.copy(request = "14FFFF")))) })
        rejects { b.engine().run(selected, listOf(unsafe)) }
        rejects { b.engine().run(selected, listOf(candidate(runtimeChange = { it.copy(session = "EXTENDED") }))) }
        rejects { b.engine().run(selected, listOf(candidate(runtimeChange = { r -> r.copy(data = r.data.copy(pids = listOf(r.live.copy(header = "8020F1", responseHeader = "80F120")))) }))) }
        rejects { b.engine().run(selected.copy(path = listOf("one")), listOf(candidate())) }
        rejects { b.engine().run(selected.copy(vehicleInstance = ""), listOf(candidate())) }
        rejects { BringUpCandidate.ranked(listOf(candidate(), candidate())) }
        check(b.adapter.commands.isEmpty())
    }
    suspend fun independentDefinitionProvenance() {
        val b = Bench()
        val missingOtherId = candidate(runtimeChange = { r ->
            val d = r.ecu.diagnostics!!
            val other = d.identification.single().copy(read = r.identification.copy(id = "other", sourceRecords = emptyList()))
            r.copy(data = r.data.copy(ecus = listOf(r.ecu.copy(diagnostics = d.copy(identification = d.identification + other)))))
        })
        val missingClear = candidate(runtimeChange = { r ->
            val d = r.ecu.diagnostics!!
            r.copy(data = r.data.copy(ecus = listOf(r.ecu.copy(diagnostics = d.copy(faults = d.faults!!.copy(clear = DiagnosticClear("14FFFF", "54")))))))
        })
        val missingWrite = candidate(runtimeChange = { r -> r.copy(data = r.data.copy(actuators = r.data.actuators.map { it.copy(sourceRecords = emptyList()) })) })
        for (c in listOf(missingOtherId, missingClear, missingWrite)) rejects { b.engine().run(selected, listOf(c)) }
        check(b.adapter.commands.isEmpty())
    }
    suspend fun contradictoryReportsAreRejected() {
        val r = Bench().engine().run(selected, listOf(candidate()))
        val a = r.attempts.single()
        rejects { r.copy(outcome = BringUpOutcome.NO_MATCH).validate() }
        rejects { r.copy(logComplete = false).validate() }
        rejects { r.copy(exchanges = emptyList()).validate() }
        rejects { r.copy(attempts = listOf(a.copy(probes = a.probes.map { if (it.role == ProbeRole.IDENTIFICATION) it.copy(request = "1A91") else it }))).validate() }
        rejects { r.copy(attempts = listOf(a.copy(probes = a.probes.map { if (it.role == ProbeRole.DTC) it.copy(payload = "580101") else it }))).validate() }
    }
    suspend fun transferDoesNotTrustFlags() = temporary { folder ->
        val b = Bench(); val report = b.engine().run(selected, listOf(candidate()))
        val text = BringUpCodec.packText(listOf(report)); val imported = BringUpCodec.readPack(text).single()
        check(imported.workingCandidate?.fingerprint == report.workingCandidate?.fingerprint)
        check(imported.identity == report.identity && imported.exchanges == report.exchanges)
        check(imported.workingCandidate?.runnable == false && imported.workingCandidate!!.runtime!!.data.actuators.none { it.verified })
        val other = Bench(); val refused = other.engine().run(selected, imported.candidates)
        check(refused.outcome == BringUpOutcome.NO_MATCH && other.adapter.commands.isEmpty())
        val store = VerifiedSetupStore(folder); store.saveLocal(report); store.saveLast(report)
        val reopened = VerifiedSetupStore(folder)
        check(reopened.lastReport()?.identity == report.identity && reopened.all().single().transferred.not())
        check(reopened.matching(selected.path, "different-system").isEmpty())
        check(reopened.importPack(text) == 1)
        check(reopened.all().size == 2 && reopened.all().count { it.transferred } == 1)
        check(BringUpCodec.readPack(reopened.exportPack()).size == 1)
        rejects { reopened.saveLocal(imported) }
    }
    suspend fun transferAtomicValidation() = temporary { folder ->
        val report = Bench().engine().run(selected, listOf(candidate())); val store = VerifiedSetupStore(folder)
        val text = BringUpCodec.packText(listOf(report)); store.importPack(text)
        val before = folder.listFiles()!!.associate { it.name to it.readBytes().toList() }
        for (bad in listOf(text.dropLast(2), text.replace("\"formatVersion\":1", "\"formatVersion\":99"),
            text.replace("\"supportCount\":1", "\"supportCount\":2"), text.replace("\"runtime\":{", "\"runtime\":{\"verifiedOnVehicle\":true,"))) {
            rejects { store.importPack(bad) }
            check(folder.listFiles()!!.associate { it.name to it.readBytes().toList() } == before)
        }
        val more = report.copy(scope = selected.copy(vehicleInstance = "Garage car 2"))
        store.importPack(BringUpCodec.packText(listOf(report, more)))
        check(store.all().size == 2 && store.all().map { it.report.scope.vehicleInstance }.toSet().size == 2)
        check(folder.listFiles()!!.none { it.name.endsWith(".partial") })
    }
    suspend fun capacityNoSilentEviction() = temporary { folder ->
        val report = Bench().engine().run(selected, listOf(candidate())); val store = VerifiedSetupStore(folder)
        for (i in 1..BringUpCodec.MAX_SETUPS) store.saveLocal(report.copy(scope = selected.copy(vehicleInstance = "Car $i")))
        rejects { store.saveLocal(report.copy(scope = selected.copy(vehicleInstance = "Overflow car"))) }
        check(store.all().size == BringUpCodec.MAX_SETUPS && store.all().any { it.report.scope.vehicleInstance == "Car 1" })
    }
    suspend fun evidenceLimit(entries: Boolean) {
        val b = Bench(); if (!entries) b.adapter.hugeIdentification = true
        val r = b.engine(if (entries) 1 else BringUpEngine.MAX_EXCHANGES, if (entries) BringUpEngine.MAX_LOG_BYTES else 8192).run(selected, listOf(candidate()))
        check(r.outcome == BringUpOutcome.ERROR && !r.logComplete && r.workingAttempt == null)
        check(r.exchanges.size <= if (entries) 1 else 40)
        r.validate()
    }
    suspend fun cancellationAndLease() = coroutineScope {
        val b = Bench(); val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        b.adapter.beforeRead = { waiting.complete(Unit); release.await() }
        var report: BringUpReport? = null
        val job = launch { b.engine().run(selected, listOf(candidate()), onFinished = { report = it }) }
        waiting.await(); job.cancelAndJoin()
        check(report?.outcome == BringUpOutcome.CANCELLED && report?.workingAttempt == null && report?.logComplete == false)
        check(b.adapter.requests.size == 1)
        val snapshot = report!!.exchanges.toList()
        b.adapter.beforeRead = {}; release.complete(Unit)
        val retry = b.engine().run(selected, listOf(candidate()))
        check(retry.outcome == BringUpOutcome.WORKING && report!!.exchanges == snapshot)
    }
    suspend fun noInterleaving() = coroutineScope {
        val b = Bench(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var first = true
        b.adapter.beforeRead = { if (first) { first = false; started.complete(Unit); release.await() } }
        val bringUp = async { b.engine().run(selected, listOf(candidate())) }
        started.await()
        val background = async { b.session.request(candidate().target!!, listOf(0x21, 0x20)) }
        yield(); check(b.adapter.requests.size == 1)
        release.complete(Unit); val report = bringUp.await(); background.await()
        check(report.outcome == BringUpOutcome.WORKING && b.adapter.requests.size == 4)
        check(report.exchanges.count { it.command == "2120" } == 1)
        check(b.adapter.requests.take(3).map { it.bytes.first() } == listOf(0x1A, 0x21, 0x18))
    }
    suspend fun readOnlyCannotEscalate() {
        val b = Bench(); val target = candidate().target!!
        val grant = WriteGate.issue(WritePurpose.CODING, target, true, scope = WriteScope(listOf(0x20), 0x81, 1, 1))
        b.session.withReadOnlyBringUp {
            rejects { b.session.request(target, listOf(0x3B, 0x20, 1), grant = grant) }
            rejects { b.session.request(target, listOf(0x14, 0xFF, 0xFF), confirmation = ClearConfirmation.kwpClear(target, 0xFFFF)) }
            rejects { b.session.request(target, listOf(0x10, 0x81), grant = grant) }
        }
        check(b.adapter.commands.isEmpty())
    }
    suspend fun controllerSameEcu(nonUnique: Boolean = false) = temporary { folder -> coroutineScope {
        val adapter = SimulatedBringUpAdapter(); val recorder = RecordingExecutor(adapter)
        lateinit var controller: BringUpController
        val session = Session(recorder, ObdProtocol.ISO_15765_4_CAN_11_500, ecuOperationGuard = { current, target -> controller.requireSameEcu(current, target) })
        val c = candidate(runtimeChange = { r -> if (nonUnique) {
            val d = r.ecu.diagnostics!!
            r.copy(identityKind = EcuIdentityKind.NON_UNIQUE, data = r.data.copy(ecus = listOf(r.ecu.copy(diagnostics = d.copy(
                faults = d.faults!!.copy(clear = DiagnosticClear("14FFFF", "54", listOf("synthetic:clear"))))))))
        } else r })
        val store = VerifiedSetupStore(folder)
        controller = BringUpController(this, store, InMemoryKeyValueStore(), { session to recorder })
        controller.select(selected.path, selected.systemId, listOf(c)); controller.vehicleInstance(selected.vehicleInstance)
        val target = c.target!!
        val grant = WriteGate.issue(WritePurpose.CODING, target, true, scope = WriteScope(listOf(0x20), 0x81, 1, 1))
        rejects { session.request(target, listOf(0x3B, 0x20, 1), grant = grant) }
        check(adapter.commands.isEmpty())
        controller.start()
        withTimeout(10000) { controller.state.first { !it.running && it.report != null } }
        check(controller.state.value.verifiedHere && controller.data(session)?.pids?.size == 1)
        if (nonUnique) {
            check(controller.data(session)!!.actuators.isEmpty() && controller.data(session)!!.ecus.single().diagnostics!!.faults!!.clear == null)
            rejects { session.request(target, listOf(0x3B, 0x20, 1), grant = grant) }
            check(adapter.requests.none { it.bytes.first() == 0x3B })
        } else {
            check(session.request(target, listOf(0x3B, 0x20, 1), grant = grant).messages.isNotEmpty())
            check(adapter.requests.takeLast(2).map { it.bytes.first() } == listOf(0x1A, 0x3B))
            adapter.serial = "SYNTHETIC-REPLACEMENT"
            rejects { session.request(target, listOf(0x3B, 0x20, 1), grant = grant) }
            check(adapter.requests.count { it.bytes.first() == 0x3B } == 1 && !controller.state.value.verifiedHere && controller.data(session) == null)
        }
        check(store.all().single().transferred.not())
    } }
    suspend fun controllerDoesNotReuseAcrossCarsOrPhones() = temporary { folder -> coroutineScope {
        val b = Bench(); val report = b.engine().run(selected, listOf(candidate())); val store = VerifiedSetupStore(folder)
        store.importPack(BringUpCodec.packText(listOf(report)))
        val controller = BringUpController(this, store, InMemoryKeyValueStore(), { b.session to b.recorder })
        controller.select(selected.path, selected.systemId, listOf(candidate())); controller.vehicleInstance(selected.vehicleInstance)
        check(controller.data(b.session) == null && !controller.state.value.verifiedHere)
        controller.start(); withTimeout(10000) { controller.state.first { !it.running && it.report != null && it.verifiedHere } }
        check(controller.data(b.session) != null)
        controller.vehicleInstance("Garage car 2"); check(controller.data(b.session) == null && controller.state.value.report == null)
        controller.vehicleInstance(selected.vehicleInstance); check(controller.data(b.session) == null)
        controller.start(); withTimeout(10000) { controller.state.first { !it.running && it.verifiedHere } }
        check(controller.data(Session(b.recorder, ObdProtocol.ISO_15765_4_CAN_11_500)) == null)
        controller.disconnected(); check(controller.data(b.session) == null)
        controller.select(selected.path, "other-system", listOf(candidate())); check(controller.data(b.session) == null)
    } }
    suspend fun nonDefaultProfileCannotAlterCandidate() {
        val b = Bench(); val custom = Session(b.recorder, ObdProtocol.ISO_15765_4_CAN_11_500, SessionOptions(extendedAddressing = true))
        val r = BringUpEngine(custom, b.recorder).run(selected, listOf(candidate()))
        check(r.outcome == BringUpOutcome.NO_MATCH && b.adapter.commands.isEmpty())
    }
    suspend fun canBringUp(truncated: Boolean) {
        val link = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500)
        val id = DiagnosticRead("serial", "Synthetic serial", "22F18C", "62F18C", minBytes = 1, maxBytes = 64, sourceRecords = listOf("synthetic:serial"))
        val dtc = DiagnosticRead("dtcs", "Synthetic DTCs", "1902FF", "5902", dataOffset = 1, maxBytes = 64, sourceRecords = listOf("synthetic:dtcs"))
        val ecu = EcuDefinition(name = "Synthetic CAN", requestId = "720", responseId = "728", link = link, sourceRecords = listOf("synthetic:ecu"),
            diagnostics = EcuReadDefinitions(listOf(IdentificationRead(id)), FaultRead(dtc, FaultLayout(4, 0, 3, statusOffset = 3))))
        val pid = CustomPid("live", "Synthetic", "221234", "A", header = "720", responseHeader = "728", link = link, sourceRecords = listOf("synthetic:pid"))
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "720", "728", truncateMultiFrame = truncated, respond = { r -> listOf(when (r) {
            listOf(0x22, 0xF1, 0x8C) -> listOf(0x62, 0xF1, 0x8C) + "SYNTHETIC-SERIAL".map(Char::code)
            listOf(0x22, 0x12, 0x34) -> listOf(0x62, 0x12, 0x34, 0x55)
            listOf(0x19, 0x02, 0xFF) -> listOf(0x59, 0x02, 0xFF, 0x01, 0x23, 0x45, 0x20)
            else -> error("Unexpected CAN probe")
        }) })))
        val recorder = RecordingExecutor(adapter); val session = Session(recorder, ObdProtocol.ISO_15765_4_CAN_11_500)
        val c = BringUpCandidate("can", "Synthetic CAN", listOf("synthetic:entry"), listOf("synthetic:entry"), emptyMap(), emptyList(),
            BringUpRuntime(CatalogData(ecus = listOf(ecu), pids = listOf(pid)), "serial", "live", identityKind = EcuIdentityKind.ECU_SERIAL), true)
        val report = BringUpEngine(session, recorder).run(selected, listOf(c))
        check(report.outcome == if (truncated) BringUpOutcome.NO_MATCH else BringUpOutcome.WORKING)
        check(adapter.violations.isEmpty() && adapter.writes.isEmpty() && adapter.clears.isEmpty())
    }
    suspend fun transferPreview() = temporary { folder ->
        val store = VerifiedSetupStore(folder); val kv = InMemoryKeyValueStore()
        val transfer = PackTransfer(emptyList(), DataPackStore(kv), ActuatorLibrary(emptyList(), kv), CodingLibrary(emptyList(), kv), verifiedSetups = store)
        val report = Bench().engine().run(selected, listOf(candidate())); val text = BringUpCodec.packText(listOf(report))
        check((PackFiles.detect(text) as Detection.Known).kind == PackKind.VERIFIED_SETUPS)
        val preview = transfer.preview(text)
        check(preview.canImport && preview.warnings.any { it.contains("NOT verification") } && store.all().isEmpty())
        check(transfer.apply(preview).contains("transferred") && store.all().single().transferred)
        check(!transfer.preview(text.dropLast(1)).canImport)
    }
    suspend fun fingerprintBindsDefinitions() {
        val c = candidate(); val changed = candidate(runtimeChange = { r -> r.copy(data = r.data.copy(pids = listOf(r.live.copy(formula = "A*2")))) })
        check(c.fingerprint != changed.fingerprint)
        val raw = BringUpCodec.candidateMap(c).toMutableMap(); raw["verifiedOnVehicle"] = true
        rejects { BringUpCodec.candidate(raw, pinned = false) }
        val untrusted = BringUpCodec.candidate(BringUpCodec.candidateMap(c), pinned = false)
        check(untrusted.fingerprint == c.fingerprint && !untrusted.runnable)
    }
}
