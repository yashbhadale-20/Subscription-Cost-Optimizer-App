package com.subscriptionoptimizer.app.ui.optimize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.OptimizeRequest
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.repository.OptimizeRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.domain.OptimizeValidation
import com.subscriptionoptimizer.app.domain.SubscriptionField
import com.subscriptionoptimizer.app.domain.SubscriptionForm
import com.subscriptionoptimizer.app.ui.compare.SubmitError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The add/edit bottom sheet. Errors appear after the first save attempt, then live. */
data class SubscriptionEditor(
    val form: SubscriptionForm,
    val isNew: Boolean,
    val errors: Map<SubscriptionField, String> = emptyMap(),
    val showErrors: Boolean = false,
)

data class OptimizeUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val editor: SubscriptionEditor? = null,
    val budget: String = "",
    val budgetError: String? = null,
    val goal: String = "",
    val goalError: String? = null,
    val savePortfolio: Boolean = true,
    val isLoadingPortfolio: Boolean = false,
    /** Loading the saved list failed; the user can still type subscriptions in. */
    val portfolioError: SubmitError? = null,
    val listError: String? = null,
    val isRunning: Boolean = false,
    val submitError: SubmitError? = null,
)

sealed interface OptimizeEvent {
    data class OpenResult(val id: String) : OptimizeEvent
    data class ShowMessage(val text: String) : OptimizeEvent
}

class OptimizeViewModel(
    private val repository: OptimizeRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OptimizeUiState())
    val uiState: StateFlow<OptimizeUiState> = _uiState.asStateFlow()

    private val _events = Channel<OptimizeEvent>(Channel.BUFFERED)
    val events: Flow<OptimizeEvent> = _events.receiveAsFlow()

    /** Once the user changes the list, a late portfolio load must not overwrite it. */
    private var edited = false

    init {
        loadPortfolio()
    }

    fun loadPortfolio() {
        _uiState.update { it.copy(isLoadingPortfolio = true, portfolioError = null) }
        viewModelScope.launch {
            repository.portfolio()
                .onSuccess { portfolio ->
                    _uiState.update {
                        if (edited) it.copy(isLoadingPortfolio = false)
                        else it.copy(isLoadingPortfolio = false, subscriptions = portfolio.subscriptions)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoadingPortfolio = false, portfolioError = e.toSubmitError("Couldn't load your saved list")) }
                }
        }
    }

    fun startAdd() {
        if (_uiState.value.subscriptions.size >= OptimizeValidation.MAX_SUBSCRIPTIONS) {
            send(OptimizeEvent.ShowMessage("At most ${OptimizeValidation.MAX_SUBSCRIPTIONS} subscriptions"))
            return
        }
        _uiState.update { it.copy(editor = SubscriptionEditor(SubscriptionForm(), isNew = true)) }
    }

    fun startEdit(id: String) {
        val sub = _uiState.value.subscriptions.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(editor = SubscriptionEditor(OptimizeValidation.fromSubscription(sub), isNew = false)) }
    }

    fun updateEditor(transform: (SubscriptionForm) -> SubscriptionForm) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            val form = transform(editor.form)
            state.copy(editor = editor.copy(form = form, errors = if (editor.showErrors) validate(form, state) else emptyMap()))
        }
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    /** Validates the sheet; on success adds or replaces the subscription and closes the sheet. */
    fun saveEditor() {
        val state = _uiState.value
        val editor = state.editor ?: return
        val errors = validate(editor.form, state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(editor = editor.copy(errors = errors, showErrors = true)) }
            return
        }
        val form = if (editor.isNew) {
            editor.form.copy(id = OptimizeValidation.newId(editor.form.name, state.subscriptions.map { it.id }))
        } else {
            editor.form
        }
        val sub = OptimizeValidation.toSubscription(form)
        edited = true
        _uiState.update { s ->
            val list = if (editor.isNew) s.subscriptions + sub else s.subscriptions.map { if (it.id == sub.id) sub else it }
            s.copy(subscriptions = list, editor = null, listError = null)
        }
    }

    fun delete(id: String) {
        edited = true
        _uiState.update { s -> s.copy(subscriptions = s.subscriptions.filterNot { it.id == id }) }
    }

    fun setBudget(text: String) = _uiState.update {
        it.copy(budget = text, budgetError = if (it.budgetError != null) OptimizeValidation.budgetError(text) else null)
    }

    fun setGoal(text: String) = _uiState.update {
        it.copy(goal = text, goalError = if (it.goalError != null) OptimizeValidation.goalError(text) else null)
    }

    fun setSavePortfolio(save: Boolean) = _uiState.update { it.copy(savePortfolio = save) }

    fun dismissSubmitError() = _uiState.update { it.copy(submitError = null) }

    fun run() {
        val state = _uiState.value
        if (state.isRunning) return
        val budgetError = OptimizeValidation.budgetError(state.budget)
        val goalError = OptimizeValidation.goalError(state.goal)
        val listError = OptimizeValidation.listError(state.subscriptions)
        _uiState.update { it.copy(budgetError = budgetError, goalError = goalError, listError = listError, submitError = null) }
        val problem = listError ?: budgetError?.let { "Check the budget" } ?: goalError?.let { "Check the goal" }
        if (problem != null) {
            send(OptimizeEvent.ShowMessage(problem))
            return
        }

        _uiState.update { it.copy(isRunning = true) }
        viewModelScope.launch {
            val request = OptimizeRequest(
                userId = settings.settings.first().userId,
                goal = state.goal.trim().ifEmpty { null },
                monthlyBudget = OptimizeValidation.parseBudget(state.budget),
                subscriptions = state.subscriptions,
                savePortfolio = state.savePortfolio,
            )
            repository.optimize(request)
                .onSuccess { saved ->
                    _uiState.update { it.copy(isRunning = false) }
                    send(OptimizeEvent.OpenResult(saved.id))
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isRunning = false, submitError = e.toSubmitError("Optimization failed")) }
                }
        }
    }

    private fun validate(form: SubscriptionForm, state: OptimizeUiState) =
        OptimizeValidation.validate(form, otherNames = state.subscriptions.filterNot { it.id == form.id }.map { it.name })

    private fun Throwable.toSubmitError(fallbackTitle: String) =
        SubmitError((this as? RepositoryError)?.title ?: fallbackTitle, message ?: "Something went wrong.")

    private fun send(event: OptimizeEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        val Factory = appViewModelFactory { OptimizeViewModel(it.optimizeRepository, it.settingsRepository) }
    }
}
