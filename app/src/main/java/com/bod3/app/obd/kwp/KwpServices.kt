package com.bod3.app.obd.kwp

import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdResponse
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.session.WriteGrant
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.UdsOutcome
import com.bod3.app.obd.uds.UdsWriteClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** KWP application services are never interpreted as UDS sub-functions. */
object KwpServices {
    fun readAllowed(request: List<Int>): Boolean = request.all { it in 0..255 } && when (request.firstOrNull()) {
        0x1A, 0x21 -> request.size == 2
        0x22 -> request.size >= 3 && request.size % 2 == 1
        // Toyota's support snapshot (FDATA SendCmdAndGetPid), not arbitrary A8 variants.
        0xA8 -> request == listOf(0xA8, 0x01)
        // Toyota native templates use these KWP reads, not UDS 18/19 substitutions.
        // 12 FF 01 00/FF is the source's four-byte status/group variant; 13 has one mask byte.
        0x12 -> request.size == 4 && request[1] == 0xFF && request[2] == 0x01 && request[3] in setOf(0x00, 0xFF)
        0x13 -> request.size == 2
        0x18 -> request.size == 4 // status mask, two-byte DTC group
        0x19 -> request.size in 1..4 // documented KWP read-DTC variant, NOT UDS report sub-functions
        0x3E -> request == listOf(0x3E) || request == listOf(0x3E, 0x00)
        0x81 -> request == listOf(0x81)
        else -> false
    }

    fun matches(request: List<Int>, payload: List<Int>): Boolean {
        if (request.isEmpty() || payload.firstOrNull() != request[0] + 0x40) return false
        val echo = when (request[0]) { 0x01, 0x02, 0x09, 0x1A, 0x21, 0x10, 0x27, 0x30, 0x31, 0x32, 0x33, 0x3B, 0xA8 -> 1; 0x22, 0x2E, 0x2F -> 2; else -> 0 }
        return request.size > echo && payload.size > echo && payload.drop(1).take(echo) == request.drop(1).take(echo)
    }
}

/** No write retries, guessed security algorithms, automatic clearing, or automatic vehicle selection. */
class KwpClient(private val session: Session, val target: EcuTarget.Physical) {
    init { require(target.link?.dialect == DiagnosticDialect.KWP2000) { "An explicit KWP link is required" } }

    private suspend fun read(bytes: List<Int>): ObdResponse = session.request(target, bytes,
        target.link?.kLine?.p2StarMs?.toLong() ?: Session.DEFAULT_REQUEST_TIMEOUT_MS)
    suspend fun startCommunication() = read(listOf(0x81))
    suspend fun readIdentification(localId: Int): ObdResponse { require(localId in 0..255); return read(listOf(0x1A, localId)) }
    suspend fun readLocal(localId: Int): ObdResponse { require(localId in 0..255); return read(listOf(0x21, localId)) }
    suspend fun readIdentifier(did: Int): ObdResponse { require(did in 0..65535); return read(listOf(0x22, did shr 8, did and 255)) }
    suspend fun readDtcs(status: Int, group: Int = 0xFFFF): ObdResponse {
        require(status in 0..255 && group in 0..65535)
        return read(listOf(0x18, status, group shr 8, group and 255))
    }
    suspend fun testerPresent() = read(requireNotNull(target.link).testerPresent)
    suspend fun clear(confirmation: ClearConfirmation): ObdResponse = session.request(target, confirmation.request,
        target.link?.kLine?.p2StarMs?.toLong() ?: 10000, confirmation = confirmation)

    private fun writer(grant: WriteGrant): UdsWriteClient {
        require(grant.target == target) { "Grant belongs to another ECU" }
        return UdsWriteClient(session, grant, target.link?.kLine?.p2StarMs?.toLong() ?: 5000)
    }
    suspend fun enterSession(type: Int, grant: WriteGrant) = writer(grant).enterSession(type)
    suspend fun unlock(spec: SecurityAccessSpec, grant: WriteGrant): UdsOutcome = writer(grant).unlock(spec)
    suspend fun inputOutput(localId: Int, control: Int, data: List<Int>, grant: WriteGrant): UdsOutcome {
        require(localId in 0..255 && control in WritePolicy.ioControlParameters)
        return writer(grant).send(listOf(0x30, localId, control) + data)
    }
    suspend fun routine(service: Int, localId: Int, data: List<Int>, grant: WriteGrant): UdsOutcome {
        require(service in setOf(0x31, 0x32, 0x33) && localId in 0..255)
        return writer(grant).send(listOf(service, localId) + data)
    }
    suspend fun writeLocal(localId: Int, data: List<Int>, grant: WriteGrant): UdsOutcome {
        require(localId in 0..255 && data.isNotEmpty())
        return writer(grant).send(listOf(0x3B, localId) + data)
    }

    /** Keep-alive stops on cancellation and never changes another caller's active ECU/transport. */
    suspend fun <T> keepAliveWhile(block: suspend () -> T): T = coroutineScope {
        val interval = target.link?.kLine?.keepAliveMs ?: 2000L
        val keepAlive = launch {
            while (isActive) { delay(interval); session.keepAliveCurrent(target) }
        }
        try { block() } finally { keepAlive.cancel() }
    }
}
