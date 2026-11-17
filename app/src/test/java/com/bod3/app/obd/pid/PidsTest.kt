package com.bod3.app.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidsTest {

    private fun bytes(hex: String): List<Int> = hex.replace(" ", "").chunked(2).map { it.toInt(16) }

    @Test
    fun decodesSupportedPidBitmap() {
        val supported = Pids.decodeSupported(0x00, bytes("BE 3E A8 13"))
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            supported,
        )
    }

    @Test
    fun supportedBitmapIsOffsetByBase() {
        assertEquals(setOf(0x21, 0x40), Pids.decodeSupported(0x20, bytes("80 00 00 01")))
        assertTrue(Pids.decodeSupported(0x40, bytes("00 00 00 00")).isEmpty())
    }

    @Test
    fun decodesCommonPids() {
        assertEquals(1726.0, Pids.ENGINE_RPM.decode(bytes("1A F8"))!!, 0.001)
        assertEquals(83.0, Pids.COOLANT_TEMP.decode(bytes("7B"))!!, 0.001)
        assertEquals(-40.0, Pids.INTAKE_TEMP.decode(bytes("00"))!!, 0.001)
        assertEquals(60.0, Pids.VEHICLE_SPEED.decode(bytes("3C"))!!, 0.001)
        assertEquals(100.0, Pids.THROTTLE.decode(bytes("FF"))!!, 0.001)
        assertEquals(50.196, Pids.ENGINE_LOAD.decode(bytes("80"))!!, 0.001)
        assertEquals(6.0, Pids.TIMING_ADVANCE.decode(bytes("8C"))!!, 0.001)
        assertEquals(4.36, Pids.MAF.decode(bytes("01 B4"))!!, 0.001)
        assertEquals(14.2, Pids.MODULE_VOLTAGE.decode(bytes("37 78"))!!, 0.001)
        assertEquals(613.0, Pids.RUN_TIME.decode(bytes("02 65"))!!, 0.001)
        assertEquals(2.5, Pids.FUEL_RATE.decode(bytes("00 32"))!!, 0.001)
    }

    @Test
    fun shortDataDecodesToNull() {
        assertNull(Pids.ENGINE_RPM.decode(bytes("1A")))
        assertNull(Pids.COOLANT_TEMP.decode(emptyList()))
    }

    @Test
    fun formatsWithConfiguredDecimals() {
        assertEquals("1726", Pids.ENGINE_RPM.format(1726.4))
        assertEquals("4.36", Pids.MAF.format(4.36))
        assertEquals("6.0", Pids.TIMING_ADVANCE.format(6.0))
    }

    @Test
    fun liveListHasUniquePids() {
        assertEquals(Pids.live.size, Pids.live.map { it.pid }.toSet().size)
        assertEquals(Pids.ENGINE_RPM, Pids.byPid(0x0C))
        assertNull(Pids.byPid(0xFF))
    }
}
