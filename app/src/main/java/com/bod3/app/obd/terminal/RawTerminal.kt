package com.bod3.app.obd.terminal

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.Session

data class TerminalLine(val timestampMs: Long, val kind: Kind, val text: String) {
    enum class Kind { INPUT, OUTPUT, ERROR, INFO }
}

/**
 * Expert raw terminal: sends commands typed by the user straight to the adapter after
 * [TerminalPolicy] approves them. It does not use the session's routing, so `ATSH` etc. typed by the
 * user take effect; when the terminal closes, [restore] puts the adapter back into the app's state.
 */
class RawTerminal(
    private val executor: CommandExecutor,
    private val session: Session?,
    private val expertMode: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLines: Int = 2_000,
) {
    private val lines = ArrayDeque<TerminalLine>()
    private var routingDirty = false

    val transcript: List<TerminalLine> get() = synchronized(lines) { lines.toList() }

    suspend fun send(input: String, timeoutMs: Long = 5_000L): TerminalDecision {
        add(TerminalLine.Kind.INPUT, input.trim())
        val decision = TerminalPolicy.decide(input, expertMode())
        when (decision) {
            is TerminalDecision.Refuse -> add(TerminalLine.Kind.ERROR, decision.reason)
            is TerminalDecision.Send -> {
                if (decision.affectsRouting) routingDirty = true
                try {
                    val response = executor.execute(decision.command, timeoutMs)
                    val text = response.lines.joinToString("\n").ifEmpty { "(no reply)" }
                    add(if (response.error != null) TerminalLine.Kind.ERROR else TerminalLine.Kind.OUTPUT, text)
                } catch (e: AdapterException) {
                    add(TerminalLine.Kind.ERROR, e.message ?: "adapter error")
                }
            }
        }
        return decision
    }

    /**
     * Restores parser-relevant settings and, if the user changed routing or protocol, makes the
     * session re-send its routing (and re-select its protocol) on the next request.
     */
    suspend fun restore() {
        for (command in TerminalPolicy.restoreCommands) {
            try {
                executor.execute(command, 2_000L)
            } catch (e: AdapterException) {
                add(TerminalLine.Kind.ERROR, "Restore $command failed: ${e.message}")
            }
        }
        if (routingDirty && session != null) {
            val protocol: ObdProtocol = session.protocol
            try {
                executor.execute("ATSP${protocol.code}", 2_000L)
            } catch (e: AdapterException) {
                add(TerminalLine.Kind.ERROR, "Restoring protocol failed: ${e.message}")
            }
            session.invalidateRouting()
        }
        routingDirty = false
        add(TerminalLine.Kind.INFO, "Adapter settings restored")
    }

    fun clear() = synchronized(lines) { lines.clear() }

    fun text(): String = transcript.joinToString("\n") { line ->
        when (line.kind) {
            TerminalLine.Kind.INPUT -> "> ${line.text}"
            TerminalLine.Kind.OUTPUT -> line.text
            TerminalLine.Kind.ERROR -> "! ${line.text}"
            TerminalLine.Kind.INFO -> "# ${line.text}"
        }
    }

    private fun add(kind: TerminalLine.Kind, text: String) = synchronized(lines) {
        lines.addLast(TerminalLine(clock(), kind, text))
        while (lines.size > maxLines) lines.removeFirst()
    }
}
