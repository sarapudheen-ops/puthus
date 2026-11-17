package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.ScriptedExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPolicyTest {

    @Test
    fun allowsReadServices() {
        listOf(
            listOf(0x01, 0x0C), listOf(0x03), listOf(0x09, 0x02), listOf(0x0A),
            listOf(0x22, 0xF1, 0x90), listOf(0x22, 0xF1, 0x87, 0xF1, 0x89),
            listOf(0x19, 0x02, 0xFF), listOf(0x19, 0x01, 0x08), listOf(0x3E, 0x00), listOf(0x3E, 0x80),
        ).forEach { assertTrue("$it should be allowed", ReadOnlyPolicy.isAllowed(it)) }
    }

    @Test
    fun rejectsEverythingThatChangesTheVehicle() {
        listOf(
            listOf(0x04), // clear OBD DTCs
            listOf(0x14, 0xFF, 0xFF, 0xFF), // clear UDS DTCs
            listOf(0x2E, 0x06, 0x00, 0x01), // write data by identifier (coding)
            listOf(0x3B, 0x01), // KWP write
            listOf(0x31, 0x01, 0x02, 0x03), // routine control
            listOf(0x2F, 0x01, 0x00, 0x03), // IO control
            listOf(0x27, 0x01), // security access
            listOf(0x10, 0x03), // extended session
            listOf(0x11, 0x01), // ECU reset
            listOf(0x28, 0x01, 0x01), // communication control
            listOf(0x85, 0x02), // DTC setting off
            listOf(0x34), listOf(0x36), // download
            listOf(0x3E, 0x01), // invalid TesterPresent
            listOf(0x19, 0x05, 0x01), // not in the allowed report list
            listOf(0x19), // no sub-function
            listOf(0x22, 0xF1), // malformed identifier
        ).forEach { assertFalse("$it should be rejected", ReadOnlyPolicy.isAllowed(it)) }
    }

    @Test
    fun sessionRejectsBeforeSendingAnything() = runBlocking<Unit> {
        val exec = ScriptedExecutor()
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)
        val error = assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { session.request(EcuTarget.Physical("7E0", "7E8"), 0x14, 0xFF, 0xFF, 0xFF) }
        }
        assertEquals(0x14, error.service)
        assertThrows(ReadOnlyViolationException::class.java) {
            runBlocking { session.request(EcuTarget.Physical("7E0", "7E8"), 0x2E, 0xF1, 0x90, 0x00) }
        }
        assertTrue(exec.sent.isEmpty())
    }
}
