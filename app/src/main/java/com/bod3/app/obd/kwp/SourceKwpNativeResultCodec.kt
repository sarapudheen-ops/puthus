package com.bod3.app.obd.kwp

import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.recipe.Hex

/**
 * Bounded port of the native answer-record boundary AFTER EndCombination.
 *
 * This is not a MIC3313 command driver. It does not run vendor code, translate
 * electrical/timer settings, infer a vehicle, or make a catalog executable.
 * The native abstract layer returns a signed LOW word and a count followed by
 * byte-length-prefixed raw UART packets. Its positive return is only the number
 * of copied bytes: a corrupt checksum, partial packet or wrong ECU can ALSO be
 * positive. Preserve that distinction; SourceKwpPlan.completeC1 independently
 * verifies the complete raw frame, checksum, exact ECU and three-byte C1.
 *
 * Compared with the supplied native queuing/EndCombination/parser instructions
 * and a pinned supplemental firmware consumer in the offline timing experiment.
 * Those experiments use synthetic UART/timer leaves, never vehicle evidence.
 */
internal object SourceKwpNativeResultCodec {
    fun endCombination(word: Long, records: List<Int>): SourceKwpReply {
        // Check the whole source reply-word domain, not a clamped integer cast.
        require(word in Int.MIN_VALUE.toLong()..0xFFFFFFFFL) { "Native result is not a 32-bit word" }
        val copied = word.toShort().toInt()
        require(records.size <= 255 && records.all { it in 0..255 }) { "Unbounded native answer records" }
        if (copied <= 0) {
            require(records.isEmpty()) { "Failed/empty native result cannot contain answer data" }
            return SourceKwpReply(word)
        }
        require(copied == records.size && copied >= 2) { "Native copied-byte count differs from its complete answer records" }
        val count = records.first()
        require(count > 0 && count <= (records.size - 1) / 2) { "Invalid native packet count" }
        val packets = ArrayList<List<Int>>(count)
        var at = 1
        repeat(count) {
            require(at < records.size) { "Missing native packet length" }
            val size = records[at++]
            require(size > 0 && size <= records.size - at) { "Truncated native raw packet" }
            packets += records.subList(at, at + size).toList()
            at += size
        }
        require(at == records.size) { "Unclaimed native answer-record tail" }
        val lines = packets.map { Hex.format(it, "") }
        // Do not remove headers/checkbytes or fabricate a C1 from the return
        // word. A short/foreign/raw packet must still fail independent framing.
        val first = packets.first()
        val serviceAt = if (first[0] and 63 == 0) 4 else 3
        return SourceKwpReply(word, first.getOrNull(serviceAt),
            ElmResponse("source-EndCombination", lines.joinToString("\r"), lines, null))
    }
}
