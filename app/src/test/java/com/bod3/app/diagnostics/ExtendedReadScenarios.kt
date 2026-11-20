package com.bod3.app.diagnostics

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.datapack.*
import com.bod3.app.obd.kwp.*
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.*
import com.bod3.app.obd.testing.*
import com.bod3.app.obd.uds.*

/** Synthetic ECU transport/replies; byte enum fixture exercises the selected-width compiler schema. */
object ExtendedReadScenarios {
    private val protocol = ObdProtocol.ISO_15765_4_CAN_11_500
    private val link = DiagnosticLink(protocol, DiagnosticDialect.KWP2000, testerPresent = listOf(0x3E))
    private val checks = listOf(
        PidSupportCheck("A801", "A", 17, listOf("fixture-snapshot"), 0xC1),
        PidSupportCheck("21C0", "bit(A,7)", sourceRecords = listOf("fixture-discovery")),
    )
    private val read = DiagnosticRead("fixture-fuel", "Fuel type (not ECU serial/calibration ID)", "21C1", "61C1",
        dataOffset = 17, minBytes = 1, maxBytes = 32, sourceRecords = listOf("fixture-row"), supportChecks = checks, dataBytes = 1)
    private val enum = IdentificationRead(read, IdentificationEncoding.BYTE_ENUM,
        mapOf(1 to "Gasoline", 2 to "Diesel", 4 to "CNG-LPG"), "-No Data-")
    private val ecu = EcuDefinition(name = "Synthetic", requestId = "7E0", responseId = "7E8", protocol = EcuProtocol.KWP,
        link = link, diagnostics = EcuReadDefinitions(identification = listOf(enum)))

    fun exactSelectedWidthAndEnumVersionCannotBeDiscarded() {
        check(ecu.problems().isEmpty()) { ecu.problems() }
        val selected = listOf(0x61, 0xC1) + List(17) { 0x20 } + listOf(2, 0xFF)
        check(read.data(selected) == listOf(2))
        check(enum.display(read.data(selected)!!) == "Diesel")
        check(enum.display(listOf(0xFE)) == "-No Data-")
        check(enum.display(emptyList()) == null && enum.display(listOf(1, 2)) == null)
        for (bad in listOf(selected.dropLast(2), selected + List(32) { 0 }, selected.mapIndexed { i, b -> if (i == 1) 0xC2 else b }))
            check(read.data(bad) == null)
        for (bad in listOf(enum.copy(byteValues = emptyMap()), enum.copy(defaultText = null), enum.copy(read = read.copy(dataBytes = null)),
            enum.copy(encoding = IdentificationEncoding.ASCII))) check(bad.problems().isNotEmpty())
        val data = DataPack("fixture", "Fixture", vehicles = listOf(VehicleDefinition("Synthetic", "Model", ecus = listOf(ecu))))
        val json = DataPackCodec.encode(data)
        check("\"formatVersion\":5" in json && DataPackCodec.decodeOrThrow(json) == data)
        check(!DataPackCodec.decode(json.replace("\"formatVersion\":5", "\"formatVersion\":4")).isValid)
        val catalog = CatalogPack("fixture", "Fixture", library = CatalogData(ecus = listOf(ecu)))
        val text = CatalogCodec.encode(catalog)
        check("\"formatVersion\":4" in text && CatalogCodec.decodeOrThrow(text) == catalog)
        check(!CatalogCodec.decode(text.replace("\"formatVersion\":4", "\"formatVersion\":3")).isValid)
        for (broken in listOf(text.replace("\"01\":\"Gasoline\"", "\"1\":\"Gasoline\""),
            text.replace("\"dataBytes\":1", "\"dataBytes\":0"), text.replace("\"dataBytes\":1", "\"dataBytes\":2"),
            text.replace("\"recordId\":193", "\"recordId\":256"), text.replace("\"request\":\"A801\"", "\"request\":\"105F\"")))
            check(!CatalogCodec.decode(broken).isValid) { "Malformed extended read was accepted: $broken" }
    }

    suspend fun identificationUsesCurrentOrderedSupportAndNeverSensorNonzeroOrSiblingEcu() {
        var supported = true; var member = true; var value = 2
        var guards = 0
        fun responses(request: List<Int>): List<List<Int>> = when (request) {
            listOf(0xA8, 1) -> listOf(listOf(0xE8, 1, 0xC1, 18) + List(17) { 0 } + listOf(if (supported) 1 else 0))
            listOf(0x21, 0xC0) -> listOf(listOf(0x61, 0xC0, if (member) 0x80 else 0))
            listOf(0x21, 0xC1) -> listOf(listOf(0x61, 0xC1) + List(17) { 0 } + listOf(value))
            else -> error("No fallback may be sent: $request")
        }
        val adapter = SimulatedCanAdapter(listOf(
            SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000, respond = ::responses),
            SimModule('6', "7E1", "7E9", dialect = DiagnosticDialect.KWP2000, respond = ::responses),
        ))
        val session = Session(adapter, protocol, ecuOperationGuard = { _, _ -> guards++; error("A read must not require or confer write proof") })
        val bench = EcuWorkbench(session)
        check(bench.readIdentification(ecu).single().display == "Diesel")
        check(adapter.requests.map { it.payload } == listOf(listOf(0xA8, 1), listOf(0x21, 0xC0), listOf(0x21, 0xC1)))
        value = 0
        check(bench.readIdentification(ecu).single().display == "-No Data-") // zero sensor data does not revoke support
        check(adapter.requests.size == 4)
        session.invalidateRouting(); supported = false
        check(bench.readIdentification(ecu).single().result is DidResult.Failed)
        check(adapter.requests.last().payload == listOf(0xA8, 1) && adapter.requests.size == 5)
        supported = true; member = false; session.invalidateRouting()
        check(bench.readIdentification(ecu).single().result is DidResult.Failed)
        check(adapter.requests.last().payload == listOf(0x21, 0xC0) && adapter.requests.size == 7)
        // A different physical ECU cannot borrow the first one's cached support replies.
        val sibling = ecu.copy(requestId = "7E1", responseId = "7E9")
        check(bench.readIdentification(sibling).single().result is DidResult.Failed)
        check(adapter.requests.takeLast(2).all { it.header == "7E1" })
        check(guards == 0 && adapter.violations.isEmpty())
    }

    suspend fun calibrationRecordsUse0904WithoutRelabelingFuelTypeOrIgnoringATruncatedId() {
        val r = DiagnosticRead("fixture-calibration", "Calibration ID", "0904", "4904", dataOffset = 1,
            minBytes = 16, maxBytes = 240, sourceRecords = listOf("fixture-program-14"))
        val definition = IdentificationRead(r, IdentificationEncoding.ASCII_RECORDS, recordBytes = 16)
        val selected = ecu.copy(diagnostics = EcuReadDefinitions(identification = listOf(definition)))
        check(selected.problems().isEmpty()) { selected.problems() }
        val a = "CAL0123456789012".map(Char::code)
        val b = "SYNTHETIC".map(Char::code) + List(7) { 0 }
        var reply = listOf(0x49, 0x04, 2) + a + b
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000) { request ->
            check(request == listOf(9, 4)) { "Calibration read must not use 21C1, 1A21 or a generic DID" }
            listOf(reply)
        }))
        val bench = EcuWorkbench(Session(adapter, protocol))
        check(bench.readIdentification(selected).single().display == "CAL0123456789012 · SYNTHETIC")
        for (bad in listOf(reply.dropLast(1), listOf(0x49, 0x05, 1) + a, listOf(0x49, 4, 1) + List(16) { 0 },
            listOf(0x49, 4, 1) + listOf(65, 0, 66) + List(13) { 0 }, listOf(0x49, 4, 16) + List(16) { a }.flatten())) {
            reply = bad
            check(bench.readIdentification(selected).single().result is DidResult.Failed)
        }
        val pack = DataPack("fixture", "Fixture", vehicles = listOf(VehicleDefinition("Synthetic", "Model", ecus = listOf(selected))))
        val encoded = DataPackCodec.encode(pack)
        check("\"formatVersion\":5" in encoded && DataPackCodec.decodeOrThrow(encoded) == pack)
        check(!DataPackCodec.decode(encoded.replace("\"formatVersion\":5", "\"formatVersion\":4")).isValid)
        check(!DataPackCodec.decode(encoded.replace("\"recordBytes\":16", "\"recordBytes\":0")).isValid)
        check(!DataPackCodec.decode(encoded.replace("\"recordBytes\":16", "\"recordBytes\":17")).isValid)
        check(!DataPackCodec.decode(encoded.replace("ASCII_RECORDS", "ASCII")).isValid)
        check(adapter.requests.all { it.header == "7E0" } && adapter.writes.isEmpty() && adapter.clears.isEmpty() && adapter.violations.isEmpty())
    }

    suspend fun supportReadLeaseCannotConsumeGrantsConfirmationsOrCrossTargets() {
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000) { emptyList() }))
        var guards = 0
        val session = Session(adapter, protocol, ecuOperationGuard = { _, _ -> guards++ })
        val grant = WriteGate.issue(WritePurpose.CODING, ecu.target, true, scope = WriteScope(listOf(1), session = 0x03))
        val clear = ClearConfirmation.kwpClear04(ecu.target)
        session.withReadOnlyEcuOperation(ecu.target) {
            suspend fun fails(block: suspend () -> Unit) { check(runCatching { block() }.isFailure) }
            fails { session.request(ecu.target, listOf(0x3B, 1, 1), grant = grant) }
            fails { session.request(ecu.target, listOf(0x04), confirmation = clear) }
            fails { session.request(ecu.target, listOf(0x10, 0x5F)) }
            fails { session.request(ecu.target.copy(requestHeader = "7E1", responseHeader = "7E9"), listOf(0x21, 0)) }
            fails { session.withEcuOperation(ecu.target) { session.request(ecu.target, listOf(0x3B, 1, 1), grant = grant) } }
        }
        check(adapter.commands.isEmpty() && !clear.isUsed && grant.isActive && guards == 0)
    }

    suspend fun onlyExactSelectedKeepaliveVariantsAndCompleteRepliesAreAccepted() {
        for (bytes in listOf(listOf(0x3E), listOf(0x3E, 0), listOf(0x3E, 1), listOf(0x21, 0), listOf(1, 0), listOf(0x21, 2), listOf(0x21, 0xE1))) {
            val selected = link.copy(testerPresent = bytes)
            check(selected.problems().isEmpty())
            var extra = emptyList<List<Int>>()
            var positive = listOf(bytes[0] + 0x40) + bytes.drop(1)
            val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000,
                respond = { listOf(positive) + extra })))
            val target = ecu.target.copy(link = selected)
            val session = Session(adapter, protocol)
            check(!session.keepAliveCurrent(target) && adapter.commands.isEmpty())
            session.request(target, bytes)
            check(session.keepAliveCurrent(target))
            extra = listOf(listOf(0x7F, bytes[0], 0x22))
            check(!session.keepAliveCurrent(target))
            extra = listOf(positive)
            check(!session.keepAliveCurrent(target))
            extra = emptyList(); positive = emptyList()
            check(!session.keepAliveCurrent(target))
            if (bytes == listOf(0x3E, 1)) {
                check(!KwpServices.readAllowed(bytes) && !ReadOnlyPolicy.isAllowed(bytes))
                check(link.copy(dialect = DiagnosticDialect.UDS, testerPresent = bytes).problems().isNotEmpty())
                val other = target.copy(link = selected.copy(testerPresent = listOf(0x3E)))
                check(runCatching { session.request(other, bytes) }.isFailure)
            }
            val pack = DataPack("fixture", "Fixture", vehicles = listOf(VehicleDefinition("Synthetic", "Model", ecus = listOf(ecu.copy(link = selected)))))
            val encoded = DataPackCodec.encode(pack)
            check(DataPackCodec.decodeOrThrow(encoded) == pack)
            if (selected.needsExtendedKeepAliveSchema) check("\"formatVersion\":5" in encoded)
        }
    }
}
