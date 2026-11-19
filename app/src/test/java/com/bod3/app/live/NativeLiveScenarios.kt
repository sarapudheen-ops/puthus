package com.bod3.app.live

import com.bod3.app.catalog.*
import com.bod3.app.datapack.*
import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.kwp.*
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.*

/** Synthetic ECU/data fixtures. Native support transport/applicability still needs source binding. */
object NativeLiveScenarios {
    private val link = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500, DiagnosticDialect.KWP2000,
        testerPresent = listOf(0x3E))
    private val snapshot = PidSupportCheck("A801", "A+B", dataOffset = 1,
        recordId = 7, sourceRecords = listOf("fixture-support-snapshot"))
    private val discovered = PidSupportCheck("2100", "bit(A,1)", sourceRecords = listOf("fixture-discovery"))
    private val pid = CustomPid("synthetic", "Synthetic signal", "2107", "(256*A+B)/4", header = "7E0",
        responseHeader = "7E8", link = link, dataOffset = 1, supportChecks = listOf(snapshot, discovered),
        sourceRecords = listOf("fixture-row"))

    fun reviewedUnsignedArithmeticCoversItsEntireByteDomain() {
        // Decoder equivalence only. This does not approve an ECU, source path,
        // initialization, applicability or an executable recovered definition.
        val first = pid.copy(id = "u8-fixture", request = "2105", formula = "(A-40)", dataOffset = 0,
            supportChecks = emptyList(), min = -40.0, max = 140.0)
        val second = pid.copy(id = "u16-fixture", request = "210C", formula = "(((256*A)+B)/4)", dataOffset = 0,
            supportChecks = emptyList(), min = 0.0, max = 16383.75)
        for (byte in 0..255) {
            val nativeIntegerText = (byte - 40).toString() // native %.0f path
            check(first.decode(listOf(0x61, 5, byte)) == nativeIntegerText.toDouble()) { "Unsigned byte $byte" }
        }
        for (raw in 0..65535) {
            val high = raw ushr 8; val low = raw and 255
            // Independent, exact decimal spelling of the native formatter's
            // integer / three-decimal branches. No vendor evaluator is run.
            val integer = (raw ushr 2).toString()
            val nativeQuarterText = if ((raw and 3) == 0) integer else integer + "." + ((raw and 3) * 250).toString().padStart(3, '0')
            check(second.decode(listOf(0x61, 12, high, low)) == nativeQuarterText.toDouble()) { "Unsigned word $raw" }
        }
        // Native %.0f / %.3f output does not round integers or exact quarters;
        // display ranges must not clamp high unsigned values to a physical guess.
        check(first.decode(listOf(0x61, 5, 255)) == 215.0)
        check(second.decode(listOf(0x61, 12, 255, 255)) == 16383.75)
        check(first.decode(listOf(0x61, 5)) == null)
        check(second.decode(listOf(0x61, 12, 255)) == null)
        for (bad in listOf(-1, 256)) {
            check(first.decode(listOf(0x61, 5, bad)) == null)
            check(second.decode(listOf(0x61, 12, 0, bad)) == null)
        }
    }

    suspend fun supportIsScopedToPollRunAndExactDefinition() {
        var support = true; var member = true; var bytes = listOf(0xEE, 0, 0)
        val adapter = SimulatedCanAdapter(listOf(
            SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000) { request -> when (request) {
                listOf(0xA8, 1) -> listOf(listOf(0xE8, 1, 7, 3, 0x99, if (support) 0xFF else 0, 0))
                listOf(0x21, 0) -> listOf(listOf(0x61, 0, if (member) 2 else 0, 0, 0, 0))
                listOf(0x21, 7) -> listOf(listOf(0x61, 7) + bytes)
                else -> emptyList()
            } },
            SimModule('6', "7E1", "7E9", dialect = DiagnosticDialect.KWP2000) { request -> when (request) {
                listOf(0xA8, 1) -> listOf(listOf(0xE8, 1, 7, 3, 0x99, 0xFF, 0))
                listOf(0x21, 0) -> listOf(listOf(0x61, 0, 0, 0, 0, 0))
                else -> error("An undiscovered sensor must not be polled")
            } }), '6')
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        fun reader() = LiveReader(session, VehicleReader(session, adapter))
        val r = reader(); val def = LiveValueDef.Custom(pid)
        check(r.read(def, EcuTarget.Broadcast, null) == 0.0) // zero is valid on the FIRST sample
        check(adapter.requests.map { it.payload } == listOf(listOf(0xA8, 1), listOf(0x21, 0), listOf(0x21, 7)))
        support = false; member = false; bytes = listOf(0xEE, 0x10, 0)
        check(r.read(def, EcuTarget.Broadcast, null) == 1024.0) // same run uses support snapshot, not sensor contents
        check(adapter.requests.size == 4)
        check(r.read(LiveValueDef.Custom(pid.copy(supportChecks = listOf(snapshot.copy(formula = "0*A")))), EcuTarget.Broadcast, null) == null)
        check(adapter.requests.size == 4) // changed condition is evaluated, not a cached authorization boolean
        check(reader().read(def, EcuTarget.Broadcast, null) == null) // next run probes support again
        check(adapter.requests.last().payload == listOf(0xA8, 1)) // no sensor poll after unsupported snapshot
        support = true
        check(reader().read(def, EcuTarget.Broadcast, null) == null) // snapshot alone cannot replace discovery membership
        check(adapter.requests.last().payload == listOf(0x21, 0))
        check(r.read(LiveValueDef.Custom(pid.copy(header = "7E1", responseHeader = "7E9")), EcuTarget.Broadcast, null) == null)
        check(adapter.requests.last().header == "7E1" && adapter.requests.last().payload == listOf(0x21, 0))
        check(adapter.violations.isEmpty())
    }

    fun supportRecordsAreAtomicAndNotSensorData() {
        val positive = listOf(0xE8, 1, 99, 0, 7, 3, 0x99, 1, 0, 8, 1, 0)
        check(snapshot.accepts(positive))
        check(!snapshot.accepts(listOf(0xE8, 1, 7, 3, 0x99, 0, 0))) // bytes outside window do not admit it
        check(!snapshot.accepts(listOf(0x61, 7, 0xEE, 0xFF, 0xFF))) // a nonzero sensor sample is NOT support
        for (bad in listOf(
            listOf(0xE8, 2, 7, 3, 0x99, 1, 0), // wrong sub-function
            listOf(0xE8, 1, 7, 3, 0x99, 1), // short record
            positive + listOf(7, 1, 1), // duplicate matching record after a valid one
            positive + listOf(8, 0), // duplicate unrelated record also invalidates the whole table
            positive + listOf(9), // incomplete trailing header
            positive + listOf(9, 2, 0), // incomplete trailing data
            listOf(0xE8, 1, 8, 3, 0xFF, 0xFF, 0xFF), // matching record absent
            listOf(0xE8, 1, 7, 0),
            listOf(0x7F, 0xA8, 0x31),
            listOf(0xE8, 1, 7, 1, 256),
        )) check(!snapshot.accepts(bad)) { "Accepted malformed support snapshot: $bad" }
        check(!discovered.accepts(listOf(0x61, 1, 0xFF)))
        check(!discovered.accepts(listOf(0x61, 0)))
        check(discovered.accepts(listOf(0x61, 0, 2)))
        check(!discovered.accepts(listOf(0x61, 0, 4))) // MSB-first membership, not a similar PID bit
    }

    fun supportAndKeepAliveRoundTripsAndVersionGates() {
        check(pid.isValid)
        for (request in listOf("140000", "2701", "1003", "3E", "81", "A802", "A8FF", "2200010002")) {
            check(pid.copy(supportChecks = listOf(snapshot.copy(request = request))).problems().isNotEmpty())
        }
        check(pid.copy(header = null, responseHeader = null).problems().isNotEmpty())
        check(pid.copy(supportChecks = listOf(discovered.copy(recordId = 7))).problems().isNotEmpty())
        check(pid.copy(supportChecks = listOf(snapshot.copy(recordId = 256))).problems().isNotEmpty())
        check(pid.copy(supportChecks = listOf(snapshot.copy(formula = "1"))).problems().isNotEmpty())
        check(pid.copy(supportChecks = listOf(snapshot.copy(formula = "unknown(A)"))).problems().isNotEmpty())
        check(pid.copy(supportChecks = List(33) { discovered }).problems().isNotEmpty())
        check(link.copy(dialect = DiagnosticDialect.UDS).problems().isNotEmpty())
        check(link.copy(testerPresent = listOf(0x10, 1)).problems().isNotEmpty())
        check(!KwpServices.readAllowed(listOf(0xA8, 2)) && KwpServices.readAllowed(listOf(0xA8, 1)))
        val pack = DataPack("fixture", "Fixture", pids = listOf(pid))
        val json = DataPackCodec.encode(pack)
        check(json.contains("\"formatVersion\":4"))
        check(DataPackCodec.decode(json).pack == pack)
        for (v in 1..3) check(!DataPackCodec.decode(json.replace("\"formatVersion\":4", "\"formatVersion\":$v")).isValid)
        check(!DataPackCodec.decode(json.replace("\"recordId\":7", "\"recordId\":7,\"otherEcu\":\"7E1\"")).isValid)
        check(!DataPackCodec.decode(json.replace("\"supportChecks\":", "\"supportFormula\":")).isValid)
        val catalog = CatalogPack("fixture", "Fixture", library = CatalogData(pids = listOf(pid)))
        val document = CatalogCodec.encode(catalog)
        check(CatalogCodec.decodeOrThrow(document) == catalog)
        for (v in 1..2) check(!CatalogCodec.decode(document.replace("\"formatVersion\":3", "\"formatVersion\":$v")).isValid)
        for (scoped in listOf(
            catalog.copy(library = CatalogData(), nodes = listOf(CatalogNode("fixture-make", null, CatalogLevel.MAKE, "Fixture", data = catalog.library))),
            catalog.copy(library = CatalogData(), platforms = listOf(CatalogPlatform("fixture-platform", "Fixture", data = catalog.library))),
        )) {
            val encoded = CatalogCodec.encode(scoped)
            check("\"formatVersion\":3" in encoded && CatalogCodec.decodeOrThrow(encoded) == scoped)
            check(!CatalogCodec.decode(encoded.replace("\"formatVersion\":3", "\"formatVersion\":2")).isValid)
        }
        check(!DataPackCodec.decode("""{"formatVersion":0,"id":"fixture","name":"Fixture"}""").isValid)
        // Metadata gates are required even when an ECU currently has no support-checked PIDs.
        val ecu = EcuDefinition(name = "Fixture", requestId = "7E0", responseId = "7E8", protocol = EcuProtocol.KWP, link = link)
        check("\"formatVersion\":4" in DataPackCodec.encode(pack.copy(pids = emptyList(), vehicles = listOf(VehicleDefinition("Fixture", "Fixture", ecus = listOf(ecu))))))
    }

    suspend fun ambiguousOrNegativeSupportAndSamplesFailClosed() {
        for (failure in listOf("duplicate-support", "negative-support", "duplicate-sample", "negative-sample")) {
            val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000) { request ->
                val payload = when (request) {
                    listOf(0xA8, 1) -> listOf(0xE8, 1, 7, 3, 0x99, 1, 0)
                    listOf(0x21, 0) -> listOf(0x61, 0, 2, 0, 0, 0)
                    listOf(0x21, 7) -> listOf(0x61, 7, 0xEE, 0x10, 0)
                    else -> error("Unexpected read")
                }
                if ((failure.endsWith("support") && request[0] == 0xA8) || (failure.endsWith("sample") && request == listOf(0x21, 7))) {
                    listOf(payload, if (failure.startsWith("duplicate")) payload else listOf(0x7F, request[0], 0x31))
                } else listOf(payload)
            }), '6')
            val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
            check(LiveReader(session, VehicleReader(session, adapter)).read(LiveValueDef.Custom(pid), EcuTarget.Broadcast, null) == null) { failure }
            if (failure.endsWith("support")) check(adapter.requests.size == 1)
            check(adapter.violations.isEmpty())
        }
    }

    suspend fun resetsAndExpiredKlineNeverReuseSupportSnapshot() {
        var now = 1000L
        val k = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
            kLine = KLineConfig(KLineInit.FAST, 0x10))
        val adapter = SimulatedKwpAdapter()
        adapter.ecu.locals[0x21] = listOf(1)
        adapter.ecu.locals[0x20] = listOf(0, 0)
        val p = CustomPid("fixture", "Fixture", "2120", "A+B", header = "8010F1", responseHeader = "80F110", link = k,
            supportChecks = listOf(PidSupportCheck("2121", "A")))
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500, clock = { now }, wait = { now += it })
        val r = LiveReader(session, VehicleReader(session, adapter)); val def = LiveValueDef.Custom(p)
        check(r.read(def, EcuTarget.Broadcast, null) == 0.0)
        check(adapter.ecu.requests == listOf(listOf(0x21, 0x21), listOf(0x21, 0x20)))
        adapter.ecu.locals[0x21] = listOf(0)
        check(r.read(def, EcuTarget.Broadcast, null) == 0.0) // still same session
        now += 6000
        check(r.read(def, EcuTarget.Broadcast, null) == null)
        check(adapter.ecu.requests.last() == listOf(0x21, 0x21))
        check(adapter.commands.count { it.startsWith("STIFI") } == 2)
        adapter.ecu.locals[0x21] = listOf(1)
        session.invalidateRouting()
        check(r.read(def, EcuTarget.Broadcast, null) == 0.0)
        check(adapter.commands.count { it.startsWith("STIFI") } == 3)
        // Invalid prerequisites are refused without ANY adapter traffic, even when constructed in memory.
        val count = adapter.commands.size
        check(r.read(LiveValueDef.Custom(p.copy(supportChecks = listOf(PidSupportCheck("140000", "A")))), EcuTarget.Broadcast, null) == null)
        check(adapter.commands.size == count)
    }

    suspend fun a801MultiMessageReplyIsOneAtomicSupportTable() {
        val fragments = listOf(listOf(0xE8, 1, 99, 0, 7, 3, 0x99), listOf(0xE8, 1, 1, 0, 8, 0))
        check(snapshot.accepts(requireNotNull(PidSupportCheck.normalizeReply(listOf(0xA8, 1), fragments))))
        check(PidSupportCheck.normalizeReply(listOf(0x21, 0), listOf(listOf(0x61, 0, 2), listOf(0x61, 0, 2))) == null)
        check(PidSupportCheck.normalizeReply(listOf(0xA8, 1), List(257) { listOf(0xE8, 1, 1) }) == null)
        check(PidSupportCheck.normalizeReply(listOf(0xA8, 1), listOf(listOf(0xE8, 1) + List(8193) { 0 })) == null)
        for (kline in listOf(false, true)) for (failure in listOf("none", "duplicate", "short", "echo", "negative", "wrong-ecu", "trailing-incomplete-frame")) {
            val replies = when (failure) {
                "duplicate" -> fragments + listOf(listOf(0xE8, 1, 7, 0))
                "short" -> listOf(fragments[0], fragments[1].dropLast(1))
                "echo" -> listOf(fragments[0], listOf(0xE8, 2) + fragments[1].drop(2))
                "negative" -> fragments + listOf(listOf(0x7F, 0xA8, 0x31))
                else -> fragments
            }
            fun answer(request: List<Int>) = when (request) {
                listOf(0xA8, 1) -> replies
                listOf(0x21, 0) -> listOf(listOf(0x61, 0, 2))
                listOf(0x21, 7) -> listOf(listOf(0x61, 7, 0xEE, 0, 0))
                else -> error("Unexpected request $request")
            }
            val base: com.bod3.app.obd.elm.CommandExecutor
            val p: CustomPid
            val requests: List<*>
            if (kline) {
                val adapter = SimulatedKwpAdapter().also { it.ecu.replyOverride = ::answer }
                base = adapter; requests = adapter.ecu.requests
                val k = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
                    kLine = KLineConfig(KLineInit.FAST, 0x10))
                p = pid.copy(header = "8010F1", responseHeader = "80F110", link = k)
            } else {
                val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000, respond = ::answer)), '6')
                base = adapter; requests = adapter.requests; p = pid
            }
            val executor = com.bod3.app.obd.elm.CommandExecutor { command, timeout ->
                val response = base.execute(command, timeout)
                if (command == "A801" && failure in listOf("wrong-ecu", "trailing-incomplete-frame")) {
                    val extra = if (kline) {
                        val header = if (failure == "wrong-ecu") listOf(0x80, 0xF1, 0x11) else listOf(0x80, 0xF1, 0x10)
                        val frame = KLineFrames.encode(header, listOf(0xE8, 1, 10, 0))
                        com.bod3.app.recipe.Hex.format(if (failure == "wrong-ecu") frame else frame.dropLast(1))
                    } else if (failure == "wrong-ecu") "7E904E8010A00" else "7E81014E8010A00"
                    com.bod3.app.obd.elm.ElmResponseParser.parse(command, response.raw + "\r" + extra)
                } else response
            }
            val session = Session(executor, ObdProtocol.ISO_15765_4_CAN_11_500, wait = {})
            val value = LiveReader(session, VehicleReader(session, executor)).read(LiveValueDef.Custom(p), EcuTarget.Broadcast, null)
            check(value == if (failure == "none") 0.0 else null) { "kline=$kline failure=$failure value=$value" }
            if (failure != "none") check(requests.size == 1) { "A partial support table allowed sensor polling: $failure" }
        }
    }

    suspend fun communicationRestartAndDisconnectExpireSameEcuSupport() {
        var support = true
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000) { request -> when (request) {
            listOf(0xA8, 1) -> listOf(listOf(0xE8, 1, 7, 3, 0x99, if (support) 1 else 0, 0))
            listOf(0x21, 0) -> listOf(listOf(0x61, 0, 2))
            listOf(0x21, 7) -> listOf(listOf(0x61, 7, 0xEE, 0, 0))
            listOf(0x81) -> listOf(listOf(0xC1, 0xE9, 0x8F))
            else -> emptyList()
        } }), '6')
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val reader = LiveReader(session, VehicleReader(session, adapter)); val def = LiveValueDef.Custom(pid)
        check(reader.read(def, EcuTarget.Broadcast, null) == 0.0)
        support = false
        KwpClient(session, pid.target as EcuTarget.Physical).startCommunication()
        check(reader.read(def, EcuTarget.Broadcast, null) == null)
        check(adapter.requests.last().payload == listOf(0xA8, 1))
        support = true; session.invalidateRouting()
        check(reader.read(def, EcuTarget.Broadcast, null) == 0.0)
        adapter.disconnected = true
        check(runCatching { reader.read(def, EcuTarget.Broadcast, null) }.isFailure)
        adapter.disconnected = false; support = false
        check(reader.read(def, EcuTarget.Broadcast, null) == null)
        check(adapter.requests.last().payload == listOf(0xA8, 1))
        check(adapter.violations.isEmpty())
    }

    fun incompleteCanMessagesCannotHideBehindValidReplies() {
        val valid = "7E806610002000000"
        val decode = { lines: List<String> -> com.bod3.app.obd.protocol.ResponseDecoder.inspect(lines, ObdProtocol.ISO_15765_4_CAN_11_500) }
        check(decode(listOf(valid)).complete)
        for (bad in listOf("7E807610002000000", "7E81014610002000000", "7E82100000000000000", "7E8", "7E801")) {
            val result = decode(listOf(valid, bad))
            check(!result.complete) { "Accepted partial exchange: $bad" }
            check(result.messages.isNotEmpty()) // the earlier valid subset exists but cannot authorize anything
        }
    }

    suspend fun exactBareKwpKeepAliveDoesNotSwitchOrInitialize() {
        var now = 1000L
        val l = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000,
            kLine = KLineConfig(KLineInit.FAST, 0x10), testerPresent = listOf(0x3E))
        val target = EcuTarget.Physical("8010F1", "80F110", link = l)
        val adapter = SimulatedKwpAdapter()
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500, clock = { now }, wait = { now += it })
        check(!session.keepAliveCurrent(target) && adapter.commands.isEmpty())
        val c = KwpClient(session, target)
        c.readLocal(0x20)
        check(session.keepAliveCurrent(target))
        c.testerPresent()
        check(adapter.ecu.requests.takeLast(2) == listOf(listOf(0x3E), listOf(0x3E)))
        val count = adapter.commands.size
        check(runCatching { session.keepAliveCurrent(target, listOf(0x3E, 0)) }.isFailure)
        check(adapter.commands.size == count)
        now += 6000
        check(!session.keepAliveCurrent(target) && adapter.commands.size == count)
    }
}
