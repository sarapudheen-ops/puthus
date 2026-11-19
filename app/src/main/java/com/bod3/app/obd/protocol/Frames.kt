package com.bod3.app.obd.protocol

/** One line of adapter output with headers on: the header and the data bytes that follow it. */
data class Frame(val header: String, val data: List<Int>)

/** A complete diagnostic message from one ECU, after multi-frame reassembly. */
data class EcuMessage(val header: String, val payload: List<Int>)

object FrameParser {

    /**
     * Parses one response line. Spaces are ignored, so this works whether or not `ATS0` was accepted.
     * For [HeaderFormat.LEGACY] the trailing checksum byte is dropped.
     *
     * Returns null for lines that are not hex frames of the expected shape.
     */
    fun parseLine(line: String, format: HeaderFormat): Frame? {
        val compact = line.filterNot { it == ' ' }.uppercase()
        if (compact.isEmpty() || !compact.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val headerLength = format.hexLength
        if (compact.length <= headerLength || (compact.length - headerLength) % 2 != 0) return null

        val header = compact.substring(0, headerLength)
        val bytes = compact.substring(headerLength).chunked(2).map { it.toInt(16) }
        val data = if (format == HeaderFormat.LEGACY) bytes.dropLast(1) else bytes
        if (data.isEmpty()) return null
        return Frame(header, data)
    }

    fun parse(lines: List<String>, format: HeaderFormat): List<Frame> =
        lines.mapNotNull { parseLine(it, format) }
}

/** Whole exchange integrity, not merely the subset of frames that could be decoded. */
data class DecodedMessages(val messages: List<EcuMessage>, val complete: Boolean)

/** ISO 15765-2 (ISO-TP) reassembly of CAN frames into messages, independently per header. */
object IsoTp {

    private class Pending(val totalLength: Int, val buffer: MutableList<Int>, var nextSequence: Int)

    fun assemble(frames: List<Frame>): List<EcuMessage> = inspect(frames).messages

    /** A valid earlier message cannot conceal a later truncated/invalid response. */
    fun inspect(frames: List<Frame>): DecodedMessages {
        val messages = mutableListOf<EcuMessage>()
        val pending = HashMap<String, Pending>()
        var complete = true

        for (frame in frames) {
            val data = frame.data
            if (data.isEmpty() || data.any { it !in 0..255 }) { complete = false; continue }
            val pci = data[0]
            when (pci shr 4) {
                0x0 -> { // Single frame: low nibble is the payload length, excluding padding.
                    if (pending.remove(frame.header) != null) complete = false
                    val length = pci and 0x0F
                    if (length !in 1..7 || data.size <= length) { complete = false; continue }
                    messages += EcuMessage(frame.header, data.drop(1).take(length))
                }

                0x1 -> { // First frame: a complete 12-bit length and initial data are required.
                    if (pending.remove(frame.header) != null) complete = false
                    if (data.size < 3) { complete = false; continue }
                    val total = ((pci and 0x0F) shl 8) or data[1]
                    if (total <= 7 || data.size - 2 >= total) { complete = false; continue }
                    pending[frame.header] = Pending(total, data.drop(2).toMutableList(), nextSequence = 1)
                }

                0x2 -> { // Consecutive frame: low nibble is a sequence number that wraps at 16.
                    val state = pending[frame.header]
                    if (state == null || data.size < 2 || (pci and 0x0F) != state.nextSequence) {
                        pending.remove(frame.header)
                        complete = false
                        continue
                    }
                    state.buffer += data.drop(1)
                    state.nextSequence = (state.nextSequence + 1) and 0x0F
                    if (state.buffer.size >= state.totalLength) {
                        messages += EcuMessage(frame.header, state.buffer.take(state.totalLength))
                        pending.remove(frame.header)
                    }
                }

                0x3 -> Unit // Flow control frames carry no diagnostic payload.
                else -> complete = false
            }
        }
        return DecodedMessages(messages, complete && pending.isEmpty())
    }
}

object ResponseDecoder {
    /** Turns adapter response lines into per-ECU messages for the given protocol. */
    /**
     * @param extendedAddressing ISO 15765-2 extended addressing: the first data byte of every CAN frame
     *   is the address of the tester and is removed before ISO-TP reassembly.
     */
    fun decode(lines: List<String>, protocol: ObdProtocol, extendedAddressing: Boolean = false): List<EcuMessage> =
        inspect(lines, protocol, extendedAddressing).messages

    fun inspect(lines: List<String>, protocol: ObdProtocol, extendedAddressing: Boolean = false): DecodedMessages {
        val frames = FrameParser.parse(lines, protocol.headerFormat)
        return if (protocol.isCan) {
            val transport = if (extendedAddressing) {
                frames.mapNotNull { frame -> frame.data.drop(1).takeIf { it.isNotEmpty() }?.let { Frame(frame.header, it) } }
            } else {
                frames
            }
            val decoded = IsoTp.inspect(transport)
            DecodedMessages(decoded.messages, decoded.complete && frames.size == lines.size && transport.size == frames.size)
        } else {
            // Enhanced K-line checks framing/checksums separately in Session.
            DecodedMessages(frames.map { EcuMessage(it.header, it.data) }, frames.size == lines.size)
        }
    }
}
