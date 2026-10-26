package com.subscriptionoptimizer.app.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.domain.CompareValidation
import com.subscriptionoptimizer.app.domain.PlanForm
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.FormTextField
import com.subscriptionoptimizer.app.ui.designsystem.components.LoadingButton
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun CompareRoute(
    onResult: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CompareViewModel = viewModel(factory = CompareViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CompareEvent.OpenResult -> onResult(event.id)
                is CompareEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    CompareScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actionsFor = { slot ->
            PlanFormActions(
                onChange = { transform -> viewModel.updatePlan(slot, transform) },
                onFeatureDraftChange = { viewModel.setFeatureDraft(slot, it) },
                onAddFeature = { viewModel.addFeature(slot) },
                onRemoveFeature = { viewModel.removeFeature(slot, it) },
                onAddUsageLimit = { viewModel.addUsageLimit(slot) },
                onUpdateUsageLimit = { viewModel.updateUsageLimit(slot, it) },
                onRemoveUsageLimit = { viewModel.removeUsageLimit(slot, it) },
            )
        },
        onPriorityChange = viewModel::setPriority,
        onQuestionChange = viewModel::setQuestion,
        onSubmit = viewModel::submit,
        onReset = viewModel::reset,
        onDismissError = viewModel::dismissSubmitError,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareScreen(
    state: CompareUiState,
    snackbarHostState: SnackbarHostState,
    actionsFor: (PlanSlot) -> PlanFormActions,
    onPriorityChange: (Priority) -> Unit,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var selectedSlot by rememberSaveable { mutableStateOf(PlanSlot.A) }

    Scaffold(
        topBar = {
            AppTopBar(title = "Compare plans") {
                IconButton(onClick = { confirmReset = true }) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = "Clear form")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            maxWidth = 1100.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    "Enter both plans as advertised. Calculations are done by the backend's " +
                        "deterministic engine; the AI only explains them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                BoxWithConstraints {
                    if (maxWidth >= 720.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            PlanSlot.entries.forEach { slot ->
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        slot.label,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.size(Spacing.sm))
                                    PlanFormSection(slot, state.editor(slot), state.showErrors, actionsFor(slot))
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            PlanSwitcher(selectedSlot, state, onSelect = { selectedSlot = it })
                            PlanFormSection(
                                selectedSlot,
                                state.editor(selectedSlot),
                                state.showErrors,
                                actionsFor(selectedSlot),
                            )
                        }
                    }
                }

                PrioritySection(state.priority, onPriorityChange)

                SectionCard(title = "Your question (optional)", icon = Icons.Outlined.QuestionAnswer) {
                    FormTextField(
                        value = state.question,
                        onValueChange = onQuestionChange,
                        label = "What would you like to know?",
                        placeholder = "e.g. Which plan suits a student budget?",
                        singleLine = false,
                        minLines = 2,
                        imeAction = ImeAction.Default,
                        maxLength = CompareValidation.MAX_QUESTION,
                        error = if (state.showErrors) state.questionError else null,
                        supporting = "Used to focus the AI explanation",
                    )
                }

                state.submitError?.let { error ->
                    ErrorBanner(
                        title = error.title,
                        message = error.message + "\nYour entries are kept.",
                        actionLabel = "Try again",
                        onAction = onSubmit,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TextButton(onClick = onOpenSettings) { Text("Check backend settings") }
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }

                LoadingButton(
                    text = "Compare plans",
                    loading = state.isSubmitting,
                    loadingText = "Comparing…",
                    onClick = onSubmit,
                    icon = Icons.Outlined.SwapHoriz,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isSubmitting) {
                    Text(
                        "Local models can take a minute or two to write the explanation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.submitError == null) {
                    TextButton(onClick = onOpenSettings) { Text("Backend settings") }
                }
                Spacer(Modifier.size(Spacing.lg))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Clear both plans?") },
            text = { Text("This removes everything you've entered for Plan A and Plan B.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    selectedSlot = PlanSlot.A
                    onReset()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSwitcher(selected: PlanSlot, state: CompareUiState, onSelect: (PlanSlot) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PlanSlot.entries.forEachIndexed { index, slot ->
            val errorCount = if (state.showErrors) state.editor(slot).errors.size else 0
            val name = state.editor(slot).form.name.trim()
            SegmentedButton(
                selected = slot == selected,
                onClick = { onSelect(slot) },
                shape = SegmentedButtonDefaults.itemShape(index, PlanSlot.entries.size),
                modifier = Modifier.semantics {
                    contentDescription = buildString {
                        append(slot.label)
                        if (name.isNotEmpty()) append(", ").append(name)
                        if (errorCount > 0) append(", ").append(errorCount).append(" problem(s)")
                    }
                },
                label = {
                    if (errorCount > 0) {
                        BadgedBox(badge = { Badge { Text("$errorCount") } }) { Text(slot.label) }
                    } else {
                        Text(slot.label)
                    }
                },
            )
        }
    }
}

@Composable
private fun PrioritySection(selected: Priority, onSelect: (Priority) -> Unit) {
    SectionCard(title = "What matters most?", icon = Icons.Outlined.Tune) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(vertical = Spacing.xs),
        ) {
            items(Priority.entries) { priority ->
                FilterChip(
                    selected = priority == selected,
                    onClick = { onSelect(priority) },
                    label = { Text(priority.label) },
                    leadingIcon = if (priority == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start),
        )
    }
}

private val previewActions = PlanFormActions({}, {}, {}, {}, {}, {}, {})

@Preview(name = "Compare – phone", showBackground = true, heightDp = 1400)
@Composable
private fun ComparePhonePreview() {
    SubscriptionOptimizerTheme {
        CompareScreen(
            state = CompareUiState(
                planA = PlanEditorState(PlanForm(name = "Basic", price = "299", features = listOf("HD", "2 screens"))),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            actionsFor = { previewActions },
            onPriorityChange = {}, onQuestionChange = {}, onSubmit = {}, onReset = {},
            onDismissError = {}, onOpenSettings = {},
        )
    }
}

@Preview(name = "Compare – tablet, errors", showBackground = true, device = Devices.TABLET)
@Composable
private fun CompareTabletPreview() {
    SubscriptionOptimizerTheme {
        CompareScreen(
            state = CompareUiState(
                showErrors = true,
                planA = PlanEditorState(
                    errors = CompareValidation.validatePlan(PlanForm()),
                ),
                submitError = SubmitError("AI model service unavailable", "Ollama is not running."),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            actionsFor = { previewActions },
            onPriorityChange = {}, onQuestionChange = {}, onSubmit = {}, onReset = {},
            onDismissError = {}, onOpenSettings = {},
        )
    }
}
