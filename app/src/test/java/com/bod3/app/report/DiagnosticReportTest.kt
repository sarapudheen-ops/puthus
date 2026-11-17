package com.bod3.app.report

import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.ProtocolAttempt
import com.bod3.app.obd.connection.SetupStep
import com.bod3.app.obd.connection.VoltageSample
import com.bod3.app.obd.dtc.DtcKind
import com.bod3.app.obd.dtc.DtcReport
import com.bod3.app.obd.dtc.EcuDtcs
import com.bod3.app.obd.elm.AdapterCapabilities
import com.bod3.app.obd.elm.AdapterIdentity
import com.bod3.app.obd.elm.AdapterInfo
import com.bod3.app.obd.protocol.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    @Test
    fun reportContainsEverySection() {
        val data = ReportData(
            generatedAtMs = 1_763_405_000_000L,
            appVersion = "1.0",
            deviceName = "vLinker MC+",
            deviceAddress = "00:11:22:33:44:55",
            adapter = AdapterInfo(AdapterIdentity.parse("ELM327 v2.2"), "vLinker MC+", AdapterCapabilities("vLinker MC+ V2.2.11", null)),
            profile = ConnectionProfile.DEFAULT,
            setup = listOf(SetupStep("ATAT1", true, "OK")),
            attempts = listOf(
                ProtocolAttempt(null, "ATSP0", "UNABLE TO CONNECT", "AUTO", false, 9_000, "no reply"),
                ProtocolAttempt(ObdProtocol.ISO_9141_2, "ATSP3", "486B104100BE3EB811", "ISO 9141-2", true, 4_000),
            ),
            protocol = "ISO 9141-2",
            vin = "WVGZZZ5NZJW000001",
            voltage = listOf(VoltageSample(0, 12.1), VoltageSample(1, 12.7)),
            dtcs = listOf(DtcReport(DtcKind.STORED, listOf(EcuDtcs("10", listOf("P0420"))), noData = false)),
            adapterLog = listOf("> ATZ", "ELM327 v2.2"),
            terminal = "> ATRV\n12.7V",
        )
        val text = DiagnosticReport.build(data)
        listOf(
            "bod3 diagnostic report", "== Adapter ==", "vLinker MC+ V2.2.11", "== Connection profile ==", "== Adapter setup ==",
            "OK  ATAT1 -> OK", "1. ATSP0", "FAILED", "2. ATSP3", "ATDP -> ISO 9141-2", "Result: ISO 9141-2",
            "VIN: WVGZZZ5NZJW000001", "Latest: 12.70 V", "P0420", "> ATZ", "== Terminal ==", "12.7V",
        ).forEach { assertTrue("missing '$it'\n$text", text.contains(it)) }
        assertEquals("bod3-report-WVGZZZ5NZJW000001-20251117-184320.txt", DiagnosticReport.fileName(1_763_405_000_000L, "WVGZZZ5NZJW000001"))
    }
}
