package com.bod3.app.obd.session

import com.bod3.app.diagnostics.ToyotaFaultWorkflow
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.profile.JsonWriter
import com.bod3.app.diagnostics.ToyotaFaultWorkflowCodec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One explicitly confirmed source DTC setup, NOT a WriteGrant and NOT a read-whitelist entry.
 * No security access, coding, actuator, clear, arbitrary 10 session or second 105F is authorized.
 * Nothing in a file/import/history can serialize or restore this permission.
 */
class DiagnosticSessionConfirmation private constructor(
    val target: EcuTarget.Physical,
    private val session: Session,
    private val workflowDigest: String,
    private val linkEpoch: Long,
    private val createdAtMs: Long,
    private val clock: () -> Long,
) {
    private val consumed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    fun revoke() { revoked.set(true) }
    internal fun belongsTo(current: Session, target: EcuTarget, workflow: ToyotaFaultWorkflow): Boolean =
        current === session && target == this.target && digest(workflow) == workflowDigest &&
            current.currentLinkEpoch(this.target) == linkEpoch &&
            !revoked.get() && !consumed.get() && clock() - createdAtMs in 0..MAX_AGE_MS

    internal fun consume(current: Session, request: List<Int>, target: EcuTarget) {
        require(current === session && target == this.target && this.target.link?.dialect == DiagnosticDialect.KWP2000 && request == listOf(0x10, 0x5F)) {
            "Diagnostic-session confirmation is for one source 105F operation on one exact connection/ECU"
        }
        require(current.currentLinkEpoch(this.target) == linkEpoch && !revoked.get() &&
            clock() - createdAtMs in 0..MAX_AGE_MS && consumed.compareAndSet(false, true)) {
            "Diagnostic-session confirmation expired, the ECU/link changed, was revoked or was already consumed"
        }
    }
    companion object {
        const val MAX_AGE_MS = 60_000L
        private fun digest(workflow: ToyotaFaultWorkflow): String = MessageDigest.getInstance("SHA-256")
            .digest(JsonWriter.write(ToyotaFaultWorkflowCodec.encode(workflow)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /**
         * Call ONLY after the operation-specific dialog, inside the guarded exact-ECU
         * lease and AFTER its fresh preparation/identity check. A reset/re-route,
         * expired initialization or reconnect invalidates the token, even on the
         * same Session object. The clock is monotonic, not a user-adjustable date.
         */
        fun confirmed(
            session: Session, target: EcuTarget.Physical, workflow: ToyotaFaultWorkflow,
            expertModeEnabled: Boolean, clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ): DiagnosticSessionConfirmation {
            require(expertModeEnabled) { "Source diagnostic session changes need Expert mode and explicit confirmation" }
            require(target.link?.dialect == DiagnosticDialect.KWP2000 && workflow.requiresSessionConfirmation && workflow.problems().isEmpty())
            require(workflow.initial.count { it.changesSession } == 1) { "Confirmation is limited to exactly one source 105F" }
            val epoch = requireNotNull(session.currentLinkEpoch(target)) { "Initialize and freshly verify this exact ECU before confirming its source diagnostic session" }
            return DiagnosticSessionConfirmation(target, session, digest(workflow), epoch, clock(), clock)
        }
    }
}
