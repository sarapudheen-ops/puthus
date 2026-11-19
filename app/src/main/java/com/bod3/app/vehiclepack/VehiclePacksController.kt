package com.bod3.app.vehiclepack

import com.bod3.app.bringup.BringUpCandidate
import com.bod3.app.catalog.CatalogData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PackView { PICKER, SYSTEMS, FEATURES }
data class UnresolvedPackItem(val id: String, val kind: String, val reasons: List<String>)
data class VehiclePacksState(
    val view: PackView = PackView.PICKER, val busy: Boolean = false, val error: String? = null,
    val page: PackLevel? = null, val trail: List<PackLevel> = emptyList(), val preview: PackVehicle? = null,
    val vehicle: PackVehicle? = null, val system: PackSystem? = null,
    val reasons: Map<String, String> = emptyMap(), val complete: Boolean = false,
    val executableDefinitions: Int = 0, val unresolvedPage: Int? = null,
    val unresolvedItems: List<UnresolvedPackItem> = emptyList(),
    val bringUpCandidates: List<BringUpCandidate> = emptyList(),
)

/** UI-independent on-demand loading. Navigation and downloading have NO diagnostic-session access. */
class VehiclePacksController(
    private val scope: CoroutineScope,
    private val catalog: () -> VehiclePackCatalog,
    private val selectedId: () -> String?,
    private val selectManually: (PackVehicle) -> Unit,
    private val beforeSystemChange: () -> Unit,
    private val systemChanged: () -> Unit,
) {
    private val _state = MutableStateFlow(VehiclePacksState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var loadSequence = 0L

    /** EMPTY while an explicitly selected pack is unloaded or has no selected/resolved system. */
    fun selectedData(): CatalogData {
        val s = _state.value
        return if (s.vehicle?.variantId == selectedId()) s.system?.data ?: CatalogData() else CatalogData()
    }
    fun selectedSystem(): PackSystem? = _state.value.takeIf { it.vehicle?.variantId == selectedId() }?.system
    private fun fetch(block: suspend (VehiclePackCatalog, () -> Unit) -> Unit) {
        val sequence = ++loadSequence
        job?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        job = scope.launch {
            try {
                val context = currentCoroutineContext()
                val client = withContext(Dispatchers.IO) { context.ensureActive(); catalog() }
                _state.update { it.copy(reasons = client.index.reasons, complete = client.index.summary["complete"] == true,
                    executableDefinitions = (client.index.summary["executableDefinitions"] as? Number)?.toInt() ?: 0) }
                block(client) { context.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Vehicle pack could not be loaded; nothing was selected") }
            } finally { if (sequence == loadSequence) _state.update { it.copy(busy = false) } }
        }
    }
    fun picker() {
        _state.update { it.copy(view = PackView.PICKER, preview = null, trail = emptyList(), page = null) }
        fetch { client, check ->
            val page = withContext(Dispatchers.IO) { client.root(check) }
            _state.update { it.copy(page = page) }
        }
    }
    fun browse(choice: PackChoice) {
        if (_state.value.busy || choice !in _state.value.page?.children.orEmpty()) return
        val old = _state.value.page ?: return
        fetch { client, check ->
            if (choice.level == "variant") {
                val vehicle = withContext(Dispatchers.IO) { client.vehicle(choice, check) }
                _state.update { it.copy(preview = vehicle) }
            } else {
                val page = withContext(Dispatchers.IO) { client.open(choice, check) }
                _state.update { it.copy(page = page, trail = it.trail + old, preview = null) }
            }
        }
    }
    fun selectPreview() {
        if (_state.value.busy) return
        val preview = _state.value.preview ?: return
        try {
            require(preview.hierarchyResolved) { "This source path has not been normalized; it cannot select diagnostic configuration" }
            beforeSystemChange()
            selectManually(preview) // Host requires disconnected + no ongoing actuator/coding operation.
            _state.update { it.copy(view = PackView.SYSTEMS, vehicle = preview, system = null, preview = null,
                error = null, unresolvedItems = emptyList(), unresolvedPage = null) }
            systemChanged()
        } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }
    fun openSelected(pathIds: List<String>) {
        _state.update { it.copy(view = PackView.SYSTEMS, error = null) }
        if (_state.value.vehicle?.variantId == selectedId() && _state.value.vehicle != null) return
        _state.update { it.copy(vehicle = null, system = null, unresolvedItems = emptyList(), unresolvedPage = null) }
        fetch { client, check ->
            val vehicle = withContext(Dispatchers.IO) { client.selectedVehicle(pathIds, check) }
            require(vehicle.variantId == selectedId()) { "Manual vehicle selection changed while the pack was loading" }
            _state.update { it.copy(vehicle = vehicle, system = null, unresolvedItems = emptyList(), unresolvedPage = null) }
            systemChanged()
        }
    }
    fun openSystem(choice: PackSystemChoice) {
        if (_state.value.busy) return
        val vehicle = _state.value.vehicle?.takeIf { it.variantId == selectedId() } ?: return
        try { beforeSystemChange() } catch (e: Exception) { _state.update { it.copy(error = e.message) }; return }
        _state.update { it.copy(system = null, bringUpCandidates = emptyList(), unresolvedItems = emptyList(), unresolvedPage = null) }
        systemChanged()
        fetch { client, check ->
            val system = withContext(Dispatchers.IO) { client.system(vehicle, choice, check) }
            val candidates = withContext(Dispatchers.IO) { client.bringUpCandidates(system, check) }
            require(vehicle.variantId == selectedId()) { "Vehicle selection changed while its system was loading" }
            _state.update { it.copy(system = system, bringUpCandidates = candidates, view = PackView.FEATURES) }
            systemChanged()
        }
    }
    fun unresolved(page: Int) {
        if (_state.value.busy) return
        val system = selectedSystem() ?: return
        val ref = system.unresolvedPages.getOrNull(page) ?: return
        fetch { client, check ->
            val rows = withContext(Dispatchers.IO) { client.unresolved(ref, check) }.map { row ->
                UnresolvedPackItem(row["id"] as String, row["kind"] as? String ?: "unclassified",
                    (row["reasons"] as List<*>).map { it as String })
            }
            require(selectedSystem()?.id == system.id) { "System changed while loading source accounting" }
            _state.update { it.copy(unresolvedPage = page, unresolvedItems = rows) }
        }
    }
    /** True when the pack screen handled Back; false means the host returns to the catalog. */
    fun back(): Boolean {
        loadSequence++; job?.cancel(); _state.update { it.copy(busy = false) }
        val s = _state.value
        when {
            s.view == PackView.FEATURES -> _state.update { it.copy(view = PackView.SYSTEMS, unresolvedItems = emptyList(), unresolvedPage = null) }
            s.view == PackView.SYSTEMS -> return false
            s.preview != null -> _state.update { it.copy(preview = null, error = null) }
            s.trail.isNotEmpty() -> _state.update { it.copy(page = it.trail.last(), trail = it.trail.dropLast(1), error = null) }
            else -> return false
        }
        return true
    }
    fun cancelLoading() { loadSequence++; job?.cancel(); _state.update { it.copy(busy = false) } }
}
