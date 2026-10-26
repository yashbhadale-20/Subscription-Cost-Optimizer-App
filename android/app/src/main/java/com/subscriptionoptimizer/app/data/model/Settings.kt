package com.subscriptionoptimizer.app.data.model

import com.subscriptionoptimizer.app.BuildConfig

enum class ThemePreference(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppSettings(
    val backendUrl: String = DEFAULT_BACKEND_URL,
    val userId: String = DEFAULT_USER_ID,
    val theme: ThemePreference = ThemePreference.SYSTEM,
) {
    companion object {
        /**
         * Debug: `http://10.0.2.2:8000`, the emulator's alias for the host machine. Release: the
         * `releaseBackendUrl` Gradle property (HTTPS), or empty so the user must enter one.
         */
        const val DEFAULT_BACKEND_URL = BuildConfig.DEFAULT_BACKEND_URL

        /** Address of the development host as seen from the Android emulator. */
        const val EMULATOR_BACKEND_URL = "http://10.0.2.2:8000"
        const val DEFAULT_USER_ID = "android_user"
    }
}

/** Outcome of probing the backend's `/health` and `/health/ollama` endpoints. */
sealed interface ConnectionTestResult {
    data class Success(
        val apiVersion: String,
        val ollamaOk: Boolean?,
        val ollamaMessage: String?,
    ) : ConnectionTestResult

    data class Failure(val message: String) : ConnectionTestResult
}
