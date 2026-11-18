package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.SimulatedVehicleElm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic detection finds a vehicle on every protocol family the vLinker MC+ supports. */
class AllProtocolsNegotiationTest {

    private fun executor(elm: SimulatedVehicleElm) = CommandExecutor { command, _ ->
        val raw = elm.respond(command) ?: throw com.bod3.app.obd.elm.CommandTimeoutException(command, 1)
        ElmResponseParser.parse(command, "$raw\r\r")
    }

    private fun negotiate(elm: SimulatedVehicleElm, profile: ConnectionProfile = ConnectionProfile.DEFAULT, log: MutableList<String> = mutableListOf()) =
        runBlocking { ProtocolNegotiator(executor(elm), { log += it }).negotiate(profile) }

    @Test
    fun autoDetectFindsEveryProtocol() {
        for (protocol in ObdProtocol.entries) {
            val elm = SimulatedVehicleElm(vehicleProtocol = protocol.code)
            val result = negotiate(elm)
            assertTrue("${protocol.code}: ${result.attempts.map { it.label + " " + it.result }}", result.succeeded)
            assertEquals(protocol.code, protocol, result.protocol!!.protocol)
        }
    }

    @Test
    fun user11BitAfterA29BitAttemptStillWorks() {
        // Remembered B/29 (wrong) is tried first and leaves 29-bit options behind; B must still be found.
        val elm = SimulatedVehicleElm(vehicleProtocol = "B")
        val result = runBlocking { ProtocolNegotiator(executor(elm)).negotiate(ConnectionProfile.DEFAULT, remembered = ObdProtocol.USER1_CAN_29) }
        assertEquals(ObdProtocol.USER1_CAN_11, result.protocol!!.protocol)
    }

    @Test
    fun forcedUserCanWithCustomBaud() {
        val elm = SimulatedVehicleElm(vehicleProtocol = "B/29", vehicleBaudKbps = 250.0)
        val profile = ConnectionProfile(id = "u", name = "User CAN", forcedProtocol = ObdProtocol.USER1_CAN_29, tryFallbacks = false, canBaudKbps = 250.0)
        val result = negotiate(elm, profile)
        assertTrue(result.succeeded)
        assertEquals(ObdProtocol.USER1_CAN_29, result.protocol!!.protocol)
        assertTrue(elm.commands.contains("ATPB4102"))
        assertEquals("ATPB 41 02; ATSPB", result.attempts.single().selectCommand)

        // Wrong baud: no reply.
        val wrong = negotiate(SimulatedVehicleElm(vehicleProtocol = "B/29", vehicleBaudKbps = 500.0), profile)
        assertFalse(wrong.succeeded)
    }

    @Test
    fun oneMegabitStnCan() {
        val elm = SimulatedVehicleElm(vehicleProtocol = "STN 31", vehicleBaudKbps = 1000.0)
        val profile = ConnectionProfile(id = "f", name = "1M", forcedProtocol = ObdProtocol.STN_ISO_15765_11, tryFallbacks = false, canBaudKbps = 1000.0)
        val result = negotiate(elm, profile)
        assertTrue(result.succeeded)
        assertTrue(elm.commands.containsAll(listOf("STP31", "STPBR1000000")))
    }

    @Test
    fun unknownHelperCommandIsLoggedAndSkippedButUnknownSelectFails() {
        // An adapter without STPBR still gets the protocol selected; the "?" is only logged.
        val elm = SimulatedVehicleElm(vehicleProtocol = "B", vehicleBaudKbps = 1000.0, rejected = mutableSetOf("STPBR1000000"))
        val log = mutableListOf<String>()
        val profile = ConnectionProfile(id = "x", name = "x", forcedProtocol = ObdProtocol.USER1_CAN_11, tryFallbacks = false, canBaudKbps = 1000.0)
        val result = negotiate(elm, profile, log)
        assertTrue(log.any { it.contains("did not accept STPBR 1000000") })
        assertFalse(result.succeeded) // the bus stayed at 500k, so the 1M vehicle did not answer
        assertNotNull(result.attempts.single().reason)

        // VT protocol on an adapter without VT: the select command gets "?" and the attempt fails cleanly.
        val noVt = SimulatedVehicleElm(vehicleProtocol = "VT KWP1281", vt = false)
        val forced = ConnectionProfile(id = "k", name = "k", forcedProtocol = ObdProtocol.VT_KWP1281, tryFallbacks = false)
        val attempt = negotiate(noVt, forced).attempts.single()
        assertFalse(attempt.success)
        assertTrue(attempt.result.startsWith("rejected"))
    }

    @Test
    fun j1939AndKwp1281UseTheirOwnProbes() {
        val j1939 = SimulatedVehicleElm(vehicleProtocol = "A")
        assertTrue(negotiate(j1939).succeeded)
        assertTrue(j1939.commands.contains("00FECA"))
        val kwp = SimulatedVehicleElm(vehicleProtocol = "VT KWP1281")
        assertTrue(negotiate(kwp).succeeded)
        assertTrue(kwp.commands.contains("VTP291"))
    }

    @Test
    fun profileCodecKeepsBaudAndExtraCommands() {
        val profile = ConnectionProfile(
            id = "p", name = "P", forcedProtocol = ObdProtocol.USER2_CAN_29, canBaudKbps = 95.2, extraInitCommands = listOf("STCSEGT1", "VTVLCW"),
        )
        val decoded = ConnectionProfileCodec.decodeList(ConnectionProfileCodec.encodeList(listOf(profile))).single()
        assertEquals(profile, decoded)
        assertEquals(listOf("ATAT1", "ATCEA", "STCSEGT1", "VTVLCW").drop(1), AdapterSetup.commands(profile))
    }
}
