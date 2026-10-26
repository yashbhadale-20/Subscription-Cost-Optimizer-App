package com.subscriptionoptimizer.app.data.repository

import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatReply
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.ComparisonResult
import com.subscriptionoptimizer.app.data.model.ConnectionTestResult
import com.subscriptionoptimizer.app.data.model.ConversationSummary
import com.subscriptionoptimizer.app.data.model.PolicyAnswer
import com.subscriptionoptimizer.app.data.model.PolicyIndexStatus
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.model.TraceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Runs a comparison through the backend's `POST /compare`; the backend saves it to history. */
interface ComparisonRepository {
    suspend fun compare(request: CompareRequest): Result<SavedComparison>
}

/** The assistant (`POST /chat`) and its saved conversations (`/conversations`). */
interface ChatRepository {
    suspend fun send(threadId: String, message: String): Result<ChatReply>
    suspend fun conversations(): Result<List<ConversationSummary>>
    suspend fun messages(threadId: String): Result<List<ChatMessage>>
    suspend fun delete(threadId: String): Result<Unit>
}

/** Snapshot of the server-side history list as last loaded. */
data class HistoryState(
    val entries: List<SavedComparison> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    /** True once a load has succeeded for the current backend and user. */
    val loaded: Boolean = false,
    val error: RepositoryError? = null,
)

/** Comparisons saved on the backend (`/history`), with a local cache of what has been fetched. */
interface HistoryRepository {
    val state: StateFlow<HistoryState>

    suspend fun refresh()

    /** From the cache when possible, otherwise `GET /history/{id}`. */
    suspend fun get(id: String): Result<SavedComparison>

    /** `GET /history/{id}/trace`. */
    suspend fun trace(id: String): Result<List<TraceEvent>>

    /** Adds a comparison that was just returned by `POST /compare`. */
    fun add(saved: SavedComparison)

    suspend fun delete(id: String): Result<Unit>
    suspend fun clear(): Result<Unit>
}

/** `GET`/`PUT /preferences`. */
interface PreferencesRepository {
    suspend fun defaultPriority(): Result<Priority?>
    suspend fun setDefaultPriority(priority: Priority?): Result<Priority?>
}

/** Grounded policy answers (`POST /policy/ask`) and index status (`GET /policy/status`). */
interface PolicyRepository {
    suspend fun ask(question: String): Result<PolicyAnswer>
    suspend fun status(): Result<PolicyIndexStatus>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setBackendUrl(url: String)
    suspend fun setUserId(userId: String)
    suspend fun setTheme(theme: ThemePreference)
}

/** Probes the backend's health endpoints. */
interface ConnectionTester {
    suspend fun test(baseUrl: String): ConnectionTestResult
}

/** The signed-in user's long-term profile the backend keeps in SQLite (`GET /memory/{user_id}`). */
interface ProfileRepository {
    suspend fun profile(): Result<Map<String, String>>
}

/** Session tokens from `POST /auth/session`, one per backend URL and user ID. */
interface TokenStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, token: String)
    suspend fun remove(key: String)
}

/**
 * Errors the UI distinguishes when a backend call fails. [title] is a short heading; the message
 * says what happened and what to try.
 */
sealed class RepositoryError(
    val title: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No usable backend URL is configured. */
    class NotConfigured(message: String) : RepositoryError("Backend not set up", message)

    /** The server could not be reached at all (refused, unknown host, blocked cleartext, TLS). */
    class Unreachable(message: String, cause: Throwable? = null) :
        RepositoryError("Can't reach the backend", message, cause)

    /** The client gave up waiting, or the backend reported that the model timed out. */
    class Timeout(message: String, cause: Throwable? = null) : RepositoryError("Request timed out", message, cause)

    /** The backend is up but Ollama or its models are not (HTTP 503 from `require_ollama`). */
    class OllamaUnavailable(message: String) : RepositoryError("AI model service unavailable", message)

    /** The backend rejected the input (HTTP 422: validation or guardrail). */
    class Rejected(message: String) : RepositoryError("Request rejected", message)

    class Server(val code: Int, message: String) : RepositoryError("Server error (HTTP $code)", message)

    /** HTTP 401: the session token was missing or not recognised. */
    class Unauthorized(message: String) : RepositoryError("Session not accepted", message)

    /** HTTP 403: the session belongs to a different user. */
    class Forbidden(message: String) : RepositoryError("Not allowed", message)

    /** HTTP 409 from `POST /auth/session`: another device already registered this user ID. */
    class UserIdTaken(message: String) : RepositoryError("User ID unavailable", message)

    /** HTTP 404 for a record (e.g. a deleted comparison). */
    class NotFound(message: String) : RepositoryError("Not found", message)

    /** The backend answered 2xx but the body didn't match the expected schema. */
    class MalformedResponse(message: String, cause: Throwable? = null) :
        RepositoryError("Unexpected response", message, cause)
}
