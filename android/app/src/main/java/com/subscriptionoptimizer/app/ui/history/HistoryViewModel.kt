package com.subscriptionoptimizer.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.HistoryState
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val history: HistoryState = HistoryState(),
    /** A delete or clear that failed; the list is unchanged. */
    val actionError: RepositoryError? = null,
    val deletingId: String? = null,
)

class HistoryViewModel(private val history: HistoryRepository) : ViewModel() {

    private val actionError = MutableStateFlow<RepositoryError?>(null)
    private val deletingId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HistoryUiState> =
        combine(history.state, actionError, deletingId) { state, error, deleting ->
            HistoryUiState(state, error, deleting)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(history.state.value))

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { history.refresh() }
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

    fun clear() {
        actionError.value = null
        viewModelScope.launch { history.clear().onFailure { actionError.value = it as? RepositoryError } }
    }

    fun dismissError() {
        actionError.value = null
    }

    companion object {
        val Factory = appViewModelFactory { HistoryViewModel(it.historyRepository) }
    }
}
