package com.bod3.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bod3.app.android.ReportSharer
import com.bod3.app.android.ToyotaSourceLibrary
import com.bod3.app.source.SourceLocation
import com.bod3.app.source.SourcePage
import com.bod3.app.source.SourceRequest
import com.bod3.app.source.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An isolated source browser; it cannot change the active catalog or send anything. */
data class ToyotaSourceState(
    val request: SourceRequest = SourceRequest(),
    val page: SourcePage? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val canGoBack: Boolean = false,
)

class ToyotaSourceViewModel(application: Application) : AndroidViewModel(application) {
    private val library = ToyotaSourceLibrary(application)
    private val history = mutableListOf<SourceRequest>()
    private var job: Job? = null
    private val _state = MutableStateFlow(ToyotaSourceState())
    val state = _state.asStateFlow()

    fun open(query: String?) {
        history.clear()
        load(if (query.isNullOrBlank()) SourceRequest() else SourceRequest(SourceLocation.Menu(), query.take(SourceRequest.MAX_QUERY)))
    }

    fun navigate(location: SourceLocation) {
        history += _state.value.request
        if (history.size > 128) history.removeAt(0)
        load(SourceRequest(location))
    }

    fun back(): Boolean {
        if (history.isEmpty()) return false
        load(history.removeAt(history.lastIndex))
        return true
    }

    fun search(query: String) = load(_state.value.request.copy(query = query.take(SourceRequest.MAX_QUERY), offset = 0), debounce = true)

    fun page(next: Boolean) {
        val state = _state.value
        val page = state.page ?: return
        if (state.loading || next && !page.hasNext) return
        val offset = (state.request.offset + if (next) page.step else -page.step).coerceIn(0, SourceRequest.MAX_OFFSET)
        load(state.request.copy(offset = offset))
    }

    fun retry() = load(_state.value.request)
    fun suspendLoading() { job?.cancel() }

    private fun load(request: SourceRequest, debounce: Boolean = false) {
        job?.cancel()
        val previous = _state.value.page.takeIf { _state.value.request.location == request.location }
        _state.value = ToyotaSourceState(request, page = previous, loading = true, canGoBack = history.isNotEmpty())
        job = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val page = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    library.load(request) { context.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                _state.value = ToyotaSourceState(request, page, canGoBack = history.isNotEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.value = ToyotaSourceState(request, error = error.message ?: "Unable to read the bundled source library", canGoBack = history.isNotEmpty())
            }
        }
    }

    suspend fun writePage(): File {
        val state = _state.value
        val page = state.page ?: error("No source page is loaded")
        return withContext(Dispatchers.IO) {
            ReportSharer.write(getApplication(), "toyota-source-page.txt", page.report(state.request))
        }
    }
}
