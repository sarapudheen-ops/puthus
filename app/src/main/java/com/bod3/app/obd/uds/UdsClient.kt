package com.bod3.app.obd.uds

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdResponse
import com.bod3.app.obd.session.Session
import kotlinx.coroutines.delay

/**
 * Read-only UDS requests. Every outcome, including negative responses and silence, is returned as a
 * value so one unsupported identifier never aborts reading the rest of a module.
 */
class UdsClient(
    private val session: Session,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxRetries: Int = MAX_RETRIES,
) {

    /**
     * Sends [request]; when the only answer is "response pending" (NRC 0x78) or "busy" (0x21) and no
     * positive reply arrived within the adapter timeout, waits and re-sends the same request.
     */
    private suspend fun send(target: EcuTarget, request: List<Int>): ObdResponse {
        var response = session.request(target, request, timeoutMs)
        var attempt = 0
        while (attempt < maxRetries && response.messages.isEmpty() && isTransientOnly(response)) {
            delay(retryDelayMs)
            response = session.request(target, request, timeoutMs)
            attempt++
        }
        return response
    }

    private fun isTransientOnly(response: ObdResponse): Boolean {
        val codes = response.negativeResponses.map { it.payload[2] }
        return codes.isNotEmpty() && codes.all { it == Nrc.RESPONSE_PENDING || it == Nrc.BUSY_REPEAT_REQUEST }
    }

    suspend fun readDid(target: EcuTarget, did: Int): DidResult {
        val response = try {
            send(target, listOf(0x22, (did shr 8) and 0xFF, did and 0xFF))
        } catch (e: AdapterException) {
            return DidResult.Failed(e.message ?: "adapter error")
        }
        response.messages.firstOrNull()?.let { return DidResult.Value(it.payload.drop(3)) }
        finalNegative(response)?.let { return DidResult.Negative(it) }
        return DidResult.NoResponse
    }

    /** `19 02 FF`: every DTC whose status matches any bit. */
    suspend fun readDtcs(target: EcuTarget, statusMask: Int = 0xFF): DtcReadResult {
        val response = try {
            send(target, listOf(0x19, 0x02, statusMask))
        } catch (e: AdapterException) {
            return DtcReadResult.Failed(e.message ?: "adapter error")
        }
        response.messages.firstOrNull()?.let { message ->
            return DtcDecoder.parseReportByStatusMask(message.payload) ?: DtcReadResult.Failed("Malformed 59 02 reply")
        }
        finalNegative(response)?.let { return DtcReadResult.Negative(it) }
        return DtcReadResult.NoResponse
    }

    /** OBD service 03 (stored DTCs) for modules that only speak OBD. */
    suspend fun readObdStoredDtcs(target: EcuTarget): DtcReadResult {
        val response = try {
            send(target, listOf(0x03))
        } catch (e: AdapterException) {
            return DtcReadResult.Failed(e.message ?: "adapter error")
        }
        response.messages.firstOrNull()?.let { message ->
            return DtcDecoder.parseObdStored(message.payload) ?: DtcReadResult.Failed("Malformed 43 reply")
        }
        finalNegative(response)?.let { return DtcReadResult.Negative(it) }
        return DtcReadResult.NoResponse
    }

    /**
     * OBD service 09 text items (09 0A ECU name, 09 04 calibration ID): `49 <pid> <count>` then ASCII.
     * Returns null when not supported.
     */
    suspend fun readObdText(target: EcuTarget, pid: Int): String? {
        val response = try {
            send(target, listOf(0x09, pid))
        } catch (e: AdapterException) {
            return null
        }
        val payload = response.messages.firstOrNull()?.payload ?: return null
        return DidText.decode(payload.drop(3).map { if (it == 0x00) 0x20 else it })
    }

    /**
     * `14 <group>` ClearDiagnosticInformation for one module, with a [ClearConfirmation.udsClear]. Not
     * retried automatically (a confirmation is single-use); a long timeout covers "response pending".
     */
    suspend fun clearDtcs(target: EcuTarget.Physical, confirmation: ClearConfirmation): UdsClearResult {
        val response = try {
            session.request(target, confirmation.request, CLEAR_TIMEOUT_MS, confirmation)
        } catch (e: AdapterException) {
            return UdsClearResult.Failed(e.message ?: "adapter error")
        }
        if (response.messages.isNotEmpty()) return UdsClearResult.Cleared
        val nrc = finalNegative(response) ?: return UdsClearResult.NoResponse
        return if (nrc == Nrc.RESPONSE_PENDING) UdsClearResult.Pending else UdsClearResult.Negative(nrc)
    }

    /** `19 06 <DTC> <record>`: DTC extended data records (occurrence counters, aging …). */
    suspend fun readExtendedData(target: EcuTarget, dtc: DtcRecord, record: Int = 0xFF): DtcRecordResult =
        readDtcRecord(target, 0x06, dtc, record)

    /** `19 04 <DTC> <record>`: DTC snapshot (freeze frame) records. */
    suspend fun readSnapshot(target: EcuTarget, dtc: DtcRecord, record: Int = 0xFF): DtcRecordResult =
        readDtcRecord(target, 0x04, dtc, record)

    private suspend fun readDtcRecord(target: EcuTarget, subFunction: Int, dtc: DtcRecord, record: Int): DtcRecordResult {
        require(dtc.raw.size >= 3) { "DTC needs its 3 raw bytes" }
        val response = try {
            send(target, listOf(0x19, subFunction) + dtc.raw.take(3) + record)
        } catch (e: AdapterException) {
            return DtcRecordResult.Failed(e.message ?: "adapter error")
        }
        response.messages.firstOrNull()?.let { message ->
            val p = message.payload
            // 59 <sub> <DTC 3 bytes> <status> <records…>
            if (p.size < 6 || p.subList(2, 5) != dtc.raw.take(3)) return DtcRecordResult.Failed("Reply is for a different DTC")
            return DtcRecordResult.Data(status = p[5], records = p.drop(6))
        }
        finalNegative(response)?.let { return DtcRecordResult.Negative(it) }
        return DtcRecordResult.NoResponse
    }

    /** The NRC that ended the request; a lone "response pending" (0x78) is reported as such. */
    private fun finalNegative(response: ObdResponse): Int? {
        val codes = response.negativeResponses.map { it.payload[2] }
        return codes.firstOrNull { it != Nrc.RESPONSE_PENDING } ?: codes.firstOrNull()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val RETRY_DELAY_MS = 300L
        const val MAX_RETRIES = 3
        const val CLEAR_TIMEOUT_MS = 10_000L
    }
}

sealed interface UdsClearResult {
    data object Cleared : UdsClearResult
    /** Only "response pending" came back: the clear may still complete; read the DTCs again to check. */
    data object Pending : UdsClearResult
    data class Negative(val nrc: Int) : UdsClearResult
    data object NoResponse : UdsClearResult
    data class Failed(val message: String) : UdsClearResult

    val display: String
        get() = when (this) {
            Cleared -> "Fault memory cleared"
            Pending -> "Module is still processing; read the fault codes again to check"
            is Negative -> "Refused: ${Nrc.describe(nrc)}" + if (nrc == 0x22) " (switch the engine off, ignition on)" else ""
            NoResponse -> "No response from the module"
            is Failed -> "Not sent: $message"
        }
}

/** Reply to 19 04 / 19 06 for one DTC. Records are shown raw: their layout is manufacturer-specific. */
sealed interface DtcRecordResult {
    data class Data(val status: Int, val records: List<Int>) : DtcRecordResult
    data class Negative(val nrc: Int) : DtcRecordResult
    data object NoResponse : DtcRecordResult
    data class Failed(val message: String) : DtcRecordResult

    val display: String
        get() = when (this) {
            is Data -> if (records.isEmpty()) "No records stored" else records.toHex()
            is Negative -> if (Nrc.meansNotSupported(nrc)) "Not supported" else "Not available: ${Nrc.describe(nrc)}"
            NoResponse -> "No response"
            is Failed -> "Not read: $message"
        }
}
