package com.subscriptionoptimizer.app.data.repository

import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatReply
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.ConnectionTestResult
import com.subscriptionoptimizer.app.data.model.ConversationSummary
import com.subscriptionoptimizer.app.data.model.PolicyAnswer
import com.subscriptionoptimizer.app.data.model.PolicyIndexStatus
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.model.TraceEvent
import com.subscriptionoptimizer.app.data.remote.BackendClient
import com.subscriptionoptimizer.app.data.remote.CallProfile
import com.subscriptionoptimizer.app.data.remote.ChatRequestDto
import com.subscriptionoptimizer.app.data.remote.PolicyAskRequestDto
import com.subscriptionoptimizer.app.data.remote.PreferencesRequestDto
import com.subscriptionoptimizer.app.data.remote.SessionManager
import com.subscriptionoptimizer.app.data.remote.profileAsText
import com.subscriptionoptimizer.app.data.remote.toDomain
import com.subscriptionoptimizer.app.data.remote.toDto
import com.subscriptionoptimizer.app.data.remote.toSaved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID

/*
 * Repositories backed by the FastAPI server. Each call reads the backend URL and user ID saved in
 * Settings at the moment it runs. User-scoped calls go through [SessionManager]. Failures surface as
 * [RepositoryError]; nothing here falls back to cached or invented data.
 */

class HttpComparisonRepository(private val sessions: SessionManager) : ComparisonRepository {
    override suspend fun compare(request: CompareRequest): Result<SavedComparison> =
        sessions.call(CallProfile.LLM) { auth, userId ->
            // The backend only accepts the session's own user ID.
            val sent = request.copy(userId = userId)
            compare(auth, sent.toDto()).toSaved(sent) { "unsaved-" + UUID.randomUUID() }
        }
}

class HttpChatRepository(private val sessions: SessionManager) : ChatRepository {
    override suspend fun send(threadId: String, message: String): Result<ChatReply> =
        sessions.call(CallProfile.LLM) { auth, userId ->
            val reply = chat(auth, ChatRequestDto(userId = userId, threadId = threadId, message = message)).toDomain()
            if (reply.text.isBlank()) throw RepositoryError.MalformedResponse("The assistant returned an empty reply.")
            reply
        }

    override suspend fun conversations(): Result<List<ConversationSummary>> =
        sessions.call(CallProfile.QUICK) { auth, _ -> conversations(auth).conversations.map { it.toDomain() } }

    override suspend fun messages(threadId: String): Result<List<ChatMessage>> =
        sessions.call(CallProfile.QUICK) { auth, _ -> conversation(auth, threadId).messages.map { it.toDomain() } }

    override suspend fun delete(threadId: String): Result<Unit> =
        sessions.call(CallProfile.QUICK) { auth, _ -> deleteConversation(auth, threadId) }
}

class HttpHistoryRepository(
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
) : HistoryRepository {

    private val _state = MutableStateFlow(HistoryState())
    override val state: StateFlow<HistoryState> = _state.asStateFlow()

    /** Everything fetched or created this session, including results the backend couldn't save. */
    private val cache = mutableMapOf<String, SavedComparison>()

    /** Backend URL + user the current list belongs to; switching either starts over. */
    private var owner: String? = null

    private suspend fun currentOwner(): String = settings.settings.first().let { "${it.backendUrl}|${it.userId}" }

    private suspend fun resetIfOwnerChanged() {
        val now = currentOwner()
        if (owner != now) {
            owner = now
            synchronized(cache) { cache.clear() }
            _state.value = HistoryState()
        }
    }

    override suspend fun refresh() {
        resetIfOwnerChanged()
        _state.update { it.copy(isLoading = true, error = null) }
        sessions.call(CallProfile.QUICK) { auth, _ -> history(auth, limit = PAGE_SIZE) }
            .onSuccess { page ->
                val entries = page.items.map { it.toDomain() }
                synchronized(cache) { entries.forEach { cache[it.id] = it } }
                _state.value = HistoryState(entries = entries, total = page.total, loaded = true)
            }
            .onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e as? RepositoryError ?: RepositoryError.MalformedResponse(e.message ?: "Error")) }
            }
    }

    override suspend fun get(id: String): Result<SavedComparison> {
        resetIfOwnerChanged()
        synchronized(cache) { cache[id] }?.let { return Result.success(it) }
        return sessions.call(CallProfile.QUICK) { auth, _ -> historyItem(auth, id).toDomain() }
            .onSuccess { saved -> synchronized(cache) { cache[saved.id] = saved } }
    }

    override suspend fun trace(id: String): Result<List<TraceEvent>> {
        synchronized(cache) { cache[id] }?.takeIf { !it.isSaved }?.let { return Result.success(it.result.traceEvents) }
        return sessions.call(CallProfile.QUICK) { auth, _ -> trace(auth, id).events.map { it.toDomain() } }
    }

    override fun add(saved: SavedComparison) {
        synchronized(cache) { cache[saved.id] = saved }
        if (saved.isSaved) {
            _state.update { it.copy(entries = listOf(saved) + it.entries.filterNot { e -> e.id == saved.id }, total = it.total + 1) }
        }
    }

    override suspend fun delete(id: String): Result<Unit> =
        sessions.call(CallProfile.QUICK) { auth, _ -> deleteHistoryItem(auth, id) }
            .onSuccess {
                synchronized(cache) { cache.remove(id) }
                _state.update { s -> s.copy(entries = s.entries.filterNot { it.id == id }, total = (s.total - 1).coerceAtLeast(0)) }
            }

    override suspend fun clear(): Result<Unit> =
        sessions.call(CallProfile.QUICK) { auth, _ -> clearHistory(auth) }
            .onSuccess {
                synchronized(cache) { cache.clear() }
                _state.update { it.copy(entries = emptyList(), total = 0) }
            }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

class HttpPreferencesRepository(private val sessions: SessionManager) : PreferencesRepository {
    override suspend fun defaultPriority(): Result<Priority?> =
        sessions.call(CallProfile.QUICK) { auth, _ -> preferences(auth).defaultPriority?.let(Priority::fromApi) }

    override suspend fun setDefaultPriority(priority: Priority?): Result<Priority?> =
        sessions.call(CallProfile.QUICK) { auth, _ ->
            setPreferences(auth, PreferencesRequestDto(priority?.apiValue)).defaultPriority?.let(Priority::fromApi)
        }
}

class HttpPolicyRepository(
    private val client: BackendClient,
    private val settings: SettingsRepository,
) : PolicyRepository {
    override suspend fun ask(question: String): Result<PolicyAnswer> =
        client.call(settings.settings.first().backendUrl, CallProfile.LLM) {
            askPolicy(PolicyAskRequestDto(question)).toDomain()
        }

    override suspend fun status(): Result<PolicyIndexStatus> =
        client.call(settings.settings.first().backendUrl, CallProfile.QUICK) { policyStatus().toDomain() }
}

class HttpProfileRepository(private val sessions: SessionManager) : ProfileRepository {
    override suspend fun profile(): Result<Map<String, String>> =
        sessions.call(CallProfile.QUICK) { auth, userId -> profile(auth, userId).profileAsText() }
}

/** Calls `GET /health`, then `GET /health/ollama`, against a URL that may not be saved yet. */
class HttpConnectionTester(private val client: BackendClient) : ConnectionTester {
    override suspend fun test(baseUrl: String): ConnectionTestResult {
        val health = client.call(baseUrl, CallProfile.QUICK) { health() }
            .getOrElse { return ConnectionTestResult.Failure(it.message ?: "Could not reach the backend.") }
        // The backend is reachable even if this probe fails, so report it separately.
        val ollama = client.call(baseUrl, CallProfile.QUICK) { ollamaHealth() }
        return ConnectionTestResult.Success(
            apiVersion = health.version,
            ollamaOk = ollama.getOrNull()?.ollamaOk,
            ollamaMessage = ollama.fold(
                onSuccess = { "${it.message} (LLM: ${it.llmModel}, embeddings: ${it.embeddingModel})" },
                onFailure = { it.message },
            ),
        )
    }
}
