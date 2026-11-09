package com.subscriptionoptimizer.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.OptimizationSummary
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.HistoryState
import com.subscriptionoptimizer.app.data.repository.OptimizeRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryTab(val label: String) { COMPARISONS("Comparisons"), OPTIMIZATIONS("Optimizations") }

/** Saved optimization runs (`GET /optimizations`). */
data class OptimizationHistoryState(
    val items: List<OptimizationSummary> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: RepositoryError? = null,
)

data class HistoryUiState(
    val history: HistoryState = HistoryState(),
    /** A delete or clear that failed; the list is unchanged. */
    val actionError: RepositoryError? = null,
    val deletingId: String? = null,
    val tab: HistoryTab = HistoryTab.COMPARISONS,
    val optimizations: OptimizationHistoryState = OptimizationHistoryState(),
)

class HistoryViewModel(
    private val history: HistoryRepository,
    private val optimize: OptimizeRepository? = null,
) : ViewModel() {

    private val actionError = MutableStateFlow<RepositoryError?>(null)
    private val deletingId = MutableStateFlow<String?>(null)
    private val tab = MutableStateFlow(HistoryTab.COMPARISONS)
    private val optimizations = MutableStateFlow(OptimizationHistoryState())

    val uiState: StateFlow<HistoryUiState> =
        combine(history.state, actionError, deletingId, tab, optimizations) { state, error, deleting, selected, runs ->
            HistoryUiState(state, error, deleting, selected, runs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(history.state.value))

    /** Called each time the screen is shown, so a changed user ID or backend URL takes effect. */
    fun refresh() {
        viewModelScope.launch { history.refresh() }
        refreshOptimizations()
    }

    private fun refreshOptimizations() {
        val repo = optimize ?: return
        optimizations.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            repo.list()
                .onSuccess { items -> optimizations.value = OptimizationHistoryState(items, loaded = true) }
                .onFailure { e ->
                    optimizations.update {
                        it.copy(isLoading = false, error = e as? RepositoryError ?: RepositoryError.MalformedResponse(e.message ?: "Error"))
                    }
                }
        }
    }

    fun selectTab(selected: HistoryTab) {
        tab.value = selected
    }

    fun delete(id: String) {
        if (deletingId.value != null) return
        deletingId.value = id
        actionError.value = null
        viewModelScope.launch {
            history.delete(id).onFailure { actionError.value = it as? RepositoryError }
            deletingId.value = null
        }
    }

    fun deleteOptimization(id: String) {
        val repo = optimize ?: return
        if (deletingId.value != null) return
        deletingId.value = id
        actionError.value = null
        viewModelScope.launch {
            repo.delete(id)
                .onSuccess { optimizations.update { s -> s.copy(items = s.items.filterNot { it.id == id }) } }
                .onFailure { actionError.value = it as? RepositoryError }
            deletingId.value = null
        }
    }

    fun clear() {
        actionError.value = null
        viewModelScope.launch { history.clear().onFailure { actionError.value = it as? RepositoryError } }
    }

    fun dismissError() {
        actionError.value = null
    }

    companion object {
        val Factory = appViewModelFactory { HistoryViewModel(it.historyRepository, it.optimizeRepository) }
    }
}
