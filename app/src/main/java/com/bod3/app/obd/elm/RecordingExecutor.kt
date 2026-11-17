package com.bod3.app.obd.elm

/** One command sent to the adapter and what came back, for the raw log. */
data class RawExchange(
    val sequence: Int,
    val timestampMs: Long,
    /** CAN header the request was sent on (tracked from `ATSH` / `ATCP`), null before any was set. */
    val header: String?,
    val command: String,
    /** Response lines as printed by the adapter (echo and prompt removed), joined with newlines. */
    val response: String,
    val durationMs: Long,
    val error: String? = null,
)

/**
 * Wraps a [CommandExecutor] and, while [isRecording], keeps a log of every exchange.
 * Thread-safe; the log is capped at [maxEntries] (oldest dropped).
 */
class RecordingExecutor(
    private val delegate: CommandExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 20_000,
) : CommandExecutor {

    private val lock = Any()
    private val entries = ArrayDeque<RawExchange>()
    private var sequence = 0
    private var priority: String? = null
    private var header: String? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    fun startRecording() = synchronized(lock) {
        entries.clear()
        sequence = 0
        isRecording = true
    }

    /** Stops recording and returns everything recorded since [startRecording]. */
    fun stopRecording(): List<RawExchange> = synchronized(lock) {
        isRecording = false
        entries.toList()
    }

    fun snapshot(): List<RawExchange> = synchronized(lock) { entries.toList() }

    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse {
        val normalized = command.trim().uppercase()
        trackHeader(normalized)
        val headerAtSend = synchronized(lock) { header }
        val start = clock()
        try {
            val response = delegate.execute(command, timeoutMs)
            record(normalized, headerAtSend, start, response.lines.joinToString("\n"), null)
            return response
        } catch (e: Exception) {
            record(normalized, headerAtSend, start, "", e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun trackHeader(command: String) {
        val compact = command.replace(" ", "")
        synchronized(lock) {
            when {
                compact.startsWith("ATCP") -> priority = compact.removePrefix("ATCP")
                compact.startsWith("ATSH") -> {
                    val value = compact.removePrefix("ATSH")
                    header = if (value.length == 6 && priority != null) priority + value else value
                }
                compact.startsWith("ATSP") || compact == "ATZ" -> {
                    header = null
                    priority = null
                }
            }
        }
    }

    private fun record(command: String, header: String?, start: Long, response: String, error: String?) {
        if (!isRecording) return
        val now = clock()
        synchronized(lock) {
            if (!isRecording) return
            entries.addLast(RawExchange(++sequence, start, header, command, response, now - start, error))
            while (entries.size > maxEntries) entries.removeFirst()
        }
    }
}
