package com.bod3.app.obd.dtc

import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DtcDecoder

/** OBD DTC services (all read-only). */
enum class DtcKind(val service: Int, val label: String) {
    STORED(0x03, "Stored (confirmed)"),
    PENDING(0x07, "Pending"),
    PERMANENT(0x0A, "Permanent"),
}

data class EcuDtcs(val ecu: String, val codes: List<String>)

data class DtcReport(val kind: DtcKind, val ecus: List<EcuDtcs>, val noData: Boolean) {
    val codes: List<String> get() = ecus.flatMap { it.codes }.distinct()
}

/**
 * Reads OBD DTCs on any protocol.
 *
 * - CAN: one ISO-TP message per ECU, `43 <count> <2-byte codes...>`.
 * - K-line / J1850 (ISO 9141-2, KWP2000): no count byte; each message carries up to three 2-byte codes
 *   after the service byte (`43 aa bb cc dd ee ff`), padded with `00 00`, and an ECU with more codes
 *   sends several messages.
 */
class ObdDtcReader(private val session: Session) {

    suspend fun read(kind: DtcKind): DtcReport {
        val response = session.request(EcuTarget.Broadcast, listOf(kind.service), READ_TIMEOUT_MS)
        if (response.isEmpty) return DtcReport(kind, emptyList(), noData = true)
        return DtcReport(kind, parse(kind, response.messages, session.protocol.isCan), noData = false)
    }

    companion object {
        const val READ_TIMEOUT_MS = 8_000L

        fun parse(kind: DtcKind, messages: List<EcuMessage>, isCan: Boolean): List<EcuDtcs> {
            val reply = kind.service + 0x40
            return messages.filter { it.payload.firstOrNull() == reply }
                .groupBy { it.header }
                .map { (ecu, group) ->
                    val bytes = group.flatMap { message ->
                        if (isCan) message.payload.drop(2) else message.payload.drop(1)
                    }
                    val codes = bytes.chunked(2)
                        .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                        .map { DtcDecoder.obdCode(it[0], it[1]) }
                        .distinct()
                    EcuDtcs(ecu, codes)
                }
        }
    }
}
