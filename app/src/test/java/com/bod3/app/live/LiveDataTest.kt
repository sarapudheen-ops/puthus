package com.bod3.app.live

import com.bod3.app.datapack.CustomPid
import com.bod3.app.obd.VehicleReader
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDataTest {

    @Test
    fun customPidDecodeChecksTheEcho() {
        val boost = CustomPid("boost", "Boost", "22F40C", "(A*256+B)/100", "bar", decimals = 2)
        assertEquals(1.5, boost.decode(listOf(0x62, 0xF4, 0x0C, 0x00, 0x96))!!, 1e-9)
        assertNull(boost.decode(listOf(0x62, 0xF4, 0x0D, 0x00, 0x96))) // other DID
        assertNull(boost.decode(listOf(0x7F, 0x22, 0x31)))
        assertNull(boost.decode(listOf(0x62, 0xF4, 0x0C, 0x00))) // too short for B
        assertEquals("1.50", boost.format(1.5))
        assertTrue(CustomPid("x", "x", "2E0600", "A").problems().single().startsWith("request:"))
        assertEquals(EcuTarget.Broadcast, boost.target)
    }

    @Test
    fun liveReaderReadsStandardAndCustomValues() = runBlocking<Unit> {
        val engine = SimModule('6', "7E0", "7E8", obd = true) { request ->
            when (request) {
                listOf(0x01, 0x0C) -> listOf(listOf(0x41, 0x0C, 0x1A, 0xF8))
                listOf(0x22, 0xF4, 0x0C) -> listOf(listOf(0x62, 0xF4, 0x0C, 0x00, 0x96))
                else -> emptyList()
            }
        }
        val adapter = SimulatedCanAdapter(listOf(engine), '6')
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val reader = LiveReader(session, VehicleReader(session, adapter))
        val rpm = reader.read(LiveValueDef.Standard(Pids.ENGINE_RPM), EcuTarget.Broadcast, null)
        assertEquals(1726.0, rpm!!, 1e-9)
        val boost = CustomPid("boost", "Boost", "22F40C", "(A*256+B)/100", "bar", header = "7E0", responseHeader = "7E8")
        assertEquals(1.5, reader.read(LiveValueDef.Custom(boost), EcuTarget.Broadcast, null)!!, 1e-9)
        assertEquals("7E0", adapter.requests.last().header)
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun recordingAndCsv() {
        val rpm = LiveValueDef.Standard(Pids.ENGINE_RPM)
        val custom = LiveValueDef.Custom(CustomPid("b", "Boost, rel", "22F40C", "A", "bar"))
        val recorder = LiveRecorder(maxRows = 2)
        recorder.add(RecordedRow(0, emptyMap())) // ignored: not recording
        recorder.start(listOf(rpm, custom), 1_763_405_000_000L)
        recorder.add(RecordedRow(1_763_405_000_000L, mapOf(rpm.key to 750.0, custom.key to 1.25)))
        recorder.add(RecordedRow(1_763_405_000_500L, mapOf(rpm.key to 812.5, custom.key to null)))
        recorder.add(RecordedRow(1_763_405_001_000L, mapOf(rpm.key to 900.0)))
        assertTrue(recorder.isFull)
        val recording = recorder.stop()!!
        assertEquals(2, recording.rows.size)
        val csv = CsvExporter.format(recording, "Tiguan")
        assertEquals(
            listOf(
                "# Tiguan",
                "elapsed_s,time_utc,Engine speed (rpm),\"Boost, rel (bar)\"",
                "0.000,2025-11-17T18:43:20.000Z,750,1.25",
                "0.500,2025-11-17T18:43:20.500Z,812.5,",
            ),
            csv.trimEnd().lines(),
        )
        assertEquals("bod3-live-20251117-184320.csv", CsvExporter.fileName(1_763_405_000_000L))
    }

    @Test
    fun catalogAndPidFormulas() {
        val available = LiveCatalog.available(setOf(0x0C, 0x05, 0xA6), listOf(CustomPid("b", "B", "010B", "A")))
        assertEquals(listOf("0105", "010C", "01A6", "custom:b"), available.map { it.key })
        assertEquals(listOf("010C", "0105"), LiveCatalog.defaultKeys(setOf(0x0C, 0x05, 0xA6)))
        assertTrue(Pids.all.size >= 80)
        assertEquals(Pids.all.size, Pids.all.map { it.pid }.distinct().size)
        assertEquals(12345.6, Pids.ODOMETER.decode(listOf(0x00, 0x01, 0xE2, 0x40))!!, 1e-6)
        assertEquals(-25.0, Pids.STFT_B1.decode(listOf(0x60))!!, 1e-9)
        assertEquals(-2.0, Pids.EVAP_VAPOR_PRESSURE.decode(listOf(0xFF, 0xF8))!!, 1e-9)
        assertEquals(0.9998, Pids.COMMANDED_LAMBDA.decode(listOf(0x7F, 0xF9))!!, 1e-3)
        assertEquals("O2 sensor 2-1 voltage", Pids.byPid(0x18)!!.name)
    }
}
