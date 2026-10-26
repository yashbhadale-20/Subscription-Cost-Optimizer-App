package com.subscriptionoptimizer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.ConnectionTestResult
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.repository.ConnectionTester
import com.subscriptionoptimizer.app.data.repository.PreferencesRepository
import com.subscriptionoptimizer.app.data.repository.ProfileRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.domain.SettingsValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Done(val result: ConnectionTestResult, val testedUrl: String) : ConnectionState
}

/** The backend's saved profile for the current user ID (`GET /memory/{user_id}`). */
sealed interface ServerProfileState {
    data object Idle : ServerProfileState
    data object Loading : ServerProfileState
    data class Loaded(val userId: String, val entries: Map<String, String>) : ServerProfileState
    data class Failed(val title: String, val message: String) : ServerProfileState
}

/** The server-side default priority (`/preferences`). */
data class PreferencesState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val loaded: Boolean = false,
    val defaultPriority: Priority? = null,
    val error: RepositoryError? = null,
)

data class SettingsUiState(
    val loaded: Boolean = false,
    val saved: AppSettings = AppSettings(),
    val urlDraft: String = "",
    val userIdDraft: String = "",
    val urlError: String? = null,
    val userIdError: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val serverProfile: ServerProfileState = ServerProfileState.Idle,
    val preferences: PreferencesState = PreferencesState(),
    val savedMessage: String? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = loaded && (urlDraft.trim().trimEnd('/') != saved.backendUrl || userIdDraft.trim() != saved.userId)
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tester: ConnectionTester,
    private val profiles: ProfileRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val initial = settings.settings.first()
            _uiState.update {
                it.copy(loaded = true, saved = initial, urlDraft = initial.backendUrl, userIdDraft = initial.userId)
            }
            loadPreferences()
            settings.settings.collect { latest -> _uiState.update { it.copy(saved = latest) } }
        }
    }

    fun loadPreferences() {
        _uiState.update { it.copy(preferences = it.preferences.copy(isLoading = true, error = null)) }
        viewModelScope.launch {
            preferences.defaultPriority()
                .onSuccess { p ->
                    _uiState.update { it.copy(preferences = PreferencesState(loaded = true, defaultPriority = p)) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(preferences = it.preferences.copy(isLoading = false, error = e as? RepositoryError))
                    }
                }
        }
    }

    /** Null clears the preference, so Compare starts on its built-in default. */
    fun setDefaultPriority(priority: Priority?) {
        if (_uiState.value.preferences.isSaving) return
        _uiState.update { it.copy(preferences = it.preferences.copy(isSaving = true, error = null)) }
        viewModelScope.launch {
            preferences.setDefaultPriority(priority)
                .onSuccess { p ->
                    _uiState.update { it.copy(preferences = PreferencesState(loaded = true, defaultPriority = p)) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(preferences = it.preferences.copy(isSaving = false, error = e as? RepositoryError))
                    }
                }
        }
    }

    fun onUrlChange(text: String) =
        // A result for a different URL would be misleading, so drop it.
        _uiState.update {
            it.copy(urlDraft = text, urlError = null, savedMessage = null, connection = ConnectionState.Idle)
        }

    fun onUserIdChange(text: String) =
        _uiState.update { it.copy(userIdDraft = text, userIdError = null, savedMessage = null) }

    fun save() {
        val state = _uiState.value
        val urlError = SettingsValidation.backendUrlError(state.urlDraft)
        val userIdError = SettingsValidation.userIdError(state.userIdDraft)
        if (urlError != null || userIdError != null) {
            _uiState.update { it.copy(urlError = urlError, userIdError = userIdError) }
            return
        }
        viewModelScope.launch {
            settings.setBackendUrl(state.urlDraft)
            settings.setUserId(state.userIdDraft)
            // Server data belongs to the URL + user pair, so reload what depends on it.
            _uiState.update { it.copy(savedMessage = "Settings saved", serverProfile = ServerProfileState.Idle) }
            loadPreferences()
        }
    }

    fun useEmulatorUrl() = onUrlChange(AppSettings.EMULATOR_BACKEND_URL)

    /** Tests the URL currently typed, so it can be checked before saving. */
    fun testConnection() {
        val url = _uiState.value.urlDraft
        val error = SettingsValidation.backendUrlError(url)
        if (error != null) {
            _uiState.update { it.copy(urlError = error) }
            return
        }
        _uiState.update { it.copy(connection = ConnectionState.Testing) }
        viewModelScope.launch {
            val result = tester.test(url)
            _uiState.update { it.copy(connection = ConnectionState.Done(result, url.trim())) }
        }
    }

    /** Uses the saved URL and user ID, since that is what comparisons are sent with. */
    fun loadServerProfile() {
        if (_uiState.value.serverProfile == ServerProfileState.Loading) return
        _uiState.update { it.copy(serverProfile = ServerProfileState.Loading) }
        viewModelScope.launch {
            val userId = settings.settings.first().userId
            val next = profiles.profile().fold(
                onSuccess = { ServerProfileState.Loaded(userId, it) },
                onFailure = {
                    ServerProfileState.Failed(
                        (it as? RepositoryError)?.title ?: "Couldn't load profile",
                        it.message ?: "Something went wrong.",
                    )
                },
            )
            _uiState.update { it.copy(serverProfile = next) }
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    companion object {
        val Factory = appViewModelFactory { SettingsViewModel(it.settingsRepository, it.connectionTester, it.profileRepository, it.preferencesRepository) }
    }
}
