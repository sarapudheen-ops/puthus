package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorEnvironment
import com.bod3.app.audit.AuditFormatter
import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditRecord
import com.bod3.app.audit.AuditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The JSON editor for adding or changing a user recipe at runtime. */
data class CodingEditorState(val text: String, val editingId: String? = null, val errors: List<String> = emptyList())

data class CodingUiState(
    val entries: List<CodingEntry> = emptyList(),
    val packErrors: List<String> = emptyList(),
    val selectedId: String? = null,
    val reading: CodingReading? = null,
    val readingInProgress: Boolean = false,
    val readError: String? = null,
    val overrideVersion: Boolean = false,
    val newValue: Long? = null,
    val valueError: String? = null,
    val diff: CodingDiff? = null,
    val confirming: Boolean = false,
    val step: CodingStep? = null,
    val lastResult: CodingResult? = null,
    val backups: List<CodingBackup> = emptyList(),
    /** Backup waiting for the rollback confirmation (from the backup list). */
    val rollbackCandidate: CodingBackup? = null,
    val message: String? = null,
    val importErrors: List<String> = emptyList(),
    val editor: CodingEditorState? = null,
    val history: List<AuditRecord> = emptyList(),
) {
    val selected: CodingEntry? get() = entries.firstOrNull { it.recipe.id == selectedId }
    val busy: Boolean get() = readingInProgress || step != null
    val backupsForSelected: List<CodingBackup> get() = backups.filter { it.recipeId == selectedId }
}

/** Screen logic for coding: recipe list, read + backup, value choice and diff, confirmed write, rollback. */
class CodingController(
    private val scope: CoroutineScope,
    private val library: CodingLibrary,
    private val audit: AuditStore,
    private val backups: CodingBackupStore,
    private val expertMode: () -> Boolean,
    private val environment: () -> ActuatorEnvironment?,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    bundledErrors: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(CodingUiState(packErrors = bundledErrors))
    val state: StateFlow<CodingUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() = _state.update {
        it.copy(
            entries = library.entries(),
            backups = backups.all().reversed(),
            history = audit.records().filter { r -> r.kind != AuditKind.ACTUATOR_TEST }.takeLast(50).reversed(),
        )
    }

    fun select(id: String?) {
        if (_state.value.busy) return
        _state.update {
            it.copy(selectedId = id, reading = null, readError = null, overrideVersion = false, newValue = null, valueError = null, diff = null, confirming = false, lastResult = null, message = null)
        }
    }

    fun setOverrideVersion(override: Boolean) = _state.update { it.copy(overrideVersion = override) }

    private fun engine(env: ActuatorEnvironment) = CodingEngine(env.session, env.probe, expertMode, audit, backups, clock)

    /** Steps 1-2: version check, read the current value, save the backup. */
    fun read() {
        val entry = _state.value.selected ?: return
        val env = environment() ?: return setMessage("Connect to the vehicle first")
        if (_state.value.busy) return
        _state.update { it.copy(readingInProgress = true, readError = null, reading = null, diff = null, newValue = null, lastResult = null) }
        job = scope.launch {
            val result = try {
                engine(env).read(entry.recipe, env.vehicle, _state.value.overrideVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReadResult.Refused("Read failed: ${e.message}")
            }
            when (result) {
                is ReadResult.Ready -> {
                    log("Coding ${entry.recipe.id}: read ${entry.recipe.describe(result.reading.value)}, backup ${result.reading.backup.id}")
                    _state.update { it.copy(readingInProgress = false, reading = result.reading) }
                }
                is ReadResult.Refused -> _state.update { it.copy(readingInProgress = false, readError = result.message) }
            }
            refresh()
        }
    }

    /** Step 3: choose the new value and see the diff. */
    fun setNewValue(value: Long?) {
        val reading = _state.value.reading ?: return
        if (value == null) return _state.update { it.copy(newValue = null, diff = null, valueError = null) }
        val problem = reading.recipe.valueProblem(value)
        _state.update {
            it.copy(
                newValue = value, valueError = problem,
                diff = if (problem == null) CodingEngine.diffOf(reading, value) else null,
            )
        }
    }

    fun requestWrite() {
        val s = _state.value
        when {
            s.busy || s.reading == null || s.diff == null -> return
            !expertMode() -> return setMessage("Turn on Expert mode in Settings to write coding")
            !s.diff.isChange -> return setMessage("The module already has this value")
        }
        _state.update { it.copy(confirming = true, message = null) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirming = false) }

    /** Steps 4-5: the user confirmed the diff. */
    fun confirmWrite() {
        val s = _state.value
        val reading = s.reading ?: return
        val value = s.newValue ?: return
        if (!s.confirming || s.busy) return
        val env = environment() ?: return setMessage("Connect to the vehicle first").also { cancelConfirmation() }
        val confirmation = CodingConfirmation.forWrite(reading, value, clock)
        _state.update { it.copy(confirming = false, step = CodingStep.CHECKS, lastResult = null) }
        job = scope.launch {
            try {
                val result = engine(env).write(reading, value, confirmation, env.vehicle) { step -> _state.update { it.copy(step = step) } }
                log("Coding ${reading.recipe.id}: ${result.outcome.label} – ${result.message}")
                _state.update { it.copy(lastResult = result, reading = if (result.outcome == CodingOutcome.VERIFIED) null else it.reading) }
            } finally {
                _state.update { it.copy(step = null) }
                refresh()
            }
        }
    }

    /** One tap from the result card: write the backup of the last write back. */
    fun rollbackLast() {
        val backupId = _state.value.lastResult?.backupId ?: return
        val backup = backups.get(backupId) ?: return setMessage("The backup no longer exists")
        runRollback(backup)
    }

    fun requestRollback(backupId: String) {
        val backup = backups.get(backupId) ?: return
        _state.update { it.copy(rollbackCandidate = backup) }
    }

    fun cancelRollback() = _state.update { it.copy(rollbackCandidate = null) }

    fun confirmRollback() {
        val backup = _state.value.rollbackCandidate ?: return
        _state.update { it.copy(rollbackCandidate = null) }
        runRollback(backup)
    }

    private fun runRollback(backup: CodingBackup) {
        if (_state.value.busy) return
        if (!expertMode()) return setMessage("Turn on Expert mode in Settings to roll back")
        val env = environment() ?: return setMessage("Connect to the vehicle first")
        val confirmation = CodingConfirmation.forRollback(backup, clock)
        _state.update { it.copy(step = CodingStep.CHECKS) }
        job = scope.launch {
            try {
                val result = engine(env).rollback(backup, confirmation, env.vehicle) { step -> _state.update { it.copy(step = step) } }
                log("Coding rollback ${backup.id}: ${result.outcome.label} – ${result.message}")
                _state.update { it.copy(lastResult = result, reading = null, diff = null, newValue = null) }
            } finally {
                _state.update { it.copy(step = null) }
                refresh()
            }
        }
    }

    // ---- recipes: import / export / editor ----

    fun importJson(text: String) {
        val result = CodingRecipeCodec.decode(text)
        val pack = result.pack ?: return _state.update { it.copy(importErrors = result.errors.ifEmpty { listOf("Unknown problem") }, message = null) }
        try {
            val imported = library.import(pack)
            _state.update { it.copy(importErrors = emptyList(), message = "Imported ${imported.added.size} new and ${imported.replaced.size} replaced coding recipe(s)") }
            log("Imported coding recipes: ${pack.recipes.joinToString { it.id }}")
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(importErrors = e.message.orEmpty().lines()) }
        }
        refresh()
    }

    fun exportJson(recipeId: String? = null): String {
        val recipes = recipeId?.let { id -> listOfNotNull(library.entry(id)?.recipe) } ?: library.userPack().recipes
        return library.export(recipes, if (recipeId != null) recipes.firstOrNull()?.name ?: "Recipe" else CodingLibrary.MY_RECIPES)
    }

    fun deleteUserRecipe(id: String) {
        if (_state.value.busy) return
        library.deleteUserRecipe(id)
        if (_state.value.selectedId == id && library.entry(id) == null) select(null)
        refresh()
        setMessage("Deleted recipe $id")
    }

    /** Opens the editor with [id]'s JSON (a copy, for bundled recipes) or a template for a new recipe. */
    fun openEditor(id: String? = null) {
        val entry = id?.let { library.entry(it) }
        val text = entry?.let { CodingRecipeCodec.encodeRecipeJson(if (it.isUser) it.recipe else it.recipe.copy(id = it.recipe.id + "-mine", verified = false)) }
            ?: TEMPLATE
        _state.update { it.copy(editor = CodingEditorState(JsonPretty.format(text), editingId = entry?.takeIf { e -> e.isUser }?.recipe?.id)) }
    }

    fun updateEditor(text: String) = _state.update { s -> s.copy(editor = s.editor?.copy(text = text, errors = emptyList())) }

    /** Validates the editor text and saves it as a user recipe (returns its id); problems are listed with their paths. */
    fun saveEditor(): String? {
        val editor = _state.value.editor ?: return null
        val result = CodingRecipeCodec.decode(editor.text)
        val recipe = result.pack?.recipes?.singleOrNull()
        if (recipe == null) {
            _state.update { s -> s.copy(editor = editor.copy(errors = result.errors.ifEmpty { listOf("The editor must contain exactly one recipe object") })) }
            return null
        }
        return try {
            library.save(recipe, editor.editingId)
            _state.update { it.copy(editor = null, message = "Saved recipe ${recipe.id}") }
            refresh()
            recipe.id
        } catch (e: IllegalArgumentException) {
            _state.update { s -> s.copy(editor = editor.copy(errors = e.message.orEmpty().lines())) }
            null
        }
    }

    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun auditText(): String = AuditFormatter.formatAll(audit.records())

    fun dismissMessage() = _state.update { it.copy(message = null, importErrors = emptyList()) }

    private fun setMessage(text: String) = _state.update { it.copy(message = text) }

    companion object {
        val TEMPLATE: String = """
            {"id":"my-recipe","name":"My coding change","description":"What it does",
             "module":{"name":"Central electronics","address":"09","requestId":"70E","responseId":"778"},
             "method":"long_coding","did":"0600","byte":0,"mask":"01",
             "values":[{"value":0,"label":"Off"},{"value":1,"label":"On"}],
             "session":"03","preconditions":{"minVoltage":12.0,"allowEngineRunning":false},
             "applicability":{"makes":[],"models":[],"partNumbers":[],"softwareVersions":[]},
             "source":"","notes":"","verified":false}
        """.trimIndent()
    }
}

/** Minimal JSON pretty-printer for the editor (re-serialises through MiniJson). */
object JsonPretty {
    fun format(json: String): String {
        val value = runCatching { com.bod3.app.profile.MiniJson.parse(json) }.getOrElse { return json }
        return StringBuilder().also { write(it, value, 0) }.toString()
    }

    private fun write(out: StringBuilder, value: Any?, indent: Int) {
        val pad = "  ".repeat(indent + 1)
        when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append(pad).append(com.bod3.app.profile.JsonWriter.write(k.toString())).append(": ")
                    write(out, v, indent + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append("  ".repeat(indent)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty() || value.all { it !is Map<*, *> && it !is List<*> }) {
                    out.append(com.bod3.app.profile.JsonWriter.write(value))
                    return
                }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append(pad)
                    write(out, v, indent + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append("  ".repeat(indent)).append(']')
            }
            else -> out.append(com.bod3.app.profile.JsonWriter.write(value))
        }
    }
}
