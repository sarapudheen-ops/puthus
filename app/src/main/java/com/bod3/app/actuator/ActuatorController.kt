package com.bod3.app.actuator

import com.bod3.app.audit.AuditFormatter
import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditRecord
import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the controller needs from the live connection. Null when not connected. */
data class ActuatorEnvironment(
    val session: Session,
    val probe: VehicleStateProbe,
    val vehicle: VehicleContext,
)

data class ActuatorUiState(
    val entries: List<ActuatorEntry> = emptyList(),
    /** Problems found in bundled packs or the saved user recipes. */
    val packErrors: List<String> = emptyList(),
    val selectedId: String? = null,
    val checking: Boolean = false,
    val preconditions: PreconditionReport? = null,
    /** The safety-notes confirmation dialog is open. */
    val confirming: Boolean = false,
    val overrideApplicability: Boolean = false,
    val phase: RunPhase? = null,
    val lastResult: RunResult? = null,
    val message: String? = null,
    val importErrors: List<String> = emptyList(),
    val history: List<AuditRecord> = emptyList(),
) {
    val selected: ActuatorEntry? get() = entries.firstOrNull { it.recipe.id == selectedId }
    val running: Boolean get() = phase != null && phase !is RunPhase.Finished
}

/**
 * Screen logic for bidirectional tests, independent of Android: recipe list, precondition check,
 * confirmation, run with live status, stop, import/export and the run history.
 */
class ActuatorController(
    private val scope: CoroutineScope,
    private val library: ActuatorLibrary,
    private val audit: AuditStore,
    private val expertMode: () -> Boolean,
    private val environment: () -> ActuatorEnvironment?,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    bundledErrors: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(ActuatorUiState(packErrors = bundledErrors))
    val state: StateFlow<ActuatorUiState> = _state.asStateFlow()

    private var control: RunControl? = null
    private var job: Job? = null
    private var checkJob: Job? = null

    fun refresh() {
        _state.update {
            it.copy(
                entries = library.entries(),
                history = audit.records().filter { r -> r.kind == AuditKind.ACTUATOR_TEST }.takeLast(50).reversed(),
            )
        }
    }

    fun select(id: String?) {
        if (_state.value.running) return
        _state.update {
            it.copy(selectedId = id, preconditions = null, confirming = false, overrideApplicability = false, lastResult = null, phase = null, message = null)
        }
    }

    fun checkPreconditions() {
        val entry = _state.value.selected ?: return
        val env = environment() ?: return setMessage("Connect to the vehicle first")
        if (_state.value.running || checkJob?.isActive == true) return
        _state.update { it.copy(checking = true) }
        checkJob = scope.launch {
            val report = try {
                PreconditionChecker.check(entry.recipe, env.probe, env.vehicle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setMessage("Could not check preconditions: ${e.message}")
                null
            }
            _state.update { it.copy(checking = false, preconditions = report ?: it.preconditions) }
        }
    }

    /** Opens the safety confirmation; refuses early with a clear message when the test cannot run. */
    fun requestStart() {
        val s = _state.value
        val entry = s.selected ?: return
        when {
            s.running -> return
            !expertMode() -> return setMessage("Turn on Expert mode in Settings to run bidirectional tests")
            environment() == null -> return setMessage("Connect to the vehicle first")
            entry.recipe.problems().isNotEmpty() -> return setMessage("This recipe is invalid: ${entry.recipe.problems().first()}")
        }
        _state.update { it.copy(confirming = true, message = null) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirming = false) }

    fun setOverrideApplicability(override: Boolean) = _state.update { it.copy(overrideApplicability = override) }

    /** The user read the safety notes and confirmed: start the run. */
    fun confirmAndStart() {
        val s = _state.value
        val entry = s.selected ?: return
        if (!s.confirming || s.running) return
        val env = environment() ?: return setMessage("Connect to the vehicle first").also { cancelConfirmation() }
        val recipe = entry.recipe
        val confirmation = ActuatorConfirmation.forRecipe(recipe, clock)
        val runControl = RunControl()
        control = runControl
        _state.update { it.copy(confirming = false, phase = RunPhase.CheckingPreconditions, lastResult = null, message = null) }
        log("Actuator test ${recipe.id} started on ${recipe.module.display}")
        val runner = ActuatorRunner(env.session, env.probe, expertMode, audit, clock)
        job = scope.launch {
            try {
                val result = runner.run(recipe, confirmation, runControl, env.vehicle, s.overrideApplicability) { phase ->
                    _state.update { it.copy(phase = phase, preconditions = (phase as? RunPhase.Finished)?.result?.preconditions ?: it.preconditions) }
                }
                log("Actuator test ${recipe.id}: ${result.outcome.label} – ${result.message}")
                _state.update { it.copy(lastResult = result, phase = RunPhase.Finished(result)) }
            } finally {
                control = null
                refresh()
            }
        }
    }

    /** Always available while a run is active; the runner sends the stop request at once. */
    fun stop() {
        control?.requestStop()
    }

    /** Leaving the screen stops a running test. */
    fun close() {
        stop()
        _state.update { it.copy(confirming = false) }
    }

    fun importJson(text: String) {
        val result = ActuatorRecipeCodec.decode(text)
        val pack = result.pack
        if (pack == null) {
            _state.update { it.copy(importErrors = result.errors.ifEmpty { listOf("Unknown problem") }, message = null) }
            return
        }
        try {
            val imported = library.import(pack)
            _state.update {
                it.copy(importErrors = emptyList(), message = "Imported ${imported.added.size} new and ${imported.replaced.size} replaced recipe(s) from \"${pack.name}\"")
            }
            log("Imported actuator recipes from ${pack.name}: ${pack.recipes.joinToString { r -> r.id }}")
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(importErrors = e.message.orEmpty().lines()) }
        }
        refresh()
    }

    fun exportJson(recipeId: String? = null): String {
        val recipes = recipeId?.let { id -> listOfNotNull(library.entry(id)?.recipe) } ?: library.userPack().recipes
        return library.export(recipes, if (recipeId != null) recipes.firstOrNull()?.name ?: "Recipe" else ActuatorLibrary.MY_RECIPES)
    }

    fun deleteUserRecipe(id: String) {
        if (_state.value.running) return
        library.deleteUserRecipe(id)
        if (_state.value.selectedId == id && library.entry(id) == null) select(null)
        refresh()
        setMessage("Deleted recipe $id")
    }

    fun auditText(): String = AuditFormatter.formatAll(audit.records())

    fun dismissMessage() = _state.update { it.copy(message = null, importErrors = emptyList()) }

    private fun setMessage(text: String) = _state.update { it.copy(message = text) }
}
