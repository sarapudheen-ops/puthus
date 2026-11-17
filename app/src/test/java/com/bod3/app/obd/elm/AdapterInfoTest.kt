package com.bod3.app.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterInfoTest {

    @Test
    fun parsesElmIdentity() {
        val identity = AdapterIdentity.parse("ELM327 v1.5")
        assertEquals("ELM327", identity.chip)
        assertEquals("1.5", identity.version)
        assertEquals("ELM327 v1.5", identity.raw)
    }

    @Test
    fun parsesIdentityWithLetterSuffixAndUsesLastLine() {
        val identity = AdapterIdentity.parse("\nELM327 v1.4b\n")
        assertEquals("ELM327", identity.chip)
        assertEquals("1.4b", identity.version)
    }

    @Test
    fun unknownIdentityKeepsRawText() {
        val identity = AdapterIdentity.parse("OBDII")
        assertEquals("OBDII", identity.raw)
        assertNull(identity.version)
    }

    @Test
    fun parsesBatteryVoltage() {
        assertEquals(12.6, VoltageParser.parse("12.6V")!!, 0.001)
        assertEquals(14.15, VoltageParser.parse("14.15 v")!!, 0.001)
        assertEquals(9.0, VoltageParser.parse("9V")!!, 0.001)
        assertNull(VoltageParser.parse("?"))
        assertNull(VoltageParser.parse("OK"))
        assertNull(VoltageParser.parse(""))
    }

    @Test
    fun probeWithVersionTextMeansSupported() {
        val response = ElmResponseParser.parse("VTVERS", "MIC3313 V2.1\r\r")
        assertEquals("MIC3313 V2.1", AdapterCapabilities.interpretProbe(response))
    }

    @Test
    fun probeRejectionsMeanUnsupported() {
        for (raw in listOf("?", "OK", "", "NO DATA", "STOPPED")) {
            val response = ElmResponseParser.parse("STI", "$raw\r\r")
            assertNull("for '$raw'", AdapterCapabilities.interpretProbe(response))
        }
    }

    @Test
    fun capabilityFlags() {
        assertFalse(AdapterCapabilities.NONE.supportsSt)
        assertFalse(AdapterCapabilities.NONE.supportsVt)
        val vt = AdapterCapabilities(vtVersion = "2.1", stVersion = null)
        assertTrue(vt.supportsVt)
        assertFalse(vt.supportsSt)
    }
}
