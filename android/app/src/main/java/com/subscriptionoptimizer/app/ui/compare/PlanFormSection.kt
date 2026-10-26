package com.subscriptionoptimizer.app.ui.compare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.domain.CompareValidation
import com.subscriptionoptimizer.app.domain.PlanField
import com.subscriptionoptimizer.app.domain.PlanForm
import com.subscriptionoptimizer.app.domain.UsageLimitEntry
import com.subscriptionoptimizer.app.ui.designsystem.components.FormTextField
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.SwitchRow
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing

/** Callbacks for editing one plan; keeps [PlanFormSection]'s signature manageable. */
class PlanFormActions(
    val onChange: ((PlanForm) -> PlanForm) -> Unit,
    val onFeatureDraftChange: (String) -> Unit,
    val onAddFeature: () -> Unit,
    val onRemoveFeature: (String) -> Unit,
    val onAddUsageLimit: () -> Unit,
    val onUpdateUsageLimit: (UsageLimitEntry) -> Unit,
    val onRemoveUsageLimit: (Long) -> Unit,
)

@Composable
fun PlanFormSection(
    slot: PlanSlot,
    editor: PlanEditorState,
    showErrors: Boolean,
    actions: PlanFormActions,
    modifier: Modifier = Modifier,
) {
    val form = editor.form
    val errors = if (showErrors) editor.errors else emptyMap()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard(title = "${slot.label} details", icon = Icons.Outlined.Inventory2) {
            FormTextField(
                value = form.name,
                onValueChange = { v -> actions.onChange { it.copy(name = v) } },
                label = "Plan name *",
                placeholder = "e.g. Premium Monthly",
                error = errors[PlanField.NAME],
            )
            FormTextField(
                value = form.provider,
                onValueChange = { v -> actions.onChange { it.copy(provider = v) } },
                label = "Provider (optional)",
                placeholder = "e.g. StreamCo",
                error = errors[PlanField.PROVIDER],
            )
        }

        SectionCard(title = "Pricing", icon = Icons.Outlined.Payments) {
            Text(
                "Billing cycle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.sm))
            BillingCycleSelector(
                selected = form.billingCycle,
                onSelect = { cycle -> actions.onChange { it.copy(billingCycle = cycle) } },
            )
            Spacer(Modifier.size(Spacing.md))
            FormTextField(
                value = form.price,
                onValueChange = { v -> actions.onChange { it.copy(price = v) } },
                label = "Price per ${form.billingCycle.periodNoun()} *",
                prefix = "₹",
                keyboardType = KeyboardType.Decimal,
                error = errors[PlanField.PRICE],
                supporting = if (form.billingCycle != BillingCycle.MONTHLY) {
                    "Converted to a monthly equivalent for comparison"
                } else {
                    null
                },
            )
            FormTextField(
                value = form.discountPercent,
                onValueChange = { v -> actions.onChange { it.copy(discountPercent = v) } },
                label = if (form.hasIntroOffer) "Introductory discount *" else "Discount (optional)",
                suffix = "%",
                keyboardType = KeyboardType.Decimal,
                error = errors[PlanField.DISCOUNT],
                supporting = "Between 0 and 100",
            )
            SwitchRow(
                title = "Introductory offer",
                description = "The discount ends after a set number of months",
                checked = form.hasIntroOffer,
                onCheckedChange = { checked -> actions.onChange { it.copy(hasIntroOffer = checked) } },
            )
            AnimatedVisibility(visible = form.hasIntroOffer) {
                Column {
                    FormTextField(
                        value = form.introMonths,
                        onValueChange = { v -> actions.onChange { it.copy(introMonths = v) } },
                        label = "Offer length *",
                        suffix = "months",
                        keyboardType = KeyboardType.Number,
                        error = errors[PlanField.INTRO_MONTHS],
                    )
                    FormTextField(
                        value = form.postIntroPrice,
                        onValueChange = { v -> actions.onChange { it.copy(postIntroPrice = v) } },
                        label = "Monthly price after the offer (optional)",
                        prefix = "₹",
                        keyboardType = KeyboardType.Decimal,
                        error = errors[PlanField.POST_INTRO_PRICE],
                        supporting = "Leave blank to use the regular price",
                    )
                }
            }
        }

        FeaturesCard(
            features = form.features,
            draft = editor.featureDraft,
            draftError = editor.featureDraftError,
            listError = errors[PlanField.FEATURES],
            actions = actions,
        )

        UsageLimitsCard(
            limits = form.usageLimits,
            error = errors[PlanField.USAGE_LIMITS],
            actions = actions,
        )

        SectionCard(title = "Terms", icon = Icons.Outlined.Gavel) {
            FormTextField(
                value = form.cancellationTerms,
                onValueChange = { v -> actions.onChange { it.copy(cancellationTerms = v) } },
                label = "Cancellation terms (optional)",
                placeholder = "e.g. Cancel anytime without penalty",
                singleLine = false,
                minLines = 2,
                imeAction = ImeAction.Default,
                maxLength = CompareValidation.MAX_TERMS,
                error = errors[PlanField.CANCELLATION],
            )
            FormTextField(
                value = form.renewalNote,
                onValueChange = { v -> actions.onChange { it.copy(renewalNote = v) } },
                label = "Renewal note (optional)",
                placeholder = "e.g. Price rises after the first year",
                singleLine = false,
                minLines = 2,
                imeAction = ImeAction.Default,
                maxLength = CompareValidation.MAX_TERMS,
                error = errors[PlanField.RENEWAL],
            )
        }
    }
}

private fun BillingCycle.periodNoun(): String = when (this) {
    BillingCycle.MONTHLY -> "month"
    BillingCycle.QUARTERLY -> "quarter"
    BillingCycle.SEMI_ANNUAL -> "6 months"
    BillingCycle.ANNUAL -> "year"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillingCycleSelector(selected: BillingCycle, onSelect: (BillingCycle) -> Unit) {
    val cycles = BillingCycle.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        cycles.forEachIndexed { index, cycle ->
            SegmentedButton(
                selected = cycle == selected,
                onClick = { onSelect(cycle) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = cycles.size),
                icon = {},
                label = {
                    Text(
                        cycle.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeaturesCard(
    features: List<String>,
    draft: String,
    draftError: String?,
    listError: String?,
    actions: PlanFormActions,
) {
    SectionCard(title = "Features", icon = Icons.Outlined.Star) {
        Row(verticalAlignment = Alignment.Top) {
            FormTextField(
                value = draft,
                onValueChange = actions.onFeatureDraftChange,
                label = "Add a feature",
                placeholder = "e.g. 4K streaming",
                error = draftError,
                imeAction = ImeAction.Done,
                onImeAction = actions.onAddFeature,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(Spacing.sm))
            FilledTonalButton(
                onClick = actions.onAddFeature,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text("Add")
            }
        }
        if (features.isEmpty()) {
            Text(
                "No features added. Features help compare value beyond price.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                features.forEach { feature ->
                    InputChip(
                        selected = false,
                        onClick = { actions.onRemoveFeature(feature) },
                        label = { Text(feature) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove $feature",
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        if (listError != null) {
            FieldError(listError)
        }
    }
}

@Composable
private fun UsageLimitsCard(
    limits: List<UsageLimitEntry>,
    error: String?,
    actions: PlanFormActions,
) {
    SectionCard(
        title = "Usage limits",
        icon = Icons.Outlined.Speed,
        trailing = {
            TextButton(onClick = actions.onAddUsageLimit) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text("Add limit")
            }
        },
    ) {
        if (limits.isEmpty()) {
            Text(
                "Optional. For example screens = 4, or storage = 100 GB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        limits.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormTextField(
                    value = entry.name,
                    onValueChange = { actions.onUpdateUsageLimit(entry.copy(name = it)) },
                    label = "Limit",
                    placeholder = "screens",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(Spacing.sm))
                FormTextField(
                    value = entry.value,
                    onValueChange = { actions.onUpdateUsageLimit(entry.copy(value = it)) },
                    label = "Value",
                    placeholder = "4",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.onRemoveUsageLimit(entry.id) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove limit ${index + 1}")
                }
            }
        }
        if (error != null) {
            FieldError(error)
        }
    }
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
