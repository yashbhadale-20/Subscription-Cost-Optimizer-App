package com.subscriptionoptimizer.app.ui.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.repository.ComparisonRepository
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.PreferencesRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.domain.CompareValidation
import com.subscriptionoptimizer.app.domain.PlanField
import com.subscriptionoptimizer.app.domain.PlanForm
import com.subscriptionoptimizer.app.domain.UsageLimitEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlanSlot(val label: String) { A("Plan A"), B("Plan B") }

data class PlanEditorState(
    val form: PlanForm = PlanForm(),
    val featureDraft: String = "",
    val featureDraftError: String? = null,
    val errors: Map<PlanField, String> = emptyMap(),
)

/** Why the last submit failed. The form is left untouched so the user can retry or edit. */
data class SubmitError(val title: String, val message: String)

data class CompareUiState(
    val planA: PlanEditorState = PlanEditorState(),
    val planB: PlanEditorState = PlanEditorState(),
    val priority: Priority = Priority.BEST_VALUE,
    val question: String = "",
    val questionError: String? = null,
    /** Errors are shown only after the first submit attempt, then live. */
    val showErrors: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: SubmitError? = null,
) {
    fun editor(slot: PlanSlot) = if (slot == PlanSlot.A) planA else planB
}

sealed interface CompareEvent {
    data class OpenResult(val id: String) : CompareEvent
    data class ShowMessage(val text: String) : CompareEvent
}

class CompareViewModel(
    private val comparisons: ComparisonRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    /** The user's saved default priority, applied until they pick one on the form. */
    private var defaultPriority: Priority? = null
    private var priorityChosen = false

    init {
        viewModelScope.launch {
            // Optional: without it the form keeps its built-in default.
            preferences.defaultPriority().onSuccess { saved ->
                defaultPriority = saved
                if (saved != null && !priorityChosen) _uiState.update { it.copy(priority = saved) }
            }
        }
    }

    private val _events = Channel<CompareEvent>(Channel.BUFFERED)
    val events: Flow<CompareEvent> = _events.receiveAsFlow()

    private var nextLimitId = 1L

    fun updatePlan(slot: PlanSlot, transform: (PlanForm) -> PlanForm) {
        updateEditor(slot) { it.copy(form = transform(it.form)) }
        revalidateIfNeeded()
    }

    fun setPriority(priority: Priority) {
        priorityChosen = true
        _uiState.update { it.copy(priority = priority) }
    }

    fun setQuestion(question: String) {
        _uiState.update { it.copy(question = question) }
        revalidateIfNeeded()
    }

    fun setFeatureDraft(slot: PlanSlot, text: String) =
        updateEditor(slot) { it.copy(featureDraft = text, featureDraftError = null) }

    fun addFeature(slot: PlanSlot) {
        val editor = _uiState.value.editor(slot)
        val error = CompareValidation.newFeatureError(editor.featureDraft, editor.form.features)
        if (error != null) {
            updateEditor(slot) { it.copy(featureDraftError = error) }
            return
        }
        updateEditor(slot) {
            it.copy(
                form = it.form.copy(features = it.form.features + it.featureDraft.trim()),
                featureDraft = "",
                featureDraftError = null,
            )
        }
        revalidateIfNeeded()
    }

    fun removeFeature(slot: PlanSlot, feature: String) =
        updatePlan(slot) { form -> form.copy(features = form.features - feature) }

    fun addUsageLimit(slot: PlanSlot) =
        updatePlan(slot) { form -> form.copy(usageLimits = form.usageLimits + UsageLimitEntry(id = nextLimitId++)) }

    fun updateUsageLimit(slot: PlanSlot, entry: UsageLimitEntry) =
        updatePlan(slot) { form ->
            form.copy(usageLimits = form.usageLimits.map { if (it.id == entry.id) entry else it })
        }

    fun removeUsageLimit(slot: PlanSlot, id: Long) =
        updatePlan(slot) { form -> form.copy(usageLimits = form.usageLimits.filterNot { it.id == id }) }

    fun dismissSubmitError() = _uiState.update { it.copy(submitError = null) }

    fun reset() {
        priorityChosen = false
        _uiState.value = CompareUiState(priority = defaultPriority ?: Priority.BEST_VALUE)
    }

    fun submit() {
        if (_uiState.value.isSubmitting) return
        val state = validate(_uiState.value).copy(showErrors = true, submitError = null)
        _uiState.value = state
        val errorCount = state.planA.errors.size + state.planB.errors.size + (if (state.questionError != null) 1 else 0)
        if (errorCount > 0) {
            val where = listOfNotNull(
                PlanSlot.A.label.takeIf { state.planA.errors.isNotEmpty() },
                PlanSlot.B.label.takeIf { state.planB.errors.isNotEmpty() },
            ).joinToString(" and ")
            val fields = if (errorCount == 1) "1 field" else "$errorCount fields"
            send(CompareEvent.ShowMessage(if (where.isEmpty()) "Check $fields" else "Check $fields in $where"))
            return
        }

        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            val userId = settings.settings.first().userId
            val request = CompareRequest(
                userId = userId,
                question = state.question.trim().ifEmpty { DEFAULT_QUESTION },
                priority = state.priority,
                planA = CompareValidation.toPlanInput(state.planA.form),
                planB = CompareValidation.toPlanInput(state.planB.form),
            )
            comparisons.compare(request)
                .onSuccess { saved ->
                    history.add(saved)
                    _uiState.update { it.copy(isSubmitting = false) }
                    send(CompareEvent.OpenResult(saved.id))
                }
                .onFailure { error ->
                    val submitError = SubmitError(
                        title = (error as? RepositoryError)?.title ?: "Comparison failed",
                        message = error.message ?: "Something went wrong.",
                    )
                    _uiState.update { it.copy(isSubmitting = false, submitError = submitError) }
                }
        }
    }

    private fun updateEditor(slot: PlanSlot, transform: (PlanEditorState) -> PlanEditorState) =
        _uiState.update { state ->
            when (slot) {
                PlanSlot.A -> state.copy(planA = transform(state.planA))
                PlanSlot.B -> state.copy(planB = transform(state.planB))
            }
        }

    private fun revalidateIfNeeded() {
        if (_uiState.value.showErrors) _uiState.update(::validate)
    }

    private fun validate(state: CompareUiState): CompareUiState = state.copy(
        planA = state.planA.copy(errors = CompareValidation.validatePlan(state.planA.form)),
        planB = state.planB.copy(
            errors = CompareValidation.validatePlan(state.planB.form, otherPlanName = state.planA.form.name),
        ),
        questionError = CompareValidation.questionError(state.question),
    )

    private fun send(event: CompareEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        /** Mirrors the backend's default `question`. */
        const val DEFAULT_QUESTION = "Compare these subscription plans and recommend the better option."

        val Factory = appViewModelFactory {
            CompareViewModel(it.comparisonRepository, it.historyRepository, it.settingsRepository, it.preferencesRepository)
        }
    }
}
