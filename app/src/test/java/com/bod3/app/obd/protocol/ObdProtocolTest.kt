package com.bod3.app.obd.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProtocolTest {

    @Test
    fun parsesAutomaticProtocolNumbers() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, ObdProtocol.fromProtocolNumber("A6"))
        assertEquals(ObdProtocol.ISO_15765_4_CAN_29_500, ObdProtocol.fromProtocolNumber("A7"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromProtocolNumber("A3"))
        assertEquals(ObdProtocol.SAE_J1939_CAN_29_250, ObdProtocol.fromProtocolNumber("AA"))
    }

    @Test
    fun parsesExplicitProtocolNumbers() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_250, ObdProtocol.fromProtocolNumber(" 8 "))
        assertEquals(ObdProtocol.SAE_J1850_PWM, ObdProtocol.fromProtocolNumber("1"))
        assertEquals(ObdProtocol.SAE_J1939_CAN_29_250, ObdProtocol.fromProtocolNumber("A"))
    }

    @Test
    fun rejectsUndeterminedOrInvalid() {
        assertNull(ObdProtocol.fromProtocolNumber("0"))
        assertNull(ObdProtocol.fromProtocolNumber("A0"))
        assertNull(ObdProtocol.fromProtocolNumber("?"))
        assertNull(ObdProtocol.fromProtocolNumber(""))
        assertNull(ObdProtocol.fromProtocolNumber("AUTO"))
    }

    @Test
    fun headerFormats() {
        assertTrue(ObdProtocol.ISO_15765_4_CAN_11_500.isCan)
        assertEquals(HeaderFormat.CAN_11, ObdProtocol.ISO_15765_4_CAN_11_500.headerFormat)
        assertEquals(HeaderFormat.CAN_29, ObdProtocol.ISO_15765_4_CAN_29_250.headerFormat)
        assertFalse(ObdProtocol.ISO_14230_4_KWP_FAST.isCan)
        assertEquals(HeaderFormat.LEGACY, ObdProtocol.SAE_J1850_VPW.headerFormat)
    }
}
