package com.subscriptionoptimizer.app.ui.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.PolicyAnswer
import com.subscriptionoptimizer.app.data.model.PolicyIndexStatus
import com.subscriptionoptimizer.app.data.repository.PolicyRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.ui.common.PolicyEvidenceList
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.FormTextField
import com.subscriptionoptimizer.app.ui.designsystem.components.LoadingButton
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PolicyUiState(
    val question: String = "",
    val isAsking: Boolean = false,
    val answer: PolicyAnswer? = null,
    val error: RepositoryError? = null,
    val indexStatus: PolicyIndexStatus? = null,
) {
    val canAsk: Boolean get() = question.isNotBlank() && question.length <= MAX_QUESTION && !isAsking

    companion object {
        /** Mirrors the backend's `PolicyAskRequest.question` limit. */
        const val MAX_QUESTION = 500
    }
}

class PolicyViewModel(private val policy: PolicyRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PolicyUiState())
    val uiState: StateFlow<PolicyUiState> = _uiState.asStateFlow()

    val suggestions = listOf(
        "Is there a refund deadline?",
        "Are taxes included in the price?",
        "How should I compare an annual plan with a monthly one?",
        "What happens after an introductory offer ends?",
    )

    init {
        // Informational only; a failure here doesn't block asking.
        viewModelScope.launch { policy.status().onSuccess { s -> _uiState.update { it.copy(indexStatus = s) } } }
    }

    fun onQuestionChange(text: String) = _uiState.update { it.copy(question = text) }

    fun ask(text: String = _uiState.value.question) {
        val question = text.trim()
        if (question.isEmpty() || _uiState.value.isAsking) return
        // The question stays in the field whatever happens, so it can be retried or edited.
        _uiState.update { it.copy(question = question, isAsking = true, error = null) }
        viewModelScope.launch {
            policy.ask(question)
                .onSuccess { answer -> _uiState.update { it.copy(isAsking = false, answer = answer) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isAsking = false, error = e as? RepositoryError ?: RepositoryError.MalformedResponse(e.message ?: "Error"))
                    }
                }
        }
    }

    companion object {
        val Factory = appViewModelFactory { PolicyViewModel(it.policyRepository) }
    }
}

@Composable
fun PolicyRoute(onBack: () -> Unit, viewModel: PolicyViewModel = viewModel(factory = PolicyViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PolicyScreen(state, viewModel.suggestions, onBack, viewModel::onQuestionChange, viewModel::ask)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PolicyScreen(
    state: PolicyUiState,
    suggestions: List<String>,
    onBack: () -> Unit,
    onQuestionChange: (String) -> Unit,
    onAsk: (String) -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = "Policy questions", onBack = onBack) }) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    "Answers come only from the subscription policy document. If it doesn't cover your " +
                        "question, you'll be told so instead of getting a guess.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.indexStatus?.takeIf { it.staleChunks.isNotEmpty() || it.missingChunks.isNotEmpty() }?.let { s ->
                    ErrorBanner(
                        title = "Policy index is out of date",
                        message = "The server's search index holds older text for section(s) " +
                            (s.staleChunks + s.missingChunks).sorted().joinToString() +
                            ". Evidence from those sections is labelled \"Older policy text\".",
                        tone = Tone.NEUTRAL,
                        icon = Icons.Outlined.Info,
                    )
                }
                FormTextField(
                    value = state.question,
                    onValueChange = onQuestionChange,
                    label = "Your question",
                    placeholder = "e.g. Is there a refund deadline?",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                    maxLength = PolicyUiState.MAX_QUESTION,
                    enabled = !state.isAsking,
                )
                LoadingButton(
                    text = "Ask the policy",
                    loadingText = "Searching the policy…",
                    loading = state.isAsking,
                    onClick = { onAsk(state.question) },
                    enabled = state.canAsk,
                    icon = Icons.Outlined.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.answer == null && !state.isAsking) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        suggestions.forEach { s ->
                            SuggestionChip(onClick = { onAsk(s) }, label = { Text(s) })
                        }
                    }
                }
                state.error?.let { error ->
                    ErrorBanner(
                        title = error.title,
                        message = error.message.orEmpty() + "\nYour question is kept.",
                        actionLabel = "Try again",
                        onAction = { onAsk(state.question) },
                    )
                }
                state.answer?.let { AnswerSection(it) }
                Spacer(Modifier.size(Spacing.lg))
            }
        }
    }
}

@Composable
private fun AnswerSection(answer: PolicyAnswer) {
    SectionCard(
        title = if (answer.answeredFromPolicy) "Answer" else "Not covered by the policy",
        icon = if (answer.answeredFromPolicy) Icons.Outlined.QuestionAnswer else Icons.Outlined.Info,
    ) {
        Text(answer.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(answer.answer, style = MaterialTheme.typography.bodyMedium)
        if (answer.answeredFromPolicy && answer.citedChunks.isEmpty()) {
            Text(
                "The answer doesn't cite a specific section. Check the evidence below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionCard(title = "Evidence", icon = Icons.Outlined.Policy) {
        PolicyEvidenceList(
            answer.evidence,
            answer.unsupportedCitations,
            emptyText = "No section of the policy was relevant enough, so the model wasn't asked.",
        )
        Text(
            "Steps: " + answer.traceEvents.joinToString(" → ") { "${it.stage.replace('_', ' ')} (${it.status})" } +
                String.format(java.util.Locale.US, " · %.1fs", answer.elapsedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Policy – empty", showBackground = true)
@Composable
private fun PolicyEmptyPreview() {
    SubscriptionOptimizerTheme {
        PolicyScreen(PolicyUiState(), listOf("Is there a refund deadline?"), {}, {}, {})
    }
}
