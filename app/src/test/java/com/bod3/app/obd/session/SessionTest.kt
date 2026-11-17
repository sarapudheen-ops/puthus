package com.bod3.app.obd.session

import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.ScriptedExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private val headerAcks = mapOf(
        "ATSH 7DF" to "OK", "ATSH 7E0" to "OK", "ATSH 7E1" to "OK", "ATCRA 7E8" to "OK", "ATCRA 7E9" to "OK",
        "ATAR" to "OK", "ATCP 18" to "OK", "ATSH DA10F1" to "OK", "ATCRA 18DAF110" to "OK",
    )

    private fun executor(vararg replies: Pair<String, String>) = ScriptedExecutor(headerAcks + replies)

    @Test
    fun broadcastRequestSendsNoHeaderCommands() = runBlocking<Unit> {
        val exec = executor("010C" to "7E804410C1AF8\r7E904410C1AF0")
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)

        val response = session.request(EcuTarget.Broadcast, 0x01, 0x0C)

        assertEquals(listOf("010C"), exec.sent)
        assertEquals(listOf("7E8", "7E9"), response.messages.map { it.header })
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), response.messages[0].payload)
    }

    @Test
    fun physicalTargetSwitchesHeaderOnlyWhenItChanges() = runBlocking<Unit> {
        val exec = executor("010C" to "7E804410C1AF8", "010D" to "7E803410D3C")
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)
        val engine = EcuTarget.Physical("7E0", "7E8")

        session.request(engine, 0x01, 0x0C)
        session.request(engine, 0x01, 0x0D)
        session.request(EcuTarget.Broadcast, 0x01, 0x0C)

        assertEquals(
            listOf("ATSH 7E0", "ATCRA 7E8", "010C", "010D", "ATSH 7DF", "ATAR", "010C"),
            exec.sent,
        )
        assertEquals(EcuTarget.Broadcast, session.currentTarget)
    }

    @Test
    fun switchingBetweenEcus() = runBlocking<Unit> {
        val exec = executor("0100" to "7E9064100BE3EA813")
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)

        session.request(EcuTarget.Physical("7E0", "7E8"), 0x01, 0x00)
        val response = session.request(EcuTarget.Physical("7E1", "7E9"), 0x01, 0x00)

        assertEquals(listOf("ATSH 7E0", "ATCRA 7E8", "0100", "ATSH 7E1", "ATCRA 7E9", "0100"), exec.sent)
        assertEquals("7E9", response.messages.single().header)
    }

    @Test
    fun physicalTargetOn29BitCan() = runBlocking<Unit> {
        val exec = executor("0100" to "18DAF110064100BE3EA813")
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_29_500)

        val response = session.request(EcuTarget.Physical("18DA10F1", "18DAF110"), 0x01, 0x00)

        assertEquals(listOf("ATCP 18", "ATSH DA10F1", "ATCRA 18DAF110", "0100"), exec.sent)
        assertEquals("18DAF110", response.messages.single().header)
    }

    @Test
    fun writeAndActuationServicesAreRejectedBeforeAnythingIsSent() {
        val exec = executor()
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)
        // 04 clear DTCs, 08 actuation, 10 session control, 11 reset, 14 clear DTCs (UDS), 2E write, 2F IO control, 31 routine, 27 security, 34 download
        for (service in listOf(0x04, 0x08, 0x10, 0x11, 0x14, 0x2E, 0x2F, 0x31, 0x27, 0x34)) {
            assertThrows(ReadOnlyViolationException::class.java) {
                runBlocking { session.request(EcuTarget.Broadcast, service, 0x00) }
            }
        }
        assertTrue(exec.sent.isEmpty())
    }

    @Test
    fun noDataIsAnEmptyResponseNotAnError() = runBlocking<Unit> {
        val exec = executor("015C" to "NO DATA")
        val response = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500).request(EcuTarget.Broadcast, 0x01, 0x5C)
        assertTrue(response.isEmpty)
        assertEquals(ElmError.NO_DATA, response.error)
    }

    @Test
    fun busErrorsAreThrown() {
        val exec = executor("010C" to "CAN ERROR")
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)
        val error = assertThrows(ObdRequestException::class.java) {
            runBlocking { session.request(EcuTarget.Broadcast, 0x01, 0x0C) }
        }
        assertEquals(ElmError.CAN_ERROR, error.error)
    }

    @Test
    fun responsesForOtherPidsOrServicesAreIgnored() = runBlocking<Unit> {
        // A stray 010D reply and a negative response mixed in with the real answer.
        val exec = executor("010C" to "7E803410D3C\r7E804410C1AF8\r7E9037F0112")
        val response = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500).request(EcuTarget.Broadcast, 0x01, 0x0C)
        assertEquals(listOf(EcuMessage("7E8", listOf(0x41, 0x0C, 0x1A, 0xF8))), response.messages)
        assertEquals(listOf(EcuMessage("7E9", listOf(0x7F, 0x01, 0x12))), response.negativeResponses)
    }

    @Test
    fun physicalAddressingNeedsCan() {
        val session = Session(executor(), ObdProtocol.ISO_9141_2)
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { session.request(EcuTarget.Physical("7E0", "7E8"), 0x01, 0x0C) }
        }
    }

    @Test
    fun legacyBroadcastSendsNoHeaderCommands() = runBlocking<Unit> {
        val exec = executor("010C" to "486B10410C1AF8AA")
        val response = Session(exec, ObdProtocol.ISO_9141_2).request(EcuTarget.Broadcast, 0x01, 0x0C)
        assertEquals(listOf("010C"), exec.sent)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), response.messages.single().payload)
    }

    @Test
    fun failedHeaderChangeIsRetriedOnNextRequest() = runBlocking<Unit> {
        val exec = ScriptedExecutor(mapOf("ATSH 7E0" to "OK", "ATCRA 7E8" to "?", "010C" to "7E804410C1AF8"))
        val session = Session(exec, ObdProtocol.ISO_15765_4_CAN_11_500)
        val engine = EcuTarget.Physical("7E0", "7E8")

        assertThrows(ObdRequestException::class.java) { runBlocking { session.request(engine, 0x01, 0x0C) } }
        assertNull(session.currentTarget)

        exec.reply("ATCRA 7E8", "OK")
        session.request(engine, 0x01, 0x0C)
        assertEquals(listOf("ATSH 7E0", "ATCRA 7E8", "ATSH 7E0", "ATCRA 7E8", "010C"), exec.sent)
    }
}
