package com.bod3.app.obd.elm

import com.bod3.app.obd.transport.Transport
import com.bod3.app.obd.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Anything that can send one command to an adapter and return its parsed response. */
fun interface CommandExecutor {
    suspend fun execute(command: String, timeoutMs: Long): ElmResponse

    /** Physical drivers override this so a whole ECU operation excludes ALL direct adapter users. */
    suspend fun <T> withExclusive(block: suspend () -> T): T = block()
}

suspend fun CommandExecutor.execute(command: String): ElmResponse =
    execute(command, AdapterDriver.DEFAULT_TIMEOUT_MS)

/**
 * Driver for ELM327-compatible adapters, tuned for the MIC3313 (Vgate/vLinker) chip.
 *
 * Responsibilities:
 * - owns the [Transport] and a background reader that splits the incoming byte stream on the `>` prompt;
 * - runs every command through a FIFO [CommandQueue] with a per-command timeout;
 * - performs the init sequence (`ATZ ATE0 ATL0 ATS0 ATH1 ATSP0`) and reads `ATI` / `AT@1`;
 * - reads the firmware versions (`VTVERS`, `STI`) once per connection, for display and the report.
 *
 * ST and VT commands are sent directly, like AT commands: the vLinker MC+ implements the full STN and
 * VT sets. There is no capability gate. If an adapter answers `?` the reply is logged and returned to
 * the caller, which decides whether to carry on.
 *
 * This class never talks to the vehicle on its own; OBD requests go through
 * [com.bod3.app.obd.session.Session].
 */
class AdapterDriver(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {},
) : CommandExecutor {

    private val prompts = Channel<String>(Channel.UNLIMITED)
    private val queue = CommandQueue()
    private val operationLock = Mutex()
    private class Lease(val driver: AdapterDriver) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    override suspend fun <T> withExclusive(block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease]?.driver === this) return block()
        return operationLock.withLock { withContext(Lease(this)) { block() } }
    }

    private var readerJob: Job? = null
    private val replyLock = Any()
    private val partialReply = StringBuilder()

    @Volatile
    private var resyncNeeded = false

    /** Set once the link is gone; every later command fails fast with this error. */
    @Volatile
    private var connectionError: TransportException? = null

    private val _linkLost = MutableStateFlow<TransportException?>(null)

    /**
     * Becomes non-null when the link drops unexpectedly (read or write failure, remote close).
     * Stays null after a deliberate [close], so observers can tell a loss from a disconnect.
     */
    val linkLost: StateFlow<TransportException?> = _linkLost.asStateFlow()

    /** Firmware versions read once during [initialize]; display and report only, never a gate. */
    @Volatile
    var capabilities: AdapterCapabilities = AdapterCapabilities.NONE
        private set

    val isOpen: Boolean get() = transport.isOpen && readerJob?.isActive == true

    /** Opens the transport and starts the reader. */
    suspend fun open() {
        logger("Opening ${transport.description}")
        transport.open()
        readerJob = scope.launch(ioDispatcher) { readLoop() }
    }

    /**
     * Resets and configures the adapter, then identifies it and reads its firmware versions.
     *
     * `ATH1` (headers on) and `ATSP0` (automatic protocol) are required; the other settings only make
     * parsing easier and are tolerated if a clone rejects them, because the parser copes either way.
     */
    suspend fun initialize(): AdapterInfo = withExclusive {
        reset()
        for (command in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")) {
            val response = sendRaw(command, CONFIG_TIMEOUT_MS)
            if (!response.isOk) {
                if (command in REQUIRED_SETTINGS) throw AdapterInitException(command, response)
                logger("Warning: adapter rejected $command; continuing")
            }
        }

        val identity = AdapterIdentity.parse(sendRaw("ATI", CONFIG_TIMEOUT_MS).text)
        val description = try {
            sendRaw("AT@1", CONFIG_TIMEOUT_MS).takeIf { it.isSuccess }?.text?.takeIf { it.isNotBlank() }
        } catch (e: CommandTimeoutException) {
            null
        }

        capabilities = readFirmwareVersions()
        AdapterInfo(identity = identity, description = description, capabilities = capabilities)
    }

    /**
     * Sends [command] (AT, ST, VT or a hex request) and waits up to [timeoutMs] for the prompt. A `?`
     * reply (unknown command) is logged and returned, not thrown.
     */
    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse = withExclusive {
        val normalized = command.trim().uppercase()
        require(normalized.isNotEmpty()) { "Empty command" }
        val response = sendRaw(normalized, timeoutMs)
        if (response.error == ElmError.UNKNOWN_COMMAND) logger("Adapter answered ? to $normalized; skipping")
        response
    }

    /** Closes the link. Safe to call more than once. */
    fun close() {
        if (connectionError == null) connectionError = TransportException("Connection closed")
        transport.close()
        readerJob?.cancel()
        prompts.close()
    }

    private suspend fun reset() {
        // ATZ reboots the adapter, which takes up to about a second; allow one retry for adapters
        // that drop the first command after the RFCOMM link comes up.
        try {
            sendRaw("ATZ", RESET_TIMEOUT_MS)
        } catch (e: CommandTimeoutException) {
            logger("No reply to ATZ, retrying")
            sendRaw("ATZ", RESET_TIMEOUT_MS)
        }
    }

    /** Reads `VTVERS` and `STI` once. Unknown (`?`) or empty replies just mean "not reported". */
    private suspend fun readFirmwareVersions(): AdapterCapabilities {
        val vtVersion = probe("VTVERS")
        val stVersion = probe("STI")
        logger("Firmware: VT=" + (vtVersion ?: "not reported") + ", ST=" + (stVersion ?: "not reported"))
        return AdapterCapabilities(vtVersion = vtVersion, stVersion = stVersion)
    }

    private suspend fun probe(command: String): String? = try {
        AdapterCapabilities.interpretProbe(sendRaw(command, PROBE_TIMEOUT_MS))
    } catch (e: CommandTimeoutException) {
        null
    }

    private suspend fun sendRaw(command: String, timeoutMs: Long): ElmResponse = queue.submit {
        connectionError?.let { throw it }
        if (resyncNeeded) {
            // The previous command timed out; give its late reply a moment to arrive so it is not
            // mistaken for the answer to this command.
            val prompt = withTimeoutOrNull(RESYNC_WAIT_MS) { receivePrompt() }
            if (prompt == null && command != "ATZ") {
                // Never attribute a previous candidate's late ECU reply to a new setup. ATZ is
                // the existing adapter-reset-only recovery exception; no ECU request is replayed.
                val error = TransportException("Adapter did not return a prompt after a timed-out/cancelled command; reconnect before sending another request")
                markLost(error)
                throw error
            }
            resyncNeeded = false
        }
        drainStalePrompts()
        connectionError?.let { throw it }

        logger("> $command")
        try {
            transport.write("$command\r".toByteArray(Charsets.US_ASCII))
        } catch (e: CancellationException) {
            resyncNeeded = true
            throw e
        } catch (e: Exception) {
            val error = e as? TransportException ?: TransportException("Write to adapter failed", e)
            markLost(error)
            throw error
        }

        val raw = try { withTimeoutOrNull(timeoutMs) { receivePrompt() } }
        catch (e: CancellationException) { resyncNeeded = true; throw e }
        if (raw == null) {
            resyncNeeded = true
            logger("! no reply to $command after $timeoutMs ms")
            throw CommandTimeoutException(command, timeoutMs, synchronized(replyLock) { partialReply.toString() })
        }
        val response = ElmResponseParser.parse(command, raw)
        logger("< " + response.lines.joinToString(" | ").ifEmpty { "(empty)" })
        response
    }

    private suspend fun receivePrompt(): String = try {
        prompts.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw TransportException("Connection to adapter closed", e)
    }

    private fun drainStalePrompts() {
        while (true) {
            val stale = prompts.tryReceive().getOrNull() ?: break
            if (stale.isNotBlank()) logger("Discarded stale reply: ${stale.trim()}")
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (currentCoroutineContext().isActive) {
                val count = transport.read(buffer)
                if (count < 0) throw TransportException("Adapter closed the connection")
                for (i in 0 until count) {
                    val char = (buffer[i].toInt() and 0xFF).toChar()
                    val completed = synchronized(replyLock) {
                        if (char == '>') partialReply.toString().also { partialReply.setLength(0) }
                        else {
                            if (partialReply.length >= MAX_RESPONSE_CHARS) throw TransportException("Adapter response exceeded its bounded input budget")
                            partialReply.append(char)
                            null
                        }
                    }
                    if (completed != null) prompts.send(completed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e as? TransportException ?: TransportException("Read failed", e)
            markLost(error)
            prompts.close(error)
        }
    }

    private fun markLost(error: TransportException) {
        if (connectionError != null) return
        connectionError = error
        logger("Connection lost: ${error.message}")
        _linkLost.value = error
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val CONFIG_TIMEOUT_MS = 2_000L
        const val RESET_TIMEOUT_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 1_500L
        private const val RESYNC_WAIT_MS = 500L
        private const val READ_BUFFER_SIZE = 512
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
        private val REQUIRED_SETTINGS = setOf("ATH1", "ATSP0")
    }
}
