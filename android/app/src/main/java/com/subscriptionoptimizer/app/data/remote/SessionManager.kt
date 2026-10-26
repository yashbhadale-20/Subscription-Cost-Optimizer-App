package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.data.repository.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs user-scoped calls with the session token for the configured backend URL and user ID.
 *
 * The first call for a URL + user pair asks `POST /auth/session` for a token and stores it. If the
 * backend later answers 401 (for example, its database was reset), the token is dropped and one new
 * session is requested before the call is retried. A 409 means another device owns the user ID; that
 * is reported, not worked around.
 */
class SessionManager(
    private val client: BackendClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) {
    private val mutex = Mutex()

    suspend fun <T> call(
        profile: CallProfile,
        block: suspend BackendApi.(auth: String, userId: String) -> T,
    ): Result<T> {
        val current = settings.settings.first()
        val baseUrl = current.backendUrl
        val userId = current.userId
        val key = "$baseUrl|$userId"

        val token = tokenFor(key, baseUrl, userId).getOrElse { return Result.failure(it) }
        val first = client.call(baseUrl, profile) { block("Bearer $token", userId) }
        if (first.exceptionOrNull() !is RepositoryError.Unauthorized) return first

        tokens.remove(key)
        val fresh = tokenFor(key, baseUrl, userId).getOrElse { return Result.failure(it) }
        return client.call(baseUrl, profile) { block("Bearer $fresh", userId) }
    }

    private suspend fun tokenFor(key: String, baseUrl: String, userId: String): Result<String> = mutex.withLock {
        tokens.get(key)?.let { return Result.success(it) }
        client.call(baseUrl, CallProfile.QUICK) { createSession(SessionRequestDto(userId)).token }
            .onSuccess { tokens.put(key, it) }
    }
}
