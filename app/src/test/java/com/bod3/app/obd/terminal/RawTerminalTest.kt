package com.bod3.app.obd.terminal

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTerminalTest {

    private fun refused(input: String, expert: Boolean = true) = TerminalPolicy.decide(input, expert) is TerminalDecision.Refuse

    @Test
    fun policyNeedsExpertModeAndKeepsReadOnlyRules() {
        assertTrue(refused("ATI", expert = false))
        assertTrue(refused("0100", expert = false))
        // AT commands, except link-breaking ones
        assertFalse(refused("ati"))
        assertFalse(refused("AT RV"))
        listOf("ATBRD 23", "ATPP 0C SV 23", "ATMA", "ATSD 00", "ATLP", "ATWM 8210F13E").forEach { assertTrue(it, refused(it)) }
        // The full ST/VT sets are sent directly, except commands that break the link or reset the adapter
        listOf("VTVERS", "STDI", "STSLCS", "STPX H:7E0, D:0100", "VTP291", "VTVLCW", "VTISOSI 01,0", "STCSEGT1", "STPBR 1000000").forEach {
            assertFalse(it, refused(it))
        }
        listOf("VTSET_BAUD 115200", "STBR 230400", "STSBR 500000", "STSLP", "STM", "STMA", "STBL").forEach { assertTrue(it, refused(it)) }
        assertFalse(refused("STMFR"))
        assertTrue((TerminalPolicy.decide("STP 61", true) as TerminalDecision.Send).affectsRouting)
        assertFalse((TerminalPolicy.decide("STI", true) as TerminalDecision.Send).affectsRouting)
        // Diagnostic requests: reads only
        assertFalse(refused("01 0C"))
        assertFalse(refused("22F190"))
        assertFalse(refused("1902FF"))
        listOf("04", "14FFFFFF", "2E F1 90 00", "2F 01 01 03", "31 01 02 03", "10 03", "27 01", "11 01", "3B", "ZZ", "123").forEach {
            assertTrue(it, refused(it))
        }
        val routing = TerminalPolicy.decide("AT SH 7E0", true) as TerminalDecision.Send
        assertEquals("ATSH7E0", routing.command)
        assertTrue(routing.affectsRouting)
        assertFalse((TerminalPolicy.decide("ATDPN", true) as TerminalDecision.Send).affectsRouting)
    }

    @Test
    fun sendsRecordsAndRestoresRouting() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val executor = CommandExecutor { command, _ ->
            sent += command
            val reply = when {
                command == "22F190" -> "62F1905756475A"
                command.startsWith("AT") -> "OK"
                else -> "NO DATA"
            }
            ElmResponseParser.parse(command, reply)
        }
        val session = Session(executor, ObdProtocol.ISO_15765_4_CAN_11_500)
        session.request(EcuTarget.Physical("7E0", "7E8"), 0x22, 0xF1, 0x90)
        val before = sent.size
        var expert = true
        val terminal = RawTerminal(executor, session, { expert }, clock = { 0L })

        terminal.send("ATSH 7E1")
        terminal.send("22F190")
        terminal.send("2E F1 90 00")
        expert = false
        terminal.send("ATI")
        assertEquals(listOf("ATSH7E1", "22F190"), sent.drop(before))
        val text = terminal.text()
        assertTrue(text, text.contains("> 22F190\n62F1905756475A"))
        assertTrue(text.contains("! Blocked by the read-only policy: 2E"))
        assertTrue(text.contains("! Turn on Expert mode"))

        sent.clear()
        terminal.restore()
        assertEquals(TerminalPolicy.restoreCommands + "ATSP6", sent)
        // The session no longer trusts its routing, so the next request re-sends all of it.
        sent.clear()
        session.request(EcuTarget.Physical("7E0", "7E8"), 0x22, 0xF1, 0x90)
        assertEquals(listOf("ATSH7E0", "ATCRA7E8", "ATFCSM0", "22F190"), sent.map { it.replace(" ", "") })

        terminal.clear()
        assertTrue(terminal.transcript.isEmpty())
    }
}
