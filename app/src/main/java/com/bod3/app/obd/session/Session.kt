package com.bod3.app.obd.session

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.protocol.EcuMessage
import com.bod3.app.obd.protocol.FrameParser
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.protocol.ResponseDecoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app is read-only: only diagnostic services that read data may be sent. Nothing that clears,
 * writes, actuates, resets, unlocks or changes the diagnostic session.
 *
 * OBD services are allowed as a whole. UDS services are allowed only with read-only sub-functions.
 */
object ReadOnlyPolicy {
    /** 01 live data, 02 freeze frame, 03 stored DTCs, 06 monitor results, 07 pending DTCs, 09 vehicle info, 0A permanent DTCs. */
    val obdServices: Set<Int> = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A)

    /** UDS 0x22 ReadDataByIdentifier: any identifier. */
    const val READ_DATA_BY_IDENTIFIER = 0x22

    /** UDS 0x19 ReadDTCInformation: only "report" sub-functions, which never change ECU state. */
    const val READ_DTC_INFORMATION = 0x19
    val readDtcSubFunctions: Set<Int> = setOf(0x01, 0x02, 0x03, 0x04, 0x06, 0x0A)

    /** UDS 0x3E TesterPresent: keeps the current (default) session alive, nothing else. */
    const val TESTER_PRESENT = 0x3E
    val testerPresentSubFunctions: Set<Int> = setOf(0x00, 0x80)

    val allowedServices: Set<Int> = obdServices + setOf(READ_DATA_BY_IDENTIFIER, READ_DTC_INFORMATION, TESTER_PRESENT)

    fun check(request: List<Int>) {
        require(request.isNotEmpty()) { "Empty request" }
        val service = request[0]
        when (service) {
            in obdServices -> Unit
            READ_DATA_BY_IDENTIFIER ->
                require(request.size >= 3 && (request.size - 1) % 2 == 0) { "0x22 needs one or more 2-byte identifiers" }
            READ_DTC_INFORMATION -> {
                val subFunction = request.getOrNull(1)
                if (subFunction == null || subFunction !in readDtcSubFunctions) {
                    throw ReadOnlyViolationException(service, subFunction)
                }
            }
            TESTER_PRESENT -> {
                val subFunction = request.getOrNull(1)
                if (subFunction == null || subFunction !in testerPresentSubFunctions) {
                    throw ReadOnlyViolationException(service, subFunction)
                }
            }
            else -> throw ReadOnlyViolationException(service)
        }
    }

    fun isAllowed(request: List<Int>): Boolean = try {
        check(request)
        true
    } catch (e: IllegalArgumentException) {
        false
    }
}

class ReadOnlyViolationException(val service: Int, val subFunction: Int? = null) :
    IllegalArgumentException(
        if (subFunction == null) {
            "Service 0x%02X is not allowed: this app is read-only".format(service)
        } else {
            "Service 0x%02X sub-function 0x%02X is not allowed: this app is read-only".format(service, subFunction)
        },
    )

class ObdRequestException(val error: ElmError, val response: ElmResponse) :
    AdapterException(error.description)

/** Result of one OBD/UDS request. */
data class ObdResponse(
    val request: List<Int>,
    val elm: ElmResponse,
    /** Positive responses (service + 0x40), one per answering ECU. */
    val messages: List<EcuMessage>,
    /** Negative responses (`7F <service> <NRC>`), if any ECU rejected the request. */
    val negativeResponses: List<EcuMessage>,
    /**
     * Header of every CAN frame that came back, even ones that could not be reassembled into a complete
     * message (e.g. a first frame whose flow control went unanswered). Used to detect modules.
     */
    val responders: List<String> = emptyList(),
) {
    val error: ElmError? get() = elm.error

    /** True when no ECU answered (`NO DATA`) or nothing usable came back. */
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * The diagnostic session: sends read-only requests over the current [protocol] and lets each request
 * target a specific ECU by CAN header.
 *
 * Routing commands (header, receive filter, flow control) are only sent for the groups that differ
 * from what is currently configured, and the routing change plus the request run atomically, so
 * several callers can address different ECUs without stepping on each other.
 */
class Session(
    private val executor: CommandExecutor,
    initialProtocol: ObdProtocol,
    val options: SessionOptions = SessionOptions.DEFAULT,
) {
    private val lock = Mutex()

    var protocol: ObdProtocol = initialProtocol
        private set

    /**
     * Routing groups the adapter is currently configured with; null means unknown and will be re-sent.
     * After `ATZ` the adapter sends functional requests and auto-receives OBD replies, i.e. [EcuTarget.Broadcast].
     */
    private var appliedHeader: List<String>? = null
    private var appliedFilter: List<String>? = null
    private var appliedFlowControl: List<String>? = null
    private var appliedTarget: EcuTarget? = EcuTarget.Broadcast

    init {
        resetToDefaultRouting()
    }

    /** The target the adapter is currently routed to, or null when unknown (after a failed change). */
    val currentTarget: EcuTarget? get() = appliedTarget

    suspend fun request(
        target: EcuTarget,
        request: List<Int>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): ObdResponse {
        ReadOnlyPolicy.check(request)
        require(request.all { it in 0..0xFF }) { "Request bytes must be 0..255" }

        return lock.withLock {
            if (target is EcuTarget.Physical && !protocol.isCan) {
                throw UnsupportedOperationException("Physical ECU addressing needs a CAN protocol (vehicle uses ${protocol.displayName})")
            }
            applyTarget(target)
            val command = request.joinToString("") { "%02X".format(it) }
            val elm = executor.execute(command, timeoutMs)
            interpret(request, elm)
        }
    }

    suspend fun request(target: EcuTarget, vararg bytes: Int): ObdResponse = request(target, bytes.toList())

    /**
     * Switches the adapter to another protocol (`ATSP<n>`), e.g. from 11-bit to 29-bit CAN on the same
     * bus for a module scan. All routing state is treated as unknown afterwards.
     */
    suspend fun switchProtocol(newProtocol: ObdProtocol) = lock.withLock {
        if (newProtocol == protocol) return@withLock
        val response = executor.execute("ATSP${newProtocol.code}", HEADER_COMMAND_TIMEOUT_MS)
        if (!response.isOk) throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
        protocol = newProtocol
        appliedHeader = null
        appliedFilter = null
        appliedFlowControl = null
        appliedTarget = null
    }

    /** Forgets the routing state, forcing the next request to re-send every routing command. */
    fun invalidateRouting() {
        appliedHeader = null
        appliedFilter = null
        appliedFlowControl = null
        appliedTarget = null
    }

    private fun resetToDefaultRouting() {
        if (!protocol.isCan) return
        // Only the adapter's power-on defaults are known; a custom broadcast filter is still to be sent.
        val default = HeaderCommands.routingFor(EcuTarget.Broadcast, protocol.headerFormat)
        appliedHeader = default.header
        appliedFilter = default.filter
        appliedFlowControl = default.flowControl
        // With a custom broadcast filter the power-on state is not the broadcast routing the app wants.
        appliedTarget = if (HeaderCommands.routingFor(EcuTarget.Broadcast, protocol.headerFormat, options) == default) EcuTarget.Broadcast else null
    }

    private suspend fun applyTarget(target: EcuTarget) {
        if (!protocol.isCan) return
        if (target == appliedTarget) return
        val wanted = HeaderCommands.routingFor(target, protocol.headerFormat, options)
        appliedTarget = null
        try {
            if (appliedHeader != wanted.header) {
                appliedHeader = null
                sendAll(wanted.header)
                appliedHeader = wanted.header
            }
            if (appliedFilter != wanted.filter) {
                appliedFilter = null
                sendAll(wanted.filter)
                appliedFilter = wanted.filter
            }
            if (appliedFlowControl != wanted.flowControl) {
                appliedFlowControl = null
                sendAll(wanted.flowControl)
                appliedFlowControl = wanted.flowControl
            }
        } catch (e: Exception) {
            // The adapter state is now uncertain: re-send header and filter next time as well.
            appliedHeader = null
            appliedFilter = null
            throw e
        }
        appliedTarget = target
    }

    private suspend fun sendAll(commands: List<String>) {
        for (command in commands) {
            val response = executor.execute(command, HEADER_COMMAND_TIMEOUT_MS)
            if (!response.isOk) throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
        }
    }

    private fun interpret(request: List<Int>, elm: ElmResponse): ObdResponse {
        if (elm.error != null && elm.error != ElmError.NO_DATA) throw ObdRequestException(elm.error, elm)
        if (elm.error != null) return ObdResponse(request, elm, emptyList(), emptyList())

        val all = ResponseDecoder.decode(elm.lines, protocol, options.extendedAddressing)
        val responders = FrameParser.parse(elm.lines, protocol.headerFormat).map { it.header }.distinct()
        val service = request[0]
        val positive = all.filter { message -> matchesRequest(request, message.payload) }
        val negative = all.filter { message ->
            message.payload.size >= 3 && message.payload[0] == 0x7F && message.payload[1] == service
        }
        return ObdResponse(request, elm, positive, negative, responders)
    }

    private fun matchesRequest(request: List<Int>, payload: List<Int>): Boolean {
        val service = request[0]
        if (payload.isEmpty() || payload[0] != service + 0x40) return false
        return when {
            // Services 01, 02 and 09 echo the PID.
            service in PID_ECHO_SERVICES && request.size >= 2 -> payload.size >= 2 && payload[1] == request[1]
            // 19 and 3E echo the sub-function without the suppress-positive-response bit.
            service in SUB_FUNCTION_SERVICES && request.size >= 2 ->
                payload.size >= 2 && payload[1] == (request[1] and 0x7F)
            // 22 echoes the first data identifier.
            service == ReadOnlyPolicy.READ_DATA_BY_IDENTIFIER && request.size >= 3 ->
                payload.size >= 3 && payload[1] == request[1] && payload[2] == request[2]
            else -> true
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5_000L
        const val HEADER_COMMAND_TIMEOUT_MS = 2_000L
        private val PID_ECHO_SERVICES = setOf(0x01, 0x02, 0x09)
        private val SUB_FUNCTION_SERVICES = setOf(ReadOnlyPolicy.READ_DTC_INFORMATION, ReadOnlyPolicy.TESTER_PRESENT)
    }
}
