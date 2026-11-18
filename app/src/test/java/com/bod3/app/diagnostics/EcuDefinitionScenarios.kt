package com.bod3.app.diagnostics

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.DiagnosticLink
import com.bod3.app.obd.kwp.KLineConfig
import com.bod3.app.obd.kwp.KLineInit
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimKwpEcu
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.testing.SimulatedKwpAdapter
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcStatusMeaning
import com.bod3.app.obd.uds.UdsClearResult
import com.bod3.app.profile.JsonWriter
import com.bod3.app.recipe.Hex

/** Synthetic data only. Runnable both by JUnit/CI and a standalone compiler without Android. */
object EcuDefinitionScenarios {
    private val definitions = EcuReadDefinitions(
        identification = listOf(IdentificationRead(DiagnosticRead("synthetic-id", "Synthetic identity", "1A90", "5A90",
            minBytes = 3, maxBytes = 3, sourceRecords = listOf("synthetic:identifier")), IdentificationEncoding.ASCII)),
        faults = FaultRead(DiagnosticRead("synthetic-faults", "Synthetic fault layout", "18 FF FF FF", "58", minBytes = 1,
            sourceRecords = listOf("synthetic:faults")), FaultLayout(3, 0, 2, statusOffset = 2, countBytes = 1),
            DiagnosticClear("14 12 34", "54", listOf("synthetic:clear"))),
    )
    private val link = DiagnosticLink(ObdProtocol.ISO_14230_4_KWP_FAST, DiagnosticDialect.KWP2000, kLine = KLineConfig(KLineInit.FAST, 0x10))
    private val ecu = EcuDefinition(name = "Synthetic KWP ECU", requestId = "8010F1", responseId = "80F110", protocol = EcuProtocol.KWP,
        link = link, sourceRecords = listOf("synthetic:ecu"), diagnostics = definitions)
    private inline fun rejects(block: () -> Unit) {
        val error = try { block(); null } catch (e: IllegalArgumentException) { e }
        check(error != null) { "Invalid definition/operation was accepted" }
    }
    private suspend inline fun rejectsOperation(crossinline block: suspend () -> Unit) {
        val error = try { block(); null } catch (e: IllegalArgumentException) { e }
        check(error != null) { "Invalid operation was accepted" }
    }

    fun roundTripsAndVersionGates() {
        check(ecu.problems().isEmpty()) { ecu.problems() }
        val data = DataPack("synthetic", "Synthetic only", vehicles = listOf(VehicleDefinition("Synthetic", "Model", ecus = listOf(ecu))))
        val json = DataPackCodec.encode(data)
        check("\"formatVersion\":3" in json)
        check(DataPackCodec.decodeOrThrow(json) == data)
        check(!DataPackCodec.decode(json.replace("\"formatVersion\":3", "\"formatVersion\":2")).isValid)
        val catalog = CatalogPack("synthetic", "Synthetic", library = CatalogData(ecus = listOf(ecu)))
        val cjson = CatalogCodec.encode(catalog)
        check("\"formatVersion\":2" in cjson)
        check(CatalogCodec.decodeOrThrow(cjson) == catalog)
        check(!CatalogCodec.decode(cjson.replace("\"formatVersion\":2", "\"formatVersion\":1")).isValid)
        val edited = CatalogCodec.dataOrThrow(JsonWriter.write(CatalogCodec.dataMap(catalog.library)))
        check(edited.ecus.single() == ecu)
        check(edited.ecus.single().diagnostics!!.sourceRecords == listOf("synthetic:identifier", "synthetic:faults", "synthetic:clear"))
        rejects { com.bod3.app.catalog.CatalogCsv.encode(catalog) } // Cannot drop explicit reads and resurrect generic defaults.
    }

    fun invalidDefinitionsAreAtomic() {
        val pack = CatalogPack("synthetic", "Synthetic", library = CatalogData(ecus = listOf(ecu)))
        val json = CatalogCodec.encode(pack)
        for (broken in listOf(
            json.replace("1A90", "3B9001"), // no writes through an identification definition
            json.replace("5A90", "5A91"), // wrong echo
            json.replace("\"dataOffset\":0", "\"dataOffset\":-1"),
            json.replace("\"minBytes\":3", "\"minBytes\":4"),
            json.replace("\"recordBytes\":3", "\"recordBytes\":2"),
            json.replace("\"statusOffset\":2", "\"statusOffset\":1"),
            json.replace("14 12 34", "14 FF FF FF"), // UDS length on KWP
            json.replace("\"encoding\":\"ASCII\"", "\"encoding\":\"GUESS\""),
            json.replace("\"diagnostics\":{", "\"diagnostics\":{\"verified\":true,"),
            json.replace("\"countBytes\":1", "\"countBytes\":1.5"),
        )) {
            val result = CatalogCodec.decode(broken)
            check(!result.isValid && result.pack == null && result.errors.isNotEmpty()) { "Accepted malformed document: $broken" }
        }
        check(ecu.copy(link = null).problems().isNotEmpty())
        check(ecu.copy(protocol = EcuProtocol.UDS).problems().isNotEmpty())
        check(definitions.copy(identification = definitions.identification + definitions.identification).problems(EcuProtocol.KWP, link).isNotEmpty())
    }

    fun exactDataWindowsAndFaultLayouts() {
        val r = DiagnosticRead("synthetic", "Synthetic", "2120", "6120", dataOffset = 1, minBytes = 2, maxBytes = 2)
        check(r.data(listOf(0x61, 0x20, 0xFF, 0x12, 0x34)) == listOf(0x12, 0x34))
        for (data in listOf(listOf(0x61, 0x21, 0xFF, 0x12, 0x34), listOf(0x61, 0x20, 0xFF, 0x12),
            listOf(0x61, 0x20, 0xFF, 0x12, 0x34, 0x56))) check(r.data(data) == null)
        val layout = definitions.faults!!.layout
        val record = (layout.decode(listOf(1, 1, 0x23, 0x88), "synthetic") as DtcReadResult.Read).records.single()
        check(record.code == "P0123" && record.status == 0x88 && record.statusMeaning == DtcStatusMeaning.RAW)
        check(record.statusText == "raw status 0x88" && !record.confirmed && !record.warningLamp)
        check((layout.decode(listOf(0), "synthetic") as DtcReadResult.Read).records.isEmpty())
        for (data in listOf(listOf(1), listOf(0, 1, 0x23, 0x88), listOf(1, 1, 0x23), listOf(2, 1, 0x23, 0x88))) {
            check(layout.decode(data, "synthetic") is DtcReadResult.Failed)
        }
        val uds = FaultLayout(4, 0, 3, statusOffset = 3, statusMeaning = DtcStatusMeaning.UDS)
        val dtc = (uds.decode(listOf(4, 0x20, 0x13, 8), "synthetic") as DtcReadResult.Read).records.single()
        check(dtc.code == "P0420-13" && dtc.confirmed)
    }

    suspend fun kwpReadsAndExactOneShotClear() {
        val adapter = SimulatedKwpAdapter()
        var now = 1000L
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500, clock = { now }, wait = { now += it })
        val bench = EcuWorkbench(session)
        val id = bench.readIdentification(ecu).single()
        check(id.label == "Synthetic identity" && id.display == "SIM" && id.request == "1A90")
        val dtc = (bench.readDtcs(ecu) as DtcReadResult.Read).records.single()
        check(dtc.code == "P0123" && dtc.statusText == "raw status 0x20")
        check(adapter.ecu.requests == listOf(listOf(0x1A, 0x90), listOf(0x18, 0xFF, 0xFF, 0xFF)))
        rejectsOperation { bench.readRecords(ecu, dtc) }
        val wrong = ClearConfirmation.kwpClear(ecu.target, 0xFFFF)
        rejectsOperation { bench.clear(ecu, wrong) }
        check(!wrong.isUsed && adapter.ecu.dtcs)
        val c = EcuWorkbench.confirmation(ecu)
        check(c.request == listOf(0x14, 0x12, 0x34))
        check(bench.clear(ecu, c) == UdsClearResult.Cleared && c.isUsed)
        rejectsOperation { bench.clear(ecu, c) }
        check(adapter.ecu.requests.count { it.first() == 0x14 } == 1)
        check((bench.readDtcs(ecu) as DtcReadResult.Read).records.isEmpty())
    }

    suspend fun explicitEmptyDefinitionsNeverFallBack() {
        val adapter = SimulatedKwpAdapter()
        val bench = EcuWorkbench(Session(adapter, link.protocol))
        val empty = ecu.copy(diagnostics = EcuReadDefinitions())
        check(bench.readIdentification(empty).isEmpty())
        check(bench.readDtcs(empty) is DtcReadResult.Failed)
        check(!EcuWorkbench.canReadIdentification(empty) && !EcuWorkbench.canReadDtcs(empty) && !EcuWorkbench.canClear(empty))
        check(adapter.commands.isEmpty())
        val udsempty = empty.copy(requestId = "7E0", responseId = "7E8", protocol = EcuProtocol.UDS, link = null)
        check(bench.readIdentification(udsempty).isEmpty() && bench.readDtcs(udsempty) is DtcReadResult.Failed)
        check(adapter.commands.isEmpty())
    }

    suspend fun kwpOverCanAndUdsUseOnlySelectedDefinitions() {
        for (kwp in listOf(true, false)) {
            val simulation = SimKwpEcu()
            val module = SimModule('6', "7E0", "7E8", respond = { r ->
                if (!kwp && r == listOf(0x19, 2, 0xFF)) listOf(listOf(0x59, 2, 0xFF, 4, 0x20, 0x13, 8)) else simulation.respond(r)
            })
            val adapter = SimulatedCanAdapter(listOf(module))
            val cLink = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500, if (kwp) DiagnosticDialect.KWP2000 else DiagnosticDialect.UDS)
            val defs = if (kwp) definitions else EcuReadDefinitions(
                identification = listOf(IdentificationRead(DiagnosticRead("synthetic-uds", "Synthetic UDS", "221234", "621234", minBytes = 2, maxBytes = 2))),
                faults = FaultRead(DiagnosticRead("synthetic-uds-fault", "Synthetic UDS faults", "19 02 FF", "59 02", dataOffset = 1), FaultLayout(4, 0, 3, 3, DtcStatusMeaning.UDS)),
            )
            val selected = ecu.copy(requestId = "7E0", responseId = "7E8", protocol = if (kwp) EcuProtocol.KWP else EcuProtocol.UDS, link = cLink, diagnostics = defs)
            check(selected.problems().isEmpty()) { selected.problems() }
            val bench = EcuWorkbench(Session(adapter, cLink.protocol))
            val reading = bench.readIdentification(selected).single()
            check(reading.display == if (kwp) "SIM" else "AB CD")
            val dtc = (bench.readDtcs(selected) as DtcReadResult.Read).records.single()
            check(dtc.code == if (kwp) "P0123" else "P0420-13")
            if (!kwp) check(dtc.confirmed)
            check(adapter.requests.all { it.header == "7E0" })
            check(adapter.requests.none { it.payload == listOf(0x22, 0xF1, 0x87) })
            // CAN transport alone must not make a KWP ECU expose UDS snapshots.
            check(!EcuWorkbench.canReadRecords(selected))
        }
    }

    suspend fun malformedPositiveAndNrcNeverBecomeSuccess() {
        var reply = listOf(0x5A, 0x90, 1)
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", respond = { listOf(reply) })))
        val selected = ecu.copy(requestId = "7E0", responseId = "7E8", link = DiagnosticLink(ObdProtocol.ISO_15765_4_CAN_11_500, DiagnosticDialect.KWP2000))
        val bench = EcuWorkbench(Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500))
        check(bench.readIdentification(selected).single().result is DidResult.Failed)
        reply = listOf(0x7F, 0x1A, 0x31)
        check(bench.readIdentification(selected).single().result == DidResult.Negative(0x31))
        reply = listOf(0x58, 2, 1, 0x23, 0x20)
        check(bench.readDtcs(selected) is DtcReadResult.Failed)
        reply = listOf(0x7F, 0x18, 0x78)
        check(bench.readDtcs(selected) == DtcReadResult.Negative(0x78))
        check(adapter.requests.size == 4) // no automatic retries, mode changes or generic fallbacks
    }
}
