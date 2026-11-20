package com.bod3.app.diagnostics

import com.bod3.app.obd.uds.DtcReadResult

/**
 * Core state/code projection of DTCF GetDTCCodeFromAnsP5 @151c4.
 *
 * P5 has no count check or kind-3 origin override: it walks records from the
 * explicit source start, with a big-endian code followed by one status byte.
 * The projection is equivalent to assembly 6 of the shared decoder with those
 * P3/P4-only rules absent. The separate kind prevents imports/converters from
 * accidentally applying P3/P4 caller behavior to a P5 workflow.
 *
 * This does NOT implement/approve P5 mask discovery, auxiliary freeze-frame
 * tables, query selection, a transport or ECU applicability. The mask must be
 * supplied by the selected source workflow, never guessed from a UDS default.
 */
data class ToyotaP5FaultLayout(
    val recordStart: Int,
    val recordBytes: Int,
    val queryKind: Int,
    val availabilityMask: Int,
    val labels: List<ToyotaFaultLabel> = emptyList(),
    override val sourceRecords: List<String> = emptyList(),
    val sourceFallback: ToyotaFaultFallback? = null,
) : FaultRecordLayout {
    private fun projection() = ToyotaFaultLayout(255, recordStart, recordBytes, 6,
        false, 2, queryKind, availabilityMask, labels, sourceRecords, sourceFallback)

    override fun problems(): List<String> = projection().problems()
    override fun decode(data: List<Int>, source: String): DtcReadResult = projection().decode(data, source)

    internal fun map(): Map<String, Any?> = projection().map().filterKeys { it in fields } + ("kind" to "TOYOTA_P5")

    companion object {
        private val fields = setOf("kind", "recordStart", "recordBytes", "queryKind", "availabilityMask", "labels", "sourceRecords", "sourceFallback")

        internal fun decode(map: Map<*, *>, path: String, errors: MutableList<String>): ToyotaP5FaultLayout? {
            val before = errors.size
            map.keys.filterNot { it in fields }.forEach { errors += "$path.$it: unknown P5 field" }
            val shared = ToyotaFaultLayout.decode(map + mapOf("kind" to "TOYOTA_P3P4", "countIndex" to 255,
                "assemblyKind" to 6, "countIsPresence" to false, "modeLowBits" to 2), path, errors)
            return if (shared == null || errors.size != before) null else ToyotaP5FaultLayout(shared.recordStart,
                shared.recordBytes, shared.queryKind, shared.availabilityMask, shared.labels, shared.sourceRecords, shared.sourceFallback)
        }
    }
}
