package com.subscriptionoptimizer.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subscriptionoptimizer.app.data.model.ComparisonResult
import com.subscriptionoptimizer.app.data.model.Recommendation
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.AppTheme
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing

fun ComparisonResult.recommendationLabel(): String = when (recommendation) {
    Recommendation.PLAN_A -> costA.planName
    Recommendation.PLAN_B -> costB.planName
    Recommendation.TIE -> "Tie"
    Recommendation.NEITHER -> "No recommendation"
}

/** One saved comparison as a tappable row, used on Home and History. */
@Composable
fun ComparisonSummaryCard(
    saved: SavedComparison,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = saved.result
    val title = "${result.costA.planName} vs ${result.costB.planName}"
    val savings = result.absoluteSavingsFirstYear
    val a11y = buildString {
        append(title).append(". Recommended: ").append(result.recommendationLabel()).append(". ")
        if (savings > 0) append("First-year difference ").append(Formatters.money(savings)).append(". ")
        append(Formatters.dateTime(saved.createdAtMillis))
    }
    AppCard(
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Formatters.dateTime(saved.createdAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatusPill(
                        text = result.recommendationLabel(),
                        tone = if (result.recommendedCost != null) Tone.PRIMARY else Tone.NEUTRAL,
                        icon = Icons.Outlined.EmojiEvents,
                    )
                }
            }
            if (savings > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Icon(
                        Icons.Outlined.Savings,
                        contentDescription = null,
                        tint = AppTheme.extendedColors.savings,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        Formatters.money(savings),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.extendedColors.savings,
                    )
                    Text(
                        "1st-year diff.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

