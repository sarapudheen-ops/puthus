package com.bod3.app.obd.session

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.protocol.HeaderFormat
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.protocol.ResponseDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOptionsTest {

    @Test
    fun extendedAddressingStripsTheAddressByte() {
        // 7E8 F1 06 41 00 BE 3F A8 13 — address byte F1, then a normal single frame.
        val single = ResponseDecoder.decode(listOf("7E8F10641 00BE3FA813".replace(" ", "")), ObdProtocol.ISO_15765_4_CAN_11_500, extendedAddressing = true)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), single.single().payload)
        // Multi-frame: 6 data bytes per frame after the address byte.
        val lines = listOf("7E8F1100962F190575647", "7E8F1215A5A5A354E5A")
        val multi = ResponseDecoder.decode(lines, ObdProtocol.ISO_15765_4_CAN_11_500, extendedAddressing = true)
        assertEquals(listOf(0x62, 0xF1, 0x90, 0x57, 0x56, 0x47, 0x5A, 0x5A, 0x5A), multi.single().payload)
    }

    @Test
    fun broadcastFilterAndCustomFlowControlRouting() {
        val options = SessionOptions(broadcastFilter = "7e8", broadcastMask = "7f8", customFlowControl = true, flowControlData = "300804")
        assertEquals(
            listOf("ATSH 7DF", "ATCF 7E8", "ATCM 7F8"),
            HeaderCommands.forTarget(EcuTarget.Broadcast, HeaderFormat.CAN_11, options).map { it.trim() },
        )
        assertEquals(
            listOf("ATSH 7E0", "ATCRA 7E8", "ATFCSH 7E0", "ATFCSD 300804", "ATFCSM1"),
            HeaderCommands.forTarget(EcuTarget.Physical("7E0", "7E8"), HeaderFormat.CAN_11, options),
        )
        // Defaults unchanged: standard pair keeps automatic flow control.
        assertEquals(listOf("ATSH 7E0", "ATCRA 7E8"), HeaderCommands.forTarget(EcuTarget.Physical("7E0", "7E8"), HeaderFormat.CAN_11))
        assertEquals(listOf("ATSH 7DF", "ATAR"), HeaderCommands.forTarget(EcuTarget.Broadcast, HeaderFormat.CAN_11))
    }

    @Test
    fun sessionSendsCustomBroadcastFilterBeforeFirstRequest() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val executor = CommandExecutor { command, _ ->
            sent += command
            ElmResponseParser.parse(command, if (command.startsWith("AT")) "OK" else "7E806410098188013")
        }
        val session = Session(executor, ObdProtocol.ISO_15765_4_CAN_11_500, SessionOptions(broadcastFilter = "7E8", broadcastMask = "7F8"))
        session.request(EcuTarget.Broadcast, 0x01, 0x00)
        assertEquals(listOf("ATCF 7E8", "ATCM 7F8", "0100"), sent)
    }
}
