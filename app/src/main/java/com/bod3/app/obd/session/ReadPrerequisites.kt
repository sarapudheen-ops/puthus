package com.bod3.app.obd.session

import com.bod3.app.datapack.PidSupportCheck

/**
 * Ordered support reads in one exact ECU read-only lease. Caches replies, not a guessed "sensor
 * nonzero" predicate. Reset, routing/session changes and expired initialization invalidate them.
 * Used by live polling, identification/fault reads and fresh read-only bring-up probes alike.
 */
class ReadPrerequisites(private val session: Session) {
    private var scope: Pair<EcuTarget.Physical, Long>? = null
    private val replies = linkedMapOf<List<Int>, List<Int>?>()

    suspend fun <T> withSupported(
        target: EcuTarget.Physical,
        checks: List<PidSupportCheck>,
        timeoutMs: Long = Session.DEFAULT_REQUEST_TIMEOUT_MS,
        block: suspend () -> T,
    ): T? = session.withReadOnlyEcuOperation(target) {
        require(checks.size <= 32 && checks.all { it.problems(target.link).isEmpty() }) { "Invalid read prerequisites" }
        if (checks.isEmpty()) return@withReadOnlyEcuOperation block()
        val current = session.currentLinkEpoch(target)?.let { target to it }
        if (current == null || scope != current) { replies.clear(); scope = current }
        for (check in checks) {
            val request = check.requestBytes
            if (request !in replies) {
                val before = scope
                val response = session.request(target, request, timeoutMs)
                val value = if (!response.complete || response.negativeResponses.isNotEmpty() || response.error != null) null
                    else PidSupportCheck.normalizeReply(request, response.messages.map { it.payload })
                val after = session.currentLinkEpoch(target)?.let { target to it }
                if (after == null) { replies.clear(); scope = null; return@withReadOnlyEcuOperation null }
                if (before != after) {
                    replies.clear(); scope = after
                    // Initial routing is expected; a reset halfway through discovery is not.
                    if (before != null) return@withReadOnlyEcuOperation null
                }
                if (replies.size >= 512) replies.remove(replies.keys.first())
                replies[request] = value
            }
            val payload = replies[request] ?: return@withReadOnlyEcuOperation null
            if (!check.accepts(payload)) return@withReadOnlyEcuOperation null
        }
        val result = block()
        if (session.currentLinkEpoch(target)?.let { target to it } != scope) {
            replies.clear(); scope = null; null
        } else result
    }
}
