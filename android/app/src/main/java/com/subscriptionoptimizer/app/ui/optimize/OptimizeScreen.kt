package com.subscriptionoptimizer.app.ui.optimize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.SubscriptionCategory
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.domain.OptimizeValidation
import com.subscriptionoptimizer.app.domain.SubscriptionField
import com.subscriptionoptimizer.app.domain.SubscriptionForm
import com.subscriptionoptimizer.app.ui.compare.BillingCycleSelector
import com.subscriptionoptimizer.app.ui.compare.periodNoun
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.EmptyState
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.FormTextField
import com.subscriptionoptimizer.app.ui.designsystem.components.LoadingButton
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionHeader
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.SwitchRow
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

const val RUNNING_MESSAGE = "Running locally on qwen2.5:3b, this can take a minute"

@Composable
fun OptimizeRoute(
    onBack: () -> Unit,
    onResult: (String) -> Unit,
    viewModel: OptimizeViewModel = viewModel(factory = OptimizeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OptimizeEvent.OpenResult -> onResult(event.id)
                is OptimizeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }
    OptimizeScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = OptimizeActions(
            onBack = onBack,
            onAdd = viewModel::startAdd,
            onEdit = viewModel::startEdit,
            onDelete = viewModel::delete,
            onEditorChange = viewModel::updateEditor,
            onEditorSave = viewModel::saveEditor,
            onEditorDismiss = viewModel::dismissEditor,
            onBudgetChange = viewModel::setBudget,
            onGoalChange = viewModel::setGoal,
            onSavePortfolioChange = viewModel::setSavePortfolio,
            onRun = viewModel::run,
            onRetryPortfolio = viewModel::loadPortfolio,
            onDismissError = viewModel::dismissSubmitError,
        ),
    )
}

data class OptimizeActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onEditorChange: ((SubscriptionForm) -> SubscriptionForm) -> Unit = {},
    val onEditorSave: () -> Unit = {},
    val onEditorDismiss: () -> Unit = {},
    val onBudgetChange: (String) -> Unit = {},
    val onGoalChange: (String) -> Unit = {},
    val onSavePortfolioChange: (Boolean) -> Unit = {},
    val onRun: () -> Unit = {},
    val onRetryPortfolio: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
fun OptimizeScreen(state: OptimizeUiState, snackbarHostState: SnackbarHostState, actions: OptimizeActions) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { AppTopBar(title = "Optimize subscriptions", onBack = actions.onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
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
                    "Type in what you pay for. The backend works out every figure; the local model only " +
                        "chooses and explains actions. No card or bank details are needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.portfolioError?.let {
                    ErrorBanner(
                        title = it.title, message = it.message, icon = Icons.Outlined.CloudOff, tone = Tone.WARNING,
                        actionLabel = "Try again", onAction = actions.onRetryPortfolio,
                    )
                }

                SectionHeader(
                    title = "Your subscriptions (${state.subscriptions.size})",
                    action = {
                        TextButton(onClick = actions.onAdd, enabled = !state.isRunning) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.size(Spacing.xs))
                            Text("Add")
                        }
                    },
                )
                when {
                    state.isLoadingPortfolio && state.subscriptions.isEmpty() ->
                        AppCard { Text("Loading your saved list…", style = MaterialTheme.typography.bodyMedium) }
                    state.subscriptions.isEmpty() -> AppCard {
                        EmptyState(
                            icon = Icons.Outlined.Subscriptions,
                            title = "No subscriptions yet",
                            message = "Add each service you pay for, with its price and how often you use it.",
                            actionLabel = "Add a subscription",
                            onAction = actions.onAdd,
                        )
                    }
                    else -> state.subscriptions.forEach { sub ->
                        SubscriptionRow(sub, enabled = !state.isRunning, onEdit = { actions.onEdit(sub.id) },
                            onDelete = { confirmDelete = sub.id })
                    }
                }
                state.listError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                SectionCard(title = "Goal (optional)") {
                    FormTextField(
                        value = state.budget,
                        onValueChange = actions.onBudgetChange,
                        label = "Monthly budget",
                        prefix = "₹",
                        keyboardType = KeyboardType.Decimal,
                        error = state.budgetError,
                        supporting = "The plan will try to bring your monthly total under this.",
                        enabled = !state.isRunning,
                    )
                    FormTextField(
                        value = state.goal,
                        onValueChange = actions.onGoalChange,
                        label = "What matters to you?",
                        placeholder = "e.g. Keep Netflix, cut what I don't use",
                        singleLine = false,
                        minLines = 2,
                        maxLength = OptimizeValidation.MAX_GOAL,
                        error = state.goalError,
                        enabled = !state.isRunning,
                    )
                    SwitchRow(
                        title = "Save my list",
                        description = "Keep these subscriptions on the backend for next time.",
                        checked = state.savePortfolio,
                        onCheckedChange = actions.onSavePortfolioChange,
                    )
                }

                state.submitError?.let {
                    ErrorBanner(title = it.title, message = it.message, actionLabel = "Dismiss", onAction = actions.onDismissError)
                }
                LoadingButton(
                    text = "Optimize my subscriptions",
                    loading = state.isRunning,
                    loadingText = "Optimizing…",
                    onClick = actions.onRun,
                    icon = Icons.Outlined.AutoFixHigh,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isRunning) {
                    Text(
                        RUNNING_MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(Spacing.lg))
            }
        }
    }

    state.editor?.let { editor ->
        SubscriptionEditorSheet(editor, actions.onEditorChange, actions.onEditorSave, actions.onEditorDismiss)
    }
    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove this subscription?") },
            text = { Text("It is removed from this list. Your saved list changes only when you run with \"Save my list\" on.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    actions.onDelete(id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionRow(sub: Subscription, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    AppCard(onClick = if (enabled) onEdit else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Formatters.money(sub.price)} / ${sub.billingCycle.periodNoun()} · ${sub.category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (sub.mustKeep) StatusPill("Must keep", tone = Tone.PRIMARY, icon = Icons.Outlined.Lock)
                    sub.usesPerMonth?.let { StatusPill("$it uses / month") }
                    if (sub.introMonths != null) StatusPill("Intro offer", tone = Tone.WARNING)
                    if (sub.sharedWith > 0) StatusPill("Shared with ${sub.sharedWith}")
                }
            }
            IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${sub.name}") }
            IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${sub.name}") }
        }
    }
}

@Composable
private fun SubscriptionEditorSheet(
    editor: SubscriptionEditor,
    onChange: ((SubscriptionForm) -> SubscriptionForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = editor.form
    val e = editor.errors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(if (editor.isNew) "Add subscription" else "Edit subscription", style = MaterialTheme.typography.titleLarge)
            FormTextField(form.name, { v -> onChange { it.copy(name = v) } }, "Name", error = e[SubscriptionField.NAME],
                placeholder = "e.g. Netflix", maxLength = OptimizeValidation.MAX_NAME)
            FormTextField(form.provider, { v -> onChange { it.copy(provider = v) } }, "Provider (optional)",
                error = e[SubscriptionField.PROVIDER])
            CategoryDropdown(form.category) { c -> onChange { it.copy(category = c) } }

            Text("Billing", style = MaterialTheme.typography.titleSmall)
            BillingCycleSelector(form.billingCycle) { c -> onChange { it.copy(billingCycle = c) } }
            FormTextField(form.price, { v -> onChange { it.copy(price = v) } }, "Price per ${form.billingCycle.periodNoun()}",
                prefix = "₹", keyboardType = KeyboardType.Decimal, error = e[SubscriptionField.PRICE])
            FormTextField(form.discountPercent, { v -> onChange { it.copy(discountPercent = v) } }, "Discount (optional)",
                suffix = "%", keyboardType = KeyboardType.Decimal, error = e[SubscriptionField.DISCOUNT])
            if (form.billingCycle == BillingCycle.MONTHLY) {
                FormTextField(form.annualPrice, { v -> onChange { it.copy(annualPrice = v) } }, "Yearly price, if offered",
                    prefix = "₹", keyboardType = KeyboardType.Decimal, error = e[SubscriptionField.ANNUAL_PRICE],
                    supporting = "Lets the plan check whether paying yearly is cheaper.")
            }
            SwitchRow("Introductory offer", form.hasIntroOffer, { v -> onChange { it.copy(hasIntroOffer = v) } },
                description = "The discount above lasts only for a few months.")
            if (form.hasIntroOffer) {
                FormTextField(form.introMonths, { v -> onChange { it.copy(introMonths = v) } }, "Offer length",
                    suffix = "months", keyboardType = KeyboardType.Number, error = e[SubscriptionField.INTRO_MONTHS])
                FormTextField(form.postIntroPrice, { v -> onChange { it.copy(postIntroPrice = v) } },
                    "Price after the offer (optional)", prefix = "₹", keyboardType = KeyboardType.Decimal,
                    error = e[SubscriptionField.POST_INTRO_PRICE])
            }
            FormTextField(form.renewalDate, { v -> onChange { it.copy(renewalDate = v) } }, "Next renewal (optional)",
                placeholder = "YYYY-MM-DD", error = e[SubscriptionField.RENEWAL_DATE])

            Text("Usage", style = MaterialTheme.typography.titleSmall)
            FormTextField(form.usesPerMonth, { v -> onChange { it.copy(usesPerMonth = v) } }, "Times used per month (optional)",
                keyboardType = KeyboardType.Number, error = e[SubscriptionField.USES])
            FormTextField(form.lastUsedDaysAgo, { v -> onChange { it.copy(lastUsedDaysAgo = v) } }, "Days since last used (optional)",
                keyboardType = KeyboardType.Number, error = e[SubscriptionField.LAST_USED])
            FormTextField(form.sharedWith, { v -> onChange { it.copy(sharedWith = v) } }, "Shared with how many others (optional)",
                keyboardType = KeyboardType.Number, error = e[SubscriptionField.SHARED_WITH])

            SwitchRow("Cheaper plan available", form.hasCheaperTier, { v -> onChange { it.copy(hasCheaperTier = v) } },
                description = "A lower tier of the same service.")
            if (form.hasCheaperTier) {
                FormTextField(form.tierName, { v -> onChange { it.copy(tierName = v) } }, "Cheaper plan name",
                    error = e[SubscriptionField.TIER_NAME])
                BillingCycleSelector(form.tierBillingCycle) { c -> onChange { it.copy(tierBillingCycle = c) } }
                FormTextField(form.tierPrice, { v -> onChange { it.copy(tierPrice = v) } },
                    "Its price per ${form.tierBillingCycle.periodNoun()}", prefix = "₹", keyboardType = KeyboardType.Decimal,
                    error = e[SubscriptionField.TIER_PRICE])
                FormTextField(form.tierFeaturesLost, { v -> onChange { it.copy(tierFeaturesLost = v) } },
                    "Features you'd lose (comma-separated)", error = e[SubscriptionField.TIER_FEATURES])
            }
            SwitchRow("Must keep", form.mustKeep, { v -> onChange { it.copy(mustKeep = v) } },
                description = "Never suggest cancelling or downgrading this one.")

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(vertical = Spacing.md)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(if (editor.isNew) "Add" else "Save") }
            }
        }
    }
}

@Composable
private fun CategoryDropdown(selected: SubscriptionCategory, onSelect: (SubscriptionCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.small,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SubscriptionCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.label) },
                    onClick = {
                        onSelect(category)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(name = "Optimize – list", showBackground = true, heightDp = 900)
@Composable
private fun OptimizePreview() {
    SubscriptionOptimizerTheme {
        OptimizeScreen(
            OptimizeUiState(
                subscriptions = listOf(
                    Subscription(id = "netflix", name = "Netflix", category = SubscriptionCategory.VIDEO_STREAMING,
                        price = 649.0, usesPerMonth = 20, mustKeep = true),
                    Subscription(id = "spotify", name = "Spotify", category = SubscriptionCategory.MUSIC,
                        price = 119.0, usesPerMonth = 1),
                ),
                isRunning = true,
            ),
            remember { SnackbarHostState() },
            OptimizeActions(),
        )
    }
}
