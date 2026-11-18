package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.protocol.ProtocolFamily
import com.bod3.app.obd.protocol.ProtocolProbe
import com.bod3.app.obd.session.ProtocolDetectionException
import com.bod3.app.obd.session.ProtocolInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One protocol tried during negotiation and what happened. */
data class ProtocolAttempt(
    /** Null = automatic search (`ATSP0`). */
    val requested: ObdProtocol?,
    /** Selection commands sent, joined with `; ` (e.g. `ATPB C1 04; ATSPB`). */
    val selectCommand: String,
    /** Reply to the probe, joined, or the error text. */
    val result: String,
    /** `ATDP` text after the probe. */
    val atdp: String?,
    val success: Boolean,
    val durationMs: Long,
    val reason: String? = null,
) {
    val label: String get() = requested?.label ?: "0 · Automatic search"
}

data class NegotiationResult(val protocol: ProtocolInfo?, val attempts: List<ProtocolAttempt>) {
    val succeeded: Boolean get() = protocol != null
}

/**
 * Finds a working vehicle protocol among everything the vLinker MC+ supports.
 *
 * Order: a forced protocol (alone unless fallbacks are on), else the protocol that last worked with
 * this adapter; then the automatic search (`ATSP0`); then every other protocol in [FALLBACK_ORDER]:
 * K-line, HS-CAN, J1850, J1939, user CAN B/C, the STN ISO 15765 / MS-CAN / SW-CAN / raw CAN protocols
 * and VT KWP1281. Each attempt sends the protocol's selection commands, its read-only probe and
 * `ATDP`, and is logged. Helper commands an adapter does not know (`?`) are logged and skipped; only a
 * rejected selection command fails the attempt.
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
                "No protocol answered (" + result.attempts.joinToString("; ") { "${it.requested?.code ?: "0"}: ${it.result}" } + ")",
            )
        }
        return result
    }

    /** Runs one attempt; returns it with the protocol in use on success. */
    private suspend fun attempt(candidate: ObdProtocol?, profile: ConnectionProfile): Pair<ProtocolAttempt, ObdProtocol?> {
        val commands = candidate?.selectCommands(profile.baudFor(candidate)) ?: listOf("ATSP0")
        val selectText = commands.joinToString("; ")
        val start = clock()
        try {
            for (command in commands) {
                val reply = executor.execute(command, SELECT_TIMEOUT_MS)
                if (reply.isOk) continue
                val text = reply.error?.description ?: reply.text
                if (isSelectCommand(command)) {
                    return ProtocolAttempt(candidate, selectText, "rejected: $text", null, false, clock() - start) to null
                }
                logger("Adapter did not accept $command ($text); continuing")
            }
            val timeout = when {
                candidate == null -> profile.searchTimeoutMs
                !candidate.isCan -> maxOf(profile.searchTimeoutMs / 2, K_LINE_MIN_TIMEOUT_MS)
                else -> profile.requestTimeoutMs
            }
            val probeKind = candidate?.probe ?: ProtocolProbe.OBD
            val probe = executor.execute(probeKind.request, timeout)
            val atdp = runCatching { executor.execute("ATDP", SELECT_TIMEOUT_MS).text.takeIf { it.isNotBlank() } }.getOrNull()
            // Raw adapter lines (e.g. "BUS INIT: ...ERROR") plus the explanation, for the log and the report.
            val text = probe.lines.joinToString(" | ").ifEmpty { "(empty)" } + (probe.error?.let { " – ${it.description}" } ?: "")
            val success = probe.error == null && when (probeKind) {
                ProtocolProbe.OBD, ProtocolProbe.RAW_CAN -> probe.lines.any { it.filterNot(Char::isWhitespace).uppercase().contains("4100") }
                ProtocolProbe.J1939, ProtocolProbe.KWP1281 -> probe.lines.any { it.isNotBlank() && !it.trim().equals("OK", ignoreCase = true) }
            }
            if (!success) return ProtocolAttempt(candidate, selectText, text, atdp, false, clock() - start, "no reply to ${probeKind.description}") to null
            val detected = if (candidate == null || candidate.family == ProtocolFamily.ELM) {
                val number = runCatching { executor.execute("ATDPN", SELECT_TIMEOUT_MS).text }.getOrNull()
                when {
                    candidate != null && (candidate.matchesProtocolNumber(number) || ObdProtocol.fromProtocolNumber(number.orEmpty()) == null) -> candidate
                    else -> ObdProtocol.fromProtocolNumber(number.orEmpty())
                        ?: return ProtocolAttempt(candidate, selectText, text, atdp, false, clock() - start, "unrecognised ATDPN '$number'") to null
                }
            } else {
                candidate
            }
            return ProtocolAttempt(candidate, selectText, text, atdp, true, clock() - start) to detected
        } catch (e: AdapterException) {
            return ProtocolAttempt(candidate, selectText, e.message ?: "adapter error", null, false, clock() - start, "adapter error") to null
        }
    }

    companion object {
        /**
         * Fallback order after the automatic search: K-line (KWP fast, KWP slow, ISO 9141-2), HS-CAN
         * (11/500, 29/500, 11/250, 29/250), J1850 (VPW, PWM), J1939, user CAN B and C, STN ISO 15765,
         * MS-CAN, SW-CAN, raw ISO 11898, and VT KWP1281 last.
         */
        val FALLBACK_ORDER: List<ObdProtocol> = listOf(
            "5", "4", "3", "6", "7", "8", "9", "2", "1", "A", "B", "B/29", "C", "C/29",
            "STN 31", "STN 32", "STN 61", "STN 62", "STN 51", "STN 52", "STN 41", "STN 42", "STN 63", "STN 64", "STN 53", "STN 54",
            "VT KWP1281",
        ).map { code -> ObdProtocol.byCode(code) ?: error("Unknown protocol $code") }

        const val SELECT_TIMEOUT_MS = 2_000L
        const val K_LINE_MIN_TIMEOUT_MS = 10_000L

        /** The command that actually switches protocol (as opposed to parameter commands such as ATPB or STPBR). */
        fun isSelectCommand(command: String): Boolean {
            val c = command.filterNot { it.isWhitespace() }.uppercase()
            return c.startsWith("ATSP") || c.startsWith("ATTP") || (c.startsWith("STP") && !c.startsWith("STPBR") && !c.startsWith("STPTO")) || c.startsWith("VTP")
        }

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
