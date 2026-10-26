package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.BuildConfig
import java.net.URI

object SettingsValidation {
    private val USER_ID = Regex("^[a-zA-Z0-9_\\-]{1,64}$")

    /** [allowCleartext] is false in release builds, which only connect over HTTPS. */
    fun backendUrlError(url: String, allowCleartext: Boolean = BuildConfig.ALLOW_CLEARTEXT): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter the backend URL"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return if (allowCleartext) "Start with http:// or https://" else "Start with https://"
        }
        if (trimmed.startsWith("http://") && !allowCleartext) return "Release builds require https://"
        val host = runCatching { URI(trimmed).host }.getOrNull()
        return if (host.isNullOrBlank()) "Enter a valid address, e.g. http://10.0.2.2:8000" else null
    }

    /** Matches the backend's `user_id` pattern. */
    fun userIdError(userId: String): String? = when {
        userId.isBlank() -> "Enter a user ID"
        !USER_ID.matches(userId.trim()) -> "Use up to 64 letters, numbers, - or _"
        else -> null
    }
}
