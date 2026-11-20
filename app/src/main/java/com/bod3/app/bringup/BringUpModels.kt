package com.bod3.app.bringup

import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.diagnostics.DiagnosticRead
import com.bod3.app.obd.kwp.DiagnosticDialect
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.profile.JsonWriter
import com.bod3.app.recipe.Hex
import java.security.MessageDigest

/** A vehicle instance is an EXPLICIT garage label/VIN, not an automatically selected model or VIN. */
data class BringUpScope(val path: List<String>, val systemId: String, val vehicleInstance: String) {
    fun validate() {
        require(path.size == 4 && path.all { it.isNotBlank() && it.length <= 256 } && path.distinct().size == 4) { "Choose the exact four-level vehicle path manually" }
        require(systemId.isNotBlank() && systemId.length <= 256)
        require(vehicleInstance.isNotBlank() && vehicleInstance == vehicleInstance.trim() && vehicleInstance.length <= 80) { "Enter a unique vehicle instance (garage label or VIN, at most 80 characters)" }
    }
    val key: String get() = digest(JsonWriter.write(listOf(path, systemId, vehicleInstance)))
}

/** Part numbers/software versions alone cannot prove that a later write addresses the SAME ECU. */
enum class EcuIdentityKind { NON_UNIQUE, ECU_SERIAL }

/** Only already translated definitions. A successful transport probe cannot translate a formula. */
data class BringUpRuntime(
    val data: CatalogData,
    val identificationId: String,
    val livePidId: String,
    val session: String = "DEFAULT",
    val identityKind: EcuIdentityKind = EcuIdentityKind.NON_UNIQUE,
) {
    val ecu: EcuDefinition get() = data.ecus.single()
    val identificationDefinition get() = ecu.diagnostics!!.identification.single { it.read.id == identificationId }
    val identification: DiagnosticRead get() = identificationDefinition.read
    val live: CustomPid get() = data.pids.single { it.id == livePidId }

    fun problems(): List<String> = buildList {
        addAll(data.problems())
        if (session != "DEFAULT") add("Bring-up never enters a non-default diagnostic session (10), unlocks, clears or writes")
        val ecu = data.ecus.singleOrNull()
        if (ecu == null) { add("Exactly one translated ECU is required"); return@buildList }
        val link = ecu.link
        if (link == null) add("An explicit protocol/dialect/baud/init link is required")
        else {
            if (link.protocol.hasCustomBaud && link.baudKbps == null) add("Custom CAN protocol needs its exact baud")
            if (link.kLine == null) {
                val length = if (link.protocol.headerFormat == com.bod3.app.obd.protocol.HeaderFormat.CAN_11) 3 else 8
                if (!link.protocol.isCan || ecu.requestId.length != length || ecu.responseId.length != length) add("CAN address width disagrees with protocol")
                if (ecu.requestId.uppercase() in setOf("7DF", "18DB33F1")) add("Functional addressing cannot verify one ECU")
            }
        }
        if (ecu.sourceRecords.isEmpty()) add("ECU definition has no source provenance")
        val ids = ecu.diagnostics?.identification.orEmpty()
        val id = ids.singleOrNull { it.read.id == identificationId }?.read
        if (id == null || id.minBytes < 1 || id.sourceRecords.isEmpty()) add("A source-bound, nonempty identification read is required")
        if (ids.any { it.read.sourceRecords.isEmpty() || it.read.supportChecks.any { c -> c.sourceRecords.isEmpty() } })
            add("Every identification definition and prerequisite needs its own source records, not just the probe")
        val dtc = ecu.diagnostics?.faults
        if (dtc == null || dtc.read.sourceRecords.isEmpty() || dtc.read.supportChecks.any { it.sourceRecords.isEmpty() })
            add("An exact source-bound DTC read, prerequisites and record layout are required")
        if (dtc?.clear?.sourceRecords?.isEmpty() == true) add("A clear definition needs independent source provenance; a working read cannot approve it")
        val live = data.pids.singleOrNull { it.id == livePidId }
        if (live == null) add("Choose exactly one translated live-data request")
        for (pid in data.pids) {
            if (pid.target != ecu.target || pid.sourceRecords.isEmpty() || pid.supportChecks.any { it.sourceRecords.isEmpty() }) add("Live definitions must retain their own provenance and exact ECU target")
            val r = runCatching { pid.requestBytes }.getOrDefault(emptyList())
            val shape = when (r.firstOrNull()) {
                0x01 -> r.size == 2
                0x21 -> r.size == 2 && link?.dialect == DiagnosticDialect.KWP2000
                0x22 -> r.size == 3
                else -> false
            }
            if (!shape) add("Live requests must be exact single-identifier 01/21/22 reads")
        }
        if (data.actuators.any { it.module.target != ecu.target || it.sourceRecords.isEmpty() } ||
            data.coding.any { it.module.target != ecu.target || it.sourceRecords.isEmpty() }) add("Write definitions cross ECU boundaries or lack provenance")
        // No unrelated vehicles, connection overrides, reusable security algorithms or global data.
        if (data.connection.values.isNotEmpty() || data.security.isNotEmpty() || data.moduleNames.isNotEmpty()) add("Bring-up runtime must be an exact ECU definition set, not global configuration")
    }
}

/** Raw recovered alternatives remain inspectable even when an unresolved mapping prevents probing. */
class BringUpCandidate internal constructor(
    val id: String,
    val name: String,
    val sourceRecords: List<String>,
    val supportRecords: List<String>,
    val metadata: Map<String, String>,
    val blockers: List<String>,
    val runtime: BringUpRuntime?,
    internal val pinned: Boolean,
) {
    val supportCount: Int get() = supportRecords.size
    val reasons: List<String> get() = (blockers + runtime?.problems().orEmpty() +
        (if (runtime == null) listOf("No fully translated identification/live/DTC probe set") else emptyList()) +
        (if (!pinned) listOf("Transferred evidence is not source-pinned executable provenance") else emptyList())).distinct()
    val runnable: Boolean get() = reasons.isEmpty()
    val fingerprint: String get() = digest(JsonWriter.write(BringUpCodec.candidateMap(this)))
    val target: EcuTarget.Physical? get() = runtime?.data?.ecus?.singleOrNull()?.target
    internal fun validate() {
        require(id.isNotBlank() && id.length <= 256 && name.isNotBlank() && name.length <= 256)
        fun records(v: List<String>) = v.isNotEmpty() && v.size <= 8192 && v.distinct().size == v.size && v.all { it.isNotBlank() && it.length <= 256 }
        require(records(sourceRecords) && records(supportRecords)) { "Missing, duplicate or excessive candidate source records" }
        require(metadata.size <= 64 && metadata.all { it.key.isNotBlank() && it.key.length <= 128 && it.value.length <= 16384 })
        require(blockers.size <= 64 && blockers.all { it.isNotBlank() && it.length <= 512 })
        require(runtime == null || runtime.problems().isEmpty()) { runtime?.problems().orEmpty().joinToString("; ") }
    }
    companion object {
        fun ranked(candidates: List<BringUpCandidate>): List<BringUpCandidate> {
            require(candidates.size <= MAX_CANDIDATES && candidates.distinctBy { it.id }.size == candidates.size) { "Excessive or duplicate bring-up candidates" }
            candidates.forEach { it.validate() }
            require(JsonWriter.write(candidates.map(BringUpCodec::candidateMap)).toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Candidate definitions exceed the 2 MiB evidence budget" }
            return candidates.sortedWith(compareByDescending<BringUpCandidate> { it.supportCount }.thenBy { it.id })
        }
        const val MAX_CANDIDATES = 256
    }
}

enum class BringUpOutcome { WORKING, NO_MATCH, CANCELLED, ERROR }
enum class AttemptOutcome { WORKING, FAILED, BLOCKED, CANCELLED }
enum class ProbeRole { IDENTIFICATION, LIVE, DTC }
data class BringUpProbe(val role: ProbeRole, val request: String, val payload: String?, val passed: Boolean, val detail: String)
data class BringUpAttempt(val candidateId: String, val fingerprint: String, val startedAtMs: Long, val finishedAtMs: Long,
                          val outcome: AttemptOutcome, val detail: String, val probes: List<BringUpProbe> = emptyList())
data class BringUpExchange(val candidateId: String?, val command: String, val timeoutMs: Long, val timestampMs: Long,
                           val durationMs: Long, val rawResponse: String, val error: String?)
data class BringUpReport(
    val scope: BringUpScope, val startedAtMs: Long, val finishedAtMs: Long, val outcome: BringUpOutcome,
    val candidates: List<BringUpCandidate>, val attempts: List<BringUpAttempt>, val exchanges: List<BringUpExchange>,
    val logComplete: Boolean, val manualCandidateId: String? = null, val detail: String = "",
) {
    val workingAttempt: BringUpAttempt? get() = attempts.singleOrNull { it.outcome == AttemptOutcome.WORKING }
    val workingCandidate: BringUpCandidate? get() = workingAttempt?.let { a -> candidates.singleOrNull { it.id == a.candidateId && it.fingerprint == a.fingerprint } }
    val identity: String? get() = workingAttempt?.probes?.singleOrNull { it.role == ProbeRole.IDENTIFICATION && it.passed }?.payload
    fun validate() {
        scope.validate(); BringUpCandidate.ranked(candidates)
        require(startedAtMs >= 0 && finishedAtMs >= startedAtMs && detail.length <= 2048)
        require(attempts.size <= candidates.size && attempts.distinctBy { it.candidateId }.size == attempts.size)
        require(attempts.count { it.outcome == AttemptOutcome.WORKING } == if (outcome == BringUpOutcome.WORKING) 1 else 0)
        require(manualCandidateId == null || candidates.any { it.id == manualCandidateId } && attempts.all { it.candidateId == manualCandidateId })
        for (a in attempts) {
            require(candidates.any { it.id == a.candidateId && it.fingerprint == a.fingerprint }) { "Attempt refers to a changed candidate" }
            require(a.startedAtMs in startedAtMs..finishedAtMs && a.finishedAtMs in a.startedAtMs..finishedAtMs && a.detail.length <= 2048)
            require(a.probes.size <= 3 && a.probes.map { it.role }.distinct().size == a.probes.size)
            require(a.probes.all { it.request.length <= 8192 && Hex.parse(it.request) != null && it.detail.length <= 2048 &&
                (it.payload == null || it.payload.length <= 16384 && Hex.parse(it.payload) != null) })
            if (a.outcome == AttemptOutcome.WORKING) {
                require(a.probes.map { it.role } == ProbeRole.entries && a.probes.all { it.passed && it.payload != null })
                val runtime = requireNotNull(candidates.single { it.id == a.candidateId }.runtime)
                require(a.probes.map { Hex.parse(it.request) } == listOf(runtime.identification.requestBytes,
                    runtime.live.requestBytes, runtime.ecu.diagnostics!!.faults!!.read.requestBytes)) { "Probe requests differ from the exact candidate definitions" }
                require(runtime.identification.data(requireNotNull(Hex.parse(a.probes[0].payload!!)))?.let(runtime.identificationDefinition::accepts) == true)
                require(runtime.live.decode(requireNotNull(Hex.parse(a.probes[1].payload!!)))?.isFinite() == true)
                val faults = requireNotNull(runtime.ecu.diagnostics?.faults)
                require(faults.read.data(requireNotNull(Hex.parse(a.probes[2].payload!!)))?.let {
                    faults.layout.decode(it, "bring-up") is com.bod3.app.obd.uds.DtcReadResult.Read } == true)
            }
        }
        require(exchanges.size <= BringUpEngine.MAX_EXCHANGES && exchanges.all { e ->
            e.candidateId == null || candidates.any { it.id == e.candidateId }
        })
        require(exchanges.all { it.command.length <= 16384 && it.rawResponse.length <= BringUpEngine.MAX_LOG_BYTES && it.timeoutMs in 1..15000 &&
            it.timestampMs >= 0 && it.durationMs >= 0 && (it.error == null || it.error.length <= 2048) })
        if (outcome == BringUpOutcome.WORKING) {
            require(logComplete && workingAttempt != null && workingCandidate?.runtime != null && !identity.isNullOrEmpty() && exchanges.isNotEmpty()) { "A working setup needs complete, positive read-only evidence" }
        } else require(workingAttempt == null)
    }
}

internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun CatalogData.withPinnedWrites(): CatalogData = copy(actuators = actuators.map { it.copy(verified = true) },
    coding = coding.map { it.copy(verified = true) }, security = security.map { it.copy(verified = true) })
internal fun BringUpRuntime.map(): Map<String, Any?> = linkedMapOf("data" to CatalogCodec.dataMap(data.unverified()),
    "identificationId" to identificationId, "livePidId" to livePidId, "session" to session, "identityKind" to identityKind.name)
