package com.subscriptionoptimizer.app.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.subscriptionoptimizer.app.data.model.PolicyEvidence
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import java.util.Locale

/**
 * The policy chunks the backend actually retrieved, newest-first as returned. Each shows its chunk
 * number and title, whether the answer cited it, its relevance, and a warning when the indexed text
 * differs from the current policy document. Tap to read the full text.
 */
@Composable
fun PolicyEvidenceList(
    evidence: List<PolicyEvidence>,
    unsupportedCitations: List<Int>,
    modifier: Modifier = Modifier,
    emptyText: String = "No policy text was relevant enough to use.",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (unsupportedCitations.isNotEmpty()) {
            ErrorBanner(
                title = "Citation not backed by retrieved policy",
                message = "The answer cites ${unsupportedCitations.joinToString { "chunk $it" }}, which " +
                    "the search did not return. Treat that part of the answer with caution.",
                tone = Tone.WARNING,
                icon = Icons.Outlined.WarningAmber,
            )
        }
        if (evidence.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        evidence.forEach { EvidenceItem(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceItem(item: PolicyEvidence) {
    var expanded by rememberSaveable(item.chunk, item.text.hashCode()) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Read policy text") {
                expanded = !expanded
            },
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chunk ${item.chunk} · ${item.title}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (item.cited) StatusPill("Cited", tone = Tone.SAVINGS, icon = Icons.Outlined.CheckCircle)
                StatusPill("Relevance ${String.format(Locale.US, "%.2f", item.relevance)}", tone = Tone.NEUTRAL)
                if (!item.matchesCurrentPolicy) StatusPill("Older policy text", tone = Tone.WARNING)
            }
            if (expanded) {
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Source: ${item.source}" + if (!item.matchesCurrentPolicy) {
                        ". The search index holds an earlier version of this section than the current policy file."
                    } else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
