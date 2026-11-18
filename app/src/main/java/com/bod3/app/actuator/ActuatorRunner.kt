package com.bod3.app.actuator

import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditRecord
import com.bod3.app.audit.AuditRecorder
import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.session.ExpertModeRequiredException
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.session.WriteScope
import com.bod3.app.obd.session.WriteGate
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.session.WritePurpose
import com.bod3.app.obd.uds.UdsOutcome
import com.bod3.app.obd.uds.UdsWriteClient
import com.bod3.app.recipe.Hex
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class StopReason(val label: String) {
    USER("Stopped by you"),
    TIMEOUT("Time limit reached"),
    CONNECTION_LOST("Connection to the adapter lost"),
    MODULE_SILENT("The module stopped answering"),
    CANCELLED("Cancelled"),
}

enum class RunOutcome(val label: String) {
    COMPLETED("Completed"),
    REFUSED("Not started"),
    START_REJECTED("Start rejected by the module"),
    FAILED("Failed"),
    CONNECTION_LOST("Connection lost"),
}

data class RunResult(
    val outcome: RunOutcome,
    val message: String,
    val stopReason: StopReason? = null,
    /** True when the module acknowledged the stop request (or the test never started). */
    val stopConfirmed: Boolean = true,
    val preconditions: PreconditionReport? = null,
    val auditId: String? = null,
)

/** Live status of a run, for the UI. */
sealed interface RunPhase {
    data object CheckingPreconditions : RunPhase
    data class Preparing(val step: String) : RunPhase
    data class Running(val elapsedMs: Long, val durationMs: Long, val lastStatus: String?) : RunPhase {
        val remainingSeconds: Long get() = ((durationMs - elapsedMs).coerceAtLeast(0) + 999) / 1000
    }
    data class Stopping(val reason: StopReason) : RunPhase
    data class Finished(val result: RunResult) : RunPhase
}

/** Lets the UI stop a run at any moment, including while it is still preparing. */
class RunControl {
    private val stop = AtomicBoolean(false)
    val stopRequested: Boolean get() = stop.get()
    fun requestStop() = stop.set(true)
}

/**
 * The user's explicit go-ahead for one run of one recipe, after reading its safety notes. Single use,
 * valid for [VALID_FOR_MS], and bound to the exact recipe content.
 */
class ActuatorConfirmation private constructor(
    private val recipe: ActuatorRecipe,
    private val createdAtMs: Long,
    private val clock: () -> Long,
) {
    private val used = AtomicBoolean(false)

    internal fun consume(recipe: ActuatorRecipe): String? = when {
        recipe != this.recipe -> "The confirmation was given for a different test"
        clock() - createdAtMs > VALID_FOR_MS -> "The confirmation expired; confirm again"
        !used.compareAndSet(false, true) -> "The confirmation was already used; confirm again"
        else -> null
    }

    companion object {
        const val VALID_FOR_MS = 60_000L
        fun forRecipe(recipe: ActuatorRecipe, clock: () -> Long = System::currentTimeMillis) = ActuatorConfirmation(recipe, clock(), clock)
    }
}

/**
 * Runs actuator recipes:
 * 1. refuse unless Expert mode is on and the user confirmed this recipe;
 * 2. check preconditions (voltage, ignition, engine, speed, applicability) right before starting;
 * 3. open the diagnostic session, perform security access when the recipe needs it;
 * 4. send the start request and keep the session alive (status poll or tester present) while running;
 * 5. stop on user request, at the time limit, or when the module/adapter stops answering;
 * 6. always send the stop request (retried once) and return to the default session.
 *
 * Every step is recorded in the [AuditStore]. Requests go through a [WriteGate] grant that covers only
 * this module and the actuator-test whitelist, and is revoked when the run ends.
 */
class ActuatorRunner(
    private val session: Session,
    private val probe: VehicleStateProbe,
    private val expertMode: () -> Boolean,
    private val audit: AuditStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun run(
        recipe: ActuatorRecipe,
        confirmation: ActuatorConfirmation,
        control: RunControl = RunControl(),
        vehicle: VehicleContext = VehicleContext(),
        overrideApplicability: Boolean = false,
        onPhase: (RunPhase) -> Unit = {},
    ): RunResult = session.withDiagnosticOperation { runExclusive(recipe, confirmation, control, vehicle, overrideApplicability, onPhase) }

    private suspend fun runExclusive(
        recipe: ActuatorRecipe, confirmation: ActuatorConfirmation, control: RunControl, vehicle: VehicleContext,
        overrideApplicability: Boolean, onPhase: (RunPhase) -> Unit,
    ): RunResult {
        val log = AuditRecorder(clock)
        log.step("Recipe ${recipe.id}: ${recipe.name} on ${recipe.module.display}")
        val (result, cancelled) = execute(recipe, confirmation, control, vehicle, overrideApplicability, onPhase, log)
        val id = newId()
        audit?.append(
            AuditRecord(
                id = id, kind = AuditKind.ACTUATOR_TEST, title = recipe.name, recipeId = recipe.id,
                module = recipe.module.display, vin = vehicle.vin, startedAtMs = log.startedAtMs, endedAtMs = clock(),
                success = result.outcome == RunOutcome.COMPLETED, outcome = "${result.outcome.label}: ${result.message}",
                details = buildMap {
                    result.stopReason?.let { put("Stop reason", it.label) }
                    put("Stop confirmed", if (result.stopConfirmed) "yes" else "NO")
                    if (overrideApplicability) put("Applicability override", "yes")
                },
                steps = log.all,
            ),
        )
        // A cancelled run is recorded (and its output stopped) before the cancellation propagates.
        cancelled?.let { throw it }
        return result.copy(auditId = id).also { onPhase(RunPhase.Finished(it)) }
    }

    private suspend fun execute(
        recipe: ActuatorRecipe,
        confirmation: ActuatorConfirmation,
        control: RunControl,
        vehicle: VehicleContext,
        overrideApplicability: Boolean,
        onPhase: (RunPhase) -> Unit,
        log: AuditRecorder,
    ): Pair<RunResult, CancellationException?> {
        fun refuse(message: String, report: PreconditionReport? = null): Pair<RunResult, CancellationException?> {
            log.step("Refused: $message", ok = false)
            return RunResult(RunOutcome.REFUSED, message, preconditions = report) to null
        }

        val problems = recipe.problems()
        if (problems.isNotEmpty()) return refuse("Invalid recipe: ${problems.first()}")
        if (!expertMode()) return refuse(ExpertModeRequiredException(WritePurpose.ACTUATOR_TEST).message!!)
        confirmation.consume(recipe)?.let { return refuse(it) }

        onPhase(RunPhase.CheckingPreconditions)
        val report = PreconditionChecker.check(recipe, probe, vehicle)
        report.items.forEach { log.step("Precondition ${it.label}: ${it.status} – ${it.detail}", ok = it.status == CheckStatus.PASS) }
        if (!report.passed(overrideApplicability)) return refuse("Preconditions not met: ${report.summary}", report)
        if (control.stopRequested) return refuse("Stopped before start")

        return session.withEcuOperation(recipe.module.target) {
            runPrepared(recipe, control, onPhase, log, report)
        }
    }

    private suspend fun runPrepared(
        recipe: ActuatorRecipe, control: RunControl, onPhase: (RunPhase) -> Unit,
        log: AuditRecorder, report: PreconditionReport,
    ): Pair<RunResult, CancellationException?> {
        val grant = try {
            WriteGate.issue(
                WritePurpose.ACTUATOR_TEST, recipe.module.target, expertMode(), clock,
                maxDurationMs = recipe.durationSeconds * 1_000L + GRANT_MARGIN_MS,
                scope = WriteScope(when (recipe.kind) {
                    ActuatorKind.LOCAL_IO_CONTROL, ActuatorKind.LOCAL_ROUTINE -> recipe.start.drop(1).take(1)
                    ActuatorKind.ROUTINE -> recipe.start.drop(2).take(2)
                    else -> recipe.start.drop(1).take(2)
                }, recipe.session, recipe.security?.level, recipe.defaultSession),
            )
        } catch (e: ExpertModeRequiredException) {
            log.step("Refused: ${e.message}", ok = false)
            return RunResult(RunOutcome.REFUSED, e.message!!, preconditions = report) to null
        }
        val client = UdsWriteClient(session, grant)

        suspend fun send(label: String, request: List<Int>, timeout: Long = UdsWriteClient.DEFAULT_TIMEOUT_MS): UdsOutcome {
            val outcome = client.send(request, timeout)
            log.step(label, Hex.format(request), outcome.display, ok = outcome.isPositive)
            return outcome
        }

        var startSent = false
        var stopReason: StopReason? = null
        var cancelled: CancellationException? = null
        var failure: RunResult? = null
        var lastStatus: String? = null
        try {
            if (recipe.session != recipe.defaultSession) {
                onPhase(RunPhase.Preparing("Opening diagnostic session"))
                val outcome = send("Open session %02X".format(recipe.session), listOf(WritePolicy.SESSION_CONTROL, recipe.session))
                if (!outcome.isPositive) failure = failed("Could not open the diagnostic session: ${outcome.display}", outcome)
            }
            if (failure == null && recipe.security != null) {
                onPhase(RunPhase.Preparing("Security access"))
                val outcome = client.unlock(recipe.security) { label, request, o -> log.step(label, Hex.format(request), o.display, o.isPositive) }
                if (!outcome.isPositive) failure = failed("Security access failed: ${outcome.display}", outcome)
            }
            if (failure == null && control.stopRequested) {
                stopReason = StopReason.USER
                failure = RunResult(RunOutcome.REFUSED, "Stopped before start", StopReason.USER)
            }
            if (failure == null) {
                onPhase(RunPhase.Preparing("Starting"))
                startSent = true
                val outcome = send("Start", recipe.start)
                if (!outcome.isPositive) {
                    failure = if (outcome is UdsOutcome.Negative) {
                        RunResult(RunOutcome.START_REJECTED, "The module refused to start: ${outcome.display}")
                    } else {
                        failed("Start not confirmed: ${outcome.display}", outcome)
                    }
                }
            }
            if (failure == null) {
                stopReason = runLoop(recipe, control, onPhase, log, { l, r, t -> send(l, r, t) }) { lastStatus = it }
            }
        } catch (e: CancellationException) {
            cancelled = e
            stopReason = StopReason.CANCELLED
        }

        // Clean-up always runs, even when the caller's coroutine was cancelled.
        val stopConfirmed = withContext(NonCancellable) {
            onPhase(RunPhase.Stopping(stopReason ?: StopReason.USER))
            var confirmed = !startSent
            if (startSent) {
                var outcome = send("Stop", recipe.stop, STOP_TIMEOUT_MS)
                if (!outcome.isPositive && outcome !is UdsOutcome.ConnectionLost) outcome = send("Stop (retry)", recipe.stop, STOP_TIMEOUT_MS)
                confirmed = outcome.isPositive
            }
            if (recipe.session != recipe.defaultSession) {
                send("Return to default session", listOf(WritePolicy.SESSION_CONTROL, recipe.defaultSession))
            }
            grant.revoke()
            confirmed
        }
        cancelled?.let { return RunResult(RunOutcome.FAILED, "Cancelled", StopReason.CANCELLED, stopConfirmed, report) to it }

        val unconfirmedHint = if (stopConfirmed) "" else " " + STOP_UNCONFIRMED_HINT
        failure?.let { return it.copy(stopConfirmed = stopConfirmed, message = it.message + unconfirmedHint, preconditions = report) to null }
        val reason = stopReason ?: StopReason.USER
        val outcome = when {
            reason == StopReason.CONNECTION_LOST -> RunOutcome.CONNECTION_LOST
            reason == StopReason.MODULE_SILENT || !stopConfirmed -> RunOutcome.FAILED
            else -> RunOutcome.COMPLETED
        }
        val message = when (outcome) {
            RunOutcome.COMPLETED -> "${reason.label}; output returned to the module" + (lastStatus?.let { " (last status $it)" } ?: "")
            else -> reason.label + if (stopConfirmed) "; stop confirmed" else ";$unconfirmedHint"
        }
        return RunResult(outcome, message, reason, stopConfirmed, report) to null
    }

    private fun failed(message: String, outcome: UdsOutcome): RunResult =
        RunResult(if (outcome is UdsOutcome.ConnectionLost) RunOutcome.CONNECTION_LOST else RunOutcome.FAILED, message)

    private suspend fun runLoop(
        recipe: ActuatorRecipe,
        control: RunControl,
        onPhase: (RunPhase) -> Unit,
        log: AuditRecorder,
        send: suspend (String, List<Int>, Long) -> UdsOutcome,
        onStatus: (String) -> Unit,
    ): StopReason {
        val startedAt = clock()
        val durationMs = recipe.durationSeconds * 1_000L
        val tick = minOf(TICK_MS, recipe.pollIntervalMs)
        var nextPoll = startedAt + minOf(recipe.pollIntervalMs, KEEP_ALIVE_MS)
        var silent = 0
        var lastStatus: String? = null
        log.step("Running for up to ${recipe.durationSeconds} s")
        onPhase(RunPhase.Running(0, durationMs, null))
        while (true) {
            currentCoroutineContext().ensureActive()
            if (control.stopRequested) return StopReason.USER
            val now = clock()
            if (now - startedAt >= durationMs) return StopReason.TIMEOUT
            if (now >= nextPoll) {
                val request = recipe.status ?: listOf(WritePolicy.TESTER_PRESENT, 0x00)
                val outcome = send(if (recipe.status != null) "Status" else "Tester present", request, UdsWriteClient.DEFAULT_TIMEOUT_MS)
                when (outcome) {
                    is UdsOutcome.Positive -> {
                        silent = 0
                        if (recipe.status != null) {
                            lastStatus = Hex.format(outcome.payload.drop(if (request[0] == WritePolicy.ROUTINE_CONTROL) 4 else 3))
                            onStatus(lastStatus)
                        }
                    }
                    is UdsOutcome.Negative -> silent = 0
                    is UdsOutcome.ConnectionLost -> return StopReason.CONNECTION_LOST
                    else -> if (++silent >= MAX_SILENT_POLLS) return StopReason.MODULE_SILENT
                }
                nextPoll = clock() + minOf(recipe.pollIntervalMs, KEEP_ALIVE_MS)
                onPhase(RunPhase.Running(clock() - startedAt, durationMs, lastStatus))
            }
            sleep(tick)
        }
    }

    companion object {
        const val TICK_MS = 100L
        /** Keep-alive interval: well inside the 5 s S3 session timer of UDS modules. */
        const val KEEP_ALIVE_MS = 2_000L
        const val MAX_SILENT_POLLS = 2
        const val STOP_TIMEOUT_MS = 3_000L
        const val GRANT_MARGIN_MS = 60_000L
        const val STOP_UNCONFIRMED_HINT =
            "The stop was not confirmed. Modules hand control back by themselves when the diagnostic session " +
                "times out (about 5 s without tester present); if the output stays active, switch the ignition off and on."
    }
}
