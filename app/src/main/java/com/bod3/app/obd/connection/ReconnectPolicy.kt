package com.bod3.app.obd.connection

/** How often and how patiently to reconnect after the Bluetooth link drops. */
data class ReconnectPolicy(val delaysMs: List<Long> = listOf(1_000, 3_000, 6_000, 10_000)) {
    val maxAttempts: Int get() = delaysMs.size

    /** Delay before attempt number [attempt] (1-based), or null when attempts are exhausted. */
    fun delayBefore(attempt: Int): Long? = delaysMs.getOrNull(attempt - 1)
}

/**
 * Runs [block] until it succeeds or the policy's attempts are exhausted, waiting the policy delay
 * before each attempt. Rethrows the last failure. Cancellation is never swallowed.
 */
suspend fun <T> ReconnectPolicy.retry(
    onAttempt: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> },
    onFailure: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    var last: Exception? = null
    for (attempt in 1..maxAttempts) {
        val wait = delayBefore(attempt) ?: break
        onAttempt(attempt, wait)
        kotlinx.coroutines.delay(wait)
        try {
            return block(attempt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            onFailure(attempt, e)
        }
    }
    throw last ?: IllegalStateException("Reconnect policy has no attempts")
}
