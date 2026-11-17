package com.bod3.app.obd.uds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsDecodingTest {

    @Test
    fun dtcCodes() {
        assertEquals("P0420", DtcDecoder.obdCode(0x04, 0x20))
        assertEquals("C0035", DtcDecoder.obdCode(0x40, 0x35))
        assertEquals("B1A2B", DtcDecoder.obdCode(0x9A, 0x2B))
        assertEquals("U0100", DtcDecoder.obdCode(0xC1, 0x00))
        assertEquals("P2002-74", DtcDecoder.udsCode(0x20, 0x02, 0x74))
    }

    @Test
    fun reportByStatusMaskSkipsEmptyRecords() {
        val payload = listOf(0x59, 0x02, 0x7F, 0x04, 0x20, 0x13, 0x08, 0x00, 0x00, 0x00, 0x00, 0xC1, 0x00, 0x00, 0x00, 0x01, 0x11, 0x00, 0x86)
        val result = DtcDecoder.parseReportByStatusMask(payload)!!
        assertEquals(0x7F, result.availabilityMask)
        assertEquals(listOf("P0420-13", "P0111-00"), result.records.map { it.code })
        assertEquals("confirmed", result.records[0].statusText)
        assertEquals("failed this cycle, pending, warning lamp", result.records[1].statusText)
        assertNull(DtcDecoder.parseReportByStatusMask(listOf(0x59, 0x01, 0xFF)))
    }

    @Test
    fun obdStoredDtcs() {
        val result = DtcDecoder.parseObdStored(listOf(0x43, 0x02, 0x01, 0x33, 0x00, 0x00, 0x04, 0x20))!!
        assertEquals(listOf("P0133", "P0420"), result.records.map { it.code })
        assertEquals("stored (OBD)", result.records[0].statusText)
    }

    @Test
    fun didTextDecoding() {
        assertEquals("03L906018", DidText.decode("03L906018".map { it.code } + listOf(0x20, 0x00, 0xFF)))
        assertNull(DidText.decode(listOf(0x01, 0x02)))
        assertNull(DidText.decode(listOf(0x00, 0x00)))
        assertEquals("01 FF", listOf(0x01, 0xFF).toHex())
    }

    @Test
    fun negativeResponseDisplay() {
        assertEquals("Not supported", DidReading(0xF187, DidResult.Negative(0x31)).display)
        assertEquals("Not available: NRC 0x33 (security access denied)", DidReading(0xF187, DidResult.Negative(0x33)).display)
        assertEquals("Spare part number", DidReading(0xF187, DidResult.NoResponse).label)
        assertEquals("Identifier 1234", KnownDids.label(0x1234))
        assertTrue(Nrc.meansNotSupported(0x7F))
        assertFalse(Nrc.meansNotSupported(0x78))
    }
}
