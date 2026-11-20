package com.bod3.app.diagnostics

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.DiagnosticLink
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcStatusMeaning
import com.bod3.app.obd.uds.ToyotaDtcState
import com.bod3.app.recipe.Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Synthetic byte vectors executed by the pinned native oracle too; no vehicle addresses asserted. */
object ToyotaFaultScenarios {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun word(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }
    private val counted = ToyotaFaultLayout(0, 1, 2, 2, false, 0, 1, 255)
    private val protocol = ObdProtocol.ISO_15765_4_CAN_11_500
    private val ecu = EcuDefinition(name = "Synthetic native-layout ECU", requestId = "7E0", responseId = "7E8",
        protocol = EcuProtocol.KWP, link = DiagnosticLink(protocol, DiagnosticDialect.KWP2000),
        diagnostics = EcuReadDefinitions(faults = FaultRead(DiagnosticRead("read", "Synthetic", "13B0", "53", minBytes = 1), counted)))

    fun everyNativeStateMaskVectorMatchesTheNativeDigest() {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0
        for (kind in 1..10) for (status in 0..255) for (mask in if (kind == 10) 0..255 else listOf(0, 255)) {
            digest.update(bytes(kind, status, mask))
            ToyotaFaultLayout.states(kind, status, mask).forEach { digest.update(word(it.textId)) }
            count++
        }
        check(count == 70144)
        check(hex(digest) == "b8df1d29c9ea83ce79e430d53b8f4df50ac2a7ff70b259aeacebcf8156983d0d")
    }

    fun everyNativeAssemblyVectorMatchesTheNativeDigest() {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0
        for ((assembly, stride) in listOf(1 to 1, 1 to 4, 2 to 2, 3 to 2, 4 to 3, 5 to 3, 6 to 4, 7 to 6)) {
            for (mode in listOf(0, 2)) for (presence in listOf(false, true)) {
                val layout = ToyotaFaultLayout(0, 1, stride, assembly, presence, mode, 1, 255)
                for (kind in listOf(1, 2, 3, 8, 9, 10)) for (value in listOf(0, 1, 4, 8, 0x10, 0x20, 0x28, 0x29, 0x7f, 0x80, 0xff)) {
                    val width = stride - when (assembly) { 5, 6 -> 1; 7 -> 3; else -> 0 }
                    val row = listOf(value) + (1 until width) + when (assembly) { 5, 6 -> listOf(value); 7 -> listOf(0x55, 0xaa, 0xff); else -> emptyList() }
                    val payload = (if (kind == 3 && mode != 2) emptyList() else listOf(3)) + row + row + List(stride) { 0 }
                    val result = layout.copy(queryKind = kind).decode(payload, "synthetic") as DtcReadResult.Read
                    digest.update(bytes(assembly, stride, mode, if (presence) 1 else 0, kind, value))
                    digest.update(word(result.records.size.toLong()))
                    result.records.forEach { digest.update(word(it.toyotaState!!.textId)); digest.update(word(it.nativeCode!!)) }
                    count++
                }
            }
        }
        check(count == 2112)
        check(hex(digest) == "bb1ed0df809e6216d04d56993cf4757453eafbd946125872385e4186b7d06cb5")
    }

    fun sourceNumericFallbackMatchesNativeWidthVectorsWithoutLosingTheKey() {
        val fallback = ToyotaFaultFallback("Synthetic source manual", listOf("synthetic:TEXT#idFFFF0048", "synthetic:InitDtcNum"))
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0
        for (assembly in 1..7) for (high in 0..255) for (low in listOf(0, 255)) {
            val key = (high.toLong() shl 16) or (high.toLong() shl 8) or low.toLong()
            digest.update(bytes(assembly))
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(key.toInt()).array())
            digest.update(fallback.code(key, assembly).toByteArray(Charsets.US_ASCII)); digest.update(0.toByte())
            count++
        }
        check(count == 3584)
        check(hex(digest) == "a259849cf4d39cf99fa6ff60eb0e614fde331694d115654d2f0b18142213c7aa")
        check(fallback.description(0xDEADBEEFL) == "[00, ad, be, ef]-Synthetic source manual")
        val layout = counted.copy(recordBytes = 4, assemblyKind = 1, sourceFallback = fallback)
        val result = (layout.decode(Hex.parse("01 DE AD 04 20")!!, "synthetic") as DtcReadResult.Read).records.single()
        check(result.code == "P0420" && result.nativeCode == 0xDEAD0420L)
        check(result.sourceDescription == "[00, ad, 04, 20]-Synthetic source manual")
        val exact = layout.copy(labels = listOf(ToyotaFaultLabel("DEAD0420", "Exact label", "Exact source description")))
        check((exact.decode(Hex.parse("01 DE AD 04 20")!!, "synthetic") as DtcReadResult.Read).records.single().code == "Exact label")
        check(layout.copy(sourceFallback = fallback.copy(sourceRecords = emptyList())).problems().isNotEmpty())
        val selected = ecu.copy(diagnostics = EcuReadDefinitions(faults = ecu.diagnostics!!.faults!!.copy(layout = layout)))
        val pack = CatalogPack("native-fallback-test", "Synthetic", library = CatalogData(ecus = listOf(selected)))
        val json = CatalogCodec.encode(pack)
        check(CatalogCodec.decodeOrThrow(json) == pack)
        check(!CatalogCodec.decode(json.replace("\"missingDescription\":", "\"unreviewedFallback\":" )).isValid)
    }

    fun exactKeyByteMergesMatchAllNativeVectorsAndHardenCountWrap() {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0
        for (arg0 in 0..255) for (arg1 in 0..255) {
            val merge = ToyotaDtcMapping()
            for ((key, a, b) in listOf(Triple(0x12345678L, arg0, arg1), Triple(0x12345678L, 4, 8), Triple(0x12345679L, 0, 1))) {
                val changed = merge.merge(key, a, b)
                digest.update(bytes(a, b, if (changed) 1 else 0)); digest.update(word(key))
                merge.records.forEach { digest.update(word(it.nativeCode)); digest.update(bytes(it.byte4, it.byte5)) }
                count++
            }
        }
        check(count == 196608)
        check(hex(digest) == "92d9955108602d4c01438a01b08f06c738c5c584502dba9a0327e7be2e731464")
        val merge = ToyotaDtcMapping()
        for (i in 0L..254) check(merge.merge(i, 1, 1))
        val before = merge.records
        check(runCatching { merge.merge(256, 1, 1) }.isFailure)
        check(merge.records == before)
        check(merge.merge(254, 4, 8) && merge.records.last() == ToyotaMergedDtc(254, 9, 5))
        check(runCatching { merge.merge(0x100000000L, 1, 1) }.isFailure)
        val exact = ToyotaDtcMapping()
        check(exact.merge(0x12345678, 1, 2) && exact.merge(0x00345678, 4, 8) && exact.records.size == 2)
    }

    fun exactLabelsAndSourceStatesNeverBecomeGenericOrOtherSystemCodes() {
        val layout = counted.copy(assemblyKind = 5, recordBytes = 3, queryKind = 10, availabilityMask = 0x05,
            labels = listOf(ToyotaFaultLabel("00000420", "P0420", "Synthetic selected-system description", listOf("synthetic:TCODE@00000420"))),
            sourceRecords = listOf("synthetic:fault-layout"))
        val records = (layout.decode(Hex.parse("03 04 20 0f 04 20 0f 01 23 04")!!, "synthetic") as DtcReadResult.Read).records
        check(records.size == 3 && records.map { it.toyotaState } == listOf(ToyotaDtcState.TEST_FAILED, ToyotaDtcState.PENDING, ToyotaDtcState.PENDING))
        check(records[0].code == "P0420" && records[0].sourceDescription == "Synthetic selected-system description")
        check(records[0].statusText == "Test Failed" && records[0].testFailed && !records[0].confirmed && !records[0].warningLamp)
        check(records[1].statusText == "Pending" && records[1].pending && !records[1].testFailed)
        check(records[2].code == "0x00000123" && records[2].sourceDescription == null) // Not a manufactured P0123.
        check(records.all { it.statusMeaning == DtcStatusMeaning.TOYOTA })
        check((layout.copy(labels = emptyList()).decode(Hex.parse("01 04 20 08")!!, "other") as DtcReadResult.Read).records.isEmpty())
        val current = (counted.decode(Hex.parse("01 04 20")!!, "synthetic") as DtcReadResult.Read).records.single()
        check(current.statusText == "Current" && !current.testFailed && !current.confirmed && !current.warningLamp)
    }

    fun nativeSchemaRoundTripsAndRejectsMalformedImportsAtomically() {
        val layout = counted.copy(labels = listOf(ToyotaFaultLabel("FFFFFFFF", "Exact source code", "Text", listOf("synthetic:label"))), sourceRecords = listOf("synthetic:layout"))
        val selected = ecu.copy(diagnostics = ecu.diagnostics!!.copy(faults = ecu.diagnostics.faults!!.copy(layout = layout)))
        check(selected.problems().isEmpty()) { selected.problems() }
        val pack = CatalogPack("native-fault-test", "Synthetic", library = CatalogData(ecus = listOf(selected)))
        val json = CatalogCodec.encode(pack)
        check(CatalogCodec.decodeOrThrow(json) == pack)
        check(CatalogCodec.decodeOrThrow(json).library.ecus.single().diagnostics!!.sourceRecords == listOf("synthetic:layout"))
        for (broken in listOf(
            json.replace("TOYOTA_P3P4", "UNKNOWN"), json.replace("\"countIndex\":0", "\"countIndex\":0.5"),
            json.replace("\"recordBytes\":2", "\"recordBytes\":0"), json.replace("\"countIsPresence\":false", "\"countIsPresence\":1"),
            json.replace("\"modeLowBits\":0", "\"modeLowBits\":8"), json.replace("\"availabilityMask\":255", "\"availabilityMask\":256"),
            json.replace("\"queryKind\":1", "\"queryKind\":11"), json.replace("FFFFFFFF", "1FFFFFFFF"),
            json.replace("\"assemblyKind\":2", "\"assemblyKind\":7"), json.replace("\"labels\":[", "\"verified\":true,\"labels\":["),
        )) check(!CatalogCodec.decode(broken).isValid) { "Accepted malformed native fault schema: $broken" }
        check(layout.copy(labels = layout.labels + layout.labels).problems().isNotEmpty())
        check(FaultLayout(2, 0, 2, statusMeaning = DtcStatusMeaning.TOYOTA).problems().isNotEmpty())
    }

    fun partialReplyAndInvalidCountsNeverReturnAnyFaults() {
        for (data in listOf(emptyList(), listOf(1), listOf(1, 4), listOf(1, 4, 32, 255), listOf(2, 4, 32), listOf(1, 4, 256), List(4097) { 0 }))
            check(counted.decode(data, "synthetic") is DtcReadResult.Failed)
        check((counted.decode(listOf(0), "synthetic") as DtcReadResult.Read).records.isEmpty())
        check((counted.copy(countIsPresence = true).decode(listOf(255, 4, 32), "synthetic") as DtcReadResult.Read).records.single().nativeCode == 0x0420L)
        check((counted.copy(queryKind = 3).decode(listOf(4, 32), "synthetic") as DtcReadResult.Read).records.single().toyotaState == ToyotaDtcState.PENDING)
        check(counted.copy(queryKind = 3, modeLowBits = 2).decode(listOf(4, 32), "synthetic") is DtcReadResult.Failed)
        for (layout in listOf(counted.copy(recordStart = -1), counted.copy(recordBytes = 5), counted.copy(assemblyKind = 5, recordBytes = 1)))
            check(layout.decode(listOf(1, 4, 32), "synthetic") is DtcReadResult.Failed)
    }

    suspend fun workbenchUsesTheExactSelectedNativeLayoutOnly() {
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "7E0", "7E8", dialect = DiagnosticDialect.KWP2000, respond = { request ->
            check(request == listOf(0x13, 0xB0)) { "Generic fallback sent: $request" }
            listOf(listOf(0x53, 1, 4, 32))
        })))
        val result = EcuWorkbench(Session(adapter, protocol)).readDtcs(ecu) as DtcReadResult.Read
        check(result.records.single().nativeCode == 0x0420L && result.records.single().toyotaState == ToyotaDtcState.CURRENT)
        check(result.records.single().code == "0x00000420")
        check(adapter.requests.map { it.payload } == listOf(listOf(0x13, 0xB0)) && adapter.violations.isEmpty())
    }
}
