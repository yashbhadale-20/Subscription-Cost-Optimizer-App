package com.subscriptionoptimizer.app.ui.results

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.domain.ProsCons
import com.subscriptionoptimizer.app.domain.ProsConsDeriver
import com.subscriptionoptimizer.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ResultsUiState {
    data object Loading : ResultsUiState

    data class Loaded(
        val saved: SavedComparison,
        val prosConsA: ProsCons,
        val prosConsB: ProsCons,
    ) : ResultsUiState

    /** Deleted, or saved under a different user or backend. */
    data object NotFound : ResultsUiState

    data class Failed(val title: String, val message: String) : ResultsUiState
}

class ResultsViewModel(
    savedStateHandle: SavedStateHandle,
    private val history: HistoryRepository,
) : ViewModel() {

    private val resultId: String? = savedStateHandle[Routes.RESULT_ID_ARG]

    private val _uiState = MutableStateFlow<ResultsUiState>(ResultsUiState.Loading)
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = resultId ?: run {
            _uiState.value = ResultsUiState.NotFound
            return
        }
        _uiState.value = ResultsUiState.Loading
        viewModelScope.launch {
            _uiState.value = history.get(id).fold(
                onSuccess = { saved ->
                    val (a, b) = ProsConsDeriver.derive(saved.result)
                    ResultsUiState.Loaded(saved, a, b)
                },
                onFailure = { e ->
                    when (e) {
                        is RepositoryError.NotFound -> ResultsUiState.NotFound
                        is RepositoryError -> ResultsUiState.Failed(e.title, e.message.orEmpty())
                        else -> ResultsUiState.Failed("Couldn't load this comparison", e.message.orEmpty())
                    }
                },
            )
        }
    }

    companion object {
        val Factory = appViewModelFactory { ResultsViewModel(createSavedStateHandle(), it.historyRepository) }
    }
}
