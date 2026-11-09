package com.subscriptionoptimizer.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.data.model.OptimizationSummary
import com.subscriptionoptimizer.app.data.repository.HistoryState
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.preview.PreviewFixtures
import com.subscriptionoptimizer.app.ui.common.ComparisonSummaryCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.EmptyState
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun HistoryRoute(
    onOpenResult: (String) -> Unit,
    onNewComparison: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOptimization: (String) -> Unit = {},
    onNewOptimization: () -> Unit = {},
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    HistoryScreen(
        state = state,
        onOpenResult = onOpenResult,
        onNewComparison = onNewComparison,
        onOpenSettings = onOpenSettings,
        onRefresh = viewModel::refresh,
        onDelete = viewModel::delete,
        onClear = viewModel::clear,
        onDismissError = viewModel::dismissError,
        optimizationActions = OptimizationHistoryActions(
            onSelectTab = viewModel::selectTab,
            onOpen = onOpenOptimization,
            onDelete = viewModel::deleteOptimization,
            onNew = onNewOptimization,
        ),
    )
}

data class OptimizationHistoryActions(
    val onSelectTab: (HistoryTab) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onNew: () -> Unit = {},
)

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenResult: (String) -> Unit,
    onNewComparison: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismissError: () -> Unit,
    optimizationActions: OptimizationHistoryActions = OptimizationHistoryActions(),
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteOptimization by rememberSaveable { mutableStateOf<String?>(null) }
    val onComparisons = state.tab == HistoryTab.COMPARISONS
    val history = state.history

    Scaffold(
        topBar = {
            AppTopBar(title = "History") {
                IconButton(onClick = onRefresh, enabled = !history.isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
                if (onComparisons && history.entries.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Delete all saved comparisons")
                    }
                }
            }
        },
    ) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "tabs") { TabChips(state.tab, optimizationActions.onSelectTab) }
                state.actionError?.let { error ->
                    item(key = "action-error") {
                        ErrorBanner(title = error.title, message = error.message.orEmpty(),
                            actionLabel = "Dismiss", onAction = onDismissError)
                    }
                }
                if (!onComparisons) {
                    optimizationItems(state, optimizationActions, onOpenSettings) { confirmDeleteOptimization = it }
                } else history.error?.let { error ->
                    item(key = "load-error") {
                        ErrorBanner(
                            title = "Couldn't load history: ${error.title}",
                            message = error.message.orEmpty() +
                                if (history.entries.isNotEmpty()) "\nShowing the last list that loaded." else "",
                            icon = Icons.Outlined.CloudOff,
                            actionLabel = "Try again",
                            onAction = onRefresh,
                        )
                    }
                    if (error is RepositoryError.UserIdTaken || error is RepositoryError.NotConfigured) {
                        item(key = "settings") { TextButton(onClick = onOpenSettings) { Text("Open settings") } }
                    }
                }
                if (onComparisons) when {
                    history.isLoading && history.entries.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    history.loaded && history.entries.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.History,
                            title = "No saved comparisons",
                            message = "Comparisons you run are saved on the backend under your user ID. " +
                                "Run one and it will appear here.",
                            actionLabel = "Compare plans",
                            onAction = onNewComparison,
                        )
                    }
                    else -> {
                        if (history.loaded) {
                            item(key = "count") {
                                Text(
                                    "${history.total} saved on the server" +
                                        if (history.total > history.entries.size) ", newest ${history.entries.size} shown" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(history.entries, key = { it.id }) { saved ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ComparisonSummaryCard(
                                    saved,
                                    onClick = { onOpenResult(saved.id) },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { confirmDelete = saved.id },
                                    enabled = state.deletingId == null,
                                ) {
                                    if (state.deletingId == saved.id) {
                                        CircularProgressIndicator(Modifier.padding(Spacing.sm))
                                    } else {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete this comparison")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all saved comparisons?") },
            text = { Text("All ${history.total} comparison(s) saved on the server for this user will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    confirmDeleteOptimization?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteOptimization = null },
            title = { Text("Delete this optimization?") },
            text = { Text("It will be removed from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteOptimization = null
                    optimizationActions.onDelete(id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOptimization = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this comparison?") },
            text = { Text("It will be removed from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TabChips(selected: HistoryTab, onSelect: (HistoryTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HistoryTab.entries.forEach { tab ->
            FilterChip(selected = tab == selected, onClick = { onSelect(tab) }, label = { Text(tab.label) })
        }
    }
}

private fun LazyListScope.optimizationItems(
    state: HistoryUiState,
    actions: OptimizationHistoryActions,
    onOpenSettings: () -> Unit,
    onAskDelete: (String) -> Unit,
) {
    val runs = state.optimizations
    runs.error?.let { error ->
        item(key = "opt-load-error") {
            ErrorBanner(
                title = "Couldn't load optimizations: ${error.title}",
                message = error.message.orEmpty() + if (runs.items.isNotEmpty()) "\nShowing the last list that loaded." else "",
                icon = Icons.Outlined.CloudOff,
            )
        }
        if (error is RepositoryError.UserIdTaken || error is RepositoryError.NotConfigured) {
            item(key = "opt-settings") { TextButton(onClick = onOpenSettings) { Text("Open settings") } }
        }
    }
    when {
        runs.isLoading && runs.items.isEmpty() -> item(key = "opt-loading") {
            Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        runs.loaded && runs.items.isEmpty() -> item(key = "opt-empty") {
            EmptyState(
                icon = Icons.Outlined.Savings,
                title = "No saved optimizations",
                message = "Plans from \"Optimize my subscriptions\" are saved here.",
                actionLabel = "Optimize subscriptions",
                onAction = actions.onNew,
            )
        }
        else -> items(runs.items, key = { "opt-" + it.id }) { run ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptimizationSummaryCard(run, onClick = { actions.onOpen(run.id) }, modifier = Modifier.weight(1f))
                IconButton(onClick = { onAskDelete(run.id) }, enabled = state.deletingId == null) {
                    if (state.deletingId == run.id) {
                        CircularProgressIndicator(Modifier.padding(Spacing.sm))
                    } else {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete this optimization")
                    }
                }
            }
        }
    }
}

@Composable
private fun OptimizationSummaryCard(run: OptimizationSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(onClick = onClick, modifier = modifier) {
        Text(
            "Saves ${Formatters.money(run.totalAnnualSavings)} a year",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "${run.subscriptionCount} subscription(s)" +
                (run.monthlyBudget?.let { " · budget ${Formatters.money(it)}/month" } ?: "") +
                " · ${Formatters.dateTime(run.createdAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        run.goal?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        if (run.usedFallback) {
            StatusPill("Deterministic plan", tone = Tone.WARNING, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

@Preview(name = "History – empty", showBackground = true)
@Composable
private fun HistoryEmptyPreview() {
    SubscriptionOptimizerTheme {
        HistoryScreen(HistoryUiState(HistoryState(loaded = true)), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "History – sample entry", showBackground = true)
@Composable
private fun HistorySamplePreview() {
    SubscriptionOptimizerTheme {
        HistoryScreen(
            HistoryUiState(HistoryState(entries = listOf(PreviewFixtures.sampleSaved), total = 1, loaded = true)),
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}
