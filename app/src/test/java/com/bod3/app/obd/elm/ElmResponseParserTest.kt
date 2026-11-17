package com.bod3.app.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {

    @Test
    fun okAcknowledgement() {
        val response = ElmResponseParser.parse("ATH1", "OK\r\r")
        assertTrue(response.isOk)
        assertNull(response.error)
        assertEquals(listOf("OK"), response.lines)
    }

    @Test
    fun echoIsRemovedEvenWithSpacesAndCase() {
        val response = ElmResponseParser.parse("ATE0", "ate 0\rOK\r\r")
        assertEquals(listOf("OK"), response.lines)
        assertTrue(response.isOk)
    }

    @Test
    fun resetReplyKeepsIdentityAndDropsGarbage() {
        val response = ElmResponseParser.parse("ATZ", "\u0000\u00ff\r\rELM327 v1.5\r\r")
        assertEquals(listOf("ELM327 v1.5"), response.lines)
        assertTrue(response.isSuccess)
        assertFalse(response.isOk)
    }

    @Test
    fun searchingAndBusInitNoiseIsDropped() {
        val response = ElmResponseParser.parse("0100", "SEARCHING...\r7E8064100BE3EA813\r\r")
        assertEquals(listOf("7E8064100BE3EA813"), response.lines)

        val kwp = ElmResponseParser.parse("0100", "BUS INIT: ...OK\r486B104100BE1FB810AA\r")
        assertEquals(listOf("486B104100BE1FB810AA"), kwp.lines)
    }

    @Test
    fun multiLineDataIsPreservedInOrder() {
        val raw = "7E81014490201314434\r7E82147503030523535\r7E82242313233343536\r\r"
        val response = ElmResponseParser.parse("0902", raw)
        assertEquals(3, response.lines.size)
        assertEquals("7E82242313233343536", response.lines.last())
    }

    @Test
    fun errorsAreClassified() {
        val cases = mapOf(
            "NO DATA" to ElmError.NO_DATA,
            "?" to ElmError.UNKNOWN_COMMAND,
            "STOPPED" to ElmError.STOPPED,
            "UNABLE TO CONNECT" to ElmError.UNABLE_TO_CONNECT,
            "SEARCHING...\rUNABLE TO CONNECT" to ElmError.UNABLE_TO_CONNECT,
            "BUS INIT: ...ERROR" to ElmError.BUS_INIT_ERROR,
            "CAN ERROR" to ElmError.CAN_ERROR,
            "BUS ERROR" to ElmError.BUS_ERROR,
            "BUS BUSY" to ElmError.BUS_BUSY,
            "FB ERROR" to ElmError.FEEDBACK_ERROR,
            "<RX ERROR" to ElmError.RX_ERROR,
            "7E8064100BE<DATA ERROR" to ElmError.DATA_ERROR,
            "BUFFER FULL" to ElmError.BUFFER_FULL,
            "LV RESET" to ElmError.LOW_VOLTAGE_RESET,
            "ACT ALERT" to ElmError.ACTIVITY_ALERT,
            "ERR94" to ElmError.INTERNAL_ERROR,
        )
        for ((raw, expected) in cases) {
            val response = ElmResponseParser.parse("0100", "$raw\r\r")
            assertEquals("for '$raw'", expected, response.error)
            assertFalse(response.isSuccess)
        }
    }

    @Test
    fun dataThatLooksLikeErrorWordsIsNotAnError() {
        val response = ElmResponseParser.parse("010C", "7E804410C1AF8\r")
        assertNull(response.error)
    }

    @Test
    fun emptyReplyHasNoLines() {
        val response = ElmResponseParser.parse("ATRV", "\r\r")
        assertTrue(response.lines.isEmpty())
        assertTrue(response.isSuccess)
    }
}
