package com.subscriptionoptimizer.app.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.ComparisonResult
import com.subscriptionoptimizer.app.data.model.CostBreakdown
import com.subscriptionoptimizer.app.data.model.Recommendation
import com.subscriptionoptimizer.app.data.model.ScoreBreakdown
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.domain.ProsCons
import com.subscriptionoptimizer.app.domain.ProsConsDeriver
import com.subscriptionoptimizer.app.data.model.SavedPlan
import com.subscriptionoptimizer.app.preview.PreviewFixtures
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
import com.subscriptionoptimizer.app.ui.designsystem.components.PreviewDataBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.ScoreBar
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.AppTheme
import com.subscriptionoptimizer.app.ui.designsystem.theme.NumberStyles
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun ResultsRoute(
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit,
    viewModel: ResultsViewModel = viewModel(factory = ResultsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ResultsScreen(state, onBack, onOpenTrace = onOpenTrace, onRetry = viewModel::load)
}

@Composable
fun ResultsScreen(
    state: ResultsUiState,
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(topBar = { AppTopBar(title = "Comparison results", onBack = onBack) }) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state) {
                ResultsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ResultsUiState.NotFound -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "Result not available",
                    message = "This comparison was deleted, or it belongs to a different user ID or backend.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )
                is ResultsUiState.Failed -> Column(Modifier.padding(Spacing.gutter)) {
                    ErrorBanner(title = state.title, message = state.message, actionLabel = "Try again", onAction = onRetry)
                }
                is ResultsUiState.Loaded -> ResultsContent(state, onOpenTrace)
            }
        }
    }
}

@Composable
private fun ResultsContent(state: ResultsUiState.Loaded, onOpenTrace: (String) -> Unit) {
    val result = state.saved.result
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (state.saved.isSample) PreviewDataBanner()
        if (!state.saved.isSaved) {
            ErrorBanner(
                title = "Not saved to history",
                message = "The backend returned this result but couldn't save it. It will be gone when you leave.",
                tone = Tone.WARNING,
                icon = Icons.Outlined.WarningAmber,
            )
        } else if (!state.saved.isSample) {
            Text(
                "Saved ${Formatters.dateTime(state.saved.createdAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        RecommendationCard(result)
        SavingsRow(result)

        if (result.missingInfo.isNotEmpty()) {
            ErrorBanner(
                title = "Some details were missing",
                message = result.missingInfo.joinToString("\n") { "• $it" },
                tone = Tone.WARNING,
                icon = Icons.Outlined.Info,
            )
        }
        if (!result.verificationPassed || result.verificationNotes.isNotEmpty()) {
            ErrorBanner(
                title = "Consistency check flagged issues",
                message = result.verificationNotes.joinToString("\n") { "• $it" }
                    .ifEmpty { "The backend's verification step did not pass." },
                tone = Tone.WARNING,
                icon = Icons.Outlined.WarningAmber,
            )
        }

        SubHeading("Cost breakdown")
        AdaptiveTwoPane(
            first = { PlanCostCard(result.costA, result.recommendation == Recommendation.PLAN_A, it) },
            second = { PlanCostCard(result.costB, result.recommendation == Recommendation.PLAN_B, it) },
        )

        val scoreA = result.scoreA
        val scoreB = result.scoreB
        if (scoreA != null && scoreB != null) {
            SubHeading("Scores")
            AdaptiveTwoPane(
                first = { ScoreCard(result.costA.planName, scoreA, it) },
                second = { ScoreCard(result.costB.planName, scoreB, it) },
            )
            Text(
                "Scores weight cost, features, usage and cancellation according to " +
                    "the '${result.priorityUsed?.label ?: "selected"}' priority.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FeatureComparisonCard(result)

        SubHeading("Pros and cons")
        AdaptiveTwoPane(
            first = { ProsConsCard(result.costA.planName, state.prosConsA, it) },
            second = { ProsConsCard(result.costB.planName, state.prosConsB, it) },
        )
        Text(
            "Derived only from the calculated figures above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = "AI explanation", icon = Icons.Outlined.AutoAwesome) {
            Text(
                result.aiExplanation.ifBlank { "The backend did not return an explanation." },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (result.assumptions.isNotEmpty()) {
            SectionCard(title = "Assumptions", icon = Icons.Outlined.Lightbulb) {
                result.assumptions.forEach { BulletText(it) }
            }
        }

        SectionCard(title = "Policy evidence", icon = Icons.Outlined.Policy) {
            Text(
                "Sections of the subscription policy retrieved for your question and given to the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PolicyEvidenceList(result.policyEvidence, result.unsupportedCitations)
        }

        if (state.saved.planA != null && state.saved.planB != null) {
            ExpandableSection(title = "Your inputs", subtitle = "As sent to the backend") {
                if (state.saved.question.isNotBlank()) KeyValueRow("Question", state.saved.question)
                PlanInputs(state.saved.planA)
                PlanInputs(state.saved.planB)
            }
        }

        if (state.saved.isSaved || result.traceEvents.isNotEmpty()) {
            OutlinedButton(onClick = { onOpenTrace(state.saved.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AccountTree, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text("Open execution trace")
            }
        }
        ExpandableSection(
            title = "Trace summary",
            subtitle = buildString {
                append("${result.trace.size} step(s)")
                if (result.elapsedSeconds > 0) append(" · ").append(String.format(java.util.Locale.US, "%.1fs", result.elapsedSeconds))
            },
        ) {
            if (result.trace.isEmpty()) {
                Text("No trace was returned.", style = MaterialTheme.typography.bodySmall)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    result.trace.forEachIndexed { index, line ->
                        Row {
                            Text(
                                "${index + 1}".padStart(2),
                                style = NumberStyles.mono,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(line, style = NumberStyles.mono)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(Spacing.lg))
    }
}

@Composable
private fun PlanInputs(plan: SavedPlan) {
    Spacer(Modifier.size(Spacing.sm))
    Text(plan.name, style = MaterialTheme.typography.titleSmall)
    plan.provider?.let { KeyValueRow("Provider", it) }
    KeyValueRow("Price", Formatters.money(plan.price) + (plan.billingCycle?.let { " / ${it.label.lowercase()}" } ?: ""))
    if (plan.discountPercent > 0) {
        KeyValueRow(
            "Discount",
            String.format(java.util.Locale.US, "%.1f%%", plan.discountPercent) +
                (plan.introMonths?.let { " for $it month(s)" } ?: ""),
        )
    }
    plan.postIntroPrice?.let { KeyValueRow("After the offer", Formatters.money(it)) }
    if (plan.features.isNotEmpty()) KeyValueRow("Features", plan.features.joinToString())
    plan.usageLimits.forEach { (k, v) -> KeyValueRow(k, v) }
    plan.cancellationTerms?.let { KeyValueRow("Cancellation", it) }
    plan.renewalNote?.let { KeyValueRow("Renewal", it) }
}

@Composable
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun RecommendationCard(result: ComparisonResult) {
    val extended = AppTheme.extendedColors
    val onHero = extended.onHero
    val headline = when (result.recommendation) {
        Recommendation.PLAN_A -> result.costA.planName
        Recommendation.PLAN_B -> result.costB.planName
        Recommendation.TIE -> "It's a tie"
        Recommendation.NEITHER -> "No clear recommendation"
    }
    Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(listOf(extended.heroGradientStart, extended.heroGradientEnd)),
                    MaterialTheme.shapes.large,
                )
                .fillMaxWidth()
                .padding(Spacing.xl)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = onHero)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (result.recommendedCost != null) "Recommended" else "Result",
                    style = MaterialTheme.typography.labelLarge,
                    color = onHero.copy(alpha = 0.9f),
                )
            }
            Spacer(Modifier.size(Spacing.sm))
            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                color = onHero,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(result.recommendationReason, style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.9f))
            Spacer(Modifier.size(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                result.priorityUsed?.let { StatusPill(it.label, tone = Tone.PRIMARY) }
                if (result.verificationPassed) {
                    StatusPill("Checks passed", tone = Tone.SAVINGS, icon = Icons.Outlined.CheckCircle)
                } else {
                    StatusPill("Check notes", tone = Tone.WARNING, icon = Icons.Outlined.WarningAmber)
                }
            }
        }
    }
}

@Composable
private fun SavingsRow(result: ComparisonResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        MetricTile(
            label = "First-year difference",
            value = Formatters.money(result.absoluteSavingsFirstYear),
            icon = Icons.Outlined.Savings,
            tone = if (result.absoluteSavingsFirstYear > 0) Tone.SAVINGS else Tone.NEUTRAL,
            supporting = savingsCaption(result.cheaperPlanFirstYear, result.percentageSavingsFirstYear),
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "Yearly, after offers",
            value = Formatters.money(result.absoluteSavingsRecurring),
            icon = Icons.Outlined.CalendarMonth,
            tone = if (result.absoluteSavingsRecurring > 0) Tone.SAVINGS else Tone.NEUTRAL,
            supporting = savingsCaption(result.cheaperPlanRecurring, result.percentageSavingsRecurring),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun savingsCaption(cheaper: String, percent: Double): String =
    if (cheaper == "tie") "Both cost the same" else "${Formatters.percent(percent)} less with $cheaper"

private fun BillingCycle.perLabel(): String = when (this) {
    BillingCycle.MONTHLY -> "/ month"
    BillingCycle.QUARTERLY -> "/ quarter"
    BillingCycle.SEMI_ANNUAL -> "/ 6 months"
    BillingCycle.ANNUAL -> "/ year"
}

@Composable
private fun PlanCostCard(cost: CostBreakdown, recommended: Boolean, modifier: Modifier) {
    SectionCard(
        title = cost.planName,
        icon = Icons.Outlined.Payments,
        modifier = modifier,
        trailing = { if (recommended) StatusPill("Recommended", tone = Tone.SAVINGS) },
    ) {
        KeyValueRow("Listed price", "${Formatters.money(cost.listedPrice)} ${cost.billingCycle.perLabel()}")
        if (cost.billingCycle != BillingCycle.MONTHLY) {
            KeyValueRow("Monthly equivalent", Formatters.money(cost.monthlyEquivalent))
        }
        KeyValueRow(
            "Discount",
            when {
                cost.discountPercent <= 0.0 -> "None"
                cost.hasIntroOffer -> "${Formatters.percent(cost.discountPercent)} for ${cost.introMonths} mo"
                else -> Formatters.percent(cost.discountPercent)
            },
        )
        KeyValueRow("Discounted monthly", Formatters.money(cost.discountedMonthly))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        KeyValueRow("First-year cost", Formatters.money(cost.firstYearCost), emphasize = true)
        Explanation(cost.firstYearExplanation)
        KeyValueRow("Yearly from year two", Formatters.money(cost.recurringAnnualCost), emphasize = true)
        Explanation(cost.recurringAnnualExplanation)
        cost.renewalCostNote?.takeIf { it.isNotBlank() }?.let {
            Explanation("Renewal: $it")
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ScoreCard(planName: String, score: ScoreBreakdown, modifier: Modifier) {
    SectionCard(title = planName, icon = Icons.Outlined.Leaderboard, modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Formatters.score(score.weightedTotal), style = NumberStyles.amountLarge)
            Text(
                " / 100 overall",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        ScoreBar("Cost", score.costScore)
        ScoreBar("Features", score.featuresScore)
        ScoreBar("Usage limits", score.usageScore)
        ScoreBar("Cancellation", score.cancellationScore)
        if (!score.sufficientData) {
            Explanation("Some dimensions had no data and were left out of the overall score.")
        }
    }
}

@Composable
private fun FeatureComparisonCard(result: ComparisonResult) {
    val features = result.featureComparison
    SectionCard(title = "Feature comparison", icon = Icons.Outlined.Star) {
        if (features.shared.isEmpty() && features.onlyInA.isEmpty() && features.onlyInB.isEmpty()) {
            Explanation("No features were provided for either plan.")
        } else {
            FeatureGroup("In both plans", features.shared, Tone.NEUTRAL)
            FeatureGroup("Only in ${result.costA.planName}", features.onlyInA, Tone.PRIMARY)
            FeatureGroup("Only in ${result.costB.planName}", features.onlyInB, Tone.PRIMARY)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureGroup(title: String, items: List<String>, tone: Tone) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) {
            Explanation("None")
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items.forEach { StatusPill(it, tone = tone) }
            }
        }
    }
}

@Composable
private fun ProsConsCard(planName: String, prosCons: ProsCons, modifier: Modifier) {
    AppCard(modifier = modifier) {
        Text(planName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.size(Spacing.md))
        if (prosCons.pros.isEmpty() && prosCons.cons.isEmpty()) {
            Explanation("Nothing stands out from the calculated figures.")
        }
        prosCons.pros.forEach { ProsConsLine(it, Icons.Outlined.ThumbUp, AppTheme.extendedColors.savings, "Pro") }
        prosCons.cons.forEach { ProsConsLine(it, Icons.Outlined.ThumbDown, MaterialTheme.colorScheme.error, "Con") }
    }
}

@Composable
private fun ProsConsLine(text: String, icon: ImageVector, tint: Color, kind: String) {
    Row(
        Modifier
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = kind, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BulletText(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Results – sample data", showBackground = true, heightDp = 2400)
@Composable
private fun ResultsPreview() {
    // PREVIEW-ONLY: fixture data, shown with the sample-data banner.
    val (a, b) = ProsConsDeriver.derive(PreviewFixtures.sampleResult)
    SubscriptionOptimizerTheme {
        ResultsScreen(ResultsUiState.Loaded(PreviewFixtures.sampleSaved, a, b), onBack = {})
    }
}

@Preview(name = "Results – not found (dark)", showBackground = true)
@Composable
private fun ResultsNotFoundPreview() {
    SubscriptionOptimizerTheme(darkTheme = true) {
        ResultsScreen(ResultsUiState.NotFound, onBack = {})
    }
}
