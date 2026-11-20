package com.bod3.app.profile

import com.bod3.app.obd.scan.*
import com.bod3.app.obd.uds.*

/** Persisted readouts only. These never establish a live ECU identity or operation permission. */
object ModuleMetadataScenarios {
    private val module = DiscoveredModule(ModuleAddress(Bus.CAN_11, "7E0", "7E8"), DetectionMethod.MANUAL_LIST,
        obd = false, negativeReply = false, firstReply = null)
    private val identification = listOf(
        DidReading(0x0904, DidResult.Value("SYNTHETIC-CAL-001".map { it.code }), "fixture-id", "Source calibration records",
            "0904", true, "SYNTHETIC-CAL-001"),
        DidReading(0x21C1, DidResult.Negative(0x31), "fixture-type", "Source type", "21C1", true),
        DidReading(0x1A90, DidResult.NoResponse, "fixture-no-reply", "Source ID", "1A90"),
        DidReading(0xF190, DidResult.Failed("fixture error")),
    )
    private val faults = DtcReadResult.Read("Synthetic exact-key source records", listOf(
        DtcRecord("SAME-PRINTED-CODE", 0x80, listOf(1, 2), DtcStatusMeaning.TOYOTA,
            0xFFFFFFFFL, ToyotaDtcState.CURRENT, "Exact first meaning"),
        DtcRecord("SAME-PRINTED-CODE", null, listOf(1, 2), DtcStatusMeaning.TOYOTA,
            0x12345678L, ToyotaDtcState.HISTORY, "Exact second meaning"),
        DtcRecord("OPAQUE", 0xFF, listOf(3, 4, 0xFF), DtcStatusMeaning.RAW),
        DtcRecord("P0420-13", 0x2F, listOf(4, 0x20, 0x13, 0x2F)),
    ), 0xFF)

    fun sourceReadoutRoundTripRetainsExactKeysLabelsStatesAndIdentificationRendering() {
        val report = ModuleReport(module, "Synthetic source ECU", identification, faults)
        val stored = ModuleCodec.encode("manual-fixture", report, 1)
        val decoded = ModuleCodec.decode(stored)
        check(decoded == report)
        check(decoded.identifiers.first().display == "SYNTHETIC-CAL-001")
        val records = (decoded.dtcs as DtcReadResult.Read).records
        check(records[0].statusText == "Current" && !records[0].warningLamp)
        check(records[1].statusText == "History" && !records[1].testFailed)
        check(!records[2].confirmed && !records[2].pending && !records[2].warningLamp)
        check(records[3].confirmed && records[3].pending)
        check("FFFFFFFF" in stored.dtcsJson && "12345678" in stored.dtcsJson)
    }

    fun legacySavedReadoutsRemainCompatibleAndUnknownStatusSchemasStayOpaque() {
        val stored = ModuleCodec.encode("manual-fixture", ModuleReport(module, null, emptyList(), DtcReadResult.NoResponse), 1)
        val legacy = stored.copy(identifiersJson = """[{"did":61840,"kind":"value","data":"4142"}]""",
            dtcsJson = """{"kind":"read","source":"UDS 19 02","mask":255,"records":[{"code":"P0420-13","status":47,"raw":"0420132F"}]}""")
        val decoded = ModuleCodec.decode(legacy)
        check(decoded.identifiers.single() == DidReading(61840, DidResult.Value(listOf(0x41, 0x42))))
        check((decoded.dtcs as DtcReadResult.Read).records.single().statusMeaning == DtcStatusMeaning.UDS)
        val future = ModuleCodec.decode(legacy.copy(dtcsJson = legacy.dtcsJson.replace("\"status\":47", "\"status\":255,\"statusMeaning\":\"FUTURE\"")))
        val record = (future.dtcs as DtcReadResult.Read).records.single()
        check(record.statusMeaning == DtcStatusMeaning.RAW && !record.warningLamp && !record.confirmed && !record.testFailed)
    }
}
