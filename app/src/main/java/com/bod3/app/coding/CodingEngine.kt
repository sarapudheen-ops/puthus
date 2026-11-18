package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.ActuatorPreconditions
import com.bod3.app.actuator.Applicability
import com.bod3.app.actuator.CheckStatus
import com.bod3.app.actuator.EngineRequirement
import com.bod3.app.actuator.PreconditionChecker
import com.bod3.app.actuator.PreconditionReport
import com.bod3.app.actuator.VehicleContext
import com.bod3.app.actuator.VehicleStateProbe
import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditRecord
import com.bod3.app.audit.AuditRecorder
import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.session.ExpertModeRequiredException
import com.bod3.app.obd.session.ObdRequestException
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.session.WriteGate
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.session.WritePurpose
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.Nrc
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.UdsOutcome
import com.bod3.app.obd.uds.UdsWriteClient
import com.bod3.app.obd.uds.hint
import com.bod3.app.recipe.Hex
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The module's current value, read (and backed up) before anything is written. */
data class CodingReading(
    val recipe: CodingRecipe,
    val data: List<Int>,
    val value: Long,
    val partNumber: String?,
    val softwareVersion: String?,
    val preconditions: PreconditionReport,
    val backup: CodingBackup,
    val versionOverridden: Boolean,
    val readAtMs: Long,
)

sealed interface ReadResult {
    data class Ready(val reading: CodingReading) : ReadResult
    data class Refused(val message: String, val preconditions: PreconditionReport? = null) : ReadResult
}

/** Before/after of one write, byte for byte. */
data class CodingDiff(val before: List<Int>, val after: List<Int>, val beforeText: String, val afterText: String) {
    val changedBytes: List<Int> get() = before.indices.filter { before[it] != after.getOrNull(it) }
    val isChange: Boolean get() = before != after
}

enum class CodingOutcome(val label: String, val success: Boolean) {
    VERIFIED("Written and verified", true),
    NO_CHANGE("Already set – nothing written", true),
    REFUSED("Not written", false),
    ABORTED("Aborted before writing – nothing changed", false),
    REJECTED("Rejected by the module – value unchanged", false),
    VERIFY_FAILED("Written but the read-back differs", false),
    UNKNOWN("Result unknown", false),
}

data class CodingResult(
    val outcome: CodingOutcome,
    val message: String,
    val before: List<Int>? = null,
    val intended: List<Int>? = null,
    val readBack: List<Int>? = null,
    val backupId: String? = null,
    val auditId: String? = null,
    val preconditions: PreconditionReport? = null,
) {
    /** True when the module may hold something other than the backup, so a rollback makes sense. */
    val canRollback: Boolean get() = backupId != null && outcome in setOf(CodingOutcome.VERIFIED, CodingOutcome.VERIFY_FAILED, CodingOutcome.UNKNOWN)
}

enum class CodingStep(val label: String) {
    CHECKS("Checking battery, engine and module"),
    REREAD("Re-reading the current value"),
    SESSION("Opening diagnostic session"),
    SECURITY("Security access"),
    WRITE("Writing"),
    VERIFY("Reading back"),
    CLOSE("Closing session"),
}

/**
 * The user's go-ahead for writing one value after seeing the diff. Single use, valid for
 * [VALID_FOR_MS], bound to the reading (and so to its backup) and the exact new data.
 */
class CodingConfirmation private constructor(private val key: Any, private val createdAtMs: Long, private val clock: () -> Long) {
    private val used = AtomicBoolean(false)

    internal fun consume(key: Any): String? = when {
        key != this.key -> "The confirmation was given for a different change"
        clock() - createdAtMs > VALID_FOR_MS -> "The confirmation expired; confirm again"
        !used.compareAndSet(false, true) -> "The confirmation was already used; confirm again"
        else -> null
    }

    companion object {
        const val VALID_FOR_MS = 60_000L
        fun forWrite(reading: CodingReading, newValue: Long, clock: () -> Long = System::currentTimeMillis) =
            CodingConfirmation(Triple(reading.backup.id, reading.data, newValue), clock(), clock)
        fun forRollback(backup: CodingBackup, clock: () -> Long = System::currentTimeMillis) =
            CodingConfirmation("rollback:${backup.id}", clock(), clock)
    }
}

/**
 * The coding engine. Writes happen only here, only in Expert mode, through a [WriteGate] grant for
 * one module limited to `10 01/03`, `27`, `3E` and `2E`. The flow is a chain of single verified steps
 * that stops cleanly at the first problem:
 *
 * [read]: refuse on a module version mismatch (unless overridden), read the identifier, save a backup.
 * [diff]: show before/after.
 * [write]: Expert mode, confirmation, battery/engine checks, re-read (must still equal the backup),
 *   session, security access, `2E`, read back and compare, back to the default session.
 * [rollback]: the same steps writing the backup's data.
 *
 * Every write and rollback (and every refusal) is recorded in the [AuditStore].
 */
class CodingEngine(
    private val session: Session,
    private val probe: VehicleStateProbe,
    private val expertMode: () -> Boolean,
    private val audit: AuditStore,
    private val backups: CodingBackupStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun read(recipe: CodingRecipe, vehicle: VehicleContext = VehicleContext(), overrideVersion: Boolean = false): ReadResult {
        val problems = recipe.problems()
        if (problems.isNotEmpty()) return ReadResult.Refused("Invalid recipe: ${problems.first()}")
        val target = recipe.module.target
        // Battery/engine are shown here and enforced right before the write; the version check gates the read.
        val report = PreconditionChecker.check(preconditionsFor(recipe.preconditions), recipe.applicability, target, probe, vehicle)
        // Part number and software are always read, for the backup and the audit log.
        val partNumber = probe.readIdentification(target, KnownDids.SPARE_PART_NUMBER.id)
        val software = probe.readIdentification(target, KnownDids.SOFTWARE_VERSION.id)
        if (report.overridableFailures.isNotEmpty() && !overrideVersion) {
            return ReadResult.Refused(
                "Module version does not match the recipe: " + report.overridableFailures.joinToString("; ") { "${it.label}: ${it.detail}" } +
                    ". Check the recipe is meant for this module, or override.",
                report,
            )
        }
        val data = when (val outcome = readDid(recipe.module, recipe.did)) {
            is DidRead.Data -> outcome.data
            is DidRead.Failed -> return ReadResult.Refused("Could not read %04X: %s".format(recipe.did, outcome.message), report)
        }
        if (!recipe.field.fits(data)) {
            return ReadResult.Refused(
                "%04X has %d byte(s) but the recipe uses byte %d – wrong module or software version".format(recipe.did, data.size, recipe.byteIndex + recipe.length - 1),
                report,
            )
        }
        val now = clock()
        val backup = CodingBackup(
            id = newId(), createdAtMs = now, recipeId = recipe.id, recipeName = recipe.name, vin = vehicle.vin,
            module = recipe.module, session = recipe.session, security = recipe.security, preconditions = recipe.preconditions,
            did = recipe.did, data = data, partNumber = partNumber, softwareVersion = software,
        )
        backups.add(backup)
        return ReadResult.Ready(CodingReading(recipe, data, recipe.field.read(data), partNumber, software, report, backup, overrideVersion, now))
    }

    fun diff(reading: CodingReading, newValue: Long): CodingDiff = diffOf(reading, newValue)

    suspend fun write(
        reading: CodingReading,
        newValue: Long,
        confirmation: CodingConfirmation,
        vehicle: VehicleContext = VehicleContext(),
        onStep: (CodingStep) -> Unit = {},
    ): CodingResult {
        val recipe = reading.recipe
        val log = AuditRecorder(clock)
        log.step("Recipe ${recipe.id}: ${recipe.name} – %04X byte %d%s".format(recipe.did, recipe.byteIndex, recipe.mask?.let { " mask %02X".format(it) } ?: ""))
        log.step("Module ${reading.partNumber ?: "?"} / ${reading.softwareVersion ?: "?"}" + if (reading.versionOverridden) " (version check overridden)" else "")
        val problem = recipe.problems().firstOrNull()?.let { "Invalid recipe: $it" } ?: recipe.valueProblem(newValue)
        val result = if (problem != null) {
            refused(log, problem)
        } else {
            val diff = diff(reading, newValue)
            log.step("Change: ${diff.beforeText} → ${diff.afterText}")
            perform(
                Target(recipe.module, recipe.session, recipe.security, recipe.preconditions, recipe.did),
                expected = reading.data, intended = diff.after, backupId = reading.backup.id,
                confirmation = confirmation, confirmationKey = Triple(reading.backup.id, reading.data, newValue),
                vehicle = vehicle, log = log, onStep = onStep,
            )
        }
        val details = linkedMapOf(
            "DID" to "%04X".format(recipe.did),
            "Before" to Hex.format(reading.data),
            "Intended" to (result.intended?.let { Hex.format(it) } ?: "-"),
            "Read back" to (result.readBack?.let { Hex.format(it) } ?: "-"),
            "Value" to "${recipe.describe(reading.value)} → ${if (problem == null) recipe.describe(newValue) else newValue.toString()}",
            "Part number" to (reading.partNumber ?: "?"),
            "Software" to (reading.softwareVersion ?: "?"),
            "Backup" to reading.backup.id,
            "Version override" to if (reading.versionOverridden) "yes" else "no",
        )
        return finish(AuditKind.CODING_WRITE, recipe.name, recipe.id, recipe.module, vehicle.vin ?: reading.backup.vin, log, result, details)
    }

    suspend fun rollback(
        backup: CodingBackup,
        confirmation: CodingConfirmation,
        vehicle: VehicleContext = VehicleContext(),
        onStep: (CodingStep) -> Unit = {},
    ): CodingResult {
        val log = AuditRecorder(clock)
        log.step("Rollback of ${backup.recipeName} to backup ${backup.id} (%04X = %s)".format(backup.did, Hex.format(backup.data)))
        val result = perform(
            Target(backup.module, backup.session, backup.security, backup.preconditions, backup.did),
            expected = null, intended = backup.data, backupId = backup.id,
            confirmation = confirmation, confirmationKey = "rollback:${backup.id}",
            vehicle = vehicle, log = log, onStep = onStep,
        )
        if (result.outcome == CodingOutcome.VERIFIED || result.outcome == CodingOutcome.NO_CHANGE) {
            backups.updateStatus(backup.id, BackupStatus.ROLLED_BACK, clock())
        }
        val details = linkedMapOf(
            "DID" to "%04X".format(backup.did),
            "Before rollback" to (result.before?.let { Hex.format(it) } ?: "-"),
            "Backup value" to Hex.format(backup.data),
            "Read back" to (result.readBack?.let { Hex.format(it) } ?: "-"),
            "Backup" to backup.id,
        )
        return finish(AuditKind.CODING_ROLLBACK, "Rollback: ${backup.recipeName}", backup.recipeId, backup.module, vehicle.vin ?: backup.vin, log, result, details)
            .copy(backupId = backup.id)
    }

    // ---- internals --------------------------------------------------------------------------------

    private data class Target(
        val module: ActuatorModule,
        val session: Int,
        val security: SecurityAccessSpec?,
        val preconditions: CodingPreconditions,
        val did: Int,
    )

    private sealed interface DidRead {
        data class Data(val data: List<Int>) : DidRead
        data class Failed(val message: String, val connectionLost: Boolean = false) : DidRead
    }

    private suspend fun readDid(module: ActuatorModule, did: Int): DidRead {
        val request = listOf(0x22, (did shr 8) and 0xFF, did and 0xFF)
        return try {
            val response = session.request(module.target, request, UdsWriteClient.DEFAULT_TIMEOUT_MS)
            response.messages.firstOrNull()?.let { return DidRead.Data(it.payload.drop(3)) }
            val nrc = response.negativeResponses.map { it.payload[2] }.firstOrNull { it != Nrc.RESPONSE_PENDING }
            DidRead.Failed(nrc?.let { "refused: ${Nrc.describe(it)}${Nrc.hint(it)}" } ?: "no response from the module")
        } catch (e: ObdRequestException) {
            DidRead.Failed("adapter error: ${e.message}")
        } catch (e: IOException) {
            DidRead.Failed("connection lost: ${e.message}", connectionLost = true)
        }
    }

    private fun preconditionsFor(p: CodingPreconditions) = ActuatorPreconditions(
        minVoltage = p.minVoltage,
        engine = if (p.allowEngineRunning) EngineRequirement.ANY else EngineRequirement.OFF,
        ignitionOn = true,
        speedZero = true,
    )

    private fun refused(log: AuditRecorder, message: String, report: PreconditionReport? = null): CodingResult {
        log.step("Refused: $message", ok = false)
        return CodingResult(CodingOutcome.REFUSED, message, preconditions = report)
    }

    private suspend fun perform(
        target: Target,
        expected: List<Int>?,
        intended: List<Int>,
        backupId: String,
        confirmation: CodingConfirmation,
        confirmationKey: Any,
        vehicle: VehicleContext,
        log: AuditRecorder,
        onStep: (CodingStep) -> Unit,
    ): CodingResult {
        if (!expertMode()) return refused(log, ExpertModeRequiredException(WritePurpose.CODING).message!!)
        confirmation.consume(confirmationKey)?.let { return refused(log, it) }

        onStep(CodingStep.CHECKS)
        val report = PreconditionChecker.check(preconditionsFor(target.preconditions), Applicability(), target.module.target, probe, vehicle)
        report.items.forEach { log.step("Check ${it.label}: ${it.status} – ${it.detail}", ok = it.status == CheckStatus.PASS) }
        if (!report.passed(false)) {
            val engine = report.blocking.firstOrNull { it.label == "Engine" && it.status == CheckStatus.FAIL }
            val hint = if (engine != null) " (this recipe does not allow writing with the engine running)" else ""
            return refused(log, "Safety checks failed: ${report.summary}$hint", report)
        }

        onStep(CodingStep.REREAD)
        val current = when (val read = readDid(target.module, target.did)) {
            is DidRead.Data -> read.data
            is DidRead.Failed -> {
                log.step("Re-read failed: ${read.message}", "22 %04X".format(target.did), ok = false)
                return CodingResult(CodingOutcome.ABORTED, "Could not re-read the value (${read.message}); nothing was written", backupId = backupId, preconditions = report)
            }
        }
        log.step("Current value", "22 %04X".format(target.did), Hex.format(current))
        if (expected != null && current != expected) {
            log.step("Value changed since it was read and backed up", ok = false)
            return CodingResult(
                CodingOutcome.ABORTED, "The module's value changed since it was read (now ${Hex.format(current)}); read it again before writing",
                before = current, backupId = backupId, preconditions = report,
            )
        }
        if (current.size != intended.size) {
            log.step("Length mismatch: module has ${current.size} bytes, write has ${intended.size}", ok = false)
            return CodingResult(CodingOutcome.ABORTED, "The identifier length changed (${current.size} vs ${intended.size} bytes); nothing was written", before = current, backupId = backupId, preconditions = report)
        }
        if (current == intended) {
            log.step("Already at the intended value; nothing written")
            return CodingResult(CodingOutcome.NO_CHANGE, "The module already has this value", current, intended, current, backupId, preconditions = report)
        }

        val grant = try {
            WriteGate.issue(WritePurpose.CODING, target.module.target, expertMode(), clock, maxDurationMs = GRANT_DURATION_MS)
        } catch (e: ExpertModeRequiredException) {
            return refused(log, e.message!!)
        }
        val client = UdsWriteClient(session, grant, WRITE_TIMEOUT_MS)
        suspend fun send(label: String, request: List<Int>): UdsOutcome {
            val outcome = client.send(request)
            log.step(label, Hex.format(request), outcome.display, outcome.isPositive)
            return outcome
        }
        var sessionOpened = false
        try {
            if (target.session != WritePolicy.DEFAULT_SESSION) {
                onStep(CodingStep.SESSION)
                val outcome = send("Open session %02X".format(target.session), listOf(WritePolicy.SESSION_CONTROL, target.session))
                if (!outcome.isPositive) return aborted("Could not open the diagnostic session: ${outcome.display}", current, backupId, report)
                sessionOpened = true
            }
            target.security?.let { spec ->
                onStep(CodingStep.SECURITY)
                val outcome = client.unlock(spec) { label, request, o -> log.step(label, Hex.format(request), o.display, o.isPositive) }
                if (!outcome.isPositive) return aborted("Security access failed: ${outcome.display}", current, backupId, report)
            }

            onStep(CodingStep.WRITE)
            val writeRequest = listOf(WritePolicy.WRITE_DATA_BY_IDENTIFIER, (target.did shr 8) and 0xFF, target.did and 0xFF) + intended
            val written = send("Write", writeRequest)
            if (written is UdsOutcome.ConnectionLost) {
                return CodingResult(
                    CodingOutcome.UNKNOWN,
                    "The connection dropped during the write, so the module may or may not have the new value. Reconnect, read the value again, and roll back if needed.",
                    current, intended, null, backupId, preconditions = report,
                )
            }

            onStep(CodingStep.VERIFY)
            val readBack = when (val read = readDid(target.module, target.did)) {
                is DidRead.Data -> read.data
                is DidRead.Failed -> {
                    log.step("Read-back failed: ${read.message}", "22 %04X".format(target.did), ok = false)
                    val what = if (written.isPositive) "The module accepted the write, but it could not be read back" else "The write was not confirmed and the value could not be read back"
                    return CodingResult(CodingOutcome.UNKNOWN, "$what (${read.message}). Read it again and roll back if needed.", current, intended, null, backupId, preconditions = report)
                }
            }
            log.step("Read back", "22 %04X".format(target.did), Hex.format(readBack), ok = readBack == intended)
            return when {
                readBack == intended -> CodingResult(
                    CodingOutcome.VERIFIED,
                    if (written.isPositive) "Written and verified by reading it back" else "Verified by reading it back (the write reply was: ${written.display})",
                    current, intended, readBack, backupId, preconditions = report,
                )
                readBack == current -> CodingResult(
                    CodingOutcome.REJECTED,
                    if (written is UdsOutcome.Negative) "The module refused the write: ${written.display}" else "The value did not change (${written.display})",
                    current, intended, readBack, backupId, preconditions = report,
                )
                else -> CodingResult(
                    CodingOutcome.VERIFY_FAILED,
                    "The read-back (${Hex.format(readBack)}) matches neither the old nor the new value. Use rollback to restore the backup.",
                    current, intended, readBack, backupId, preconditions = report,
                )
            }
        } finally {
            withContext(NonCancellable) {
                if (sessionOpened) {
                    onStep(CodingStep.CLOSE)
                    send("Return to default session", listOf(WritePolicy.SESSION_CONTROL, WritePolicy.DEFAULT_SESSION))
                }
                grant.revoke()
            }
        }
    }

    private fun aborted(message: String, current: List<Int>, backupId: String, report: PreconditionReport) =
        CodingResult(CodingOutcome.ABORTED, "$message; nothing was written", before = current, backupId = backupId, preconditions = report)

    private fun finish(
        kind: AuditKind,
        title: String,
        recipeId: String,
        module: ActuatorModule,
        vin: String?,
        log: AuditRecorder,
        result: CodingResult,
        details: Map<String, String>,
    ): CodingResult {
        if (kind == AuditKind.CODING_WRITE && result.backupId != null) {
            val status = when (result.outcome) {
                CodingOutcome.VERIFIED -> BackupStatus.WRITTEN
                CodingOutcome.VERIFY_FAILED, CodingOutcome.REJECTED -> BackupStatus.WRITE_FAILED
                CodingOutcome.UNKNOWN -> BackupStatus.WRITE_UNKNOWN
                else -> null
            }
            status?.let { backups.updateStatus(result.backupId, it, clock()) }
        }
        val id = newId()
        audit.append(
            AuditRecord(
                id = id, kind = kind, title = title, recipeId = recipeId, module = module.display, vin = vin,
                startedAtMs = log.startedAtMs, endedAtMs = clock(), success = result.outcome.success,
                outcome = "${result.outcome.label}: ${result.message}", details = details, steps = log.all,
            ),
        )
        return result.copy(auditId = id)
    }

    companion object {
        fun diffOf(reading: CodingReading, newValue: Long): CodingDiff {
            val recipe = reading.recipe
            val after = recipe.field.write(reading.data, newValue)
            return CodingDiff(reading.data, after, recipe.describe(reading.value), recipe.describe(newValue))
        }

        const val WRITE_TIMEOUT_MS = 5_000L
        const val GRANT_DURATION_MS = 2 * 60_000L
    }
}
