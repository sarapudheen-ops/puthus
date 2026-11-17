package com.bod3.app.obd.elm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises adapter commands. ELM327-style adapters process exactly one command at a time and answer
 * with a `>` prompt, so every request/response exchange must run alone.
 *
 * Backed by a kotlinx [Mutex], which is fair: callers are served in the order they called [submit].
 */
class CommandQueue {
    private val mutex = Mutex()
    private val waiting = AtomicInteger(0)

    /** Number of commands currently running or waiting to run. */
    val pending: Int get() = waiting.get()

    suspend fun <T> submit(block: suspend () -> T): T {
        waiting.incrementAndGet()
        try {
            return mutex.withLock { block() }
        } finally {
            waiting.decrementAndGet()
        }
    }
}
