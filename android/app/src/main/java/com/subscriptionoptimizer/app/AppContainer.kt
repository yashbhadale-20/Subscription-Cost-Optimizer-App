package com.subscriptionoptimizer.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.subscriptionoptimizer.app.data.remote.BackendClient
import com.subscriptionoptimizer.app.data.remote.SessionManager
import com.subscriptionoptimizer.app.data.repository.ChatRepository
import com.subscriptionoptimizer.app.data.repository.ComparisonRepository
import com.subscriptionoptimizer.app.data.repository.ConnectionTester
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.HttpChatRepository
import com.subscriptionoptimizer.app.data.repository.HttpComparisonRepository
import com.subscriptionoptimizer.app.data.repository.HttpConnectionTester
import com.subscriptionoptimizer.app.data.repository.HttpHistoryRepository
import com.subscriptionoptimizer.app.data.repository.HttpPolicyRepository
import com.subscriptionoptimizer.app.data.repository.HttpPreferencesRepository
import com.subscriptionoptimizer.app.data.repository.HttpProfileRepository
import com.subscriptionoptimizer.app.data.repository.PolicyRepository
import com.subscriptionoptimizer.app.data.repository.PreferencesRepository
import com.subscriptionoptimizer.app.data.repository.ProfileRepository
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.data.settings.DataStoreSettingsRepository
import com.subscriptionoptimizer.app.data.settings.DataStoreTokenStore

/** Manual dependency container, created once by [SubscriptionOptimizerApplication]. */
class AppContainer(context: Context) {
    private val backendClient = BackendClient(
        allowCleartext = BuildConfig.ALLOW_CLEARTEXT,
        enableLogging = BuildConfig.DEBUG,
    )

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context)
    private val sessions = SessionManager(backendClient, settingsRepository, DataStoreTokenStore(context))

    val historyRepository: HistoryRepository = HttpHistoryRepository(sessions, settingsRepository)
    val comparisonRepository: ComparisonRepository = HttpComparisonRepository(sessions)
    val chatRepository: ChatRepository = HttpChatRepository(sessions)
    val preferencesRepository: PreferencesRepository = HttpPreferencesRepository(sessions)
    val policyRepository: PolicyRepository = HttpPolicyRepository(backendClient, settingsRepository)
    val profileRepository: ProfileRepository = HttpProfileRepository(sessions)
    val connectionTester: ConnectionTester = HttpConnectionTester(backendClient)
}

/** Builds a ViewModel factory that receives the app's [AppContainer]. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: CreationExtras.(AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as SubscriptionOptimizerApplication
        this.create(app.container)
    }
}
