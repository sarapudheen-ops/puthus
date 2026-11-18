package com.bod3.app.obd.kwp

import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.recipe.Hex

/** ISO 14230-2 length framing and additive checksum; ISO 9141 uses a fixed three-byte header. */
object KLineFrames {
    fun encode(header: List<Int>, payload: List<Int>, iso9141: Boolean = false): List<Int> {
        require(header.size == 3 && header.all { it in 0..255 })
        require(payload.size in 1..255 && payload.all { it in 0..255 })
        val framed = if (iso9141) header + payload else {
            require(header[0] and 0x80 != 0) { "Only addressed KWP frames may be transmitted" }
            val prefix = header.toMutableList()
            prefix[0] = (header[0] and 0xC0) or if (payload.size <= 63) payload.size else 0
            prefix + (if (payload.size > 63) listOf(payload.size) else emptyList()) + payload
        }
        return framed + checksum(framed)
    }
    fun checksum(bytes: List<Int>): Int = bytes.sum() and 0xFF

    /** Never accept a partial message, wrong ECU or corrupt checksum as a positive response. */
    fun decode(line: String, expectedHeader: List<Int>, iso9141: Boolean = false): EcuMessage? {
        if (expectedHeader.size != 3) return null
        val bytes = Hex.parse(line.trim()) ?: return null
        if (bytes.size < 5 || checksum(bytes.dropLast(1)) != bytes.last()) return null
        val headerSize: Int
        val length: Int
        if (iso9141) {
            if (bytes.take(3) != expectedHeader) return null
            headerSize = 3; length = bytes.size - 4
        } else {
            // Physical addressing only; functional/no-address frames cannot identify this ECU.
            if ((bytes[0] and 0xC0) != 0x80 || bytes[1] != expectedHeader[1] || bytes[2] != expectedHeader[2]) return null
            val shortLength = bytes[0] and 0x3F
            headerSize = if (shortLength == 0) 4 else 3
            length = if (shortLength == 0) bytes.getOrNull(3) ?: return null else shortLength
            if (length == 0 || bytes.size != headerSize + length + 1) return null
        }
        return EcuMessage(Hex.format(bytes.take(3), ""), bytes.subList(headerSize, headerSize + length))
    }
}
