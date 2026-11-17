package com.bod3.app.obd.uds

import com.bod3.app.obd.elm.AdapterException
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

    /** The NRC that ended the request; a lone "response pending" (0x78) is reported as such. */
    private fun finalNegative(response: ObdResponse): Int? {
        val codes = response.negativeResponses.map { it.payload[2] }
        return codes.firstOrNull { it != Nrc.RESPONSE_PENDING } ?: codes.firstOrNull()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val RETRY_DELAY_MS = 300L
        const val MAX_RETRIES = 3
    }
}
