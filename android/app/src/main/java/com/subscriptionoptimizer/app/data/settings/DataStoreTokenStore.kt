package com.subscriptionoptimizer.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.subscriptionoptimizer.app.data.repository.TokenStore
import kotlinx.coroutines.flow.first

// A separate file from user settings, and excluded from backups (android:allowBackup="false").
private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "sessions")

class DataStoreTokenStore(context: Context) : TokenStore {
    private val dataStore = context.applicationContext.sessionDataStore

    override suspend fun get(key: String): String? = dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun put(key: String, token: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = token }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}
