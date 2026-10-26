package com.subscriptionoptimizer.app.ui.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.subscriptionoptimizer.app.domain.Formatters
import com.subscriptionoptimizer.app.ui.common.PolicyEvidenceList
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatRole
import com.subscriptionoptimizer.app.preview.PreviewFixtures
import com.subscriptionoptimizer.app.ui.designsystem.components.AppCard
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.PulsingDots
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun AssistantRoute(viewModel: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AssistantScreen(
        state = state,
        suggestedPrompts = viewModel.suggestedPrompts,
        onInputChange = viewModel::onInputChange,
        onSend = { viewModel.send() },
        onSuggestion = { viewModel.send(it) },
        onRetry = viewModel::retry,
        onNewConversation = viewModel::newConversation,
        onShowConversations = viewModel::showConversations,
        onHideConversations = viewModel::hideConversations,
        onOpenConversation = viewModel::openConversation,
        onDeleteConversation = viewModel::deleteConversation,
        onDismissNotice = viewModel::dismissNotice,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    state: AssistantUiState,
    suggestedPrompts: List<String>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestion: (String) -> Unit,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit,
    onShowConversations: () -> Unit = {},
    onHideConversations: () -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onDismissNotice: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + (if (state.isSending) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "AI Assistant") {
                IconButton(onClick = onShowConversations) {
                    Icon(Icons.Outlined.Forum, contentDescription = "Saved conversations")
                }
                if (state.messages.isNotEmpty()) {
                    IconButton(onClick = onNewConversation) {
                        Icon(Icons.Outlined.Add, contentDescription = "New conversation")
                    }
                }
            }
        },
    ) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                state.threadError?.let { error ->
                    ErrorBanner(
                        title = "Couldn't open the conversation: ${error.title}",
                        message = error.message.orEmpty(),
                        icon = Icons.Outlined.CloudOff,
                        actionLabel = "Try again",
                        onAction = { onOpenConversation(state.threadId) },
                        modifier = Modifier.padding(Spacing.gutter),
                    )
                }
                if (state.isLoadingThread) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.messages.isEmpty()) {
                    Box(Modifier.weight(1f)) {
                        AssistantWelcome(suggestedPrompts, enabled = !state.isSending, onSuggestion = onSuggestion)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        state.notice?.let { notice ->
                            item(key = "notice") {
                                ErrorBanner(
                                    title = "Conversation restored",
                                    message = notice,
                                    tone = Tone.NEUTRAL,
                                    icon = Icons.Outlined.Info,
                                    actionLabel = "OK",
                                    onAction = onDismissNotice,
                                )
                            }
                        }
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message,
                                undelivered = state.error?.failedMessageId == message.id,
                            )
                        }
                        if (state.isSending) {
                            item(key = "typing") { TypingBubble() }
                        }
                        state.error?.let { error ->
                            item(key = "error") {
                                ErrorBanner(
                                    title = error.title,
                                    message = error.message,
                                    icon = Icons.Outlined.CloudOff,
                                    actionLabel = "Retry",
                                    onAction = onRetry,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MessageInput(state, onInputChange, onSend)
            }
        }
    }

    if (state.conversations.visible) {
        ConversationsSheet(
            state = state.conversations,
            currentThreadId = state.threadId,
            onDismiss = onHideConversations,
            onOpen = onOpenConversation,
            onDelete = onDeleteConversation,
            onRetry = onShowConversations,
        )
    }
}

@Composable
private fun ConversationsSheet(
    state: ConversationsState,
    currentThreadId: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text("Saved conversations", style = MaterialTheme.typography.titleLarge)
            Text(
                "Saved on the server under your user ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                ErrorBanner(title = it.title, message = it.message.orEmpty(), actionLabel = "Try again", onAction = onRetry)
            }
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() && state.error == null -> Text(
                    "No saved conversations yet. Send a message and it will be saved here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Spacing.lg),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(state.items, key = { it.threadId }) { convo ->
                        AppCard(onClick = { onOpen(convo.threadId) }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(convo.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text(
                                        "${convo.messageCount} message(s) · ${Formatters.dateTime(convo.updatedAtMillis)}" +
                                            if (convo.threadId == currentThreadId) " · open now" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { confirmDelete = convo.threadId }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete conversation")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this conversation?") },
            text = { Text("Its saved messages will be removed from the server.") },
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
private fun AssistantWelcome(prompts: List<String>, enabled: Boolean, onSuggestion: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Spacing.xl)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.size(Spacing.lg))
                Text(
                    "Ask about subscription costs",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    "Conversations are saved on the server under your user ID; reopen them from the " +
                        "list icon above. Policy sections used for a reply are shown under it. " +
                        "For exact figures, use Compare.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Text(
                "Try asking",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(prompts) { prompt ->
            AppCard(
                onClick = { if (enabled) onSuggestion(prompt) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(prompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, undelivered: Boolean) {
    val isUser = message.role == ChatRole.USER
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }
    val speaker = if (isUser) "You" else "Assistant"
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$speaker: ${message.text}" + if (undelivered) ". Not answered." else ""
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            AssistantAvatar()
            Spacer(Modifier.width(Spacing.sm))
        }
        Box(
            Modifier.fillMaxWidth(0.85f),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                Surface(
                    shape = shape,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                            .animateContentSize(),
                    )
                }
                if (!isUser && (message.evidence.isNotEmpty() || message.unsupportedCitations.isNotEmpty())) {
                    var showEvidence by rememberSaveable(message.id) { mutableStateOf(false) }
                    TextButton(onClick = { showEvidence = !showEvidence }) {
                        Text(
                            (if (showEvidence) "Hide" else "Show") + " policy evidence (${message.evidence.size})" +
                                if (message.evidence.any { it.cited }) " · cited" else "",
                        )
                    }
                    if (showEvidence) PolicyEvidenceList(message.evidence, message.unsupportedCitations)
                }
                if (undelivered) {
                    Text(
                        "Not answered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(
        Modifier.semantics {
            contentDescription = "Assistant is replying"
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar()
        Spacer(Modifier.width(Spacing.sm))
        Surface(
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            PulsingDots(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg))
        }
    }
}

@Composable
private fun MessageInput(state: AssistantUiState, onInputChange: (String) -> Unit, onSend: () -> Unit) {
    val tooLong = state.input.length > AssistantUiState.MAX_MESSAGE
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about a subscription…") },
            maxLines = 4,
            isError = tooLong,
            supportingText = if (tooLong) {
                { Text("Keep messages under ${AssistantUiState.MAX_MESSAGE} characters") }
            } else {
                null
            },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
        )
        Spacer(Modifier.width(Spacing.sm))
        FilledIconButton(
            onClick = onSend,
            enabled = state.canSend,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
        }
    }
}

@Preview(name = "Assistant – empty", showBackground = true)
@Composable
private fun AssistantEmptyPreview() {
    SubscriptionOptimizerTheme {
        AssistantScreen(
            AssistantUiState(threadId = "preview"),
            listOf("Is an annual plan cheaper than paying monthly?", "Which costs aren't included?"),
            {}, {}, {}, {}, {},
        )
    }
}

@Preview(name = "Assistant – sample conversation (dark)", showBackground = true)
@Composable
private fun AssistantConversationPreview() {
    // PREVIEW-ONLY: fixture messages.
    SubscriptionOptimizerTheme(darkTheme = true) {
        AssistantScreen(
            AssistantUiState(threadId = "preview", messages = PreviewFixtures.sampleChat, isSending = true),
            emptyList(),
            {}, {}, {}, {}, {},
        )
    }
}
