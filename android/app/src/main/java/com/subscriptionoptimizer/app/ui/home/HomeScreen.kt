package com.subscriptionoptimizer.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.preview.PreviewFixtures
import com.subscriptionoptimizer.app.ui.common.ComparisonSummaryCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.EmptyState
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionHeader
import com.subscriptionoptimizer.app.ui.designsystem.theme.AppTheme
import com.subscriptionoptimizer.app.ui.designsystem.theme.NumberStyles
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun HomeRoute(
    onNewComparison: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPolicy: () -> Unit,
    onOpenResult: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onNewComparison = onNewComparison,
        onOpenAssistant = onOpenAssistant,
        onOpenHistory = onOpenHistory,
        onOpenPolicy = onOpenPolicy,
        onOpenResult = onOpenResult,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onNewComparison: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPolicy: () -> Unit,
    onOpenResult: (String) -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = "Overview") }) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                DashboardHero(state.figures, onNewComparison)

                SectionHeader("Quick actions")
                QuickActions(onNewComparison, onOpenAssistant, onOpenHistory, onOpenPolicy)

                SectionHeader(
                    title = "Recent comparisons",
                    action = if (state.recent.isNotEmpty()) {
                        { TextButton(onClick = onOpenHistory) { Text("See all") } }
                    } else {
                        null
                    },
                )
                if (state.recent.isEmpty() && state.isLoading) {
                    AppCard { Text("Loading saved comparisons…", style = MaterialTheme.typography.bodyMedium) }
                } else if (state.recent.isEmpty()) {
                    AppCard {
                        EmptyState(
                            icon = Icons.Outlined.History,
                            title = "No comparisons yet",
                            message = "Comparisons you run will appear here.",
                        )
                    }
                } else {
                    state.recent.forEach { saved ->
                        ComparisonSummaryCard(saved, onClick = { onOpenResult(saved.id) })
                    }
                }
                state.historyError?.let {
                    Text(
                        "Couldn't refresh history: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.size(Spacing.lg))
            }
        }
    }
}

@Composable
private fun DashboardHero(figures: DashboardFigures?, onNewComparison: () -> Unit) {
    val extended = AppTheme.extendedColors
    val onHero = extended.onHero
    Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(listOf(extended.heroGradientStart, extended.heroGradientEnd)),
                    MaterialTheme.shapes.large,
                )
                .padding(Spacing.xl)
                .fillMaxWidth(),
        ) {
            Text(
                "Your subscription costs",
                style = MaterialTheme.typography.titleMedium,
                color = onHero,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.size(Spacing.xs))
            Text(
                text = if (figures == null) {
                    "Figures appear after your first comparison."
                } else {
                    "Based on your latest comparison: ${figures.planAName} vs ${figures.planBName}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onHero.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(Spacing.lg))

            HeroFigure(
                label = "Est. annual cost",
                value = figures?.annualCost?.let(Formatters::money),
                large = true,
                color = onHero,
            )
            Spacer(Modifier.size(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HeroFigure(
                    label = "Est. monthly cost",
                    value = figures?.monthlyCost?.let(Formatters::money),
                    color = onHero,
                    modifier = Modifier.weight(1f),
                )
                HeroFigure(
                    label = "Est. savings (1st year)",
                    value = figures?.estimatedSavings?.let(Formatters::money),
                    color = onHero,
                    modifier = Modifier.weight(1f),
                    highlight = figures?.estimatedSavings != null,
                )
            }

            if (figures != null && figures.recommendedPlanName == null) {
                Spacer(Modifier.size(Spacing.md))
                Text(
                    "The latest comparison had no clear recommendation, so no plan cost is shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.85f),
                )
            }
            if (figures == null) {
                Spacer(Modifier.size(Spacing.lg))
                Surface(
                    onClick = onNewComparison,
                    shape = MaterialTheme.shapes.medium,
                    color = onHero,
                    contentColor = extended.heroGradientStart,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.sm))
                        Text("Compare two plans", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroFigure(
    label: String,
    value: String?,
    color: Color,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    highlight: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.85f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (highlight) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(AppTheme.extendedColors.savings, MaterialTheme.shapes.extraSmall),
                )
                Spacer(Modifier.size(Spacing.xs + 2.dp))
            }
            Text(
                text = value ?: "—",
                style = if (large) NumberStyles.amountLarge else NumberStyles.amountMedium,
                color = color,
            )
        }
        if (value == null) {
            Text("Not available yet", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickActions(
    onNewComparison: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPolicy: () -> Unit,
) {
    val actions = listOf(
        QuickAction("New comparison", Icons.Outlined.SwapHoriz, onNewComparison),
        QuickAction("Ask assistant", Icons.Outlined.AutoAwesome, onOpenAssistant),
        QuickAction("History", Icons.Outlined.Insights, onOpenHistory),
        QuickAction("Policy questions", Icons.Outlined.Policy, onOpenPolicy),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        actions.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                pair.forEach { action ->
                    AppCard(onClick = action.onClick, modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.size(Spacing.md))
                            Text(
                                action.label,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Home – empty", showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    SubscriptionOptimizerTheme {
        HomeScreen(HomeUiState(), {}, {}, {}, {}, {})
    }
}

@Preview(name = "Home – sample data (dark)", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeSamplePreview() {
    // PREVIEW-ONLY: fixture data, never shown at runtime.
    val sample = PreviewFixtures.sampleSaved
    SubscriptionOptimizerTheme(darkTheme = true) {
        HomeScreen(
            HomeUiState(
                figures = DashboardFigures(
                    planAName = sample.result.costA.planName,
                    planBName = sample.result.costB.planName,
                    recommendedPlanName = sample.result.costB.planName,
                    monthlyCost = sample.result.costB.discountedMonthly,
                    annualCost = sample.result.costB.firstYearCost,
                    estimatedSavings = sample.result.absoluteSavingsFirstYear,
                ),
                recent = listOf(sample),
                totalComparisons = 1,
            ),
            {}, {}, {}, {}, {},
        )
    }
}
