package com.subscriptionoptimizer.app.ui.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subscriptionoptimizer.app.ui.designsystem.theme.AppTheme
import com.subscriptionoptimizer.app.ui.designsystem.theme.NumberStyles
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing

/** Semantic color roles for pills, tiles and banners. */
enum class Tone { NEUTRAL, PRIMARY, SAVINGS, WARNING, ERROR }

data class ToneColors(val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
fun Tone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val extended = AppTheme.extendedColors
    return when (this) {
        Tone.NEUTRAL -> ToneColors(scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        Tone.PRIMARY -> ToneColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        Tone.SAVINGS -> ToneColors(extended.savingsContainer, extended.onSavingsContainer)
        Tone.WARNING -> ToneColors(extended.warningContainer, extended.onWarningContainer)
        Tone.ERROR -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** The app's standard flat card: lowest surface with a hairline border. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(Spacing.lg), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors, border = border, content = inner)
    } else {
        Card(modifier = modifier, colors = colors, border = border, content = inner)
    }
}

/** A titled card section. The title is exposed to accessibility services as a heading. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            trailing?.invoke(this)
        }
        Spacer(Modifier.size(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action?.invoke()
    }
}

/** A labelled figure with an icon badge. Read by screen readers as one item. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.PRIMARY,
    supporting: String? = null,
    valueStyle: TextStyle = NumberStyles.amountMedium,
) {
    val colors = tone.colors()
    AppCard(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Surface(shape = CircleShape, color = colors.container, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.size(Spacing.md))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.xs))
        Text(value, style = valueStyle, color = MaterialTheme.colorScheme.onSurface)
        if (supporting != null) {
            Spacer(Modifier.size(Spacing.xs))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    icon: ImageVector? = null,
) {
    val colors = tone.colors()
    Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A two-column label/value row used in breakdown tables. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs + 2.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            value,
            style = if (emphasize) NumberStyles.amountSmall.copy(fontWeight = FontWeight.SemiBold) else NumberStyles.amountSmall,
            color = valueColor,
        )
    }
}
