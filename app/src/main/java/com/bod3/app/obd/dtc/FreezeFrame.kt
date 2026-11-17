package com.bod3.app.obd.dtc

import com.bod3.app.obd.pid.PidDefinition
import com.bod3.app.obd.pid.Pids
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DtcDecoder

data class FreezeFrameValue(val definition: PidDefinition, val value: Double) {
    val formatted: String get() = definition.format(value)
}

/** Snapshot an ECU stored when [dtc] set the MIL (OBD service 02, frame [frame]). */
data class FreezeFrame(val ecu: String, val frame: Int, val dtc: String?, val values: List<FreezeFrameValue>)

/**
 * Reads freeze frames with OBD service 02: `02 02 <frame>` for the DTC that caused the frame, the
 * supported-PID bitmaps `02 00/20/40… <frame>`, then each supported PID. Replies are `42 <pid> <frame> data…`.
 * ECUs without a stored frame answer DTC `00 00`, or nothing, and are left out.
 */
class FreezeFrameReader(private val session: Session, private val timeoutMs: Long = 4_000L) {

    suspend fun read(frame: Int = 0): List<FreezeFrame> {
        val dtcs = mutableMapOf<String, String?>()
        for (message in session.request(EcuTarget.Broadcast, listOf(0x02, 0x02, frame), timeoutMs).messages) {
            val p = message.payload
            if (p.size >= 5 && p[2] == frame) dtcs[message.header] = if (p[3] == 0 && p[4] == 0) null else DtcDecoder.obdCode(p[3], p[4])
        }
        val withFrame = dtcs.filterValues { it != null }.keys
        if (withFrame.isEmpty()) return emptyList()

        val supported = mutableMapOf<String, MutableSet<Int>>()
        var base = 0x00
        while (base <= 0xE0) {
            for (message in session.request(EcuTarget.Broadcast, listOf(0x02, base, frame), timeoutMs).messages) {
                val p = message.payload
                if (message.header !in withFrame || p.size < 7 || p[2] != frame) continue
                supported.getOrPut(message.header) { sortedSetOf() } += Pids.decodeSupported(base, p.subList(3, 7))
            }
            if (supported.values.none { (base + 0x20) in it }) break
            base += 0x20
        }

        val values = mutableMapOf<String, MutableList<FreezeFrameValue>>()
        val pids = supported.values.flatten().toSortedSet().mapNotNull { Pids.byPid(it) }
        for (definition in pids) {
            for (message in session.request(EcuTarget.Broadcast, listOf(0x02, definition.pid, frame), timeoutMs).messages) {
                val p = message.payload
                if (message.header !in withFrame || p.size < 3 || p[2] != frame) continue
                if (supported[message.header]?.contains(definition.pid) != true) continue
                definition.decode(p.drop(3))?.let { values.getOrPut(message.header) { mutableListOf() } += FreezeFrameValue(definition, it) }
            }
        }
        return withFrame.map { ecu -> FreezeFrame(ecu, frame, dtcs[ecu], values[ecu].orEmpty()) }
    }
}
