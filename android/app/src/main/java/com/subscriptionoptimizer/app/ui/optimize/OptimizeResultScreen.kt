package com.subscriptionoptimizer.app.ui.optimize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.OptimizationResult
import com.subscriptionoptimizer.app.data.model.OptimizeAction
import com.subscriptionoptimizer.app.data.model.OptimizeActionType
import com.subscriptionoptimizer.app.data.model.RenewalAlert
import com.subscriptionoptimizer.app.data.model.SavedOptimization
import com.subscriptionoptimizer.app.data.repository.OptimizeRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.ui.common.PolicyEvidenceList
import com.subscriptionoptimizer.app.ui.designsystem.components.AdaptiveTwoPane
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.EmptyState
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.ExpandableSection
import com.subscriptionoptimizer.app.ui.designsystem.components.KeyValueRow
import com.subscriptionoptimizer.app.ui.designsystem.components.MetricTile
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme
import com.subscriptionoptimizer.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OptimizeResultUiState {
    data object Loading : OptimizeResultUiState
    data class Loaded(val saved: SavedOptimization) : OptimizeResultUiState
    data object NotFound : OptimizeResultUiState
    data class Failed(val title: String, val message: String) : OptimizeResultUiState
}

class OptimizeResultViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: OptimizeRepository,
) : ViewModel() {
    private val id: String? = savedStateHandle[Routes.RESULT_ID_ARG]
    private val _uiState = MutableStateFlow<OptimizeResultUiState>(OptimizeResultUiState.Loading)
    val uiState: StateFlow<OptimizeResultUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = id ?: run {
            _uiState.value = OptimizeResultUiState.NotFound
            return
        }
        _uiState.value = OptimizeResultUiState.Loading
        viewModelScope.launch {
            _uiState.value = repository.get(id).fold(
                onSuccess = { OptimizeResultUiState.Loaded(it) },
                onFailure = { e ->
                    if (e is RepositoryError.NotFound) OptimizeResultUiState.NotFound
                    else OptimizeResultUiState.Failed((e as? RepositoryError)?.title ?: "Couldn't load the plan", e.message.orEmpty())
                },
            )
        }
    }

    companion object {
        val Factory = appViewModelFactory { OptimizeResultViewModel(createSavedStateHandle(), it.optimizeRepository) }
    }
}

@Composable
fun OptimizeResultRoute(
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit,
    viewModel: OptimizeResultViewModel = viewModel(factory = OptimizeResultViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OptimizeResultScreen(state, onBack, onOpenTrace, onRetry = viewModel::load)
}

@Composable
fun OptimizeResultScreen(
    state: OptimizeResultUiState,
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = "Your optimization plan", onBack = onBack) }) { padding ->
        CenteredContent(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                OptimizeResultUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                OptimizeResultUiState.NotFound -> EmptyState(
                    icon = Icons.Outlined.Savings,
                    title = "Plan not available",
                    message = "It was deleted or belongs to another user.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )
                is OptimizeResultUiState.Failed -> Column(Modifier.padding(Spacing.gutter)) {
                    ErrorBanner(title = state.title, message = state.message, actionLabel = "Try again", onAction = onRetry)
                }
                is OptimizeResultUiState.Loaded -> ResultContent(state.saved, onOpenTrace)
            }
        }
    }
}

@Composable
private fun ResultContent(saved: SavedOptimization, onOpenTrace: (String) -> Unit) {
    val r = saved.result
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (r.usedFallback) {
            ErrorBanner(
                title = "Deterministic plan (AI not used)",
                message = (r.fallbackReason ?: "The local model didn't give a usable answer.") +
                    " These actions come from the rule-based engine; every figure is still calculated.",
                tone = Tone.WARNING,
                icon = Icons.Outlined.Info,
            )
        }
        if (!saved.isSaved) {
            ErrorBanner(
                title = "Not saved",
                message = "The backend couldn't save this plan, so it is only available until you close the app.",
                tone = Tone.WARNING,
            )
        }
        SavingsHero(r)
        r.budget?.let { b ->
            SectionCard(title = "Budget", icon = Icons.AutoMirrored.Outlined.TrendingDown) {
                KeyValueRow("Target per month", Formatters.money(b.target))
                KeyValueRow("This plan per month", Formatters.money(r.optimizedMonthlyTotal), emphasize = true)
                StatusPill(
                    when {
                        b.met -> "Budget met"
                        b.achievable -> "Not met by this plan: ${Formatters.money(b.gap)} over"
                        else -> "Not reachable with these subscriptions: ${Formatters.money(b.gap)} over"
                    },
                    tone = if (b.met) Tone.SAVINGS else Tone.WARNING,
                )
            }
        }

        if (r.aiSummary.isNotBlank()) {
            SectionCard(title = if (r.usedFallback) "Summary" else "AI summary", icon = Icons.Outlined.AutoAwesome) {
                SelectionContainer { Text(r.aiSummary, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        if (r.actions.isEmpty()) {
            AppCard { Text("No changes save money with the details given.", style = MaterialTheme.typography.bodyMedium) }
        }
        OptimizeActionType.entries.forEach { type ->
            val group = r.actions.filter { it.action == type }
            if (group.isNotEmpty()) {
                SectionCard(title = "${type.label} (${group.size})", icon = type.icon()) {
                    group.forEach { ActionItem(it) }
                }
            }
        }

        if (r.renewalAlerts.isNotEmpty()) {
            SectionCard(title = "Renewals coming up", icon = Icons.Outlined.CalendarMonth) {
                r.renewalAlerts.forEach { RenewalItem(it) }
            }
        }

        SectionCard(title = "Policy evidence", icon = Icons.Outlined.Policy) {
            PolicyEvidenceList(r.policyEvidence, r.unsupportedCitations)
        }

        ExpandableSection(
            title = "Checks and assumptions",
            subtitle = if (r.verificationPassed) "Totals verified" else "Verification found a problem",
        ) {
            StatusPill(
                if (r.verificationPassed) "Every total re-adds" else "Verification failed",
                tone = if (r.verificationPassed) Tone.SAVINGS else Tone.ERROR,
                icon = if (r.verificationPassed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            )
            r.verificationNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.size(Spacing.sm))
            r.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (r.missingInfo.isNotEmpty()) {
                Spacer(Modifier.size(Spacing.sm))
                Text("Missing information", style = MaterialTheme.typography.titleSmall)
                r.missingInfo.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }

        OutlinedButton(onClick = { onOpenTrace(saved.id) }, modifier = Modifier.fillMaxWidth()) {
            Text("View execution trace (${r.traceEvents.size} steps)")
        }
        Text(
            "Generated ${Formatters.dateTime(saved.createdAtMillis)} in ${String.format(java.util.Locale.US, "%.1f", r.elapsedSeconds)}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(Spacing.lg))
    }
}

@Composable
private fun SavingsHero(r: OptimizationResult) {
    AdaptiveTwoPane(
        first = { m ->
            MetricTile(
                label = "Per month",
                value = Formatters.money(r.optimizedMonthlyTotal),
                supporting = "Now ${Formatters.money(r.currentMonthlyTotal)}",
                icon = Icons.AutoMirrored.Outlined.TrendingDown,
                tone = Tone.PRIMARY,
                modifier = m,
            )
        },
        second = { m ->
            MetricTile(
                label = "Saved per year",
                value = Formatters.money(r.totalAnnualSavings),
                supporting = "${Formatters.percent(r.percentageSavings)} of ${Formatters.money(r.currentAnnualTotal)}",
                icon = Icons.Outlined.Savings,
                tone = Tone.SAVINGS,
                modifier = m,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionItem(action: OptimizeAction) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(action.name, style = MaterialTheme.typography.titleSmall)
        Text(action.reason, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (action.annualSavings > 0) {
                StatusPill("Saves ${Formatters.money(action.annualSavings)} / year", tone = Tone.SAVINGS)
                StatusPill("${Formatters.money(action.monthlySavings)} / month")
            }
            action.policyChunks.forEach { StatusPill("Chunk $it", icon = Icons.Outlined.Policy) }
        }
    }
}

@Composable
private fun RenewalItem(alert: RenewalAlert) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            "${alert.name} · ${alert.renewalDate} (in ${alert.daysUntil} day${if (alert.daysUntil == 1) "" else "s"})",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (alert.kind == "intro_ending") {
                "Offer ends: ${Formatters.money(alert.currentMonthly)} → ${Formatters.money(alert.afterMonthly)} a month " +
                    "(+${Formatters.money(alert.priceJumpMonthly)})."
            } else {
                "Next charge: ${Formatters.money(alert.chargeAmount)}."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun OptimizeActionType.icon() = when (this) {
    OptimizeActionType.CANCEL -> Icons.Outlined.ErrorOutline
    OptimizeActionType.DOWNGRADE -> Icons.AutoMirrored.Outlined.TrendingDown
    OptimizeActionType.SWITCH_TO_ANNUAL -> Icons.Outlined.CalendarMonth
    OptimizeActionType.REVIEW_BEFORE_RENEWAL -> Icons.Outlined.WarningAmber
    OptimizeActionType.KEEP -> Icons.Outlined.CheckCircle
}

@Preview(name = "Optimize result – fallback", showBackground = true, heightDp = 1400)
@Composable
private fun OptimizeResultPreview() {
    SubscriptionOptimizerTheme {
        OptimizeResultScreen(
            OptimizeResultUiState.Loaded(
                SavedOptimization(
                    id = "preview",
                    createdAtMillis = 0L,
                    goal = null,
                    monthlyBudget = null,
                    subscriptions = emptyList(),
                    result = OptimizationResult(
                        currentMonthlyTotal = 1545.0, currentAnnualTotal = 18540.0,
                        actions = listOf(
                            OptimizeAction("cancel:spotify", "spotify", "Spotify", OptimizeActionType.CANCEL,
                                "Spotify is rarely used (used 1 time(s) a month).", 119.0, 1428.0, listOf(12)),
                        ),
                        optimizedMonthlyTotal = 1426.0, optimizedAnnualTotal = 17112.0,
                        totalMonthlySavings = 119.0, totalAnnualSavings = 1428.0, percentageSavings = 7.7,
                        budget = null, renewalAlerts = emptyList(), aiSummary = "Sample summary.",
                        policyEvidence = emptyList(), citedChunks = emptyList(), unsupportedCitations = emptyList(),
                        assumptions = listOf("Prices are in INR and exclude taxes and fees."), missingInfo = emptyList(),
                        verificationPassed = true, verificationNotes = emptyList(), usedFallback = true,
                        fallbackReason = "Ollama is unavailable.", traceEvents = emptyList(), elapsedSeconds = 0.4,
                    ),
                ),
            ),
            onBack = {}, onOpenTrace = {}, onRetry = {},
        )
    }
}
