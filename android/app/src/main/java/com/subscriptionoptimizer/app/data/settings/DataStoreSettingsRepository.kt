package com.subscriptionoptimizer.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            backendUrl = prefs[KEY_BACKEND_URL] ?: AppSettings.DEFAULT_BACKEND_URL,
            userId = prefs[KEY_USER_ID] ?: AppSettings.DEFAULT_USER_ID,
            theme = prefs[KEY_THEME]
                ?.let { stored -> ThemePreference.entries.firstOrNull { it.name == stored } }
                ?: ThemePreference.SYSTEM,
        )
    }

    override suspend fun setBackendUrl(url: String) {
        dataStore.edit { it[KEY_BACKEND_URL] = url.trim().trimEnd('/') }
    }

    override suspend fun setUserId(userId: String) {
        dataStore.edit { it[KEY_USER_ID] = userId.trim() }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[KEY_THEME] = theme.name }
    }

    private companion object {
        val KEY_BACKEND_URL = stringPreferencesKey("backend_url")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_THEME = stringPreferencesKey("theme")
    }
}
