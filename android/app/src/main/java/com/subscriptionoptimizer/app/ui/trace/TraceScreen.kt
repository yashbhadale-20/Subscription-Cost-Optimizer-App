package com.subscriptionoptimizer.app.ui.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.TraceEvent
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.EmptyState
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.KeyValueRow
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.NumberStyles
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme
import com.subscriptionoptimizer.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface TraceUiState {
    data object Loading : TraceUiState
    data class Loaded(val events: List<TraceEvent>) : TraceUiState
    data object NotFound : TraceUiState
    data class Failed(val title: String, val message: String) : TraceUiState
}

class TraceViewModel(savedStateHandle: SavedStateHandle, private val history: HistoryRepository) : ViewModel() {
    private val id: String? = savedStateHandle[Routes.RESULT_ID_ARG]
    private val _uiState = MutableStateFlow<TraceUiState>(TraceUiState.Loading)
    val uiState: StateFlow<TraceUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = id ?: run {
            _uiState.value = TraceUiState.NotFound
            return
        }
        _uiState.value = TraceUiState.Loading
        viewModelScope.launch {
            _uiState.value = history.trace(id).fold(
                onSuccess = { TraceUiState.Loaded(it) },
                onFailure = { e ->
                    if (e is RepositoryError.NotFound) TraceUiState.NotFound
                    else TraceUiState.Failed((e as? RepositoryError)?.title ?: "Couldn't load the trace", e.message.orEmpty())
                },
            )
        }
    }

    companion object {
        val Factory = appViewModelFactory { TraceViewModel(createSavedStateHandle(), it.historyRepository) }
    }
}

@Composable
fun TraceRoute(onBack: () -> Unit, viewModel: TraceViewModel = viewModel(factory = TraceViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TraceScreen(state, onBack, onRetry = viewModel::load)
}

@Composable
fun TraceScreen(state: TraceUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(topBar = { AppTopBar(title = "Execution trace", onBack = onBack) }) { padding ->
        CenteredContent(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                TraceUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                TraceUiState.NotFound -> EmptyState(
                    icon = Icons.Outlined.AccountTree,
                    title = "Trace not available",
                    message = "The comparison was deleted or belongs to another user.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )
                is TraceUiState.Failed -> Column(Modifier.padding(Spacing.gutter)) {
                    ErrorBanner(title = state.title, message = state.message, actionLabel = "Try again", onAction = onRetry)
                }
                is TraceUiState.Loaded -> TraceList(state.events)
            }
        }
    }
}

@Composable
private fun TraceList(events: List<TraceEvent>) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "summary") { TraceSummary(events) }
        if (events.isEmpty()) {
            item(key = "empty") {
                Text(
                    "This comparison was saved without trace events (it predates structured traces).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(events, key = { it.step }) { TraceStep(it) }
    }
}

@Composable
private fun TraceSummary(events: List<TraceEvent>) {
    val warnings = events.count { it.status == "warning" || it.status == "failed" }
    val errors = events.count { it.status == "error" }
    val totalMs = events.mapNotNull { it.durationMs }.sum()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            "${events.size} step(s) recorded by the backend · ${String.format(Locale.US, "%.1f", totalMs / 1000)}s timed",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            when {
                errors > 0 -> "$errors step(s) failed. Their results were not replaced with made-up output."
                warnings > 0 -> "$warnings check(s) raised a warning."
                else -> "Every step and check completed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "\"function\" steps are code the backend called directly; \"llm\" steps are model calls; " +
                "\"tool_call\" appears only when the model itself called a tool. Prompts and model reasoning are not recorded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceStep(event: TraceEvent) {
    val (tone, icon) = statusStyle(event.status)
    AppCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Top) {
            Text("${event.step}".padStart(2), style = NumberStyles.mono, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = Spacing.sm).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stageTitle(event.stage), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatusPill(event.status, tone = tone, icon = icon)
                    StatusPill(event.kind, tone = Tone.NEUTRAL)
                    event.durationMs?.let { StatusPill(String.format(Locale.US, "%.0f ms", it), tone = Tone.NEUTRAL) }
                }
                SelectionContainer { Text(event.name, style = NumberStyles.mono) }
                Text(event.detail, style = MaterialTheme.typography.bodySmall)
                event.data.filterValues { it.isNotBlank() && it != "[]" && it != "{}" && it != "null" }
                    .forEach { (k, v) -> KeyValueRow(k.replace('_', ' '), v) }
            }
        }
    }
}

/** "ai_explanation" -> "AI explanation", "input_guardrail" -> "Input guardrail". */
private fun stageTitle(stage: String): String =
    stage.split('_').joinToString(" ").replaceFirstChar { it.uppercase() }.replace(Regex("^Ai\\b"), "AI")

private fun statusStyle(status: String): Pair<Tone, ImageVector> = when (status) {
    "ok", "passed" -> Tone.SAVINGS to Icons.Outlined.CheckCircle
    "warning", "failed" -> Tone.WARNING to Icons.Outlined.WarningAmber
    "error" -> Tone.ERROR to Icons.Outlined.ErrorOutline
    else -> Tone.NEUTRAL to Icons.Outlined.RemoveCircleOutline
}

@Preview(name = "Trace", showBackground = true, heightDp = 900)
@Composable
private fun TracePreview() {
    SubscriptionOptimizerTheme {
        TraceScreen(
            TraceUiState.Loaded(
                listOf(
                    TraceEvent(1, "input_guardrail", "check", "ta2_subscription_optimizer.guardrail", "passed", "Looks okay.", 0.1),
                    TraceEvent(2, "ai_explanation", "llm", "review_agent (qwen2.5:3b)", "error", "TimeoutError", null),
                ),
            ),
            onBack = {}, onRetry = {},
        )
    }
}
