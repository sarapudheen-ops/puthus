package com.bod3.app.obd.dtc

import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DtcDecoder

/** OBD DTC read services. */
enum class DtcKind(val service: Int, val label: String) {
    STORED(0x03, "Stored (confirmed)"),
    PENDING(0x07, "Pending"),
    PERMANENT(0x0A, "Permanent"),
}

data class EcuDtcs(val ecu: String, val codes: List<String>)

/** Outcome of OBD service 04 per answering ECU. */
data class ClearResult(
    /** ECUs that confirmed with `44`. */
    val cleared: List<String>,
    /** ECUs that refused, with the NRC (`22` = conditions not correct, e.g. engine running). */
    val refused: Map<String, Int>,
) {
    val noResponse: Boolean get() = cleared.isEmpty() && refused.isEmpty()
}

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

    /**
     * OBD service 04: clears stored and pending codes, freeze frames and readiness monitors on every
     * emission-related ECU. Needs a [ClearConfirmation.obdClear] created after the user confirmed.
     * Permanent codes (0A) are not cleared by this; the ECU removes them after a passing drive cycle.
     */
    suspend fun clear(confirmation: ClearConfirmation): ClearResult {
        val response = session.request(EcuTarget.Broadcast, listOf(0x04), CLEAR_TIMEOUT_MS, confirmation)
        return ClearResult(
            cleared = response.messages.map { it.header }.distinct(),
            refused = response.negativeResponses.associate { it.header to it.payload[2] },
        )
    }

    companion object {
        const val READ_TIMEOUT_MS = 8_000L
        const val CLEAR_TIMEOUT_MS = 10_000L

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
