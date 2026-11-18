package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.terminal.TerminalDecision
import com.bod3.app.obd.terminal.TerminalPolicy
import com.bod3.app.obd.testing.SimulatedVehicleElm
import com.bod3.app.obd.testing.executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clearing fault memory is the only non-read request before M5, and only with a fresh user confirmation. */
class ClearConfirmationTest {

    private val elm = SimulatedVehicleElm(vehicleProtocol = "6")
    private val session = Session(elm.executor(), ObdProtocol.ISO_15765_4_CAN_11_500)

    @Test
    fun clearWithoutConfirmationIsRefusedAndNeverSent() = runBlocking<Unit> {
        assertThrows(ReadOnlyViolationException::class.java) { runBlocking { session.request(EcuTarget.Broadcast, listOf(0x04)) } }
        assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { session.request(EcuTarget.Physical("70E", "778"), listOf(0x14, 0xFF, 0xFF, 0xFF)) }
        }
        assertFalse(elm.commands.contains("04"))
        assertEquals(0, elm.clearCount)
    }

    @Test
    fun confirmedClearIsSentOnceAndCannotBeReused() = runBlocking<Unit> {
        val confirmation = ClearConfirmation.obdClear()
        val response = session.request(EcuTarget.Broadcast, listOf(0x04), confirmation = confirmation)
        assertEquals(listOf(0x44), response.messages.single().payload)
        assertTrue(confirmation.isUsed)
        val error = assertThrows(ClearNotConfirmedException::class.java) {
            runBlocking { session.request(EcuTarget.Broadcast, listOf(0x04), confirmation = confirmation) }
        }
        assertTrue(error.message!!.contains("already used"))
        assertEquals(1, elm.clearCount)
    }

    @Test
    fun confirmationIsBoundToRequestTargetAndTime() = runBlocking<Unit> {
        val engine = EcuTarget.Physical("7E0", "7E8")
        val bcm = EcuTarget.Physical("70E", "778")
        // Another module
        assertThrows(ClearNotConfirmedException::class.java) {
            runBlocking { session.request(bcm, listOf(0x14, 0xFF, 0xFF, 0xFF), confirmation = ClearConfirmation.udsClear(engine)) }
        }
        // Another DTC group
        assertThrows(ClearNotConfirmedException::class.java) {
            runBlocking { session.request(engine, listOf(0x14, 0x00, 0x00, 0x01), confirmation = ClearConfirmation.udsClear(engine)) }
        }
        // Expired
        var now = 0L
        val old = ClearConfirmation.obdClear { now }
        now = ClearConfirmation.VALID_FOR_MS + 1
        assertThrows(ClearNotConfirmedException::class.java) {
            runBlocking { session.request(EcuTarget.Broadcast, listOf(0x04), confirmation = old) }
        }
        // A confirmation never authorises anything other than a clear.
        assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { session.request(engine, listOf(0x2E, 0xF1, 0x90, 0x00), confirmation = ClearConfirmation.udsClear(engine)) }
        }
        assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { session.request(engine, listOf(0x10, 0x03), confirmation = ClearConfirmation.udsClear(engine)) }
        }
        assertEquals(0, elm.clearCount)
    }

    @Test
    fun terminalCannotClear() {
        assertTrue(TerminalPolicy.decide("04", expertMode = true) is TerminalDecision.Refuse)
        assertTrue(TerminalPolicy.decide("14FFFFFF", expertMode = true) is TerminalDecision.Refuse)
        assertFalse(ReadOnlyPolicy.isAllowed(listOf(0x04)))
        assertTrue(ReadOnlyPolicy.isClearRequest(listOf(0x14, 0xFF, 0xFF, 0xFF)))
        assertFalse(ReadOnlyPolicy.isClearRequest(listOf(0x14, 0xFF)))
    }
}
