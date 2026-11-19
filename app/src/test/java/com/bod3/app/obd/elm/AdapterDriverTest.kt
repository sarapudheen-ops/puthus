package com.bod3.app.obd.elm

import com.bod3.app.obd.testing.FakeAdapterTransport
import com.bod3.app.obd.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
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
    fun vendorCommandsAreSentDirectlyWithoutDetection() = runBlocking<Unit> {
        val transport = mic3313()
        val driver = openDriver(transport)

        // No initialize(): ST/VT commands are not gated on firmware detection.
        assertEquals("MIC3313 V2.1", driver.execute("vtvers", 1_000).text)
        assertEquals(listOf("VTVERS"), transport.written)
        driver.close()
    }

    @Test
    fun unknownVendorCommandIsLoggedAndReturnedNotThrown() = runBlocking<Unit> {
        val transport = mic3313()
        val log = mutableListOf<String>()
        val driver = AdapterDriver(transport, scope, Dispatchers.Default, logger = { log += it })
        driver.open()
        driver.initialize()
        transport.written.clear()

        val reply = driver.execute("STDI", 1_000)
        assertEquals(ElmError.UNKNOWN_COMMAND, reply.error)
        assertEquals(listOf("STDI"), transport.written)
        assertTrue(log.any { it.contains("answered ? to STDI") })
        driver.close()
    }

    @Test
    fun firmwareVersionsAreForDisplayOnly() = runBlocking<Unit> {
        val transport = mic3313(mapOf("VTVERS" to "?", "STI" to "OK"))
        val driver = openDriver(transport)

        val info = driver.initialize()

        assertNull(info.capabilities.vtVersion)
        assertNull(info.capabilities.stVersion) // a bare OK from a clone is not a version string
        assertEquals(1, transport.written.count { it == "VTVERS" }) // read once per connection
        // Even without a reported VT version, VT commands are still sent.
        transport.written.clear()
        driver.execute("VTTOST 20", 1_000)
        assertEquals(listOf("VTTOST 20"), transport.written)
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
    fun cancelledExchangeMustDrainItsPromptBeforeAnotherCandidate() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        val transport = FakeAdapterTransport { command -> if (command == "0100") { sent.complete(Unit); null } else "12.6V" }
        val driver = openDriver(transport)
        val job = async { driver.execute("0100", 5000) }
        sent.await(); job.cancelAndJoin()
        transport.inject("7E8 06 41 00 BE 3E A8 13\r>")
        assertEquals("12.6V", driver.execute("ATRV", 1000).text)
        assertEquals(listOf("0100", "ATRV"), transport.written)
        driver.close()
    }

    @Test
    fun missingPromptAfterCancellationRefusesTheNextCommandInsteadOfMisattributingAReply() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        val transport = FakeAdapterTransport { sent.complete(Unit); null }
        val driver = openDriver(transport)
        val job = async { driver.execute("0100", 5000) }
        sent.await(); job.cancelAndJoin()
        assertThrows(TransportException::class.java) { runBlocking { driver.execute("ATRV", 1000) } }
        assertEquals(listOf("0100"), transport.written)
        assertTrue(driver.linkLost.value != null)
        driver.close()
    }

    @Test
    fun timeoutEvidenceKeepsThePartialUnterminatedResponse() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        val transport = FakeAdapterTransport { sent.complete(Unit); null }
        val driver = openDriver(transport)
        val recorder = RecordingExecutor(driver)
        val evidence = mutableListOf<ObservedExchange>()
        val remove = recorder.observe(object : ExchangeObserver {
            override fun before(command: String, timeoutMs: Long) = Unit
            override fun after(exchange: ObservedExchange) { evidence += exchange }
        })
        val job = async { runCatching { recorder.execute("1A90", 300) }.exceptionOrNull() }
        sent.await(); transport.inject("83 F1 10 5A")
        val error = job.await() as CommandTimeoutException
        assertEquals("83 F1 10 5A", error.partialResponse)
        assertEquals(error.partialResponse, evidence.single().rawResponse)
        assertTrue(evidence.single().error!!.contains("within 300 ms"))
        remove(); driver.close()
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
