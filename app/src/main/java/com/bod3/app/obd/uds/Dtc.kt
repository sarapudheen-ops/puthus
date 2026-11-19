package com.bod3.app.obd.uds

/** KWP/manufacturer status bits are not implicitly UDS status bits. */
enum class DtcStatusMeaning { UDS, RAW }

/** A diagnostic trouble code; RAW status remains opaque unless its bit meanings are documented. */
data class DtcRecord(val code: String, val status: Int?, val raw: List<Int>, val statusMeaning: DtcStatusMeaning = DtcStatusMeaning.UDS) {
    val testFailed: Boolean get() = statusMeaning == DtcStatusMeaning.UDS && status != null && status and 0x01 != 0
    val pending: Boolean get() = statusMeaning == DtcStatusMeaning.UDS && status != null && status and 0x04 != 0
    val confirmed: Boolean get() = statusMeaning == DtcStatusMeaning.UDS && (status == null || status and 0x08 != 0)
    val warningLamp: Boolean get() = statusMeaning == DtcStatusMeaning.UDS && status != null && status and 0x80 != 0

    /** Short status summary, e.g. "confirmed, MIL". */
    val statusText: String
        get() {
            if (statusMeaning == DtcStatusMeaning.RAW) return status?.let { "raw status 0x%02X".format(it) } ?: "stored"
            if (status == null) return "stored (OBD)"
            val parts = buildList {
                if (testFailed) add("failing now")
                if (status and 0x02 != 0) add("failed this cycle")
                if (pending) add("pending")
                if (status and 0x08 != 0) add("confirmed")
                if (warningLamp) add("warning lamp")
            }
            return parts.joinToString(", ").ifEmpty { "status 0x%02X".format(status) }
        }
}

sealed interface DtcReadResult {
    /** `source` is "UDS 19 02" or "OBD 03". */
    data class Read(val source: String, val records: List<DtcRecord>, val availabilityMask: Int? = null) : DtcReadResult

    data class Negative(val nrc: Int) : DtcReadResult

    data object NoResponse : DtcReadResult

    data class Failed(val message: String) : DtcReadResult

    val faultCount: Int? get() = (this as? Read)?.records?.size

    val display: String
        get() = when (this) {
            is Read -> if (records.isEmpty()) "No faults" else "${records.size} fault(s)"
            is Negative -> if (Nrc.meansNotSupported(nrc)) "Not supported" else "Not available: ${Nrc.describe(nrc)}"
            NoResponse -> "Not supported (no response)"
            is Failed -> "Not read: $message"
        }
}

object DtcDecoder {
    private val letters = charArrayOf('P', 'C', 'B', 'U')

    /** Two-byte SAE J2012 code, e.g. `04 20` -> `P0420`. */
    fun obdCode(high: Int, low: Int): String {
        val letter = letters[(high shr 6) and 0x03]
        val first = (high shr 4) and 0x03
        return "%c%d%X%02X".format(letter, first, high and 0x0F, low)
    }

    /** Three-byte UDS code: the J2012 code plus the failure type byte, e.g. `04 20 13` -> `P0420-13`. */
    fun udsCode(high: Int, middle: Int, failureType: Int): String = "%s-%02X".format(obdCode(high, middle), failureType)

    /**
     * Parses a positive reply to `19 02 <mask>`: `59 02 <availability mask>` followed by 4-byte records
     * (3 DTC bytes + status). Records with status 0 and the all-zero padding record are skipped.
     */
    fun parseReportByStatusMask(payload: List<Int>): DtcReadResult.Read? {
        if (payload.size < 3 || payload[0] != 0x59 || payload[1] != 0x02) return null
        val records = payload.drop(3).chunked(4)
            .filter { it.size == 4 }
            .filterNot { it[0] == 0 && it[1] == 0 && it[2] == 0 }
            .filter { it[3] != 0 }
            .map { DtcRecord(udsCode(it[0], it[1], it[2]), it[3], it) }
        return DtcReadResult.Read("UDS 19 02", records, availabilityMask = payload[2])
    }

    /** Parses an OBD service 03 reply on CAN: `43 <count>` followed by 2-byte codes. */
    fun parseObdStored(payload: List<Int>): DtcReadResult.Read? {
        if (payload.size < 2 || payload[0] != 0x43) return null
        val records = payload.drop(2).chunked(2)
            .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { DtcRecord(obdCode(it[0], it[1]), null, it) }
        return DtcReadResult.Read("OBD 03", records)
    }
}
