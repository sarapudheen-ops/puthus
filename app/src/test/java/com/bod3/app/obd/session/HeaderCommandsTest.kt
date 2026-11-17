package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.HeaderFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HeaderCommandsTest {

    @Test
    fun broadcastOn11BitCan() {
        assertEquals(listOf("ATSH 7DF", "ATAR"), HeaderCommands.forTarget(EcuTarget.Broadcast, HeaderFormat.CAN_11))
    }

    @Test
    fun physicalOn11BitCanSetsHeaderAndReceiveFilter() {
        assertEquals(
            listOf("ATSH 7E0", "ATCRA 7E8"),
            HeaderCommands.forTarget(EcuTarget.Physical("7e0", "7e8"), HeaderFormat.CAN_11),
        )
    }

    @Test
    fun physicalWithoutResponseHeaderUsesAutoReceive() {
        assertEquals(
            listOf("ATSH 7E1", "ATAR"),
            HeaderCommands.forTarget(EcuTarget.Physical("7E1"), HeaderFormat.CAN_11),
        )
    }

    @Test
    fun physicalOn29BitCanSplitsPriorityByte() {
        assertEquals(
            listOf("ATCP 18", "ATSH DA10F1", "ATCRA 18DAF110"),
            HeaderCommands.forTarget(EcuTarget.Physical("18DA10F1", "18DAF110"), HeaderFormat.CAN_29),
        )
    }

    @Test
    fun broadcastOn29BitCan() {
        assertEquals(
            listOf("ATCP 18", "ATSH DB33F1", "ATAR"),
            HeaderCommands.forTarget(EcuTarget.Broadcast, HeaderFormat.CAN_29),
        )
    }

    @Test
    fun headerLengthMustMatchFormat() {
        assertThrows(IllegalArgumentException::class.java) {
            HeaderCommands.forTarget(EcuTarget.Physical("18DA10F1"), HeaderFormat.CAN_11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeaderCommands.forTarget(EcuTarget.Physical("7E0", "7E8"), HeaderFormat.CAN_29)
        }
    }

    @Test
    fun targetHeadersMustBeHex() {
        assertThrows(IllegalArgumentException::class.java) { EcuTarget.Physical("7G0") }
    }

    @Test
    fun physicalTargetDerivedFromResponseHeader() {
        assertEquals(EcuTarget.Physical("7E0", "7E8"), EcuTarget.physicalFor("7E8", HeaderFormat.CAN_11))
        assertEquals(EcuTarget.Physical("7E1", "7E9"), EcuTarget.physicalFor("7e9", HeaderFormat.CAN_11))
        assertEquals(
            EcuTarget.Physical("18DA10F1", "18DAF110"),
            EcuTarget.physicalFor("18DAF110", HeaderFormat.CAN_29),
        )
        assertNull(EcuTarget.physicalFor("7A8", HeaderFormat.CAN_11))
        assertNull(EcuTarget.physicalFor("18DB33F1", HeaderFormat.CAN_29))
        assertNull(EcuTarget.physicalFor("486B10", HeaderFormat.LEGACY))
    }
}
