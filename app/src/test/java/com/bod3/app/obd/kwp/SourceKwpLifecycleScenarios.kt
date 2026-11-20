package com.bod3.app.obd.kwp

import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.session.*
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.SimulatedKwpAdapter
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.diagnostics.EcuReadDefinitionsCodec
import com.bod3.app.diagnostics.EcuWorkbench
import com.bod3.app.obd.uds.DtcReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Actual source plans and oracle-pinned traces; every adapter reply remains explicitly synthetic. */
object SourceKwpLifecycleScenarios {
    private fun map(value: Any?) = value as Map<*, *>
    private fun list(value: Any?) = value as List<*>
    private fun long(value: Any?) = (value as Number).toLong()
    private val contracts: Map<*, *> by lazy {
        val file = File(requireNotNull(System.getProperty("toyota.lifecycle.contracts.file")) { "Missing actual source lifecycle contract path" })
        val raw = file.readBytes()
        val pinRaw = javaClass.classLoader!!.getResourceAsStream("source-kwp-contracts-v1.json")?.use { it.readBytes() }
            ?: File("tools/thinkdiag/schema/source-kwp-contracts-v1.json").readBytes()
        val pin = map(MiniJson.parse(pinRaw.toString(Charsets.UTF_8)))
        check(raw.size.toLong() == long(pin["bytes"]))
        check(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) } == pin["sha256"])
        val root = map(MiniJson.parse(raw.toString(Charsets.UTF_8)))
        check(root["kind"] == "source-kwp-lifecycle-contracts" && long(root["productionExecutableDefinitions"]) == 0L)
        for (key in listOf("sourceEntries", "fastVectors", "isoVectors", "productionExecutableDefinitions", "sourceBindingsSha256", "fastConsumerCallsSha256", "isoConsumerCallsSha256")) check(root[key] == pin[key])
        root
    }
    private fun entries() = list(contracts["entries"]).map(::map)
    private fun plan(entry: Map<*, *>) = SourceKwpPlanCodec.decode(entry["plan"])
    private fun mandatoryEntry() = entries().single { e -> val p = plan(e); p.region == 1 && p.function == 61L && p.selector == 17 }
    private fun mandatory() = plan(mandatoryEntry())

    private fun reply(p: SourceKwpPlan, word: Long = 9, service: Int? = 0xC1): SourceKwpReply {
        val payload = if (service == 0x7F) listOf(0x7F, 0x81, 0x22) else listOf(service ?: 0, 0xE9, 0x8F)
        val wire = KLineFrames.encode(listOf(0x80, p.sendAddress[0], p.sendAddress[1]), payload)
        return SourceKwpReply(word, service, if (word.toShort() <= 0) null else ElmResponseParser.parse("synthetic", Hex.format(wire)))
    }

    private class Port(val p: SourceKwpPlan, replies: List<SourceKwpReply> = emptyList(), addresses: List<Long> = emptyList(),
                       var initial: SourceKwpReply = reply(p, 1)) : SourceKwpIo {
        val calls = mutableListOf<SourceKwpCall>()
        val replies = ArrayDeque(replies)
        val addresses = ArrayDeque(addresses)
        var pending: SourceKwpReply? = null
        var queuedWord = 9L
        var rejected: SourceKwpApi? = null
        var readPacket: suspend (String, Long) -> ElmResponse = { _, _ -> error("Undeclared synthetic read response") }
        var before: suspend (SourceKwpCall) -> Unit = {}
        override suspend fun invoke(call: SourceKwpCall): SourceKwpReply {
            calls += call; before(call)
            if (call.api == rejected) return SourceKwpReply(-1)
            return when (call.api) {
                SourceKwpApi.PRIMARY, SourceKwpApi.ALTERNATE -> {
                    pending = replies.removeFirst(); SourceKwpReply(queuedWord) // deliberately not the combined result
                }
                SourceKwpApi.END -> pending?.also { pending = null } ?: SourceKwpReply()
                SourceKwpApi.ADDRESS_INIT -> SourceKwpReply(addresses.removeFirst())
                SourceKwpApi.INITIAL -> initial
                SourceKwpApi.READ_PACKET -> SourceKwpReply(elm = readPacket(call.arguments[0] as String, long(call.arguments[1])))
                else -> SourceKwpReply()
            }
        }
        fun exhausted() { check(replies.isEmpty() && addresses.isEmpty() && pending == null) }
    }
    private fun equalCalls(expected: Any?, port: Port) {
        // Numeric integer/float spelling differs between Python canonical JSON
        // and the app writer; parsed numeric values/argument order do not.
        val actual = MiniJson.parse(JsonWriter.write(port.calls.map { it.report() }))
        check(actual == expected) { "Kotlin source consumer calls differ: ${port.calls}" }
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return } catch (_: java.io.IOException) { return }
        error("Invalid source lifecycle/evidence was accepted")
    }

    suspend fun actual284EntryFastRetryAndIsoContractsMatchPinnedNativeDomains() {
        var fast = 0; var iso = 0; var fallbacks = 0; var zeroWithoutProof = 0
        val entries = entries(); check(entries.size == 284)
        for (entry in entries) {
            val p = plan(entry)
            for (raw in list(entry["fast"])) {
                val c = map(raw)
                val replies = list(c["replies"]).map { value -> val r = map(value); reply(p, long(r["word"]), (r["service"] as? Number)?.toInt()) }
                val port = Port(p, replies)
                val result = SourceKwpLifecycle(p, port).fastEnter()
                check(result.nativeStatus == long(c["status"]).toInt())
                check(result.initialized == (c["positiveSourceReply"] == true && result.nativeStatus == 0))
                check(result.wantsIsoFallback(p.nativeTransport) == c["wantsIsoFallback"])
                if (result.wantsIsoFallback(p.nativeTransport)) fallbacks++
                if (result.nativeStatus == 0 && !result.completeC1) zeroWithoutProof++
                equalCalls(c["calls"], port); port.exhausted(); fast++
            }
            for (raw in list(entry["iso"])) {
                val c = map(raw)
                val port = Port(p, addresses = list(c["addressWords"]).map(::long), initial = reply(p, long(c["initialWord"])))
                val result = SourceKwpLifecycle(p, port).isoEnter()
                check(result.nativeStatus == long(c["status"]).toInt())
                check(result.initialized == (result.nativeStatus == 0 && long(c["initialWord"]) > 0))
                equalCalls(c["calls"], port); port.exhausted(); iso++
            }
        }
        check(fast == 2272 && iso == 2272 && fallbacks == 17 && zeroWithoutProof == 284)
    }

    suspend fun actual284KeepaliveAndExitBindingsNeverUseUniversalTesterPresentOrP3Defaults() {
        val requests = linkedMapOf<String, Int>(); val exits = linkedMapOf<String, Int>()
        for (entry in entries()) {
            val p = plan(entry)
            for ((key, execute) in listOf<Pair<String, suspend (SourceKwpLifecycle) -> Unit>>(
                    "keepaliveOn" to { it.keepalive(true) }, "keepaliveOff" to { it.keepalive(false) }, "exit" to { it.exit() })) {
                val port = Port(p); execute(SourceKwpLifecycle(p, port)); equalCalls(entry[key], port)
            }
            val request = Hex.format(p.keepalive.request, "").lowercase(); requests[request] = (requests[request] ?: 0) + 1
            val exit = Hex.format(p.exit.request, "").lowercase(); exits[exit] = (exits[exit] ?: 0) + 1
        }
        check(requests == mapOf("2100" to 174, "0100" to 42, "3e" to 41, "3e01" to 20, "2102" to 4, "21e1" to 3))
        check(exits == mapOf("82" to 236, "3e" to 42, "3e01" to 6))
    }

    suspend fun completeC1ProofRejectsAckZeroPartialDuplicateWrongEcuChecksumServiceAndMixedEvidence() {
        val p = mandatory(); check(p.baud == 10416 && p.sourceRecord == "SYSTEMENTER.BIN@00002f63")
        check(Hex.format(p.packet(p.initial.request), "") == "8113F08105")
        val valid = reply(p)
        val line = valid.elm!!.lines.single()
        val damaged = listOf("", "OK", line.dropLast(2), line + "00", line + "\r" + line,
            Hex.format(KLineFrames.encode(listOf(0x80, 0xF0, 0x14), listOf(0xC1, 0xE9, 0x8F))),
            Hex.format(KLineFrames.encode(listOf(0x80, 0xF0, 0x13), listOf(0xC1, 0xE9))),
            Hex.format(KLineFrames.encode(listOf(0x80, 0xF0, 0x13), listOf(0xC1, 0xE9, 0x8F, 0))),
            Hex.format(Hex.parse(line)!!.dropLast(1) + (Hex.parse(line)!!.last() xor 1)),
            line + "\rNO DATA", line + "\r?", line + "\r" + Hex.format(KLineFrames.encode(listOf(0x80, 0xF0, 0x13), listOf(0x7F, 0x81, 0x22))))
        for (raw in damaged) {
            val port = Port(p, listOf(valid.copy(elm = ElmResponseParser.parse("synthetic", raw))))
            val r = SourceKwpLifecycle(p, port).enter()
            check(r.nativeStatus == 0 && !r.initialized && !r.isoFallback)
            check(port.calls.count { it.api == SourceKwpApi.PRIMARY } == 1)
        }
        val zero = valid.copy(word = 0)
        check(!SourceKwpLifecycle(p, Port(p, listOf(zero, zero))).enter().initialized)
        check(!SourceKwpLifecycle(p, Port(p, addresses = listOf(0), initial = zero)).isoEnter().initialized)
        check(!SourceKwpLifecycle(p, Port(p, listOf(valid.copy(service = 0x50)))).enter().initialized)
        // The queued send return cannot select the alternate API.
        val queuedFailure = Port(p, listOf(valid)).also { it.queuedWord = 0xFFFFFFFFL }
        check(SourceKwpLifecycle(p, queuedFailure).enter().initialized)
        check(queuedFailure.calls.none { it.api == SourceKwpApi.ALTERNATE })
    }

    suspend fun onlyTransport5ExhaustedAdapterFailureDispatchesSameEntryIsoAndNeverReplays105F() {
        val p = entries().map(::plan).first { it.nativeTransport == 5 }
        val fail = SourceKwpReply(0xFFFFFFFFL)
        val port = Port(p, List(4) { fail }, listOf(-1, 0))
        val result = SourceKwpLifecycle(p, port).enter()
        check(result.initialized && result.isoFallback)
        check(port.calls.count { it.api == SourceKwpApi.ADDRESS_INIT } == 2)
        check(port.calls.filter { it.api == SourceKwpApi.ADDRESS_INIT }.all { it.arguments.first() == p.addressInit })
        check(port.calls.single { it.api == SourceKwpApi.INITIAL }.arguments == listOf(255, "81", p.flags, 0))
        port.exhausted()
        for (replies in listOf(List(2) { reply(p, 9, 0x7F) }, List(2) { SourceKwpReply() })) {
            val noIso = Port(p, replies)
            check(!SourceKwpLifecycle(p, noIso).enter().initialized)
            check(noIso.calls.none { it.api == SourceKwpApi.ADDRESS_INIT })
        }
        val mandatory = mandatory(); val noFallback = Port(mandatory, List(4) { fail })
        check(!SourceKwpLifecycle(mandatory, noFallback).enter().initialized)
        check(noFallback.calls.none { it.api == SourceKwpApi.ADDRESS_INIT })
        check(port.calls.none { call -> call.arguments.any { it is String && it == "105f" } })
    }

    suspend fun syntheticMultiplePinsAlternateConnectorAndInputMutationRetainExactAbiAndBounds() {
        val original = mandatory()
        val pinList = mutableListOf(7 to 7, 15 to 15); val timing = original.timing.toMutableList()
        val p = original.copy(pinPairs = pinList, timing = timing)
        val port = Port(p, List(4) { SourceKwpReply(-1) } + reply(p))
        val engine = SourceKwpLifecycle(p, port, connector = 17)
        pinList.clear(); timing[3] = 1 // cannot mutate the engine's suspended operation
        check(engine.enter().initialized)
        val pins = port.calls.filter { it.api == SourceKwpApi.SELECT_PIN }
        check(pins.map { it.arguments } == listOf(listOf(17, 14, 0x1900, 0, 0), listOf(17, 1, 0, 7, 7),
            listOf(17, 14, 0x1900, 0, 0), listOf(17, 1, 0, 15, 15)))
        check(port.calls.filter { it.api == SourceKwpApi.PULSE }.all { it.arguments == listOf(2, 25, 25) })
        val bad = original.copy(initial = original.initial.copy(request = listOf(0x10, 0x5F)))
        val untouched = Port(original)
        rejects { SourceKwpLifecycle(bad, untouched).enter() }; check(untouched.calls.isEmpty())
    }

    suspend fun failedReentryCannotRetryAReadAndRestoresContextWithoutEnteringDiagnosticSession() {
        val p = mandatory()
        for (successful in listOf(true, false)) {
            val port = Port(p, if (successful) listOf(reply(p)) else listOf(SourceKwpReply(), SourceKwpReply()))
            var read = 0
            val action: suspend () -> Unit = { SourceKwpLifecycle(p, port).reenterRead(11); read++ }
            if (successful) action() else rejects { action() }
            check(read == if (successful) 1 else 0)
            check(port.calls.first() == SourceKwpCall(SourceKwpApi.CONTEXT, listOf(255)))
            check(port.calls[1].api == SourceKwpApi.EXIT)
            check(port.calls.last() == SourceKwpCall(SourceKwpApi.CONTEXT, listOf(11)))
            check(port.calls.none { it.arguments.any { value -> value is String && value.contains("105f") } })
        }
        val port = Port(p, listOf(reply(p)))
        port.before = { if (it.api == SourceKwpApi.PULSE) throw CancellationException("synthetic cancel") }
        try { SourceKwpLifecycle(p, port).reenterRead(11); error("Cancellation swallowed") } catch (_: CancellationException) { }
        check(port.calls.last() == SourceKwpCall(SourceKwpApi.CONTEXT, listOf(11)))
        check(port.calls.none { it.api == SourceKwpApi.PRIMARY })
    }

    /** Legacy timing fields below are SYNTHETIC declarations, NOT native API equivalence.
     * The source transport scope must never emit/use them as bus settings. */
    private fun declaration(p: SourceKwpPlan): EcuTarget.Physical {
        val link = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
            kLine = KLineConfig(KLineInit.FAST, p.addressInit, baud = p.baud, fastLowMs = p.timing[3], fastHighMs = p.timing[4]),
            testerPresent = p.keepalive.request)
        return EcuTarget.Physical(Hex.format(listOf(0x80, p.sendAddress[1], p.sendAddress[0]), ""),
            Hex.format(listOf(0x80, p.sendAddress[0], p.sendAddress[1]), ""), link = link)
    }
    private fun fakeWire(p: SourceKwpPlan, payload: List<Int>): ElmResponse = ElmResponseParser.parse("synthetic",
        Hex.format(KLineFrames.encode(listOf(0x80, p.sendAddress[0], p.sendAddress[1]), payload)))

    suspend fun sessionSourceLeaseUsesOnlyTheSourcePortAndCannotEscalateOrFallBackToGenericKline() {
        val p = mandatory(); val target = declaration(p); val adapter = SimulatedKwpAdapter()
        var now = 0L; var writes = 0
        val s = Session(adapter, target.link!!.protocol, clock = { now }, wait = { error("Legacy P3 pacing used") },
            ecuOperationGuard = { _, _ -> writes++; error("Source C1 is not serial/write proof") })
        val port = Port(p, listOf(reply(p)))
        port.readPacket = { wire, _ -> check(Hex.parse(wire) == p.packet(listOf(0x21, 5))); fakeWire(p, listOf(0x61, 5, 80)) }
        val grant = WriteGate.issue(WritePurpose.CODING, target, true, scope = WriteScope(listOf(0x20)))
        s.withSourceKwpTransport(target, p, port, sourceContextByte = 11) {
            check(s.currentTarget == target && s.currentLinkEpoch(target) != null)
            val epoch = s.currentLinkEpoch(target)
            now = 100_000 // the generic declaration's P3 defaults are NOT source clocks
            check(s.request(target, listOf(0x21, 5)).messages.single().payload == listOf(0x61, 5, 80))
            check(s.currentLinkEpoch(target) == epoch)
            val before = port.calls.size
            rejects { s.request(target, listOf(0x3B, 0x20, 1), grant = grant) }
            rejects { s.request(target, listOf(0x04), confirmation = ClearConfirmation.kwpClear04(target)) }
            rejects { s.request(target, listOf(0x10, 0x5F)) }
            rejects { s.request(target.copy(requestHeader = "8014F0", responseHeader = "80F014"), listOf(0x21, 5)) }
            rejects { s.withReadOnlyBringUp { s.prepareBringUp(target) } }
            check(!s.keepAliveCurrent(target)) // source/driver owns the exact keepalive
            check(port.calls.size == before && writes == 0)
            s.withSourceKwpTransport(target, p, port, sourceContextByte = 11) { check(port.calls.size == before) }
            rejects { s.withSourceKwpTransport(target, p.copy(function = 65597), port, sourceContextByte = 11) {} }
        }
        check(s.currentTarget == null && s.currentLinkEpoch(target) == null)
        check(port.calls.takeLast(3).map { it.api } == listOf(SourceKwpApi.KEEPALIVE, SourceKwpApi.EXIT, SourceKwpApi.PAUSE_US))
        check(port.calls.takeLast(3).first().arguments == listOf("", 0, 0, 0))
        rejects { s.request(target, listOf(0x21, 5)) }
        check(adapter.commands.isEmpty()) { "Source transport silently used generic AT/ST/VT initialization" }
    }

    suspend fun sessionSourceCancellationAndConfigurationRejectionNeverCreateProofOrReconnectForCleanup() = coroutineScope {
        val p = mandatory(); val target = declaration(p)
        for (api in listOf(SourceKwpApi.ENABLE_L, SourceKwpApi.SELECT_PIN, SourceKwpApi.BAUD, SourceKwpApi.INTERVALS, SourceKwpApi.PULSE)) {
            val adapter = SimulatedKwpAdapter(); val s = Session(adapter, target.link!!.protocol)
            val port = Port(p, listOf(reply(p))).also { it.rejected = api }; var ran = false
            rejects { s.withSourceKwpTransport(target, p, port, 11) { ran = true } }
            check(!ran && s.currentTarget == null && adapter.commands.isEmpty())
            check(port.calls.none { it.api == SourceKwpApi.KEEPALIVE || it.api == SourceKwpApi.EXIT || it.api == SourceKwpApi.READ_PACKET })
        }
        val adapter = SimulatedKwpAdapter(); val s = Session(adapter, target.link!!.protocol)
        val port = Port(p, listOf(reply(p))); val ready = CompletableDeferred<Unit>()
        val operation = launch {
            s.withSourceKwpTransport(target, p, port, 11) { ready.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        ready.await(); operation.cancel(); operation.join()
        check(port.calls.takeLast(3).map { it.api } == listOf(SourceKwpApi.KEEPALIVE, SourceKwpApi.EXIT, SourceKwpApi.PAUSE_US))
        check(s.currentTarget == null && adapter.commands.isEmpty())
        val resetPort = Port(p, listOf(reply(p)))
        s.withSourceKwpTransport(target, p, resetPort, 11) {
            s.invalidateRouting()
            rejects { s.request(target, listOf(0x21, 5)) }
        }
        check(resetPort.calls.last().api == SourceKwpApi.KEEPALIVE)
        check(resetPort.calls.none { it.api == SourceKwpApi.EXIT || it.api == SourceKwpApi.READ_PACKET })
    }

    suspend fun sourceLifecycleAndReadLeaseAreExclusiveAgainstOtherRequestsAndReleaseAfterCancellation() = coroutineScope {
        val p = mandatory(); val target = declaration(p)
        val commands = mutableListOf<String>()
        val adapter = com.bod3.app.obd.elm.CommandExecutor { command, _ ->
            commands += command
            ElmResponseParser.parse(command, if (command.startsWith("AT")) "OK" else "NO DATA")
        }
        val s = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val port = Port(p, listOf(reply(p)))
        val holder = launch { s.withSourceKwpTransport(target, p, port, 11) { entered.complete(Unit); release.await() } }
        entered.await()
        val other = async { s.request(EcuTarget.ENGINE_CAN_11, listOf(0x22, 0x12, 0x34)) }
        yield(); check(!other.isCompleted && commands.isEmpty())
        holder.cancel(); holder.join(); other.await()
        check(commands.isNotEmpty() && port.calls.takeLast(2).map { it.api } == listOf(SourceKwpApi.EXIT, SourceKwpApi.PAUSE_US))
    }

    /** Real compiled diagnostic/source-entry definitions, scripted native API I/O and ECU replies.
     * Not enabled vehicle-system loading, a MIC3313 mapping or Android acceptance. */
    suspend fun actualMandatoryWorkbenchUsesSourceExitEnterRestoreAndIdenticalReadOnceWithout105FReplay() {
        val p = mandatory(); val target = declaration(p)
        val errors = mutableListOf<String>()
        val defs = EcuReadDefinitionsCodec.decode(MiniJson.parse(File(requireNotNull(System.getProperty("toyota.diagnostics.file"))).readText()), "source", errors)!!
        check(errors.isEmpty() && defs.faults!!.workflow!!.functionKey == p.function)
        val ecu = EcuDefinition(name = "ECMECT actual definitions, scripted API port", requestId = target.requestHeader,
            responseId = target.responseHeader!!, protocol = EcuProtocol.KWP, link = target.link,
            sourceRecords = p.sourceRecords, diagnostics = defs)
        check(ecu.problems().isEmpty()) { ecu.problems() }
        for (failure in listOf("no-data", "reentry-zero", "negative", "mixed-no-data", "wrong-ecu", "checksum", "disconnect")) {
            val adapter = SimulatedKwpAdapter(); var readGuards = 0; var writeGuards = 0; var attempts = 0
            val s = Session(adapter, target.link!!.protocol, ecuOperationGuard = { _, _ -> writeGuards++; error("No reviewed serial write proof") },
                diagnosticSessionGuard = { current, exact -> readGuards++; check(current.currentTarget == exact && exact == target) })
            val port = Port(p, listOf(reply(p)) + if (failure == "reentry-zero") listOf(SourceKwpReply(), SourceKwpReply()) else listOf(reply(p)))
            val requests = mutableListOf<String>()
            port.readPacket = { packet, _ ->
                val payload = KLineFrames.decode(packet, Hex.parse(target.requestHeader)!!)!!.payload
                val request = Hex.format(payload, ""); requests += request
                val response = when (request) {
                    "0904" -> listOf(0x49, 4, 1) + "SYNTH-CAL-ID-001".map(Char::code)
                    "07" -> listOf(0x47, 1, 0x23)
                    "21E1" -> listOf(0x61, 0xE1, 0, 0, 0, 0, 0, 0, 0)
                    "21E2" -> listOf(0x61, 0xE2, 0, 0, 0, 0, 0x20)
                    "105F" -> listOf(0x50, 0x5F)
                    "0101" -> listOf(0x41, 1, 0)
                    "13B0", "1380" -> listOf(0x53, 1, 1, 0x23)
                    "0A" -> listOf(0x4A, 0xFF)
                    else -> error("Unexpected actual source read $request")
                }
                val valid = fakeWire(p, response)
                if (request != "13B0" || attempts++ != 0) valid else when (failure) {
                    "no-data", "reentry-zero" -> ElmResponseParser.parse("synthetic", "NO DATA")
                    "negative" -> fakeWire(p, listOf(0x7F, 0x13, 0x22))
                    "mixed-no-data" -> ElmResponseParser.parse("synthetic", valid.raw + "\rNO DATA")
                    "wrong-ecu" -> ElmResponseParser.parse("synthetic", Hex.format(KLineFrames.encode(listOf(0x80, 0xF0, 0x14), response)))
                    "checksum" -> ElmResponseParser.parse("synthetic", Hex.format(Hex.parse(valid.lines.single())!!.let { it.dropLast(1) + (it.last() xor 1) }))
                    else -> throw java.io.IOException("Synthetic lost source link; never reconnect/replay")
                }
            }
            val result = s.withSourceKwpTransport(target, p, port, 11) {
                EcuWorkbench(s).readDtcsWithConfirmedSourceSession(ecu, true)
            }
            check((result is DtcReadResult.Read) == (failure == "no-data")) { "$failure: $result" }
            if (result is DtcReadResult.Read) check(result.records.size == 3 && result.records.all { it.nativeCode == 0x123L })
            val reentered = failure in listOf("no-data", "reentry-zero")
            val contextCalls = port.calls.filter { it.api == SourceKwpApi.CONTEXT }
            check(contextCalls.map { it.arguments } == if (reentered) listOf(listOf(255), listOf(11)) else emptyList<List<Int>>()) { failure }
            check(requests.count { it == "105F" } == 1 && requests.count { it == "0904" } == 1)
            check(requests.count { it == "13B0" } == if (failure == "no-data") 2 else 1)
            check(readGuards == 1 && writeGuards == 0 && adapter.commands.isEmpty())
            check(s.currentTarget == null)
            if (failure == "disconnect" || failure == "reentry-zero")
                check(port.calls.count { it.api == SourceKwpApi.EXIT } == if (failure == "reentry-zero") 1 else 0)
        }
    }

    fun sourceProjectionCodecRejectsMissingTruncatedCrossRegionUnknownBooleanAndSessionSmuggling() = runBlocking {
        val raw = map(mandatoryEntry()["plan"])
        val entry = map(raw["entry"])
        val initial = map(raw["initial"])
        val values = listOf(raw - "region", raw + ("unknown" to 1), raw + ("region" to 2),
            raw + ("consumerLibrarySha256" to "0".repeat(64)), raw + ("requiresIsoFallbackPort" to true),
            raw + ("entry" to (entry + ("baud" to true))), raw + ("entry" to (entry + ("baud" to 1e40))),
            raw + ("entry" to (entry + ("baud" to Double.POSITIVE_INFINITY))),
            raw + ("entry" to (entry + ("sourceTimingHex" to "053c00"))),
            raw + ("entry" to (entry + ("selector" to 18))), raw + ("entry" to (entry + ("nativeTransport" to 5))),
            raw + ("entry" to (entry + ("initialTemplates" to listOf(104, 104)))),
            raw + ("initial" to (initial + ("requestHex" to "105f"))), raw + ("initial" to (initial + ("ignored" to 1))))
        for (value in values) rejects { SourceKwpPlanCodec.decode(value) }
        check(SourceKwpPlanCodec.decode(raw) == mandatory())
    }
}
