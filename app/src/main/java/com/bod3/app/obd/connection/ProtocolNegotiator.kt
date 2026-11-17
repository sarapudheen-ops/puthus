package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.ProtocolDetectionException
import com.bod3.app.obd.session.ProtocolInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One protocol tried during negotiation and what happened. */
data class ProtocolAttempt(
    /** Null = automatic search (`ATSP0`). */
    val requested: ObdProtocol?,
    val selectCommand: String,
    /** Reply to the `0100` probe, joined, or the error text. */
    val result: String,
    /** `ATDP` text after the probe. */
    val atdp: String?,
    val success: Boolean,
    val durationMs: Long,
    val reason: String? = null,
) {
    val label: String get() = requested?.let { "${it.code} · ${it.displayName}" } ?: "0 · Automatic search"
}

data class NegotiationResult(val protocol: ProtocolInfo?, val attempts: List<ProtocolAttempt>) {
    val succeeded: Boolean get() = protocol != null
}

/**
 * Finds a working vehicle protocol.
 *
 * Order: the protocol that last worked with this adapter, then the automatic search (`ATSP0`), then
 * `ATSP5, 4, 3, 6, 7, 8, 9` (KWP fast, KWP slow, ISO 9141-2, then the four ISO 15765-4 CAN variants).
 * A forced protocol is tried first and alone unless fallbacks are enabled. Each attempt sends `ATSPx`,
 * the read-only probe `0100` and `ATDP`, and is logged. The probe succeeds only when a `41 00` reply
 * comes back.
 */
class ProtocolNegotiator(
    private val executor: CommandExecutor,
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun negotiate(profile: ConnectionProfile, remembered: ObdProtocol? = null): NegotiationResult {
        val attempts = mutableListOf<ProtocolAttempt>()
        for (candidate in candidates(profile, remembered)) {
            currentCoroutineContext().ensureActive()
            val (attempt, detected) = attempt(candidate, profile)
            attempts += attempt
            logger("Protocol ${attempt.label}: ${if (attempt.success) "OK" else "failed"} – ${attempt.result}" + (attempt.atdp?.let { " (ATDP: $it)" } ?: ""))
            if (attempt.success && detected != null) {
                return NegotiationResult(ProtocolInfo(detected, attempt.atdp ?: detected.displayName), attempts)
            }
        }
        return NegotiationResult(null, attempts)
    }

    /** Like [negotiate] but throws when nothing worked, with every attempt in the message. */
    suspend fun negotiateOrThrow(profile: ConnectionProfile, remembered: ObdProtocol? = null): NegotiationResult {
        val result = negotiate(profile, remembered)
        if (result.protocol == null) {
            throw ProtocolDetectionException(
                "No protocol answered (" + result.attempts.joinToString("; ") { "${it.requested?.code ?: '0'}: ${it.result}" } + ")",
            )
        }
        return result
    }

    /** Runs one attempt; returns it with the protocol the adapter reports (`ATDPN`) on success. */
    private suspend fun attempt(candidate: ObdProtocol?, profile: ConnectionProfile): Pair<ProtocolAttempt, ObdProtocol?> {
        val select = "ATSP" + (candidate?.code ?: '0')
        val start = clock()
        try {
            val selected = executor.execute(select, SELECT_TIMEOUT_MS)
            if (!selected.isOk) {
                val reason = "rejected: " + (selected.error?.description ?: selected.text)
                return ProtocolAttempt(candidate, select, reason, null, false, clock() - start) to null
            }
            val timeout = when {
                candidate == null -> profile.searchTimeoutMs
                !candidate.isCan -> maxOf(profile.searchTimeoutMs / 2, K_LINE_MIN_TIMEOUT_MS)
                else -> profile.requestTimeoutMs
            }
            val probe = executor.execute("0100", timeout)
            val atdp = runCatching { executor.execute("ATDP", SELECT_TIMEOUT_MS).text.takeIf { it.isNotBlank() } }.getOrNull()
            // Raw adapter lines (e.g. "BUS INIT: ...ERROR") plus the explanation, for the log and the report.
            val text = probe.lines.joinToString(" | ").ifEmpty { "(empty)" } + (probe.error?.let { " – ${it.description}" } ?: "")
            val success = probe.error == null && probe.lines.any { it.filterNot(Char::isWhitespace).uppercase().contains("4100") }
            if (!success) return ProtocolAttempt(candidate, select, text, atdp, false, clock() - start, "no 41 00 reply") to null
            val number = runCatching { executor.execute("ATDPN", SELECT_TIMEOUT_MS).text }.getOrNull()
            val detected = number?.let(ObdProtocol::fromProtocolNumber) ?: candidate
                ?: return ProtocolAttempt(candidate, select, text, atdp, false, clock() - start, "unrecognised ATDPN '$number'") to null
            return ProtocolAttempt(candidate, select, text, atdp, true, clock() - start) to detected
        } catch (e: AdapterException) {
            return ProtocolAttempt(candidate, select, e.message ?: "adapter error", null, false, clock() - start, "adapter error") to null
        }
    }

    companion object {
        /** Fallback order after the automatic search: KWP fast, KWP slow, ISO 9141-2, CAN 11/500, 29/500, 11/250, 29/250. */
        val FALLBACK_ORDER: List<ObdProtocol> = listOf('5', '4', '3', '6', '7', '8', '9').map { code -> ObdProtocol.entries.first { it.code == code } }

        const val SELECT_TIMEOUT_MS = 2_000L
        const val K_LINE_MIN_TIMEOUT_MS = 10_000L

        /** Candidates in order; null means the automatic search. */
        fun candidates(profile: ConnectionProfile, remembered: ObdProtocol?): List<ObdProtocol?> {
            val forced = profile.forcedProtocol
            val list = mutableListOf<ObdProtocol?>()
            if (forced != null) {
                list += forced
                if (!profile.tryFallbacks) return list
            } else if (remembered != null) {
                list += remembered
            }
            list += null
            if (profile.tryFallbacks) list += FALLBACK_ORDER
            return list.distinct()
        }
    }
}
