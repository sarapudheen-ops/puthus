package com.bod3.app.bringup

import com.bod3.app.catalog.CatalogData
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.elm.RecordingExecutor
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Testable screen state, deliberately distinguishing saved/transferred evidence from THIS connection. */
data class BringUpState(
    val vehicleInstance: String = "", val candidates: List<BringUpCandidate> = emptyList(),
    val manualCandidateId: String? = null, val running: Boolean = false,
    val progress: BringUpEngine.Progress? = null, val report: BringUpReport? = null,
    val saved: List<VerifiedSetupStore.Saved> = emptyList(), val verifiedHere: Boolean = false,
    val message: String? = null,
)

class BringUpController(
    private val scope: CoroutineScope,
    private val store: VerifiedSetupStore,
    private val preferences: KeyValueStore,
    private val environment: () -> Pair<Session, RecordingExecutor>?,
    private val accessChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(BringUpState())
    val state = _state.asStateFlow()
    private var path: List<String> = emptyList()
    private var systemId: String? = null
    private var generation = 0L
    private var job: Job? = null
    private data class Proof(val scope: BringUpScope, val candidate: BringUpCandidate, val identity: String, val session: Session)
    private var proof: Proof? = null

    /** Navigation only. No connection reads, selection by VIN, or activation from a saved flag. */
    fun select(path: List<String>, systemId: String?, candidates: List<BringUpCandidate>) {
        val ranked = BringUpCandidate.ranked(candidates)
        if (this.path == path && this.systemId == systemId && _state.value.candidates.map { it.fingerprint } == ranked.map { it.fingerprint }) return
        check(!_state.value.running) { "Stop bring-up before changing systems" }
        generation++; this.path = path.toList(); this.systemId = systemId; proof = null
        val key = labelKey(path)
        _state.value = BringUpState(vehicleInstance = preferences.get(key).orEmpty(), candidates = ranked)
        accessChanged()
        reloadSaved()
    }
    fun vehicleInstance(text: String) {
        if (_state.value.running || text.length > 80) return
        proof = null
        _state.update { it.copy(vehicleInstance = text, verifiedHere = false, report = null, progress = null, message = null) }
        accessChanged()
    }
    fun choose(id: String?) {
        if (_state.value.running || (id != null && _state.value.candidates.none { it.id == id })) return
        _state.update { it.copy(manualCandidateId = id, message = null) }
    }
    private fun selectedScope(): BringUpScope = BringUpScope(path, requireNotNull(systemId) { "Select one exact vehicle system first" }, _state.value.vehicleInstance.trim()).also { it.validate() }

    fun start() {
        if (_state.value.running) return
        val currentScope = try { selectedScope() } catch (e: Exception) { return message(e.message ?: "Choose a vehicle instance") }
        val candidates = _state.value.candidates
        val manual = _state.value.manualCandidateId
        if (candidates.isEmpty()) return message("No recovered ECU-entry candidates are available for this exact system")
        val canProbe = candidates.any { it.runnable && (manual == null || it.id == manual) }
        val env = environment()
        if (canProbe && env == null) return message("Connect to the adapter and finish other diagnostic operations first")
        val token = generation
        proof = null
        preferences.put(labelKey(path), currentScope.vehicleInstance)
        _state.update { it.copy(running = true, verifiedHere = false, progress = null, report = null, message = null) }
        accessChanged()
        job = scope.launch {
            var finished: BringUpReport? = null
            try {
                if (!canProbe) {
                    val now = System.currentTimeMillis()
                    val attempts = candidates.filter { manual == null || it.id == manual }.map { c ->
                        BringUpAttempt(c.id, c.fingerprint, now, now, AttemptOutcome.BLOCKED, c.reasons.joinToString("; ").take(2048))
                    }
                    finished = BringUpReport(currentScope, now, now, BringUpOutcome.NO_MATCH, candidates, attempts, emptyList(), true, manual,
                        "No eligible translated candidates; no adapter or ECU requests were sent")
                } else {
                    val connected = requireNotNull(env)
                    BringUpEngine(connected.first, connected.second).run(currentScope, candidates, manual,
                        onProgress = { p -> _state.update { it.copy(progress = p) } }, onFinished = { finished = it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.message ?: "Bring-up failed before a request was sent")
            } finally {
                withContext(NonCancellable) {
                    val report = finished
                    var saved = false
                    if (report != null) try {
                        withContext(Dispatchers.IO) {
                            store.saveLast(report)
                            if (report.outcome == BringUpOutcome.WORKING) { store.saveLocal(report); saved = true }
                        }
                    } catch (e: Exception) { message("Evidence was not saved; setup NOT activated: ${e.message}") }
                    if (generation == token) {
                        val c = report?.workingCandidate
                        if (saved && c != null && env != null && environment()?.first === env.first && runCatching { selectedScope() }.getOrNull() == currentScope) {
                            proof = Proof(currentScope, c, requireNotNull(report.identity), env.first)
                        }
                        _state.update { it.copy(running = false, report = report, verifiedHere = proof != null,
                            message = it.message ?: report?.detail) }
                        accessChanged()
                        reloadSaved()
                    }
                }
            }
        }
    }
    fun stop() { job?.cancel() }
    fun disconnected() {
        proof = null; job?.cancel()
        _state.update { it.copy(verifiedHere = false) }
        accessChanged()
    }

    /** No global or cross-system fallback. Persisted/imported claims alone never return data. */
    fun data(session: Session?): CatalogData? = currentProof(session)?.candidate?.runtime?.let { r ->
        if (r.identityKind == EcuIdentityKind.ECU_SERIAL) r.data else r.data.copy(actuators = emptyList(), coding = emptyList(),
            ecus = r.data.ecus.map { ecu -> ecu.copy(diagnostics = ecu.diagnostics?.let { d -> d.copy(faults = d.faults?.copy(clear = null)) }) })
    }
    private fun currentProof(session: Session?): Proof? = proof?.takeIf { p ->
        p.session === session && p.candidate.runnable && p.scope == runCatching { selectedScope() }.getOrNull() &&
            _state.value.candidates.any { it.id == p.candidate.id && it.fingerprint == p.candidate.fingerprint }
    }

    /** Runs INSIDE Session's exclusive ECU/write lease, before session/security/write/clear bytes. */
    suspend fun requireSameEcu(session: Session, target: EcuTarget.Physical) = recheckCurrentEcu(session, target, forWrite = true)

    /**
     * Only the separately confirmed source DTC setup. A calibration ID is usable
     * as read-setup evidence, NOT as a unique hardware identity or write proof.
     * Session's narrow source-read lease independently forbids every WriteGrant,
     * clear, actuator and coding operation, including calls nested inside it.
     */
    suspend fun requireSourceReadSession(session: Session, target: EcuTarget.Physical) = recheckCurrentEcu(session, target, forWrite = false)

    private suspend fun recheckCurrentEcu(session: Session, target: EcuTarget.Physical, forWrite: Boolean) {
        val p = currentProof(session) ?: error("Complete read-only bring-up on this vehicle/system and connection before a confirmed source session, active test, coding or clear")
        require(p.candidate.target == target) { "Bring-up was verified for a different ECU/link" }
        if (forWrite) require(p.candidate.runtime?.identityKind == EcuIdentityKind.ECU_SERIAL) {
            "Identification is not a reviewed unique ECU serial; read-only verification cannot unlock writes"
        }
        val identification = try {
            withTimeoutOrNull(BringUpEngine.CANDIDATE_TIMEOUT_MS) {
                session.withReadOnlyBringUp {
                    // Reapply the exact setup, not a cache that a terminal/reset could have changed.
                    session.prepareBringUp(target)
                    val result = BringUpEngine.probe(session, requireNotNull(p.candidate.runtime), ProbeRole.IDENTIFICATION)
                    if (result.passed) session.restoreBringUpTiming(target)
                    result
                }
            } ?: error("Same-ECU setup/identification recheck timed out")
        } catch (e: Exception) {
            proof = null
            _state.update { it.copy(verifiedHere = false, message = "ECU identity recheck failed; repeat read-only bring-up") }
            accessChanged()
            throw e
        }
        if (!identification.passed || identification.payload != p.identity || currentProof(session) !== p) {
            proof = null
            _state.update { it.copy(verifiedHere = false, message = "ECU identity changed or could not be rechecked; repeat read-only bring-up") }
            accessChanged()
            error("Same-ECU identification check failed; no write was authorized")
        }
    }

    fun reloadSaved() {
        val token = generation; val currentPath = path; val currentSystem = systemId
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { if (currentSystem == null) emptyList() else store.matching(currentPath, currentSystem) }
                val report = withContext(Dispatchers.IO) { store.lastReport() }?.takeIf { it.scope.path == currentPath && it.scope.systemId == currentSystem }
                if (generation == token) _state.update { s -> s.copy(saved = saved,
                    report = s.report ?: report?.takeIf { it.scope.vehicleInstance == s.vehicleInstance.trim() }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) message("Saved bring-up evidence could not be loaded: ${e.message}") }
        }
    }
    fun reportText(): String = BringUpCodec.reportText(requireNotNull(_state.value.report) { "Run bring-up first to create a report" })
    private fun message(text: String) { _state.update { it.copy(message = text) } }
    private fun labelKey(path: List<String>) = "bringup.vehicle-instance.${digest(path.joinToString("\u0000"))}"
}
