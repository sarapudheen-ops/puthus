package com.bod3.app.bringup

import com.bod3.app.obd.elm.ExchangeObserver
import com.bod3.app.obd.elm.ObservedExchange
import com.bod3.app.obd.elm.RecordingExecutor
import com.bod3.app.obd.session.ObdResponse
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.recipe.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** No write client, clear confirmation, session-control request, security algorithm or fallback. */
class BringUpEngine(
    private val session: Session,
    private val recorder: RecordingExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLogBytes: Int = MAX_LOG_BYTES,
    private val maxExchanges: Int = MAX_EXCHANGES,
) {
    data class Progress(val candidateId: String, val index: Int, val total: Int, val role: ProbeRole? = null)
    private class EvidenceLimit : IllegalStateException("Evidence budget exhausted; no setup was verified and no log was silently discarded")

    suspend fun run(
        scope: BringUpScope, candidates: List<BringUpCandidate>, manualCandidateId: String? = null,
        onProgress: (Progress) -> Unit = {}, onFinished: (BringUpReport) -> Unit = {},
    ): BringUpReport {
        scope.validate()
        require(maxLogBytes in 1024..MAX_LOG_BYTES && maxExchanges in 1..MAX_EXCHANGES)
        val ranked = BringUpCandidate.ranked(candidates)
        require(manualCandidateId == null || ranked.any { it.id == manualCandidateId }) { "Manual candidate is not part of this exact selected system" }
        val selected = if (manualCandidateId == null) ranked else ranked.filter { it.id == manualCandidateId }
        val started = clock()
        val attempts = mutableListOf<BringUpAttempt>()
        val exchanges = mutableListOf<BringUpExchange>()
        var active: BringUpCandidate? = null
        var attemptStarted = started
        var probes = mutableListOf<BringUpProbe>()
        var logBytes = 0L
        var complete = true
        var outcome = BringUpOutcome.NO_MATCH
        var detail = "No candidate passed all three exact, correctly framed reads"
        val observer = object : ExchangeObserver {
            override fun before(command: String, timeoutMs: Long) {
                if (exchanges.size >= maxExchanges || logBytes + command.toByteArray(Charsets.UTF_8).size + 256 > maxLogBytes) {
                    complete = false; throw EvidenceLimit()
                }
            }
            override fun after(exchange: ObservedExchange) {
                val overhead = exchange.command.toByteArray(Charsets.UTF_8).size + 256
                val remaining = (maxLogBytes - logBytes - overhead).coerceAtLeast(0).toInt()
                val rawSize = exchange.rawResponse.toByteArray(Charsets.UTF_8).size
                val overflow = rawSize > remaining || (exchange.error?.length ?: 0) > 2048
                // Overflowed runs are explicitly INCOMPLETE, never saved as verified. Keep bounded
                // partial evidence for the report rather than silently dropping an old exchange.
                val raw = if (overflow) exchange.rawResponse.take(remaining / 4) else exchange.rawResponse
                exchanges += BringUpExchange(active?.id, exchange.command, exchange.timeoutMs, exchange.timestampMs,
                    exchange.durationMs, raw, exchange.error?.take(2048))
                logBytes += overhead + raw.toByteArray(Charsets.UTF_8).size
                if (overflow) { complete = false; throw EvidenceLimit() }
            }
        }
        try {
            session.withReadOnlyBringUp {
                val remove = recorder.observe(observer)
                try {
                    for ((index, candidate) in selected.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        active = candidate; attemptStarted = clock(); probes = mutableListOf()
                        onProgress(Progress(candidate.id, index + 1, selected.size))
                        if (!candidate.runnable) {
                            attempts += BringUpAttempt(candidate.id, candidate.fingerprint, attemptStarted, clock(), AttemptOutcome.BLOCKED,
                                candidate.reasons.joinToString("; ").take(2048))
                            active = null
                            continue
                        }
                        val runtime = requireNotNull(candidate.runtime)
                        val error = try {
                            val finished = withTimeoutOrNull(CANDIDATE_TIMEOUT_MS) {
                                session.prepareBringUp(runtime.ecu.target)
                                for (role in ProbeRole.entries) {
                                    onProgress(Progress(candidate.id, index + 1, selected.size, role))
                                    val probe = probe(session, runtime, role)
                                    probes += probe
                                    if (!probe.passed) return@withTimeoutOrNull probe.detail
                                }
                                session.restoreBringUpTiming(runtime.ecu.target)
                                ""
                            }
                            finished ?: "Candidate exceeded the ${CANDIDATE_TIMEOUT_MS / 1000}-second bring-up budget"
                        } catch (e: EvidenceLimit) {
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            (e.message ?: e.javaClass.simpleName).take(2048)
                        }
                        val working = error.isEmpty() && complete && probes.size == 3 && probes.all { it.passed } &&
                            probes.all { p -> exchanges.any { it.candidateId == candidate.id && it.command.filterNot(Char::isWhitespace) == p.request && it.error == null } }
                        attempts += BringUpAttempt(candidate.id, candidate.fingerprint, attemptStarted, clock(),
                            if (working) AttemptOutcome.WORKING else AttemptOutcome.FAILED,
                            if (working) "All three positive replies passed exact address, framing, integrity, echo and data-layout checks" else error.ifEmpty { "Full request/response evidence was not captured" }, probes.toList())
                        active = null
                        if (working) {
                            outcome = BringUpOutcome.WORKING
                            detail = "Verified on this vehicle by read-only bring-up; formulas and write definitions retain their independent source review requirements"
                            break
                        }
                        session.invalidateRouting()
                    }
                } finally { remove() }
            }
        } catch (e: CancellationException) {
            active?.let { c -> attempts += BringUpAttempt(c.id, c.fingerprint, attemptStarted, clock(), AttemptOutcome.CANCELLED, "Cancelled; no setup verified", probes.toList()) }
            outcome = BringUpOutcome.CANCELLED; detail = "Cancelled; no write/clear/session-control requests were sent"
            throw e
        } catch (e: Exception) {
            active?.let { c -> attempts += BringUpAttempt(c.id, c.fingerprint, attemptStarted, clock(), AttemptOutcome.FAILED,
                (e.message ?: e.javaClass.simpleName).take(2048), probes.toList()) }
            outcome = BringUpOutcome.ERROR; detail = (e.message ?: e.javaClass.simpleName).take(2048)
        } finally {
            session.invalidateRouting()
            // This synchronous callback runs even on cancellation; Android persists it separately.
            val report = BringUpReport(scope, started, clock().coerceAtLeast(started), outcome, ranked, attempts.toList(), exchanges.toList(), complete, manualCandidateId, detail)
            onFinished(report)
        }
        return BringUpReport(scope, started, clock().coerceAtLeast(started), outcome, ranked, attempts.toList(), exchanges.toList(), complete, manualCandidateId, detail)
    }

    companion object {
        const val READ_TIMEOUT_MS = 1000L
        const val CANDIDATE_TIMEOUT_MS = 12000L
        const val MAX_EXCHANGES = 16384
        const val MAX_LOG_BYTES = 4 * 1024 * 1024

        /** Also used to re-identify the same ECU inside each subsequent guarded write operation. */
        internal suspend fun probe(session: Session, runtime: BringUpRuntime, role: ProbeRole): BringUpProbe {
            val request = when (role) {
                ProbeRole.IDENTIFICATION -> runtime.identification.requestBytes
                ProbeRole.LIVE -> runtime.live.requestBytes
                ProbeRole.DTC -> requireNotNull(runtime.ecu.diagnostics?.faults).read.requestBytes
            }
            val requestHex = Hex.format(request, "")
            val response = withTimeoutOrNull(READ_TIMEOUT_MS + 500) { session.request(runtime.ecu.target, request, READ_TIMEOUT_MS) }
                ?: return BringUpProbe(role, requestHex, null, false, "Read timed out; no late/partial reply was accepted")
            val payload = positive(response) ?: return BringUpProbe(role, requestHex, null, false,
                "No single complete positive reply from the exact ECU (silent, negative, wrong framing/checksum/echo, or mixed messages)")
            val valid = when (role) {
                ProbeRole.IDENTIFICATION -> runtime.identification.data(payload)?.isNotEmpty() == true
                ProbeRole.LIVE -> payload.size <= 4096 && runtime.live.decode(payload)?.isFinite() == true
                ProbeRole.DTC -> runtime.ecu.diagnostics!!.faults!!.let { f -> f.read.data(payload)?.let { f.layout.decode(it, "bring-up") is DtcReadResult.Read } == true }
            }
            return BringUpProbe(role, requestHex, Hex.format(payload, ""), valid,
                if (valid) "Exact positive reply and definition bounds accepted" else "Reply failed the translated data window/formula/record layout")
        }

        private fun positive(response: ObdResponse): List<Int>? = response.takeIf {
            it.complete && it.error == null && it.negativeResponses.isEmpty() && it.messages.size == 1
        }?.messages?.single()?.payload
    }
}
