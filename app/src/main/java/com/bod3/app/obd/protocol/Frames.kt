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

/** ISO 15765-2 (ISO-TP) reassembly of CAN frames into messages, independently per header. */
object IsoTp {

    private class Pending(val totalLength: Int, val buffer: MutableList<Int>, var nextSequence: Int)

    fun assemble(frames: List<Frame>): List<EcuMessage> {
        val messages = mutableListOf<EcuMessage>()
        val pending = HashMap<String, Pending>()

        for (frame in frames) {
            val data = frame.data
            val pci = data[0]
            when (pci shr 4) {
                0x0 -> { // Single frame: low nibble is the payload length.
                    pending.remove(frame.header)
                    val length = pci and 0x0F
                    if (length == 0) continue
                    messages += EcuMessage(frame.header, data.drop(1).take(length))
                }

                0x1 -> { // First frame: 12-bit total length, then the start of the payload.
                    if (data.size < 2) continue
                    val total = ((pci and 0x0F) shl 8) or data[1]
                    val state = Pending(total, data.drop(2).toMutableList(), nextSequence = 1)
                    if (state.buffer.size >= total) {
                        messages += EcuMessage(frame.header, state.buffer.take(total))
                    } else {
                        pending[frame.header] = state
                    }
                }

                0x2 -> { // Consecutive frame: low nibble is a sequence number that wraps at 16.
                    val state = pending[frame.header] ?: continue
                    if ((pci and 0x0F) != state.nextSequence) {
                        pending.remove(frame.header) // Out of order: drop the incomplete message.
                        continue
                    }
                    state.buffer += data.drop(1)
                    state.nextSequence = (state.nextSequence + 1) and 0x0F
                    if (state.buffer.size >= state.totalLength) {
                        messages += EcuMessage(frame.header, state.buffer.take(state.totalLength))
                        pending.remove(frame.header)
                    }
                }

                else -> Unit // Flow control frames carry no payload.
            }
        }
        return messages
    }
}

object ResponseDecoder {
    /** Turns adapter response lines into per-ECU messages for the given protocol. */
    /**
     * @param extendedAddressing ISO 15765-2 extended addressing: the first data byte of every CAN frame
     *   is the address of the tester and is removed before ISO-TP reassembly.
     */
    fun decode(lines: List<String>, protocol: ObdProtocol, extendedAddressing: Boolean = false): List<EcuMessage> {
        val frames = FrameParser.parse(lines, protocol.headerFormat)
        return if (protocol.isCan) {
            val transport = if (extendedAddressing) {
                frames.mapNotNull { frame -> frame.data.drop(1).takeIf { it.isNotEmpty() }?.let { Frame(frame.header, it) } }
            } else {
                frames
            }
            IsoTp.assemble(transport)
        } else {
            // Non-CAN protocols have no transport layer: every line is its own message.
            frames.map { EcuMessage(it.header, it.data) }
        }
    }
}
