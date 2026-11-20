package com.bod3.app.obd.session

import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.profile.JsonWriter
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ephemeral UI intent, NOT a Session request capability. It remembers what the
 * dialog actually displayed before waiting for confirmation. Even a successful
 * consume must still pass the fresh selected-ECU read-session guard and create
 * a separate epoch-bound DiagnosticSessionConfirmation inside the bus lease.
 * No export/import/restore API, saved-state handle, VIN or historical authority.
 */
class DiagnosticSessionPrompt private constructor(
    private val session: Session,
    private val definitionDigest: String,
    private val epoch: Long,
    private val createdAtMs: Long,
    private val clock: () -> Long,
) {
    private val used = AtomicBoolean(false)
    fun cancel() { used.set(true) }
    // Every attempted use is terminal, even a mismatch. Returning to the old
    // ECU or re-enabling Expert mode cannot revive a refused dialog intent.
    fun consume(currentSession: Session, selectedEcu: EcuDefinition, expertModeEnabled: Boolean): Boolean =
        used.compareAndSet(false, true) && currentSession === session && expertModeEnabled &&
            session.diagnosticPromptEpoch() == epoch && digest(selectedEcu) == definitionDigest &&
            clock() - createdAtMs in 0..DiagnosticSessionConfirmation.MAX_AGE_MS

    companion object {
        private fun digest(ecu: EcuDefinition): String = MessageDigest.getInstance("SHA-256")
            .digest(JsonWriter.write(DataPackCodec.encodeEcu(ecu)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun open(session: Session, ecu: EcuDefinition, expertModeEnabled: Boolean,
                 clock: () -> Long = { System.nanoTime() / 1_000_000 }): DiagnosticSessionPrompt {
            require(expertModeEnabled) { "Enable Expert mode before confirming a source diagnostic-session change" }
            require(ecu.problems().isEmpty() && ecu.diagnostics?.faults?.workflow?.requiresSessionConfirmation == true) {
                "This selected ECU has no valid source DTC session workflow"
            }
            return DiagnosticSessionPrompt(session, digest(ecu), session.diagnosticPromptEpoch(), clock(), clock)
        }
    }
}
