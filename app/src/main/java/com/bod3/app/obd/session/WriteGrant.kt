package com.bod3.app.obd.session

import java.util.concurrent.atomic.AtomicBoolean

/** Why non-read requests are being sent. Each purpose has its own small whitelist of UDS services. */
enum class WritePurpose(val label: String) {
    /** Bidirectional (actuator) tests: InputOutputControlByIdentifier `2F` and RoutineControl `31`. */
    ACTUATOR_TEST("Actuator test"),

    /** Coding engine: WriteDataByIdentifier `2E` (long coding, adaptation channels). */
    CODING("Coding"),
}

/**
 * Which non-read UDS requests may ever leave the app, and only together with a [WriteGrant].
 *
 * Common to every purpose:
 * - `10 01` / `10 03` DiagnosticSessionControl (default and extended session). Never `10 02`
 *   (programming) or manufacturer-specific sessions.
 * - `27 xx` SecurityAccess (request seed / send key), levels `01`-`7E`.
 * - `3E 00` / `3E 80` TesterPresent.
 *
 * Per purpose:
 * - [WritePurpose.ACTUATOR_TEST]: `2F <DID> <ioControlParameter 00-03> …` and `31 <01|02|03> <routine id> …`.
 * - [WritePurpose.CODING]: `2E <DID> <data…>`.
 *
 * Everything else (ECU reset, communication control, DTC setting, memory writes, downloads, flashing)
 * is never sent, whatever the purpose.
 */
object WritePolicy {
    const val SESSION_CONTROL = 0x10
    const val SECURITY_ACCESS = 0x27
    const val TESTER_PRESENT = 0x3E
    const val IO_CONTROL = 0x2F
    const val ROUTINE_CONTROL = 0x31
    const val WRITE_DATA_BY_IDENTIFIER = 0x2E

    const val DEFAULT_SESSION = 0x01
    const val EXTENDED_SESSION = 0x03
    val allowedSessions: Set<Int> = setOf(DEFAULT_SESSION, EXTENDED_SESSION)

    /** ioControlParameter: returnControlToECU, resetToDefault, freezeCurrentState, shortTermAdjustment. */
    val ioControlParameters: Set<Int> = setOf(0x00, 0x01, 0x02, 0x03)

    /** RoutineControl sub-functions: start, stop, request results. */
    val routineSubFunctions: Set<Int> = setOf(0x01, 0x02, 0x03)

    private val common = setOf(SESSION_CONTROL, SECURITY_ACCESS, TESTER_PRESENT)

    fun services(purpose: WritePurpose): Set<Int> = common + when (purpose) {
        WritePurpose.ACTUATOR_TEST -> setOf(IO_CONTROL, ROUTINE_CONTROL)
        WritePurpose.CODING -> setOf(WRITE_DATA_BY_IDENTIFIER)
    }

    /** Every service any grant can permit, used by simulators to tell writes from violations. */
    val allServices: Set<Int> = WritePurpose.entries.flatMap { services(it) }.toSet()

    /** Throws [WriteNotPermittedException] unless [request] is on the whitelist for [purpose]. */
    fun check(purpose: WritePurpose, request: List<Int>) {
        if (request.isEmpty()) throw WriteNotPermittedException("Empty request")
        val service = request[0]
        if (service !in services(purpose)) {
            throw WriteNotPermittedException("Service 0x%02X is not allowed for %s".format(service, purpose.label.lowercase()))
        }
        val sub = request.getOrNull(1)
        fun refuse(reason: String): Nothing = throw WriteNotPermittedException("0x%02X: %s".format(service, reason))
        when (service) {
            SESSION_CONTROL -> if (request.size != 2 || (sub!! and 0x7F) !in allowedSessions) {
                refuse("only the default (01) and extended (03) sessions may be opened")
            }
            SECURITY_ACCESS -> if (sub == null || (sub and 0x7F) !in 0x01..0x7E) refuse("security level must be 01-7E")
            TESTER_PRESENT -> if (request.size != 2 || sub !in setOf(0x00, 0x80)) refuse("only 3E 00 / 3E 80")
            IO_CONTROL -> if (request.size < 4 || request[3] !in ioControlParameters) {
                refuse("needs <DID> <ioControlParameter 00-03>")
            }
            ROUTINE_CONTROL -> if (request.size < 4 || (sub!! and 0x7F) !in routineSubFunctions) {
                refuse("needs <01|02|03> <routine id>")
            }
            WRITE_DATA_BY_IDENTIFIER -> if (request.size < 4) refuse("needs <DID> and at least one data byte")
        }
    }

    fun isAllowed(purpose: WritePurpose, request: List<Int>): Boolean =
        runCatching { check(purpose, request) }.isSuccess
}

/**
 * Permission to send whitelisted non-read requests ([WritePolicy]) to exactly one module, for one
 * operation (an actuator test run or a coding write). Only [WriteGate] creates grants, and only in
 * Expert mode. A grant expires after [maxDurationMs] and is revoked when the operation ends, so a
 * stale grant can never be reused.
 */
class WriteGrant internal constructor(
    val purpose: WritePurpose,
    val target: EcuTarget.Physical,
    private val issuedAtMs: Long,
    val maxDurationMs: Long,
    private val clock: () -> Long,
) {
    private val revoked = AtomicBoolean(false)

    val isActive: Boolean get() = !revoked.get() && clock() - issuedAtMs <= maxDurationMs

    fun revoke() = revoked.set(true)

    /** Throws when [request] to [target] is not covered by this grant. */
    fun check(request: List<Int>, target: EcuTarget) {
        if (revoked.get()) throw WriteNotPermittedException("The ${purpose.label.lowercase()} has ended; start it again")
        if (clock() - issuedAtMs > maxDurationMs) throw WriteNotPermittedException("The ${purpose.label.lowercase()} timed out; start it again")
        if (target != this.target) throw WriteNotPermittedException("The permission was given for a different module")
        WritePolicy.check(purpose, request)
    }
}

/** Issues [WriteGrant]s. Refuses unless Expert mode is on (which itself needs a risk acknowledgement). */
object WriteGate {
    const val DEFAULT_MAX_DURATION_MS = 10 * 60_000L

    fun issue(
        purpose: WritePurpose,
        target: EcuTarget.Physical,
        expertModeEnabled: Boolean,
        clock: () -> Long = System::currentTimeMillis,
        maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    ): WriteGrant {
        if (!expertModeEnabled) throw ExpertModeRequiredException(purpose)
        require(maxDurationMs in 1..60 * 60_000L) { "Grant duration must be 1 ms to 1 hour" }
        return WriteGrant(purpose, target, clock(), maxDurationMs, clock)
    }
}

class ExpertModeRequiredException(purpose: WritePurpose) :
    IllegalStateException("${purpose.label} needs Expert mode: turn it on in Settings and acknowledge the risks")

class WriteNotPermittedException(message: String) : IllegalArgumentException(message)
