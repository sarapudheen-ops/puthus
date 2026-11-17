package com.bod3.app.obd.session

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.protocol.ResponseDecoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only diagnostic services that read data may be sent. Nothing that clears, writes or actuates. */
object ReadOnlyPolicy {
    /** 01 live data, 02 freeze frame, 03 stored DTCs, 06 monitor results, 07 pending DTCs, 09 vehicle info, 0A permanent DTCs. */
    val allowedServices: Set<Int> = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A)

    fun check(request: List<Int>) {
        require(request.isNotEmpty()) { "Empty request" }
        val service = request[0]
        if (service !in allowedServices) throw ReadOnlyViolationException(service)
    }
}

class ReadOnlyViolationException(val service: Int) :
    IllegalArgumentException("Service 0x%02X is not allowed: this app is read-only".format(service))

class ObdRequestException(val error: ElmError, val response: ElmResponse) :
    AdapterException(error.description)

/** Result of one OBD request. */
data class ObdResponse(
    val request: List<Int>,
    val elm: ElmResponse,
    /** Positive responses (service + 0x40), one per answering ECU. */
    val messages: List<EcuMessage>,
    /** Negative responses (`7F`), if any ECU rejected the request. */
    val negativeResponses: List<EcuMessage>,
) {
    val error: ElmError? get() = elm.error

    /** True when no ECU answered (`NO DATA`) or nothing usable came back. */
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * The OBD session: sends read-only diagnostic requests over a detected [protocol] and lets each request
 * target a specific ECU by CAN header.
 *
 * Header changes (`ATSH` / `ATCP` / `ATCRA` / `ATAR`) are only sent when the target differs from the one
 * currently configured, and the header switch plus the request run atomically, so several callers can
 * address different ECUs without stepping on each other.
 */
class Session(
    private val executor: CommandExecutor,
    val protocol: ObdProtocol,
) {
    private val lock = Mutex()

    /**
     * Target the adapter is currently configured for, or null when unknown (after a failed header change).
     * After `ATZ` the adapter sends functional requests and auto-filters replies, i.e. [EcuTarget.Broadcast].
     */
    private var appliedTarget: EcuTarget? = EcuTarget.Broadcast

    val currentTarget: EcuTarget? get() = appliedTarget

    suspend fun request(
        target: EcuTarget,
        request: List<Int>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): ObdResponse {
        ReadOnlyPolicy.check(request)
        require(request.all { it in 0..0xFF }) { "Request bytes must be 0..255" }
        if (target is EcuTarget.Physical && !protocol.isCan) {
            throw UnsupportedOperationException("Physical ECU addressing needs a CAN protocol (vehicle uses ${protocol.displayName})")
        }

        return lock.withLock {
            applyTarget(target)
            val command = request.joinToString("") { "%02X".format(it) }
            val elm = executor.execute(command, timeoutMs)
            interpret(request, elm)
        }
    }

    suspend fun request(target: EcuTarget, vararg bytes: Int): ObdResponse = request(target, bytes.toList())

    private suspend fun applyTarget(target: EcuTarget) {
        if (target == appliedTarget || !protocol.isCan) return
        for (command in HeaderCommands.forTarget(target, protocol.headerFormat)) {
            val response = executor.execute(command, HEADER_COMMAND_TIMEOUT_MS)
            if (!response.isOk) {
                // The adapter's header state is now unknown; force a full re-send next time.
                appliedTarget = null
                throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
            }
        }
        appliedTarget = target
    }

    private fun interpret(request: List<Int>, elm: ElmResponse): ObdResponse {
        if (elm.error != null && elm.error != ElmError.NO_DATA) throw ObdRequestException(elm.error, elm)
        val all = if (elm.error == null) ResponseDecoder.decode(elm.lines, protocol) else emptyList()
        val service = request[0]
        val positive = all.filter { message -> matchesRequest(request, message.payload) }
        val negative = all.filter { message ->
            message.payload.size >= 3 && message.payload[0] == 0x7F && message.payload[1] == service
        }
        return ObdResponse(request, elm, positive, negative)
    }

    private fun matchesRequest(request: List<Int>, payload: List<Int>): Boolean {
        val service = request[0]
        if (payload.isEmpty() || payload[0] != service + 0x40) return false
        // Services 01, 02 and 09 echo the PID.
        if (service in PID_ECHO_SERVICES && request.size >= 2) {
            return payload.size >= 2 && payload[1] == request[1]
        }
        return true
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5_000L
        const val HEADER_COMMAND_TIMEOUT_MS = 2_000L
        private val PID_ECHO_SERVICES = setOf(0x01, 0x02, 0x09)
    }
}
