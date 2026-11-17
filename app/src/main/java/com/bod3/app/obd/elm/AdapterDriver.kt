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
import kotlinx.coroutines.withTimeoutOrNull

/** Anything that can send one command to an adapter and return its parsed response. */
fun interface CommandExecutor {
    suspend fun execute(command: String, timeoutMs: Long): ElmResponse
}

suspend fun CommandExecutor.execute(command: String): ElmResponse =
    execute(command, AdapterDriver.DEFAULT_TIMEOUT_MS)

/**
 * Driver for ELM327-compatible adapters, tuned for the MIC3313 (Vgate/vLinker) chip.
 *
 * Responsibilities:
 * - owns the [Transport] and a background reader that splits the incoming byte stream on the `>` prompt;
 * - runs every command through a FIFO [CommandQueue] with a per-command timeout;
 * - performs the init sequence (`ATZ ATE0 ATL0 ATS0 ATH1 ATSP0`) and reads `ATI` / `VTVERS`;
 * - detects vendor extensions and refuses to send ST/VT commands the adapter has not been shown to support.
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
    private var readerJob: Job? = null

    @Volatile
    private var resyncNeeded = false

    @Volatile
    private var capabilitiesDetected = false

    /** Set once the link is gone; every later command fails fast with this error. */
    @Volatile
    private var connectionError: TransportException? = null

    private val _linkLost = MutableStateFlow<TransportException?>(null)

    /**
     * Becomes non-null when the link drops unexpectedly (read or write failure, remote close).
     * Stays null after a deliberate [close], so observers can tell a loss from a disconnect.
     */
    val linkLost: StateFlow<TransportException?> = _linkLost.asStateFlow()

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
     * Resets and configures the adapter, then identifies it and detects vendor extensions.
     *
     * `ATH1` (headers on) and `ATSP0` (automatic protocol) are required; the other settings only make
     * parsing easier and are tolerated if a clone rejects them, because the parser copes either way.
     */
    suspend fun initialize(): AdapterInfo {
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

        capabilities = detectCapabilities()
        return AdapterInfo(identity = identity, description = description, capabilities = capabilities)
    }

    /**
     * Sends [command] and waits up to [timeoutMs] for the prompt.
     *
     * ST and VT commands are rejected with [UnsupportedCommandException] unless capability detection
     * has run and found the matching extension.
     */
    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse {
        val normalized = command.trim().uppercase()
        require(normalized.isNotEmpty()) { "Empty command" }
        checkExtensionAllowed(normalized)
        return sendRaw(normalized, timeoutMs)
    }

    /** Closes the link. Safe to call more than once. */
    fun close() {
        if (connectionError == null) connectionError = TransportException("Connection closed")
        transport.close()
        readerJob?.cancel()
        prompts.close()
    }

    private fun checkExtensionAllowed(command: String) {
        val family = when {
            command.startsWith("ST") -> "ST"
            command.startsWith("VT") -> "VT"
            else -> return
        }
        if (!capabilitiesDetected) {
            throw UnsupportedCommandException(command, "adapter capabilities have not been detected yet")
        }
        val supported = if (family == "ST") capabilities.supportsSt else capabilities.supportsVt
        if (!supported) {
            throw UnsupportedCommandException(command, "adapter does not support $family commands")
        }
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

    /**
     * Probes vendor extensions. These probes are the only ST/VT commands ever sent before
     * capabilities are known; an ELM327 answers unknown commands with `?`, which is harmless.
     */
    private suspend fun detectCapabilities(): AdapterCapabilities {
        val vtVersion = probe("VTVERS")
        val stVersion = probe("STI")
        capabilitiesDetected = true
        val detected = AdapterCapabilities(vtVersion = vtVersion, stVersion = stVersion)
        logger(
            "Capabilities: VT=" + (vtVersion ?: "no") + ", ST=" + (stVersion ?: "no"),
        )
        return detected
    }

    private suspend fun probe(command: String): String? = try {
        AdapterCapabilities.interpretProbe(sendRaw(command, PROBE_TIMEOUT_MS))
    } catch (e: CommandTimeoutException) {
        null
    }

    private suspend fun sendRaw(command: String, timeoutMs: Long): ElmResponse = queue.submit {
        if (resyncNeeded) {
            // The previous command timed out; give its late reply a moment to arrive so it is not
            // mistaken for the answer to this command.
            withTimeoutOrNull(RESYNC_WAIT_MS) { receivePrompt() }
            resyncNeeded = false
        }
        drainStalePrompts()
        connectionError?.let { throw it }

        logger("> $command")
        try {
            transport.write("$command\r".toByteArray(Charsets.US_ASCII))
        } catch (e: Exception) {
            val error = e as? TransportException ?: TransportException("Write to adapter failed", e)
            markLost(error)
            throw error
        }

        val raw = withTimeoutOrNull(timeoutMs) { receivePrompt() }
        if (raw == null) {
            resyncNeeded = true
            logger("! no reply to $command after $timeoutMs ms")
            throw CommandTimeoutException(command, timeoutMs)
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
        val pending = StringBuilder()
        try {
            while (currentCoroutineContext().isActive) {
                val count = transport.read(buffer)
                if (count < 0) throw TransportException("Adapter closed the connection")
                for (i in 0 until count) {
                    val char = (buffer[i].toInt() and 0xFF).toChar()
                    if (char == '>') {
                        prompts.send(pending.toString())
                        pending.setLength(0)
                    } else {
                        pending.append(char)
                    }
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
        private val REQUIRED_SETTINGS = setOf("ATH1", "ATSP0")
    }
}
