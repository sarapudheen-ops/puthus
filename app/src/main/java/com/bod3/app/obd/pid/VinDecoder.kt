package com.bod3.app.obd.pid

import com.bod3.app.obd.protocol.EcuMessage

object VinDecoder {

    /**
     * Extracts the VIN from Service 09 PID 02 responses (`49 02 ...`).
     *
     * - CAN: one reassembled message, `49 02 01` followed by 17 ASCII bytes.
     * - J1850 / ISO 9141 / KWP: five messages `49 02 <seq> b1 b2 b3 b4`, the first padded with zeros.
     *
     * Each ECU's messages are ordered by sequence number and joined. Bytes that cannot appear in a VIN
     * (padding, the count/sequence byte) are dropped and the last 17 characters are used.
     */
    fun decode(messages: List<EcuMessage>): String? {
        for ((_, group) in messages.groupBy { it.header }) {
            val parts = group.map { it.payload }
                .filter { it.size >= 3 && it[0] == 0x49 && it[1] == 0x02 }
                .sortedBy { it[2] }
            if (parts.isEmpty()) continue
            val text = parts.flatMap { it.drop(2) }
                .filter { it in 0x30..0x39 || it in 0x41..0x5A }
                .map { it.toChar() }
                .joinToString("")
            if (text.length >= VIN_LENGTH) return text.takeLast(VIN_LENGTH)
        }
        return null
    }

    const val VIN_LENGTH = 17
}
