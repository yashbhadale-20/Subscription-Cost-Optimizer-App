package com.subscriptionoptimizer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.BuildConfig
import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.ConnectionTestResult
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.ui.designsystem.components.AppTopBar
import com.subscriptionoptimizer.app.ui.designsystem.components.CenteredContent
import com.subscriptionoptimizer.app.ui.designsystem.components.ErrorBanner
import com.subscriptionoptimizer.app.ui.designsystem.components.FormTextField
import com.subscriptionoptimizer.app.ui.designsystem.components.KeyValueRow
import com.subscriptionoptimizer.app.ui.designsystem.components.LoadingButton
import com.subscriptionoptimizer.app.ui.designsystem.components.SectionCard
import com.subscriptionoptimizer.app.ui.designsystem.components.StatusPill
import com.subscriptionoptimizer.app.ui.designsystem.components.Tone
import com.subscriptionoptimizer.app.ui.designsystem.theme.Spacing
import com.subscriptionoptimizer.app.ui.designsystem.theme.SubscriptionOptimizerTheme

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onUserIdChange = viewModel::onUserIdChange,
        onSave = viewModel::save,
        onUseEmulatorUrl = viewModel::useEmulatorUrl,
        onTestConnection = viewModel::testConnection,
        onLoadServerProfile = viewModel::loadServerProfile,
        onThemeChange = viewModel::setTheme,
        onDefaultPriorityChange = viewModel::setDefaultPriority,
        onRetryPreferences = viewModel::loadPreferences,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onUseEmulatorUrl: () -> Unit,
    onTestConnection: () -> Unit,
    onLoadServerProfile: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onDefaultPriorityChange: (Priority?) -> Unit = {},
    onRetryPreferences: () -> Unit = {},
) {
    Scaffold(topBar = { AppTopBar(title = "Settings") }) { padding ->
        CenteredContent(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                BackendSection(state, onUrlChange, onUserIdChange, onSave, onUseEmulatorUrl, onTestConnection)
                PreferencesSection(state, onDefaultPriorityChange, onRetryPreferences)
                ServerProfileSection(state, onLoadServerProfile)
                ThemeSection(state.saved.theme, onThemeChange)
                AboutSection(state)
                Spacer(Modifier.size(Spacing.lg))
            }
        }
    }
}

@Composable
private fun BackendSection(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onUseEmulatorUrl: () -> Unit,
    onTestConnection: () -> Unit,
) {
    SectionCard(title = "Backend", icon = Icons.Outlined.Link) {
        FormTextField(
            value = state.urlDraft,
            onValueChange = onUrlChange,
            label = "Backend URL",
            placeholder = AppSettings.DEFAULT_BACKEND_URL.ifEmpty { "https://api.example.com" },
            keyboardType = KeyboardType.Uri,
            error = state.urlError,
            supporting = if (BuildConfig.ALLOW_CLEARTEXT) {
                "Emulator: http://10.0.2.2:8000. Phone on the same Wi-Fi: your computer's LAN IP, " +
                    "e.g. http://192.168.1.20:8000. Not localhost: that is the device itself."
            } else {
                "The HTTPS address of the deployed backend."
            },
            enabled = state.loaded,
        )
        if (BuildConfig.ALLOW_CLEARTEXT) {
            TextButton(onClick = onUseEmulatorUrl, enabled = state.loaded) { Text("Use emulator address") }
        }
        FormTextField(
            value = state.userIdDraft,
            onValueChange = onUserIdChange,
            label = "User ID",
            error = state.userIdError,
            supporting = "Identifies your saved profile on the backend",
            imeAction = ImeAction.Done,
            onImeAction = onSave,
            enabled = state.loaded,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onSave,
                enabled = state.hasUnsavedChanges,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Save") }
            LoadingButton(
                text = "Test connection",
                loadingText = "Testing…",
                loading = state.connection == ConnectionState.Testing,
                onClick = onTestConnection,
                icon = Icons.Outlined.NetworkCheck,
                enabled = state.loaded,
                modifier = Modifier.weight(1.4f),
            )
        }
        state.savedMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ConnectionResult(state.connection)
    }
}

@Composable
private fun ConnectionResult(connection: ConnectionState) {
    val done = connection as? ConnectionState.Done ?: return
    when (val result = done.result) {
        is ConnectionTestResult.Success -> {
            ErrorBanner(
                title = "Connected to ${done.testedUrl}",
                message = buildString {
                    append("API version ").append(result.apiVersion).append(". ")
                    when (result.ollamaOk) {
                        true -> append("Ollama and the required models are available.")
                        false -> append("Ollama issue: ").append(result.ollamaMessage.orEmpty())
                        null -> append("Ollama status could not be checked.")
                    }
                },
                tone = if (result.ollamaOk == true) Tone.SAVINGS else Tone.WARNING,
                icon = if (result.ollamaOk == true) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            )
        }
        is ConnectionTestResult.Failure -> ErrorBanner(
            title = "Couldn't reach ${done.testedUrl}",
            message = result.message,
            icon = Icons.Outlined.CloudOff,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferencesSection(
    state: SettingsUiState,
    onChange: (Priority?) -> Unit,
    onRetry: () -> Unit,
) {
    val prefs = state.preferences
    SectionCard(title = "Comparison priority", icon = Icons.Outlined.Tune) {
        Text(
            "Saved on the server for ${state.saved.userId}. New comparisons start with this priority.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        prefs.error?.let {
            ErrorBanner(title = it.title, message = it.message.orEmpty(), actionLabel = "Try again", onAction = onRetry)
        }
        if (prefs.isLoading) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        } else if (prefs.loaded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = prefs.defaultPriority == null,
                    onClick = { onChange(null) },
                    enabled = !prefs.isSaving,
                    label = { Text("No default") },
                )
                Priority.entries.forEach { p ->
                    FilterChip(
                        selected = prefs.defaultPriority == p,
                        onClick = { onChange(p) },
                        enabled = !prefs.isSaving,
                        label = { Text(p.label) },
                    )
                }
            }
            prefs.defaultPriority?.let {
                Text(it.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServerProfileSection(state: SettingsUiState, onLoad: () -> Unit) {
    SectionCard(title = "Saved on the server", icon = Icons.Outlined.Storage) {
        Text(
            "The backend remembers your last comparison under your saved user ID (${state.saved.userId}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val profile = state.serverProfile) {
            ServerProfileState.Idle, ServerProfileState.Loading -> Unit
            is ServerProfileState.Loaded ->
                if (profile.entries.isEmpty()) {
                    Text("Nothing saved for ${profile.userId} yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    profile.entries.toSortedMap().forEach { (key, value) ->
                        KeyValueRow(key.replace('_', ' ').replaceFirstChar { it.uppercase() }, value)
                    }
                }
            is ServerProfileState.Failed -> ErrorBanner(
                title = profile.title,
                message = profile.message,
                icon = Icons.Outlined.CloudOff,
            )
        }
        LoadingButton(
            text = if (state.serverProfile is ServerProfileState.Loaded) "Refresh" else "Load saved profile",
            loadingText = "Loading…",
            loading = state.serverProfile == ServerProfileState.Loading,
            onClick = onLoad,
            enabled = state.loaded,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThemeSection(selected: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    SectionCard(title = "Appearance", icon = Icons.Outlined.Palette) {
        Column(Modifier.selectableGroup()) {
            ThemePreference.entries.forEach { theme ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(
                            selected = theme == selected,
                            onClick = { onSelect(theme) },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == selected, onClick = null)
                    Spacer(Modifier.width(Spacing.md))
                    Icon(theme.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.md))
                    Text(theme.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun ThemePreference.icon(): ImageVector = when (this) {
    ThemePreference.SYSTEM -> Icons.Outlined.SettingsBrightness
    ThemePreference.LIGHT -> Icons.Outlined.LightMode
    ThemePreference.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun AboutSection(state: SettingsUiState) {
    SectionCard(title = "About", icon = Icons.Outlined.Info) {
        KeyValueRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        KeyValueRow("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
        val apiVersion = ((state.connection as? ConnectionState.Done)?.result as? ConnectionTestResult.Success)?.apiVersion
        KeyValueRow("Backend API version", apiVersion ?: "Run a connection test")
        Spacer(Modifier.size(Spacing.sm))
        Text("Backend integration", style = MaterialTheme.typography.labelLarge)
        IntegrationRow("Connection test (GET /health, /health/ollama)", connected = true)
        IntegrationRow("Plan comparison (POST /compare)", connected = true)
        IntegrationRow("AI assistant (POST /chat)", connected = true)
        IntegrationRow("Saved comparisons and traces (/history)", connected = true)
        IntegrationRow("Saved conversations (/conversations)", connected = true)
        IntegrationRow("Policy questions (POST /policy/ask)", connected = true)
        IntegrationRow("Preferences and profile (/preferences, /memory)", connected = true)
    }
}

@Composable
private fun IntegrationRow(label: String, connected: Boolean, pendingLabel: String = "Not connected yet") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusPill(
            text = if (connected) "Available" else pendingLabel,
            tone = if (connected) Tone.SAVINGS else Tone.NEUTRAL,
        )
    }
}

@Preview(name = "Settings", showBackground = true, heightDp = 1300)
@Composable
private fun SettingsPreview() {
    SubscriptionOptimizerTheme {
        SettingsScreen(
            state = SettingsUiState(
                loaded = true,
                urlDraft = AppSettings.DEFAULT_BACKEND_URL,
                userIdDraft = AppSettings.DEFAULT_USER_ID,
                connection = ConnectionState.Done(
                    ConnectionTestResult.Failure("Failed to connect to /10.0.2.2:8000"),
                    AppSettings.DEFAULT_BACKEND_URL,
                ),
            ),
            onUrlChange = {}, onUserIdChange = {}, onSave = {}, onUseEmulatorUrl = {},
            onTestConnection = {}, onLoadServerProfile = {}, onThemeChange = {},
        )
    }
}

@Preview(name = "Settings (dark)", showBackground = true, heightDp = 1300, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    SubscriptionOptimizerTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsUiState(loaded = true, urlDraft = AppSettings.DEFAULT_BACKEND_URL, userIdDraft = "student_101"),
            onUrlChange = {}, onUserIdChange = {}, onSave = {}, onUseEmulatorUrl = {},
            onTestConnection = {}, onLoadServerProfile = {}, onThemeChange = {},
        )
    }
}
