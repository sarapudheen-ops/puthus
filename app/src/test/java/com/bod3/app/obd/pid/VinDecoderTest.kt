package com.bod3.app.obd.pid

import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.protocol.ResponseDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinDecoderTest {

    private fun bytes(hex: String): List<Int> = hex.replace(" ", "").chunked(2).map { it.toInt(16) }

    @Test
    fun decodesCanVin() {
        val lines = listOf("7E81014490201314434", "7E82147503030523535", "7E82242313233343536")
        val messages = ResponseDecoder.decode(lines, ObdProtocol.ISO_15765_4_CAN_11_500)
        assertEquals("1D4GP00R55B123456", VinDecoder.decode(messages))
    }

    @Test
    fun decodesCanVinWithoutCountByte() {
        val payload = bytes("49 02") + "WVWZZZ1JZXW000001".map { it.code }
        assertEquals("WVWZZZ1JZXW000001", VinDecoder.decode(listOf(EcuMessage("7E8", payload))))
    }

    @Test
    fun decodesLegacyVinFromFiveMessagesInAnyOrder() {
        val lines = listOf(
            "486B10490203303052355F",
            "486B10490201000000311A",
            "486B104902053334353644",
            "486B104902024434475033",
            "486B104902043542313210",
        )
        val messages = ResponseDecoder.decode(lines, ObdProtocol.ISO_9141_2)
        assertEquals("1D4GP00R55B123456", VinDecoder.decode(messages))
    }

    @Test
    fun picksTheEcuThatReturnedACompleteVin() {
        val partial = EcuMessage("7E9", bytes("49 02 01 00 00"))
        val full = EcuMessage("7E8", bytes("49 02 01") + "1D4GP00R55B123456".map { it.code })
        assertEquals("1D4GP00R55B123456", VinDecoder.decode(listOf(partial, full)))
    }

    @Test
    fun returnsNullWhenNoVin() {
        assertNull(VinDecoder.decode(emptyList()))
        assertNull(VinDecoder.decode(listOf(EcuMessage("7E8", bytes("41 00 BE 3E A8 13")))))
        assertNull(VinDecoder.decode(listOf(EcuMessage("7E8", bytes("49 02 01 31 32")))))
    }
}
