package com.bod3.app.obd.uds

import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdRequestException
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.session.WriteGrant
import com.bod3.app.obd.session.WritePolicy
import java.io.IOException

/** Outcome of one non-read UDS request. A write request is never re-sent automatically. */
sealed interface UdsOutcome {
    data class Positive(val payload: List<Int>) : UdsOutcome
    data class Negative(val nrc: Int) : UdsOutcome
    /** Only "response pending" (7F xx 78) came back within the timeout: the result is unknown. */
    data object Pending : UdsOutcome
    data object NoResponse : UdsOutcome
    /** The adapter reported an error (e.g. `CAN ERROR`, `BUS ERROR`). */
    data class AdapterError(val message: String) : UdsOutcome
    /** The Bluetooth link or adapter stopped answering. */
    data class ConnectionLost(val message: String) : UdsOutcome

    val isPositive: Boolean get() = this is Positive

    val display: String
        get() = when (this) {
            is Positive -> "OK" + if (payload.size > 1) " (${payload.toHex()})" else ""
            is Negative -> "Refused: ${Nrc.describe(nrc)}" + Nrc.hint(nrc)
            Pending -> "The module is still busy (response pending); result unknown"
            NoResponse -> "No response from the module"
            is AdapterError -> "Adapter error: $message"
            is ConnectionLost -> "Connection lost: $message"
        }
}

/** How a security-access key is computed from the seed. Algorithms come from the recipe, never guessed. */
enum class SecurityAlgorithm(val label: String) {
    /** The key is a fixed byte string from the recipe (e.g. a module that accepts a login code). */
    STATIC_KEY("Static key"),
    /** key = seed + constant, as an unsigned big-endian number of the seed's length (wrapping). */
    SEED_PLUS_CONSTANT("Seed + constant"),
    /** key = seed XOR constant (constant repeated/right-aligned to the seed length). */
    SEED_XOR_CONSTANT("Seed XOR constant"),
}

/** Security access for one level: request seed with [level] (odd), send key with [level] + 1. */
data class SecurityAccessSpec(val level: Int, val algorithm: SecurityAlgorithm, val constant: List<Int>) {
    fun problems(): List<String> = buildList {
        if (level !in 0x01..0x7D || level % 2 == 0) add("security level must be odd, 01-7D (request seed)")
        if (constant.isEmpty()) add("security constant/key must not be empty")
        if (constant.size > 16) add("security constant/key must be at most 16 bytes")
        if (constant.any { it !in 0..0xFF }) add("security constant/key must be bytes")
    }

    fun keyFor(seed: List<Int>): List<Int> = when (algorithm) {
        SecurityAlgorithm.STATIC_KEY -> constant
        SecurityAlgorithm.SEED_PLUS_CONSTANT -> {
            val aligned = align(constant, seed.size)
            val out = IntArray(seed.size)
            var carry = 0
            for (i in seed.indices.reversed()) {
                val sum = seed[i] + aligned[i] + carry
                out[i] = sum and 0xFF
                carry = sum shr 8
            }
            out.toList()
        }
        SecurityAlgorithm.SEED_XOR_CONSTANT -> {
            val aligned = align(constant, seed.size)
            seed.indices.map { seed[it] xor aligned[it] }
        }
    }

    private fun align(bytes: List<Int>, size: Int): List<Int> =
        if (bytes.size >= size) bytes.takeLast(size) else List(size - bytes.size) { 0 } + bytes
}

/**
 * Non-read UDS services for actuator tests and coding. Every request needs a [WriteGrant] for the
 * target; [Session] enforces it. Errors are values, so callers can always run their clean-up steps.
 */
class UdsWriteClient(
    private val session: Session,
    private val grant: WriteGrant,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val target: EcuTarget.Physical get() = grant.target

    /** Last request/response pair, for the audit log. */
    var lastExchange: Pair<List<Int>, UdsOutcome>? = null
        private set

    suspend fun send(request: List<Int>, timeout: Long = timeoutMs): UdsOutcome {
        val outcome = try {
            val response = session.request(grant.target, request, timeout, grant = grant)
            val positive = response.messages.firstOrNull()
            when {
                positive != null -> UdsOutcome.Positive(positive.payload)
                response.negativeResponses.isNotEmpty() -> {
                    val codes = response.negativeResponses.map { it.payload[2] }
                    val final = codes.firstOrNull { it != Nrc.RESPONSE_PENDING }
                    if (final != null) UdsOutcome.Negative(final) else UdsOutcome.Pending
                }
                else -> UdsOutcome.NoResponse
            }
        } catch (e: ObdRequestException) {
            UdsOutcome.AdapterError(e.message ?: "adapter error")
        } catch (e: IOException) {
            UdsOutcome.ConnectionLost(e.message ?: "no reply")
        }
        lastExchange = request to outcome
        return outcome
    }

    suspend fun enterSession(sessionType: Int): UdsOutcome = send(listOf(WritePolicy.SESSION_CONTROL, sessionType))

    suspend fun testerPresent(): UdsOutcome = send(listOf(WritePolicy.TESTER_PRESENT, 0x00))

    /**
     * Request seed, compute the key, send it. An all-zero seed means the level is already unlocked.
     * Returns the outcome of the last step, plus a log of each step through [onStep].
     */
    suspend fun unlock(spec: SecurityAccessSpec, onStep: (String, List<Int>, UdsOutcome) -> Unit = { _, _, _ -> }): UdsOutcome {
        val seedRequest = listOf(WritePolicy.SECURITY_ACCESS, spec.level)
        val seedOutcome = send(seedRequest)
        onStep("Request seed (level %02X)".format(spec.level), seedRequest, seedOutcome)
        if (seedOutcome !is UdsOutcome.Positive) return seedOutcome
        val seed = seedOutcome.payload.drop(2)
        if (seed.isEmpty()) return UdsOutcome.AdapterError("Seed reply has no seed bytes")
        if (seed.all { it == 0 }) return seedOutcome
        val keyRequest = listOf(WritePolicy.SECURITY_ACCESS, spec.level + 1) + spec.keyFor(seed)
        val keyOutcome = send(keyRequest)
        onStep("Send key", keyRequest, keyOutcome)
        return keyOutcome
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}

fun Nrc.hint(code: Int): String = when (code) {
    0x22 -> " – check ignition on, engine off, vehicle stationary"
    0x33 -> " – security access is needed first"
    0x35 -> " – the security key is wrong for this module"
    0x36 -> " – too many key attempts; wait (often 10 min with ignition on) before retrying"
    0x37 -> " – wait before requesting a seed again"
    0x7E, 0x7F -> " – not available in this diagnostic session"
    0x31 -> " – the identifier or value is not supported by this module"
    0x24 -> " – steps were sent in the wrong order (e.g. key before seed)"
    else -> ""
}
