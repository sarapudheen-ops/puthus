package com.bod3.app.obd.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot permission to send exactly one fault-memory clear request: OBD service `04` or UDS `14`
 * (ClearDiagnosticInformation). These are the only non-read requests the app sends before the coding
 * engine, and only after the user confirmed in a dialog.
 *
 * A confirmation is bound to the exact request bytes and target, expires after [VALID_FOR_MS] and is
 * consumed on first use, so it can never be replayed or reused for another ECU. Everything else still
 * goes through [ReadOnlyPolicy], and the expert terminal cannot obtain one.
 */
class ClearConfirmation private constructor(
    val request: List<Int>,
    val target: EcuTarget,
    private val createdAtMs: Long,
    private val clock: () -> Long,
) {
    private val used = AtomicBoolean(false)

    val isUsed: Boolean get() = used.get()

    /** Checks the confirmation for [request] to [target] and consumes it. Throws when it does not apply. */
    internal fun consume(request: List<Int>, target: EcuTarget) {
        if (request != this.request || target != this.target) {
            throw ClearNotConfirmedException("The confirmation was given for a different request or module")
        }
        if (clock() - createdAtMs > VALID_FOR_MS) throw ClearNotConfirmedException("The confirmation expired; confirm again")
        if (!used.compareAndSet(false, true)) throw ClearNotConfirmedException("The confirmation was already used; confirm again")
    }

    companion object {
        const val VALID_FOR_MS = 60_000L

        /** OBD service 04: clears stored/pending codes, freeze frames and readiness on every emission ECU. */
        fun obdClear(clock: () -> Long = System::currentTimeMillis) =
            ClearConfirmation(listOf(0x04), EcuTarget.Broadcast, clock(), clock)

        /** KWP 14 uses a two-byte group, unlike UDS. Bound to this physical ECU and exact group. */
        fun kwpClear(target: EcuTarget.Physical, group: Int = 0xFFFF, clock: () -> Long = System::currentTimeMillis): ClearConfirmation {
            require(target.link?.dialect == com.bod3.app.obd.kwp.DiagnosticDialect.KWP2000)
            require(group in 0..0xFFFF)
            return ClearConfirmation(listOf(0x14, group shr 8, group and 255), target, clock(), clock)
        }

        /** UDS `14 <group>` to one module; group `FFFFFF` = all DTCs. */
        fun udsClear(target: EcuTarget.Physical, group: Int = 0xFFFFFF, clock: () -> Long = System::currentTimeMillis): ClearConfirmation {
            require(target.link?.dialect != com.bod3.app.obd.kwp.DiagnosticDialect.KWP2000) { "KWP clear needs an exact two-byte group" }
            require(group in 0..0xFFFFFF) { "DTC group must be 3 bytes" }
            return ClearConfirmation(listOf(0x14, (group shr 16) and 0xFF, (group shr 8) and 0xFF, group and 0xFF), target, clock(), clock)
        }
    }
}

class ClearNotConfirmedException(message: String) : IllegalArgumentException(message)
