package com.bod3.app.obd.testing

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.elm.ElmResponseParser
import com.bod3.app.obd.transport.Transport
import com.bod3.app.obd.transport.TransportException
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/** Executor that answers from a table of raw adapter replies and records what was sent. */
class ScriptedExecutor(replies: Map<String, String> = emptyMap()) : CommandExecutor {
    private val replies = replies.mapKeys { normalize(it.key) }.toMutableMap()
    val sent: MutableList<String> = mutableListOf()

    fun reply(command: String, raw: String) {
        replies[normalize(command)] = raw
    }

    override suspend fun execute(command: String, timeoutMs: Long): ElmResponse {
        sent += command
        val raw = replies[normalize(command)] ?: "?"
        return ElmResponseParser.parse(command, raw)
    }

    private fun normalize(command: String) = command.filterNot { it.isWhitespace() }.uppercase()
}

/**
 * In-memory adapter: every command written (terminated by CR) is passed to [respond]; a non-null result
 * is sent back followed by the `>` prompt, a null result simulates an adapter that never answers.
 */
class FakeAdapterTransport(private val respond: (String) -> String?) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val partialCommand = StringBuilder()
    private var leftover: ByteArray? = null

    val written: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    override var isOpen: Boolean = false
        private set

    override val description: String = "fake adapter"

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw TransportException("Fake adapter is closed")
        partialCommand.append(String(data, Charsets.US_ASCII))
        while (true) {
            val end = partialCommand.indexOf("\r")
            if (end < 0) break
            val command = partialCommand.substring(0, end)
            partialCommand.delete(0, end + 1)
            written += command
            respond(command)?.let { incoming.send("$it\r\r>".toByteArray(Charsets.US_ASCII)) }
        }
    }

    /** Pushes bytes as if the adapter sent them unprompted (e.g. a late reply). */
    suspend fun inject(text: String) {
        incoming.send(text.toByteArray(Charsets.US_ASCII))
    }

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = leftover ?: try {
            incoming.receive()
        } catch (e: ClosedReceiveChannelException) {
            return -1
        }
        val count = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, endIndex = count)
        leftover = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
        return count
    }

    override fun close() {
        isOpen = false
        incoming.close()
    }
}
