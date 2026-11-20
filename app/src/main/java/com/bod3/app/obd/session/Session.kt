package com.bod3.app.obd.session

import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.kwp.KLineAdapter
import com.bod3.app.obd.kwp.KLineFrames
import com.bod3.app.obd.kwp.KwpServices
import com.bod3.app.recipe.Hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.io.IOException
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
 * The default policy is read-only: only diagnostic services that read data may be sent. Nothing that
 * clears, writes, actuates, resets, unlocks or changes the diagnostic session. The only exceptions are a
 * confirmed fault-memory clear ([ClearConfirmation]) and the whitelisted services of [WritePolicy],
 * which need a [WriteGrant] issued in Expert mode for one operation on one module.
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

    /** OBD `04` and UDS `14 <3-byte group>`: allowed only with a [ClearConfirmation], never through [check]. */
    fun isClearRequest(request: List<Int>): Boolean =
        (request.size == 1 && request[0] == 0x04) || (request.size in 3..4 && request[0] == 0x14)

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
    /** Positive responses (service + 0x40); some exact KWP reads return several messages. */
    val messages: List<EcuMessage>,
    /** Negative responses (`7F <service> <NRC>`), if any ECU rejected the request. */
    val negativeResponses: List<EcuMessage>,
    /**
     * Header of every CAN frame that came back, even ones that could not be reassembled into a complete
     * message (e.g. a first frame whose flow control went unanswered). Used to detect modules.
     */
    val responders: List<String> = emptyList(),
    /** No lost/invalid transport frames, unexpected ECU, service or identifier in this exchange. */
    val complete: Boolean = true,
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
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val logger: (String) -> Unit = {},
    /** Recovered systems additionally require a same-ECU read-only bring-up, checked inside the lease. */
    private val ecuOperationGuard: suspend (Session, EcuTarget.Physical) -> Unit = { _, _ -> },
    /** Source 105F reads have a separate guard; success here MUST NOT establish write authority. */
    private val diagnosticSessionGuard: suspend (Session, EcuTarget.Physical) -> Unit = ecuOperationGuard,
) {
    private val lock = Mutex()
    private val operationLock = Mutex()
    private class Lease(val session: Session, val target: EcuTarget.Physical?) : AbstractCoroutineContextElement(Key) {
        var guardedTarget: EcuTarget.Physical? = null
        var sourceSessionTarget: EcuTarget.Physical? = null
        companion object Key : CoroutineContext.Key<Lease>
    }
    private class BringUpReadOnly(val session: Session) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<BringUpReadOnly>
    }
    private class ReadOnlyAccess(val session: Session) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ReadOnlyAccess>
    }

    private class SourceReadSession(val session: Session) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SourceReadSession>
    }

    /** An exact-ECU read lease cannot consume a grant or escalate into an active operation. */
    suspend fun <T> withReadOnlyEcuOperation(target: EcuTarget.Physical, block: suspend () -> T): T = withDiagnosticOperation {
        val lease = currentCoroutineContext()[Lease]
        require(lease?.target == null || lease.target == target) { "A read operation cannot change its ECU" }
        withContext(Lease(this, target) + ReadOnlyAccess(this)) { block() }
    }

    /** One exclusive, non-escalatable read-only operation, including adapter configuration/init. */
    suspend fun <T> withReadOnlyBringUp(block: suspend () -> T): T = withDiagnosticOperation {
        withContext(BringUpReadOnly(this)) { block() }
    }

    suspend fun prepareBringUp(target: EcuTarget.Physical) {
        require(currentCoroutineContext()[BringUpReadOnly]?.session === this) { "Bring-up preparation needs a read-only lease" }
        require(!options.extendedAddressing && !options.customFlowControl && options.flowControlData == "300000" && options.canBaudKbps == null) {
            "Bring-up needs a standard addressing profile; custom addressing/flow-control/baud settings would change the candidate"
        }
        val link = requireNotNull(target.link)
        invalidateRouting()
        lock.withLock {
            if (link.kLine != null) {
                // Initialization has its own bounded budget (not the 1-second diagnostic-read
                // budget). In particular, a valid 5-baud handshake can take several seconds.
                KLineAdapter.initialize(executor, target, logger, strictBringUp = true)
                protocol = link.protocol
                appliedTarget = target
                lastExchangeAtMs = clock()
            } else {
                sendAll(listOf("ATPC") + link.protocol.selectCommands(link.baudKbps) + listOf("ATH1", "ATCAF1", "ATCFC1"))
                protocol = link.protocol
            }
        }
    }

    /** Restore the candidate's documented normal timeout before activating it or issuing any grant. */
    suspend fun restoreBringUpTiming(target: EcuTarget.Physical) {
        require(currentCoroutineContext()[BringUpReadOnly]?.session === this)
        val k = target.link?.kLine ?: return
        lock.withLock {
            require(appliedTarget == target) { "Bring-up target is no longer current" }
            sendAll(listOf("ATST %02X".format(((k.p2StarMs + 3) / 4).coerceIn(1, 255)), "STPTO ${k.p2StarMs}"))
        }
    }

    /** Serializes safety checks as well as the write. No stale checks while waiting behind a poll/run. */
    suspend fun <T> withDiagnosticOperation(block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease]?.session === this) return block()
        return executor.withExclusive { operationLock.withLock { withContext(Lease(this, null)) { block() } } }
    }

    /** Holds routing across session/security/write/verify/cleanup, excluding background live polls. */
    suspend fun <T> withEcuOperation(target: EcuTarget.Physical, block: suspend () -> T): T = withDiagnosticOperation {
        val lease = currentCoroutineContext()[Lease]
        require(lease?.target == null || lease.target == target) { "An operation cannot change its ECU" }
        if (lease?.target == target) block() else withContext(Lease(this, target)) {
            if (currentCoroutineContext()[BringUpReadOnly]?.session !== this@Session) ensureOperationGuard(target)
            block()
        }
    }

    /**
     * Narrow source DTC lease: exact reads plus one separately confirmed 105F.
     * No clear/WriteGrant/session-security/coding/actuator escalation, even if
     * this source has only a nonunique calibration ID. The active-operation
     * guard/ECU_SERIAL requirement remains a different authority entirely.
     */
    suspend fun <T> withSourceReadSessionOperation(target: EcuTarget.Physical, block: suspend () -> T): T = withDiagnosticOperation {
        require(currentCoroutineContext()[BringUpReadOnly]?.session !== this && currentCoroutineContext()[ReadOnlyAccess]?.session !== this) {
            "Read-only bring-up/access cannot enter a source diagnostic session"
        }
        val lease = currentCoroutineContext()[Lease]
        require(lease?.target == null || lease.target == target) { "Source diagnostic operation cannot change its ECU" }
        if (currentCoroutineContext()[SourceReadSession]?.session === this) {
            ensureSourceSessionGuard(target); block()
        } else withContext(Lease(this, target) + SourceReadSession(this)) {
            ensureSourceSessionGuard(target); block()
        }
    }

    private suspend fun ensureSourceSessionGuard(target: EcuTarget.Physical) {
        val lease = currentCoroutineContext()[Lease] ?: error("Missing diagnostic operation lease")
        require(currentCoroutineContext()[BringUpReadOnly]?.session !== this && currentCoroutineContext()[ReadOnlyAccess]?.session !== this) {
            "A read-only lease cannot change the diagnostic session"
        }
        if (lease.sourceSessionTarget == target) return
        diagnosticSessionGuard(this, target)
        lease.sourceSessionTarget = target
    }

    private suspend fun ensureOperationGuard(target: EcuTarget.Physical) {
        val lease = currentCoroutineContext()[Lease] ?: error("Missing diagnostic operation lease")
        if (lease.guardedTarget == target) return
        require(currentCoroutineContext()[ReadOnlyAccess]?.session !== this && currentCoroutineContext()[SourceReadSession]?.session !== this) {
            "A read/source-session lease cannot escalate into an active operation"
        }
        ecuOperationGuard(this, target)
        lease.guardedTarget = target
    }

    private suspend fun <T> routedOperation(target: EcuTarget, block: suspend () -> T): T {
        val lease = currentCoroutineContext()[Lease]
        if (lease?.session === this) {
            require(lease.target == null || target == lease.target) { "Operation is bound to a different ECU" }
            return block()
        }
        return withDiagnosticOperation(block)
    }
    private var baseProtocol = initialProtocol
    private var lastExchangeAtMs: Long? = null

    private fun isRead(target: EcuTarget, request: List<Int>): Boolean {
        val link = (target as? EcuTarget.Physical)?.link
        return if (link?.dialect == DiagnosticDialect.KWP2000)
            KwpServices.readAllowed(request) ||
                (request == link.testerPresent && KwpServices.isSourceKeepAlive(request)) ||
                (request.firstOrNull() in ReadOnlyPolicy.obdServices && ReadOnlyPolicy.isAllowed(request))
        else ReadOnlyPolicy.isAllowed(request)
    }

    var protocol: ObdProtocol = initialProtocol
        private set

    /** Bit rate used when (re-)selecting protocols with a configurable rate. */
    val protocolBaudKbps: Double? get() = options.canBaudKbps

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

    private var linkEpoch = 0L

    /** UI intent generation only; not proof, a permission, or a serializable connection ID. */
    internal fun diagnosticPromptEpoch(): Long = linkEpoch

    /** Prerequisite snapshots must not survive routing resets, ECU switches or expired K-line init. */
    internal fun currentLinkEpoch(target: EcuTarget.Physical): Long? {
        if (appliedTarget != target) return null
        val k = target.link?.kLine
        if (k != null && lastExchangeAtMs?.let { clock() - it >= k.p3MaxMs } == true) return null
        return linkEpoch
    }

    suspend fun request(
        target: EcuTarget,
        request: List<Int>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        confirmation: ClearConfirmation? = null,
        grant: WriteGrant? = null,
        diagnosticSession: DiagnosticSessionConfirmation? = null,
    ): ObdResponse {
        require(request.isNotEmpty() && request.all { it in 0..0xFF }) { "Request must contain bytes in 0..255" }
        require(timeoutMs > 0) { "Request timeout must be positive" }
        require(listOfNotNull(confirmation, grant, diagnosticSession).size <= 1) { "An exchange cannot combine operation permissions" }
        return routedOperation(target) {
            if (currentCoroutineContext()[BringUpReadOnly]?.session === this || currentCoroutineContext()[ReadOnlyAccess]?.session === this) {
                require(confirmation == null && grant == null && diagnosticSession == null && isRead(target, request)) { "A read-only lease cannot consume a write/clear grant" }
            }
            if (currentCoroutineContext()[SourceReadSession]?.session === this) {
                require(confirmation == null && grant == null && (isRead(target, request) || diagnosticSession != null)) {
                    "A source read-session lease cannot clear, code, actuate or consume a write grant"
                }
            }
            if (!isRead(target, request) && target is EcuTarget.Physical) {
                if (diagnosticSession != null) ensureSourceSessionGuard(target) else ensureOperationGuard(target)
            }
            when {
                diagnosticSession != null -> Unit // Epoch and single use checked INSIDE the exchange lock.
                confirmation != null && ReadOnlyPolicy.isClearRequest(request) -> confirmation.consume(request, target)
                grant != null && !isRead(target, request) -> grant.check(request, target)
                isRead(target, request) -> Unit
                else -> throw ReadOnlyViolationException(request.first())
            }
            lock.withLock {
                diagnosticSession?.consume(this, request, target)
                exchange(target, request, timeoutMs, (grant != null || diagnosticSession != null) && !isRead(target, request))
            }
        }
    }

    /** Used by owned session keep-alives: never switches ECU, initializes a bus or resends a write. */
    suspend fun keepAliveCurrent(target: EcuTarget.Physical, request: List<Int> = target.link?.testerPresent ?: listOf(0x3E, 0x00)): Boolean {
        require(request == (target.link?.testerPresent ?: listOf(0x3E, 0x00))) { "Keepalive differs from this ECU's definition" }
        require(if (target.link?.dialect == DiagnosticDialect.KWP2000) KwpServices.isSourceKeepAlive(request)
            else request == listOf(0x3E, 0x00)) { "Unsupported keepalive for the selected dialect" }
        return routedOperation(target) { lock.withLock {
            val k = target.link?.kLine
            if (appliedTarget != target || (k != null && lastExchangeAtMs?.let { clock() - it >= k.p3MaxMs } == true)) return@withLock false
            val response = exchange(target, request, k?.p2StarMs?.toLong() ?: DEFAULT_REQUEST_TIMEOUT_MS, false)
            response.complete && response.negativeResponses.isEmpty() && response.messages.size == 1
        } }
    }

    private suspend fun exchange(target: EcuTarget, request: List<Int>, timeoutMs: Long, writing: Boolean): ObdResponse = try {
        exchangeCurrent(target, request, timeoutMs, writing)
    } catch (e: Exception) {
        // A disconnect, reset, partial routing change or cancelled exchange makes all ECU-local
        // prerequisite snapshots stale. Never reconnect/replay a write behind a saved grant.
        invalidateRouting()
        throw e
    }

    private suspend fun exchangeCurrent(target: EcuTarget, request: List<Int>, timeoutMs: Long, writing: Boolean): ObdResponse {
        val physical = target as? EcuTarget.Physical
        val k = physical?.link?.kLine
        val expired = k != null && lastExchangeAtMs?.let { clock() - it >= k.p3MaxMs } == true
        if (writing && k != null && (expired || appliedTarget != target)) {
            throw IOException("K-line session is no longer current; re-read and confirm the operation again")
        }
        if (k != null && (appliedTarget != target || expired)) {
            invalidateRouting()
            KLineAdapter.initialize(executor, physical, logger,
                strictBringUp = currentCoroutineContext()[BringUpReadOnly]?.session === this)
            protocol = physical.link!!.protocol
            appliedTarget = target
            lastExchangeAtMs = clock()
        } else if (k == null) {
            val wantedProtocol = physical?.link?.protocol ?: baseProtocol
            if (protocol != wantedProtocol || (appliedTarget as? EcuTarget.Physical)?.link?.kLine != null) {
                for (command in wantedProtocol.selectCommands(physical?.link?.baudKbps ?: protocolBaudKbps)) sendAll(listOf(command))
                protocol = wantedProtocol
                invalidateRouting()
                if (!protocol.isCan) sendAll(listOf("ATSH " + if (protocol == ObdProtocol.ISO_9141_2) "686AF1" else "C033F1"))
            }
            if (physical != null && !protocol.isCan) throw UnsupportedOperationException("Enhanced K-line needs a complete per-ECU link")
            applyTarget(target)
        }
        if (k != null) lastExchangeAtMs?.let { last ->
            val remaining = k.p3MinMs - (clock() - last)
            if (remaining > 0) wait(remaining)
        }
        val command = request.joinToString("") { "%02X".format(it) }
        // Explicit startCommunication/session/write/clear operations can change applicability
        // without changing the ECU header. They also expire a live run's prerequisite snapshot.
        if (writing || ReadOnlyPolicy.isClearRequest(request) ||
            (physical?.link?.dialect == DiagnosticDialect.KWP2000 && request == listOf(0x81))) linkEpoch++
        // Adapter supplies the length and checksum; the response is independently verified below.
        val elm = executor.execute(command, timeoutMs)
        lastExchangeAtMs = clock()
        return interpret(target, request, elm)
    }

    suspend fun request(target: EcuTarget, vararg bytes: Int): ObdResponse = request(target, bytes.toList())

    /**
     * Switches the adapter to another protocol (`ATSP<n>`), e.g. from 11-bit to 29-bit CAN on the same
     * bus for a module scan. All routing state is treated as unknown afterwards.
     */
    suspend fun switchProtocol(newProtocol: ObdProtocol) = routedOperation(EcuTarget.Broadcast) { lock.withLock {
        baseProtocol = newProtocol
        if (newProtocol == protocol && (appliedTarget as? EcuTarget.Physical)?.link?.kLine == null) return@withLock
        for (command in newProtocol.selectCommands(protocolBaudKbps)) {
            val response = executor.execute(command, HEADER_COMMAND_TIMEOUT_MS)
            if (!response.isOk) throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
        }
        protocol = newProtocol
        invalidateRouting()
    } }

    /** Forgets the routing state, forcing the next request to re-send every routing command. */
    fun invalidateRouting() {
        linkEpoch++
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
        linkEpoch++
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
            val timeout = if (currentCoroutineContext()[BringUpReadOnly]?.session === this) 1000L else HEADER_COMMAND_TIMEOUT_MS
            val response = executor.execute(command, timeout)
            if (!response.isOk) throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
        }
    }

    private fun interpret(target: EcuTarget, request: List<Int>, elm: ElmResponse): ObdResponse {
        if (elm.error != null && elm.error != ElmError.NO_DATA) throw ObdRequestException(elm.error, elm)
        if (elm.error != null) return ObdResponse(request, elm, emptyList(), emptyList())

        val physical = target as? EcuTarget.Physical
        val link = physical?.link
        val decoded = if (link?.kLine != null) {
            val expected = requireNotNull(Hex.parse(physical.responseHeader.orEmpty()))
            val messages = elm.lines.mapNotNull { KLineFrames.decode(it, expected, protocol == ObdProtocol.ISO_9141_2) }
            com.bod3.app.obd.protocol.DecodedMessages(messages, messages.size == elm.lines.size)
        } else ResponseDecoder.inspect(elm.lines, protocol, options.extendedAddressing)
        val all = decoded.messages
        val responders = if (link?.kLine != null) all.mapNotNull { it.header }.distinct() else FrameParser.parse(elm.lines, protocol.headerFormat).map { it.header }.distinct()
        val service = request[0]
        val addressed = all.filter { m -> link?.kLine != null || physical?.responseHeader == null || physical.responseMask != null || m.header.equals(physical.responseHeader, true) }
        val positive = addressed.filter { message ->
            if (link?.dialect == DiagnosticDialect.KWP2000) KwpServices.matches(request, message.payload) else matchesRequest(request, message.payload)
        }
        val negative = addressed.filter { message ->
            message.payload.size >= 3 && message.payload[0] == 0x7F && message.payload[1] == service
        }
        return ObdResponse(request, elm, positive, negative, responders,
            complete = decoded.complete && addressed.size == all.size && positive.size + negative.size == addressed.size)
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
            // 22, 2E and 2F echo the (first) data identifier.
            service in DID_ECHO_SERVICES && request.size >= 3 ->
                payload.size >= 3 && payload[1] == request[1] && payload[2] == request[2]
            // 31 echoes the sub-function and the routine identifier.
            service == WritePolicy.ROUTINE_CONTROL && request.size >= 4 ->
                payload.size >= 4 && payload[1] == (request[1] and 0x7F) && payload[2] == request[2] && payload[3] == request[3]
            else -> true
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5_000L
        const val HEADER_COMMAND_TIMEOUT_MS = 2_000L
        private val PID_ECHO_SERVICES = setOf(0x01, 0x02, 0x09)
        private val SUB_FUNCTION_SERVICES = setOf(
            ReadOnlyPolicy.READ_DTC_INFORMATION, ReadOnlyPolicy.TESTER_PRESENT, WritePolicy.SESSION_CONTROL, WritePolicy.SECURITY_ACCESS,
        )
        private val DID_ECHO_SERVICES = setOf(ReadOnlyPolicy.READ_DATA_BY_IDENTIFIER, WritePolicy.WRITE_DATA_BY_IDENTIFIER, WritePolicy.IO_CONTROL)
    }
}
