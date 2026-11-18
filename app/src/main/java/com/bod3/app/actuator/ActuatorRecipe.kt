package com.bod3.app.actuator

import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.session.WritePurpose
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.recipe.Hex

/** Engine state a test needs. */
enum class EngineRequirement(val label: String) {
    OFF("Engine off"),
    RUNNING("Engine running"),
    ANY("Engine on or off"),
}

/** Conditions checked right before a test starts (and shown to the user before they confirm). */
data class ActuatorPreconditions(
    val minVoltage: Double = 12.0,
    val engine: EngineRequirement = EngineRequirement.OFF,
    val ignitionOn: Boolean = true,
    val speedZero: Boolean = true,
)

/** The module a recipe talks to, on CAN (UDS). */
data class ActuatorModule(
    val name: String,
    val address: String? = null,
    val requestId: String,
    val responseId: String,
) {
    val target: EcuTarget.Physical get() = EcuTarget.Physical(requestId, responseId)
    val display: String get() = name + (address?.let { " ($it)" } ?: "") + " $requestId/$responseId"
}

/**
 * Which vehicles/modules a recipe was written for. Empty lists mean "not restricted". Part numbers and
 * software versions are compared with the module's `F187` / `F189` identification.
 */
data class Applicability(
    val makes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val partNumbers: List<String> = emptyList(),
    val softwareVersions: List<String> = emptyList(),
) {
    val isRestricted: Boolean get() = makes.isNotEmpty() || models.isNotEmpty() || partNumbers.isNotEmpty() || softwareVersions.isNotEmpty()

    fun matchesMake(make: String?): Boolean? = if (makes.isEmpty()) true else make?.let { m -> makes.any { it.equals(m.trim(), true) } }

    fun matchesModel(model: String?): Boolean? =
        if (models.isEmpty()) true else model?.let { m -> models.any { m.contains(it, ignoreCase = true) || it.contains(m.trim(), true) } }

    fun matchesPartNumber(partNumber: String?): Boolean? =
        if (partNumbers.isEmpty()) true else partNumber?.let { p -> partNumbers.any { normalize(p).startsWith(normalize(it)) } }

    fun matchesSoftware(version: String?): Boolean? =
        if (softwareVersions.isEmpty()) true else version?.let { v -> softwareVersions.any { normalize(v) == normalize(it) } }

    companion object {
        fun normalize(text: String) = text.filterNot { it.isWhitespace() || it == '-' || it == '.' }.uppercase()
    }
}

enum class ActuatorKind(val label: String) {
    IO_CONTROL("Output control (2F)"),
    ROUTINE("Routine (31)"),
}

/**
 * A bidirectional test: start an output or routine in one module, keep it running for at most
 * [durationSeconds], and always stop it with [stop].
 *
 * @param session Diagnostic session to open first (`03` extended; `01` default runs in the current session).
 * @param security Optional security access performed after opening the session.
 * @param start `2F <DID> <01|02|03> [state…]` or `31 01 <routine id> [options…]`.
 * @param stop `2F <same DID> 00` (return control to ECU) or `31 02 <same routine id>`.
 * @param status Optional request polled while running: `31 03 <same routine id>` or a read `22 <DID>`.
 */
data class ActuatorRecipe(
    val id: String,
    val name: String,
    val description: String,
    val module: ActuatorModule,
    val session: Int = WritePolicy.EXTENDED_SESSION,
    val security: SecurityAccessSpec? = null,
    val start: List<Int>,
    val stop: List<Int>,
    val status: List<Int>? = null,
    val durationSeconds: Int = 10,
    val pollIntervalMs: Long = 1_000,
    val preconditions: ActuatorPreconditions = ActuatorPreconditions(),
    val safetyNotes: List<String>,
    val applicability: Applicability = Applicability(),
    val source: String? = null,
    val notes: String? = null,
    /** True once the recipe was confirmed on a real vehicle; templates ship unverified. */
    val verified: Boolean = false,
) {
    val kind: ActuatorKind? get() = when (start.firstOrNull()) {
        WritePolicy.IO_CONTROL -> ActuatorKind.IO_CONTROL
        WritePolicy.ROUTINE_CONTROL -> ActuatorKind.ROUTINE
        else -> null
    }

    /** Every problem that makes this recipe unusable; empty when it is valid. */
    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id: use lowercase letters, digits, '.', '_' or '-' (got \"$id\")")
        if (name.isBlank()) add("name: must not be empty")
        val idLengths = setOf(module.requestId.length, module.responseId.length)
        if (!HEX_ID.matches(module.requestId) || !HEX_ID.matches(module.responseId)) {
            add("module: requestId and responseId must be 3-digit (11-bit) or 8-digit (29-bit) hex CAN IDs")
        } else if (idLengths.size != 1) {
            add("module: requestId and responseId must both be 11-bit or both 29-bit")
        }
        if (session !in WritePolicy.allowedSessions) add("session: only 01 (default) or 03 (extended) are allowed")
        security?.problems()?.forEach { add("securityAccess: $it") }
        if (safetyNotes.none { it.isNotBlank() }) add("safety: at least one safety note is required")
        if (durationSeconds !in 1..MAX_DURATION_SECONDS) add("durationSeconds: must be 1-$MAX_DURATION_SECONDS")
        if (pollIntervalMs !in 200..10_000) add("pollIntervalMs: must be 200-10000")
        if (preconditions.minVoltage !in 9.0..16.0) add("preconditions.minVoltage: must be 9-16 V")

        if (!WritePolicy.isAllowed(WritePurpose.ACTUATOR_TEST, start) ||
            start[0] !in setOf(WritePolicy.IO_CONTROL, WritePolicy.ROUTINE_CONTROL)
        ) {
            add("start: must be \"2F <DID> <01|02|03> …\" or \"31 01 <routine id> …\" (got ${Hex.format(start)})")
            return@buildList
        }
        when (kind) {
            ActuatorKind.IO_CONTROL -> {
                if (start[3] == 0x00) add("start: ioControlParameter 00 returns control; use 01, 02 or 03 to start")
                if (stop.size != 4 || stop[0] != 0x2F || stop.subList(1, 3) != start.subList(1, 3) || stop[3] != 0x00) {
                    add("stop: must be \"2F ${Hex.format(start.subList(1, 3))} 00\" (return control to ECU)")
                }
                if (status != null && !(status.size == 3 && status[0] == 0x22)) add("status: must be a read \"22 <DID>\"")
            }
            ActuatorKind.ROUTINE -> {
                if (start[1] != 0x01) add("start: routine must be started with sub-function 01")
                if (stop.size < 4 || stop[0] != 0x31 || stop[1] != 0x02 || stop.subList(2, 4) != start.subList(2, 4)) {
                    add("stop: must be \"31 02 ${Hex.format(start.subList(2, 4))}\" (stop routine)")
                }
                if (status != null) {
                    val isResults = status.size >= 4 && status[0] == 0x31 && status[1] == 0x03 && status.subList(2, 4) == start.subList(2, 4)
                    val isRead = status.size == 3 && status[0] == 0x22
                    if (!isResults && !isRead) add("status: must be \"31 03 ${Hex.format(start.subList(2, 4))}\" or a read \"22 <DID>\"")
                }
            }
            null -> Unit
        }
    }

    companion object {
        const val MAX_DURATION_SECONDS = 120
        val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val HEX_ID = Regex("[0-9A-F]{3}|[0-9A-F]{8}")
    }
}
