package com.bod3.app.obd.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSelectionTest {

    @Test
    fun everyElmProtocolOneToCIsAvailable() {
        val dpns = ObdProtocol.entries.mapNotNull { it.dpn }.toSet()
        assertEquals("123456789ABC".toSet(), dpns)
        for (c in "123456789A") {
            assertEquals(listOf("ATSP$c"), ObdProtocol.entries.first { it.dpn == c }.selectCommands())
        }
    }

    @Test
    fun userCanBDefaultsAndCustomBaud() {
        assertEquals(listOf("ATPB C1 04", "ATSPB"), ObdProtocol.USER1_CAN_11.selectCommands())
        assertEquals(listOf("ATPB 41 04", "ATSPB"), ObdProtocol.USER1_CAN_29.selectCommands())
        assertEquals(listOf("ATPB C1 02", "ATSPB"), ObdProtocol.USER1_CAN_11.selectCommands(250.0))
        assertEquals(listOf("ATPB 41 05", "ATSPB"), ObdProtocol.USER1_CAN_29.selectCommands(100.0))
        // 1 Mbit/s has no whole divisor of 500k: set exactly with STPBR after selecting.
        assertEquals(listOf("ATPB C1 01", "ATSPB", "STPBR 1000000"), ObdProtocol.USER1_CAN_11.selectCommands(1000.0))
        assertEquals(listOf("ATPB 41 01", "ATSPB", "STPBR 33300"), ObdProtocol.USER1_CAN_29.selectCommands(33.3))
    }

    @Test
    fun userCanCUsesProgrammableParameters() {
        assertEquals(listOf("ATPP 2E SV C1", "ATPP 2E ON", "ATPP 2F SV 0A", "ATPP 2F ON", "ATSPC"), ObdProtocol.USER2_CAN_11.selectCommands())
        assertEquals(listOf("ATPP 2E SV 41", "ATPP 2E ON", "ATPP 2F SV 0A", "ATPP 2F ON", "ATSPC"), ObdProtocol.USER2_CAN_29.selectCommands())
        assertEquals(listOf("ATPP 2E SV C1", "ATPP 2E ON", "ATPP 2F SV 04", "ATPP 2F ON", "ATSPC"), ObdProtocol.USER2_CAN_11.selectCommands(125.0))
    }

    @Test
    fun stnAndVtProtocols() {
        assertEquals(listOf("STP 61"), ObdProtocol.STN_MS_CAN_15765_11.selectCommands())
        assertEquals(listOf("STP 52"), ObdProtocol.STN_SW_CAN_15765_29.selectCommands())
        assertEquals(listOf("STP 31", "STPBR 1000000"), ObdProtocol.STN_ISO_15765_11.selectCommands(1000.0))
        assertEquals(listOf("STP 31"), ObdProtocol.STN_ISO_15765_11.selectCommands(500.0))
        assertEquals(listOf("STP 42"), ObdProtocol.STN_ISO_11898_29.selectCommands(250.0)) // raw CAN: fixed rate, baud ignored
        assertEquals(listOf("VTP291"), ObdProtocol.VT_KWP1281.selectCommands())
        assertFalse(ObdProtocol.VT_KWP1281.isCan)
        assertTrue(ObdProtocol.STN_MS_CAN_15765_29.isCan)
        assertEquals(HeaderFormat.CAN_29, ObdProtocol.STN_MS_CAN_15765_29.headerFormat)
        assertEquals(PhysicalBus.MS_CAN, ObdProtocol.STN_MS_CAN_15765_29.bus)
    }

    @Test
    fun codesAreUniqueAndLookupWorks() {
        assertEquals(ObdProtocol.entries.size, ObdProtocol.entries.map { it.code }.toSet().size)
        assertEquals(ObdProtocol.USER1_CAN_29, ObdProtocol.byCode("b/29"))
        assertEquals(ObdProtocol.VT_KWP1281, ObdProtocol.byCode("VT KWP1281"))
        assertNull(ObdProtocol.byCode("Z"))
        assertEquals(ObdProtocol.USER1_CAN_11, ObdProtocol.fromProtocolNumber("AB"))
        assertTrue(ObdProtocol.USER1_CAN_29.matchesProtocolNumber("B"))
        assertFalse(ObdProtocol.STN_MS_CAN_15765_11.matchesProtocolNumber("6"))
    }
}
