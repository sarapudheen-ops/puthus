package com.bod3.app.obd.elm

import com.bod3.app.obd.testing.FakeAdapterTransport
import com.bod3.app.obd.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterDriverTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A MIC3313-style adapter: plain ELM327 replies plus the VT extension, no ST extension. */
    private fun mic3313(overrides: Map<String, String?> = emptyMap()): FakeAdapterTransport {
        val replies = mapOf(
            "ATZ" to "\r\rELM327 v1.5",
            "ATE0" to "ATE0\rOK", // echo is still on when ATE0 arrives
            "ATL0" to "OK",
            "ATS0" to "OK",
            "ATH1" to "OK",
            "ATSP0" to "OK",
            "ATI" to "ELM327 v1.5",
            "AT@1" to "OBDII to RS232 Interpreter",
            "VTVERS" to "MIC3313 V2.1",
            "STI" to "?",
            "ATRV" to "12.6V",
        ) + overrides
        return FakeAdapterTransport { command -> if (command in replies) replies[command] else "?" }
    }

    private suspend fun openDriver(transport: FakeAdapterTransport): AdapterDriver =
        AdapterDriver(transport, scope, Dispatchers.Default).also { it.open() }

    @Test
    fun initializeRunsTheInitSequenceInOrder() = runBlocking<Unit> {
        val transport = mic3313()
        val driver = openDriver(transport)

        val info = driver.initialize()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATI", "AT@1", "VTVERS", "STI"),
            transport.written,
        )
        assertEquals("ELM327", info.identity.chip)
        assertEquals("1.5", info.identity.version)
        assertEquals("OBDII to RS232 Interpreter", info.description)
        assertEquals("MIC3313 V2.1", info.capabilities.vtVersion)
        assertNull(info.capabilities.stVersion)
        driver.close()
    }

    @Test
    fun extensionCommandsAreBlockedUntilCapabilitiesAreDetected() = runBlocking<Unit> {
        val transport = mic3313()
        val driver = openDriver(transport)

        val error = assertThrows(UnsupportedCommandException::class.java) {
            runBlocking { driver.execute("VTVERS", 1_000) }
        }
        assertTrue(error.message!!.contains("not been detected"))
        assertTrue(transport.written.isEmpty())
        driver.close()
    }

    @Test
    fun onlyDetectedExtensionsAreAllowed() = runBlocking<Unit> {
        val transport = mic3313()
        val driver = openDriver(transport)
        driver.initialize()
        transport.written.clear()

        assertEquals("MIC3313 V2.1", driver.execute("vtvers", 1_000).text)
        assertThrows(UnsupportedCommandException::class.java) {
            runBlocking { driver.execute("STDI", 1_000) }
        }
        assertEquals(listOf("VTVERS"), transport.written)
        driver.close()
    }

    @Test
    fun plainElm327HasNoExtensions() = runBlocking<Unit> {
        val transport = mic3313(mapOf("VTVERS" to "?", "STI" to "OK"))
        val driver = openDriver(transport)

        val info = driver.initialize()

        assertNull(info.capabilities.vtVersion)
        assertNull(info.capabilities.stVersion) // a bare OK from a clone is not a version string
        assertThrows(UnsupportedCommandException::class.java) { runBlocking { driver.execute("VTVERS", 1_000) } }
        driver.close()
    }

    @Test
    fun requiredSettingRejectedFailsInitialisation() = runBlocking<Unit> {
        val driver = openDriver(mic3313(mapOf("ATH1" to "?")))
        val error = assertThrows(AdapterInitException::class.java) { runBlocking { driver.initialize() } }
        assertEquals("ATH1", error.command)
        driver.close()
    }

    @Test
    fun optionalSettingRejectedIsTolerated() = runBlocking<Unit> {
        val driver = openDriver(mic3313(mapOf("ATS0" to "?", "ATL0" to "?")))
        val info = driver.initialize()
        assertEquals("1.5", info.identity.version)
        driver.close()
    }

    @Test
    fun commandTimesOutAndDriverRecovers() = runBlocking<Unit> {
        val transport = mic3313(mapOf("0100" to null))
        val driver = openDriver(transport)

        val error = assertThrows(CommandTimeoutException::class.java) {
            runBlocking { driver.execute("0100", 200) }
        }
        assertEquals("0100", error.command)

        // The late reply to 0100 arrives; it must not be mistaken for the ATRV answer.
        transport.inject("7E8064100BE3EA813\r\r>")
        assertEquals("12.6V", driver.execute("ATRV", 1_000).text)
        driver.close()
    }

    @Test
    fun resetIsRetriedOnceWhenTheFirstAtzIsLost() = runBlocking<Unit> {
        var resets = 0
        val transport = FakeAdapterTransport { command ->
            when (command) {
                "ATZ" -> if (++resets == 1) null else "ELM327 v1.5"
                "ATI" -> "ELM327 v1.5"
                "VTVERS", "STI", "AT@1" -> "?"
                else -> "OK"
            }
        }
        val driver = openDriver(transport)
        driver.initialize()
        assertEquals(2, transport.written.count { it == "ATZ" })
        driver.close()
    }

    @Test
    fun concurrentCommandsAreSerialised() = runBlocking<Unit> {
        val transport = FakeAdapterTransport { command -> "R:$command" }
        val driver = openDriver(transport)

        val commands = (1..20).map { "AT%02d".format(it) }
        val replies = commands.map { command -> async(Dispatchers.Default) { driver.execute(command, 2_000) } }.awaitAll()

        for ((command, reply) in commands.zip(replies)) {
            assertEquals("R:$command", reply.text)
        }
        driver.close()
    }

    @Test
    fun closedConnectionSurfacesAsTransportException() = runBlocking<Unit> {
        val transport = mic3313()
        val driver = openDriver(transport)
        transport.close()

        assertThrows(TransportException::class.java) { runBlocking { driver.execute("ATRV", 1_000) } }
    }
}
