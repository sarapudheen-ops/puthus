package com.bod3.app.diagnostics

import kotlinx.coroutines.async
import kotlinx.coroutines.launch

import com.bod3.app.catalog.*
import com.bod3.app.datapack.*
import com.bod3.app.obd.kwp.*
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.*
import com.bod3.app.obd.testing.*
import com.bod3.app.obd.uds.*
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex
import java.security.MessageDigest

/** Synthetic adapter/replies ONLY. The separate generated-file case uses real source definitions. */
object ToyotaWorkflowScenarios {
    private val records = listOf("fixture-not-vehicle-data")
    private fun frame(id: Int, req: String, mask: String = "", rep: String = "") = ToyotaReadFrame(id, req, mask, rep, records)
    private val setup = ToyotaDtcSetup(132, 0, 0, listOf(frame(132, "0101", "FFFF80", "410180")), records)
    private val workflow = ToyotaFaultWorkflow(1, 11, 1,
        listOf(ToyotaFunctionDetail(1, 0, null, 0, 0, records), ToyotaFunctionDetail(2, 0, null, 0, 0, records),
            ToyotaFunctionDetail(3, 1, frame(10008, "07", "FF", "47"), 71, 0, records),
            ToyotaFunctionDetail(43, 1, frame(10404, "21E2", "00000000000020", "61E2"), 32, 0, records),
            ToyotaFunctionDetail(57, 0, null, 0, 0, records)),
        listOf(frame(5, "105F", "0040", "505F")),
        listOf(ToyotaFaultQuery(13, 3, 0x3903, listOf(setup), listOf(frame(13, "07", rep = "47")), records),
            ToyotaFaultQuery(10, 1, 1, emptyList(), listOf(frame(10, "13B0", rep = "53")), records),
            ToyotaFaultQuery(11, 2, 2, emptyList(), listOf(frame(11, "1380", "00FF", "53")), records),
            ToyotaFaultQuery(165, 5, 43, emptyList(), listOf(frame(165, "0A")), records)), records)
    private val layout = ToyotaFaultLayout(0, 1, 2, 2, false, 1, 1, 0,
        labels = listOf(ToyotaFaultLabel("00000123", "SYNTHETIC", "Not vehicle data", records)), sourceRecords = records)
    private val fault = FaultRead(DiagnosticRead("fixture", "Current", "13B0", "53", minBytes = 1, sourceRecords = records), layout, workflow = workflow)
    // Explicit synthetic timing settings. These are NOT a mapping of the Toyota timing ABI to ST/VT.
    private val link = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
        kLine = KLineConfig(KLineInit.FAST, 0x13), testerPresent = listOf(0x3E))
    private val ecu = EcuDefinition(name = "Synthetic source-workflow exercise", requestId = "8013F0", responseId = "80F013",
        protocol = EcuProtocol.KWP, link = link, sourceRecords = records, diagnostics = EcuReadDefinitions(faults = fault))

    private fun adapter(): SimulatedKwpAdapter = SimulatedKwpAdapter().also { a ->
        a.responseHeader = listOf(0x80, 0xF0, 0x13)
        a.ecu.replyOverride = { r -> when (Hex.format(r, "")) {
            "07" -> listOf(listOf(0x47, 0x01, 0x23))
            "21E2" -> listOf(listOf(0x61, 0xE2, 0, 0, 0, 0, 0x20))
            "21E1" -> listOf(listOf(0x61, 0xE1, 0, 0, 0, 0, 0, 0, 0))
            "0101" -> listOf(listOf(0x41, 1, 0)) // MIL off: rule 0 must not become an expected-prefix test
            "13B0", "1380" -> listOf(listOf(0x53, 1, 0x01, 0x23))
            "0A" -> listOf(listOf(0x4A, 0xFF)) // native kind 5 intentionally does not decode these bytes
            else -> null
        } }
    }
    private suspend fun session(a: SimulatedKwpAdapter, guard: suspend (Session, EcuTarget.Physical) -> Unit = { _, _ -> }): Session {
        val s = Session(a, link.protocol, clock = { 0L }, wait = {}, ecuOperationGuard = guard)
        s.withReadOnlyBringUp { s.prepareBringUp(ecu.target) }
        a.commands.clear() // Fixture setup only; assertions below inspect operation commands.
        return s
    }

    fun supportPredicateParityWithTheNativeFdataDomain() {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0
        val masks = listOf("", "FF", "00FF", "00F00F", "000000", "000180")
        for (mask in masks) for (minimum in listOf(0, 2, 5)) for (mode in listOf(1, 2)) for (value in 0..255) for (expected in listOf(0, 1, 128)) {
            val detail = ToyotaFunctionDetail(1, mode, frame(1, "0101", mask, "4101"), expected, minimum, records)
            val result = detail.decision(listOf(value, value xor 255))
            val rawMask = if (mask.isEmpty()) emptyList() else Hex.parse(mask)!!
            digest.update((listOf(rawMask.size, minimum, mode, value, expected, if (result.supported) 1 else 0, result.maskedByte ?: 0xAA) + rawMask).map(Int::toByte).toByteArray())
            count++
        }
        check(count == 27648)
        check(digest.digest().joinToString("") { "%02x".format(it) } == "5289af252e4454245472816647e4feed6616a5b6f97520ba697492f3798f3b60")
        check(ToyotaFunctionDetail(57, 0, null, 0, 255, records).accepts(null))
        check(!ToyotaFunctionDetail(43, 1, frame(1, "0101", "00FFFF", "4101"), 1, 0, records).accepts(listOf(1)))
    }

    suspend fun completeWorkflowRetainsSetupPendingCurrentHistoryAndNondecodedConditionalRead() {
        check(ecu.problems().isEmpty()) { ecu.problems() }
        val a = adapter(); var guarded = 0
        val s = session(a) { current, target ->
            check(target == ecu.target); guarded++
            val reply = current.request(target, listOf(0x1A, 0x90)) // synthetic fresh-identity guard, not a real Toyota ID
            check(reply.complete && reply.messages.size == 1)
        }
        lateinit var confirm: DiagnosticSessionConfirmation
        val result = s.withSourceReadSessionOperation(ecu.target) {
            // Fresh identity/preparation runs before issuing the epoch-bound token.
            confirm = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)
            EcuWorkbench(s).readDtcs(ecu, confirm)
        } as DtcReadResult.Read
        check(result.records.map { it.toyotaState } == listOf(ToyotaDtcState.PENDING, ToyotaDtcState.CURRENT, ToyotaDtcState.HISTORY))
        check(result.records.all { it.nativeCode == 0x123L && it.code == "SYNTHETIC" && it.sourceDescription == "Not vehicle data" })
        check(a.ecu.requests.map { Hex.format(it, "") } == listOf("1A90", "07", "21E2", "105F", "0101", "07", "13B0", "1380", "0A"))
        check(guarded == 1)
        val before = a.commands.size
        check(EcuWorkbench(s).readDtcs(ecu, confirm) is DtcReadResult.Failed && a.commands.size == before)
        check(!ReadOnlyPolicy.isAllowed(listOf(0x10, 0x5F)) && !KwpServices.readAllowed(listOf(0x10, 0x5F)))
        check(!WritePolicy.isAllowed(WritePurpose.CODING, listOf(0x10, 0x5F), DiagnosticDialect.KWP2000))
    }

    suspend fun noConfirmationWrongConnectionWorkflowExpiredAndReadOnlyLeaseSendNoSessionBytes() {
        val a = adapter(); val s = session(a); val reader = EcuWorkbench(s)
        check(reader.readDtcs(ecu) is DtcReadResult.Failed && a.commands.isEmpty())
        check(runCatching { DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, false) }.isFailure)
        val other = session(a)
        val wrong = DiagnosticSessionConfirmation.confirmed(other, ecu.target, workflow, true)
        check(reader.readDtcs(ecu, wrong) is DtcReadResult.Failed && a.commands.isEmpty())
        val changed = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow.copy(nativeMode = 0), true)
        check(reader.readDtcs(ecu, changed) is DtcReadResult.Failed && a.commands.isEmpty())
        var now = 0L
        val expired = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true, clock = { now })
        now = 60001
        check(reader.readDtcs(ecu, expired) is DtcReadResult.Failed && a.commands.isEmpty())
        val valid = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)
        s.withReadOnlyEcuOperation(ecu.target) {
            check(runCatching { s.request(ecu.target, listOf(0x10, 0x5F), diagnosticSession = valid) }.isFailure)
        }
        check(a.commands.isEmpty() && valid.belongsTo(s, ecu.target, workflow))
        val guardRejects = session(a) { _, _ -> error("No fresh same-current-ECU proof") }
        val blocked = DiagnosticSessionConfirmation.confirmed(guardRejects, ecu.target, workflow, true)
        check(runCatching { EcuWorkbench(guardRejects).readDtcs(ecu, blocked) }.isFailure && a.commands.isEmpty())
        check(!blocked.belongsTo(guardRejects, ecu.target, workflow))
    }

    suspend fun sessionPermissionCannotSurviveResetRouteChangeReconnectOrExpiredInitialization() {
        val a = adapter()
        val fresh = Session(a, link.protocol)
        check(runCatching { DiagnosticSessionConfirmation.confirmed(fresh, ecu.target, workflow, true) }.isFailure)
        for (kind in listOf("reset", "route-change", "reconnect", "expired-init")) {
            var now = 0L
            val s = Session(a, link.protocol, clock = { now }, wait = {})
            s.withReadOnlyBringUp { s.prepareBringUp(ecu.target) }
            val permission = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)
            when (kind) {
                "reset" -> s.invalidateRouting()
                "route-change" -> {
                    a.responseHeader = listOf(0x80, 0xF0, 0x14)
                    s.request(ecu.copy(requestId = "8014F0", responseId = "80F014").target, listOf(0x21, 0x20))
                    a.responseHeader = listOf(0x80, 0xF0, 0x13)
                }
                "reconnect" -> {
                    s.invalidateRouting()
                    s.withReadOnlyBringUp { s.prepareBringUp(ecu.target) }
                }
                "expired-init" -> now = link.kLine!!.p3MaxMs.toLong()
            }
            val before = a.commands.size
            check(EcuWorkbench(s).readDtcs(ecu, permission) is DtcReadResult.Failed) { kind }
            check(a.commands.size == before) { "Stale $kind confirmation sent bytes" }
            check(runCatching { s.request(ecu.target, listOf(0x10, 0x5F), diagnosticSession = permission) }.isFailure)
            check(a.commands.size == before) { "Direct consumption bypassed $kind epoch binding" }
        }
    }

    suspend fun atomicQueryFailureWrongEcuBadChecksumWrongEchoAndMixedRepliesNeverReturnPartialFaults() {
        for (bad in listOf("partial-history", "wrong-echo", "negative", "mixed", "duplicate", "wrong-ecu", "checksum")) {
            val a = adapter(); val source = a.ecu.replyOverride
            a.ecu.replyOverride = { r -> when {
                r == listOf(0x13, 0x80) && bad == "partial-history" -> listOf(listOf(0x53, 1, 1))
                r == listOf(0x10, 0x5F) && bad == "wrong-echo" -> listOf(listOf(0x50, 0x5E))
                r == listOf(0x13, 0x80) && bad == "negative" -> listOf(listOf(0x7F, 0x13, 0x22))
                r == listOf(0x13, 0x80) && bad == "mixed" -> listOf(listOf(0x53, 1, 1, 0x23), listOf(0x7F, 0x13, 0x22))
                r == listOf(0x13, 0x80) && bad == "duplicate" -> listOf(listOf(0x53, 1, 1, 0x23), listOf(0x53, 1, 1, 0x23))
                else -> source(r)
            } }
            a.onRequest = { r -> if (r == listOf(0x13, 0x80)) { a.wrongEcu = bad == "wrong-ecu"; a.corruptChecksum = bad == "checksum" } }
            val s = session(a)
            check(EcuWorkbench(s).readDtcs(ecu, DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)) is DtcReadResult.Failed) { bad }
            check(a.ecu.requests.none { it.first() in setOf(0x04, 0x14, 0x27, 0x2E, 0x2F, 0x30, 0x31, 0x3B) })
        }
    }

    suspend fun detail57StillEnablesPendingAndSourceKind5IsNotInventedAsPermanentCodes() {
        val a = adapter(); val source = a.ecu.replyOverride
        a.ecu.replyOverride = { r -> if (r == listOf(0x21, 0xE2)) listOf(listOf(0x61, 0xE2, 0, 0, 0, 0, 0)) else source(r) }
        val s = session(a)
        val result = EcuWorkbench(s).readDtcs(ecu, DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)) as DtcReadResult.Read
        check(result.records.any { it.toyotaState == ToyotaDtcState.PENDING })
        check(a.ecu.requests.none { it == listOf(0x0A) })
        check(workflow.queries[0].selected(setOf(57)) && !workflow.queries[0].selected(setOf(132)))
        check(!workflow.queries[0].copy(membershipWord = 0).selected(setOf(0)))
        check(workflow.queries[0].copy(membershipWord = 0xFF00).selected(emptySet()))
    }

    suspend fun sourceReadSessionLeaseCannotAuthorizeWritesClearsOrGeneralSessions() {
        val a = adapter(); var readGuards = 0; var writeGuards = 0
        val s = Session(a, link.protocol, clock = { 0L }, wait = {}, ecuOperationGuard = { _, _ ->
            writeGuards++; error("No reviewed unique serial write proof")
        }, diagnosticSessionGuard = { current, target ->
            readGuards++
            check(current.request(target, listOf(0x1A, 0x90)).messages.size == 1)
        })
        val coding = WriteGate.issue(WritePurpose.CODING, ecu.target, true, scope = WriteScope(listOf(0x20)))
        val clear = ClearConfirmation.kwpClear04(ecu.target)
        s.withSourceReadSessionOperation(ecu.target) {
            check(readGuards == 1 && writeGuards == 0)
            val before = a.commands.size
            for (request in listOf(listOf(0x3B, 0x20, 1), listOf(0x10, 3), listOf(0x27, 1)))
                check(runCatching { s.withEcuOperation(ecu.target) { s.request(ecu.target, request, grant = coding) } }.isFailure)
            check(runCatching { s.request(ecu.target, clear.request, confirmation = clear) }.isFailure)
            check(!clear.isUsed && a.commands.size == before)
            val permission = DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true)
            check(EcuWorkbench(s).readDtcs(ecu, permission) is DtcReadResult.Read)
        }
        check(readGuards == 1 && writeGuards == 0)
        check(runCatching { s.request(ecu.target, listOf(0x3B, 0x20, 1), grant = coding) }.isFailure)
        check(writeGuards == 1 && a.ecu.requests.none { it[0] in setOf(0x04, 0x27, 0x3B) })
    }

    suspend fun promptBindsDisplayedEcuConnectionEpochExpertExpiryAndSingleUseButIsNotACapability() {
        val a = adapter(); val s = session(a)
        for (kind in listOf("ECU", "definition", "connection", "reset", "expert", "expiry", "cancel")) {
            var now = 0L
            val prompt = DiagnosticSessionPrompt.open(s, ecu, true, clock = { now })
            var selected = ecu; var current = s; var expert = true
            when (kind) {
                "ECU" -> selected = ecu.copy(name = "Another selected ECU")
                "definition" -> selected = ecu.copy(diagnostics = ecu.diagnostics!!.copy(faults = fault.copy(workflow = workflow.copy(nativeMode = 0))))
                "connection" -> current = session(a)
                "reset" -> s.invalidateRouting()
                "expert" -> expert = false
                "expiry" -> now = DiagnosticSessionConfirmation.MAX_AGE_MS + 1
                "cancel" -> prompt.cancel()
            }
            check(!prompt.consume(current, selected, expert)) { kind }
            check(!prompt.consume(s, ecu, true)) { "A refused intent was revived: $kind" }
        }
        check(runCatching { DiagnosticSessionPrompt.open(s, ecu, false) }.isFailure)
        val prompt = DiagnosticSessionPrompt.open(s, ecu, true)
        check(prompt.consume(s, ecu, true) && !prompt.consume(s, ecu, true))
        val before = a.commands.size
        check(runCatching { s.request(ecu.target, listOf(0x10, 0x5F)) }.isFailure)
        check(a.commands.size == before) // A consumed UI intent itself authorizes no bytes.
    }

    suspend fun queuedDialogIntentIsCheckedAfterAcquiringTheBusAndCannotSurviveCancellationOrReset() = kotlinx.coroutines.coroutineScope {
        for (invalidate in listOf("reset", "expert-disabled", "closed", "expiry", "unchanged")) {
            val a = adapter(); val s = session(a); var now = 0L
            val prompt = DiagnosticSessionPrompt.open(s, ecu, true, clock = { now })
            val held = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holder = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                s.withDiagnosticOperation { held.complete(Unit); release.await() }
            }
            held.await()
            val queued = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                s.withDiagnosticOperation { prompt.consume(s, ecu, true) }
            }
            check(!queued.isCompleted && a.commands.isEmpty())
            when (invalidate) {
                "reset" -> s.invalidateRouting()
                "expert-disabled", "closed" -> prompt.cancel()
                "expiry" -> now = DiagnosticSessionConfirmation.MAX_AGE_MS + 1
            }
            release.complete(Unit); holder.join()
            check(queued.await() == (invalidate == "unchanged")) { invalidate }
            check(!prompt.consume(s, ecu, true) && a.commands.isEmpty())
        }
    }

    suspend fun malformedDiscoveryAndSetupCannotBecomeUnsupportedOrTriggerReadRetry() {
        for (stage in listOf("07", "21E2", "0101")) for (bad in listOf("wrong-echo", "mixed", "checksum", "wrong-ecu")) {
            val a = adapter(); val source = a.ecu.replyOverride
            a.ecu.replyOverride = { r -> if (Hex.format(r, "") != stage) source(r) else when (bad) {
                "wrong-echo" -> listOf(listOf(r[0] + 0x40, 0xFF)) // force invalid transport echo below for non-ID 07
                "mixed" -> source(r)!! + listOf(listOf(0x7F, r[0], 0x22))
                else -> source(r)
            } }
            a.onRequest = { r -> if (Hex.format(r, "") == stage) {
                a.corruptChecksum = bad == "checksum" || bad == "wrong-echo" && stage == "07"
                a.wrongEcu = bad == "wrong-ecu"
            } }
            val s = session(a); var retried = 0
            val result = ToyotaFaultWorkflowReader(s).read(ecu, fault,
                DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true), reenter = { retried++ })
            check(result is DtcReadResult.Failed && retried == 0) { "$stage/$bad" }
            check(Hex.format(a.ecu.requests.last(), "") == stage)
        }
    }

    suspend fun onlyAnUnambiguousFailedReadCanUseOneReadReentryAndNeverRepeatTheSessionSetup() {
        for (failure in listOf("no-data", "negative", "malformed")) {
            val a = adapter(); val source = a.ecu.replyOverride; var attempts = 0; var retried = 0
            a.ecu.replyOverride = { r -> if (r == listOf(0x13, 0xB0) && attempts++ == 0) when (failure) {
                "no-data" -> emptyList()
                "negative" -> listOf(listOf(0x7F, 0x13, 0x22))
                else -> listOf(listOf(0x54, 1, 1, 0x23))
            } else source(r) }
            val s = session(a)
            val result = ToyotaFaultWorkflowReader(s).read(ecu, fault,
                DiagnosticSessionConfirmation.confirmed(s, ecu.target, workflow, true), reenter = { retried++ })
            check((result is DtcReadResult.Read) == (failure == "no-data")) { failure }
            check(retried == if (failure == "no-data") 1 else 0)
            check(a.ecu.requests.count { it == listOf(0x10, 0x5F) } == 1)
        }
    }

    fun sourceWorkflowCannotBeSilentlyDroppedDowngradedOrChangedToAnActiveCommand() {
        val pack = DataPack("fixture", "Fixture", vehicles = listOf(VehicleDefinition("Synthetic", "Model", ecus = listOf(ecu))))
        val json = DataPackCodec.encode(pack)
        check("\"formatVersion\":6" in json && DataPackCodec.decodeOrThrow(json) == pack)
        for (version in 1..5) check(!DataPackCodec.decode(json.replace("\"formatVersion\":6", "\"formatVersion\":$version")).isValid)
        val catalog = CatalogPack("fixture", "Fixture", library = CatalogData(ecus = listOf(ecu)))
        val text = CatalogCodec.encode(catalog)
        check("\"formatVersion\":5" in text && CatalogCodec.decodeOrThrow(text) == catalog)
        check(!CatalogCodec.decode(text.replace("\"formatVersion\":5", "\"formatVersion\":4")).isValid)
        for (bad in listOf(json.replace("105F", "1002"), json.replace("105F", "3101"), json.replace("505F", "505E"),
            json.replace("\"nativeMode\":11", "\"nativeMode\":7"), json.replace("\"membershipWord\":14595", "\"membershipWord\":true"),
            json.replace("\"rule\":0", "\"rule\":5"), json.replace("\"frameId\":132", "\"frameId\":65536")))
            check(!DataPackCodec.decode(bad).isValid) { "Bad source workflow accepted" }
    }

    /** Real compiled formulas/units/applicability; all wire replies and adapter timing are SYNTHETIC. */
    suspend fun actualCompiledLiveReadsWhenSupplied() {
        val path = System.getProperty("toyota.source.review.file") ?: return
        val review = MiniJson.parse(java.io.File(path).readText()) as Map<*, *>
        check(review["productionExecutableDefinitions"] == 0.0)
        val values = review["liveReads"] as List<*>
        val rows = values.map { value ->
            val row = value as Map<*, *>
            row.entries.associate { (key, data) -> key.toString() to data } + mapOf(
                "header" to ecu.requestId, "responseHeader" to ecu.responseId, "link" to DiagnosticLinkCodec.encode(link))
        }
        val pack = DataPackCodec.decodeOrThrow(JsonWriter.write(mapOf("formatVersion" to 6, "id" to "compiled-source-on-synthetic-link",
            "name" to "Actual source reads on a synthetic adapter/link only", "pids" to rows)))
        check(pack.pids.size == 30 && pack.pids.all { it.isValid && it.supportChecks.size == 2 })
        val coolant = pack.pids.single { it.request == "2105" }
        val rpm = pack.pids.single { it.request == "210C" }
        check(coolant.formula == "(A-40)" && coolant.unit == "degree C" && coolant.dataOffset == 0)
        check(rpm.formula == "(((256*A)+B)/4)" && rpm.unit == "rpm" && rpm.dataOffset == 0)
        check(coolant.sourceRecords.any { it.startsWith("FUNCDATA_EU.BIN@00051245") })
        check(rpm.sourceRecords.any { it.startsWith("FUNCDATA_EU.BIN@00051371") })
        for (supported in listOf(true, false)) {
            val a = adapter(); val source = a.ecu.replyOverride
            a.ecu.replyOverride = { request -> when (request) {
                listOf(0xA8, 1) -> listOf(listOf(0xE8, 1, 5, 1, if (supported) 1 else 0, 12, 2, 1, 1))
                listOf(0x21, 0) -> listOf(listOf(0x61, 0, 0x08, 0x10, 0, 0))
                else -> source(request)
            } }
            val s = session(a)
            val reader = com.bod3.app.live.LiveReader(s, com.bod3.app.obd.VehicleReader(s, a))
            for (byte in listOf(0, 40, 255)) {
                a.ecu.locals[5] = listOf(byte)
                val result = reader.read(com.bod3.app.live.LiveValueDef.Custom(coolant), EcuTarget.Broadcast, null)
                check(result == if (supported) byte - 40.0 else null)
            }
            a.ecu.locals[12] = listOf(0xFF, 0xFF)
            check(reader.read(com.bod3.app.live.LiveValueDef.Custom(rpm), EcuTarget.Broadcast, null) == 16383.75)
            if (!supported) check(a.ecu.requests.none { it == listOf(0x21, 5) })
            check(a.ecu.requests.all { it[0] in setOf(0xA8, 0x21) })
        }
    }

    suspend fun confirmedWorkbenchCreatesItsCapabilityAfterFreshSourceIdentificationNotBeforeTheGuard() {
        val identified = ecu.copy(diagnostics = EcuReadDefinitions(listOf(IdentificationRead(
            DiagnosticRead("synthetic-id", "Synthetic ID", "1A90", "5A90", minBytes = 1, sourceRecords = records))), fault))
        for (ok in listOf(true, false)) {
            val a = adapter(); val source = a.ecu.replyOverride
            a.ecu.replyOverride = { request -> if (request == listOf(0x1A, 0x90) && !ok) listOf(listOf(0x7F, 0x1A, 0x31)) else source(request) }
            var reads = 0; var writes = 0
            val s = Session(a, link.protocol, clock = { 0L }, wait = {}, ecuOperationGuard = { _, _ -> writes++; error("No serial write proof") },
                diagnosticSessionGuard = { current, target -> reads++; current.withReadOnlyBringUp { current.prepareBringUp(target) } })
            val workbench = EcuWorkbench(s)
            check(runCatching { workbench.readDtcsWithConfirmedSourceSession(identified, false) }.isFailure && a.commands.isEmpty())
            val result = workbench.readDtcsWithConfirmedSourceSession(identified, true)
            check((result is DtcReadResult.Read) == ok)
            check(reads == 1 && writes == 0)
            check(a.ecu.requests.count { it == listOf(0x10, 0x5F) } == if (ok) 1 else 0)
            check(a.ecu.requests.first() == listOf(0x1A, 0x90))
        }
    }

    /** CI/local generated source definitions, not a replacement JSON fixture committed to the APK. */
    suspend fun actualCompiledEcmectDefinitionsWhenSupplied() {
        val path = System.getProperty("toyota.diagnostics.file") ?: return
        val errors = mutableListOf<String>()
        val definitions = requireNotNull(EcuReadDefinitionsCodec.decode(MiniJson.parse(java.io.File(path).readText()), "source", errors)) { errors }
        check(errors.isEmpty()) { errors }
        val w = definitions.faults!!.workflow!!
        check(w.functionKey == 61L && w.nativeMode == 11 && w.nativeTransport == 1)
        check((definitions.faults.layout as ToyotaFaultLayout).let { it.modeLowBits == 1 && it.labels.size == 1414 })
        check(definitions.identification.single().let { it.read.request == "0904" && it.recordBytes == 16 })
        val actual = ecu.copy(diagnostics = definitions)
        check(actual.problems().isEmpty()) { actual.problems() }
        val a = adapter(); val s = session(a)
        val result = EcuWorkbench(s).readDtcs(actual, DiagnosticSessionConfirmation.confirmed(s, actual.target, w, true)) as DtcReadResult.Read
        check(result.records.size == 3 && result.records.all { it.nativeCode == 0x123L })
        check(a.ecu.requests.map { Hex.format(it, "") } == listOf("07", "21E1", "21E2", "105F", "0101", "07", "13B0", "1380", "0A"))
        val b = adapter(); val source = b.ecu.replyOverride
        b.ecu.replyOverride = { r -> if (r == listOf(9, 4)) listOf(listOf(0x49, 4, 1) + "SYNTH-CAL-ID-001".map(Char::code)) else source(r) }
        val current = session(b)
        check(EcuWorkbench(current).readDtcsWithConfirmedSourceSession(actual, true) is DtcReadResult.Read)
        check(b.ecu.requests.first() == listOf(9, 4) && b.ecu.requests.count { it == listOf(0x10, 0x5F) } == 1)
    }
}
