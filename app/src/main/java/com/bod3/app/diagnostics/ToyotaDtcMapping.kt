package com.bod3.app.diagnostics

/** Opaque native bytes. They are NOT UDS state bits, ECU identity or operation authorization. */
internal data class ToyotaMergedDtc(val nativeCode: Long, val byte4: Int, val byte5: Int)

/**
 * DTCF SetDiagCodeData @15760. Preserve exact keys and first-seen order; OR each
 * byte into an existing row. A missing row is added only when BOTH bytes are
 * nonzero. This port hardens the native one-byte count against wraparound.
 * There is no request, device access, applicability or write grant here.
 */
internal class ToyotaDtcMapping {
    private val rows = mutableListOf<ToyotaMergedDtc>()
    val records: List<ToyotaMergedDtc> get() = rows.toList()

    fun merge(nativeCode: Long, arg0: Int, arg1: Int): Boolean {
        require(nativeCode in 0..0xFFFFFFFFL && arg0 in 0..255 && arg1 in 0..255)
        val i = rows.indexOfFirst { it.nativeCode == nativeCode }
        if (i >= 0) {
            val old = rows[i]
            rows[i] = old.copy(byte4 = old.byte4 or arg1, byte5 = old.byte5 or arg0)
            return true
        }
        if (arg0 == 0 || arg1 == 0) return false
        require(rows.size < 255) { "Native DTC merge count would wrap" }
        rows += ToyotaMergedDtc(nativeCode, arg1, arg0)
        return true
    }
}
