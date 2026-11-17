package com.bod3.app.obd.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    private fun bytes(hex: String): List<Int> = hex.replace(" ", "").chunked(2).map { it.toInt(16) }

    @Test
    fun parsesCan11LineWithoutSpaces() {
        val frame = FrameParser.parseLine("7E8064100BE3EA813", HeaderFormat.CAN_11)!!
        assertEquals("7E8", frame.header)
        assertEquals(bytes("06 41 00 BE 3E A8 13"), frame.data)
    }

    @Test
    fun parsesCan11LineWithSpaces() {
        val frame = FrameParser.parseLine("7E8 06 41 00 BE 3E A8 13", HeaderFormat.CAN_11)!!
        assertEquals("7E8", frame.header)
        assertEquals(7, frame.data.size)
    }

    @Test
    fun parsesCan29Line() {
        val frame = FrameParser.parseLine("18DAF110064100BE3EA813", HeaderFormat.CAN_29)!!
        assertEquals("18DAF110", frame.header)
        assertEquals(bytes("06 41 00 BE 3E A8 13"), frame.data)
    }

    @Test
    fun legacyLineDropsChecksum() {
        val frame = FrameParser.parseLine("48 6B 10 41 00 BE 1F B8 10 AA", HeaderFormat.LEGACY)!!
        assertEquals("486B10", frame.header)
        assertEquals(bytes("41 00 BE 1F B8 10"), frame.data)
    }

    @Test
    fun rejectsNonFrames() {
        assertNull(FrameParser.parseLine("NO DATA", HeaderFormat.CAN_11))
        assertNull(FrameParser.parseLine("OK", HeaderFormat.CAN_11))
        assertNull(FrameParser.parseLine("7E8", HeaderFormat.CAN_11))
        assertNull(FrameParser.parseLine("7E806410", HeaderFormat.CAN_11)) // odd number of data digits
        assertNull(FrameParser.parseLine("12.6V", HeaderFormat.CAN_11))
    }

    @Test
    fun singleFrameTrimsPadding() {
        val frames = FrameParser.parse(listOf("7E8064100BE3EA8135555"), HeaderFormat.CAN_11)
        val messages = IsoTp.assemble(frames)
        assertEquals(listOf(EcuMessage("7E8", bytes("41 00 BE 3E A8 13"))), messages)
    }

    @Test
    fun multiFrameVinIsReassembled() {
        val lines = listOf(
            "7E81014490201314434",
            "7E82147503030523535",
            "7E82242313233343536",
        )
        val messages = IsoTp.assemble(FrameParser.parse(lines, HeaderFormat.CAN_11))
        assertEquals(1, messages.size)
        val payload = messages[0].payload
        assertEquals(20, payload.size)
        assertEquals(bytes("49 02 01"), payload.take(3))
        assertEquals("1D4GP00R55B123456", payload.drop(3).map { it.toChar() }.joinToString(""))
    }

    @Test
    fun interleavedEcusAreReassembledIndependently() {
        val lines = listOf(
            "7E81014490201314434",
            "7E9064100BE3EA813",
            "7E82147503030523535",
            "7E82242313233343536",
        )
        val messages = IsoTp.assemble(FrameParser.parse(lines, HeaderFormat.CAN_11))
        assertEquals(listOf("7E9", "7E8"), messages.map { it.header })
        assertEquals(20, messages.first { it.header == "7E8" }.payload.size)
    }

    @Test
    fun outOfOrderConsecutiveFrameDropsMessage() {
        val lines = listOf("7E81014490201314434", "7E82242313233343536")
        assertTrue(IsoTp.assemble(FrameParser.parse(lines, HeaderFormat.CAN_11)).isEmpty())
    }

    @Test
    fun incompleteMultiFrameIsDropped() {
        val lines = listOf("7E81014490201314434", "7E82147503030523535")
        assertTrue(IsoTp.assemble(FrameParser.parse(lines, HeaderFormat.CAN_11)).isEmpty())
    }

    @Test
    fun sequenceNumberWrapsAfter15() {
        val payload = (0 until 120).map { it and 0xFF }
        val frames = mutableListOf(Frame("7E8", listOf(0x10, payload.size) + payload.take(6)))
        var offset = 6
        var sequence = 1
        while (offset < payload.size) {
            frames += Frame("7E8", listOf(0x20 or sequence) + payload.subList(offset, minOf(offset + 7, payload.size)))
            offset += 7
            sequence = (sequence + 1) and 0x0F
        }
        val messages = IsoTp.assemble(frames)
        assertEquals(1, messages.size)
        assertEquals(payload, messages[0].payload)
    }

    @Test
    fun legacyResponsesAreOneMessagePerLine() {
        val lines = listOf("486B104100BE1FB810AA", "486B184100800000000B")
        val messages = ResponseDecoder.decode(lines, ObdProtocol.ISO_9141_2)
        assertEquals(listOf("486B10", "486B18"), messages.map { it.header })
        assertEquals(bytes("41 00 BE 1F B8 10"), messages[0].payload)
    }

    @Test
    fun decoderUsesProtocolHeaderFormat() {
        val messages = ResponseDecoder.decode(listOf("18DAF110064100BE3EA813"), ObdProtocol.ISO_15765_4_CAN_29_500)
        assertEquals(listOf(EcuMessage("18DAF110", bytes("41 00 BE 3E A8 13"))), messages)
    }
}
