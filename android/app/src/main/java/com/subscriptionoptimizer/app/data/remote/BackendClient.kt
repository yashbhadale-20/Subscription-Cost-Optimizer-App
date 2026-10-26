package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** How long a call may take. LLM-backed routes wait on a local model; health probes should fail fast. */
enum class CallProfile(val connectSeconds: Long, val readSeconds: Long, val callSeconds: Long) {
    QUICK(connectSeconds = 5, readSeconds = 10, callSeconds = 15),

    /** The backend allows the model 180 s (`llm_timeout_seconds`) plus retrieval, so wait a bit longer. */
    LLM(connectSeconds = 10, readSeconds = 240, callSeconds = 270),
}

/**
 * Builds [BackendApi] instances for whatever base URL is configured and runs calls against them,
 * translating every failure into a [RepositoryError]. Never substitutes a fallback result.
 */
class BackendClient(
    private val allowCleartext: Boolean,
    enableLogging: Boolean = false,
) {
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .apply {
            // BASIC logs method, URL, status and timing only — no bodies or headers.
            if (enableLogging) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    private val apis = mutableMapOf<Pair<HttpUrl, CallProfile>, BackendApi>()

    suspend fun <T> call(
        baseUrl: String,
        profile: CallProfile,
        block: suspend BackendApi.() -> T,
    ): Result<T> {
        val url = parseBaseUrl(baseUrl).getOrElse { return Result.failure(it) }
        return try {
            Result.success(api(url, profile).block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(mapError(e, url, profile))
        }
    }

    private fun parseBaseUrl(raw: String): Result<HttpUrl> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(RepositoryError.NotConfigured("Enter the backend URL in Settings."))
        }
        val url = (trimmed.trimEnd('/') + "/").toHttpUrlOrNull()
            ?: return Result.failure(
                RepositoryError.NotConfigured("\"$trimmed\" isn't a valid URL. Fix it in Settings."),
            )
        if (!url.isHttps && !allowCleartext) {
            return Result.failure(
                RepositoryError.NotConfigured("This build only connects over HTTPS. Use an https:// backend URL."),
            )
        }
        return Result.success(url)
    }

    private fun api(url: HttpUrl, profile: CallProfile): BackendApi = synchronized(apis) {
        apis.getOrPut(url to profile) {
            val client = baseClient.newBuilder()
                .connectTimeout(profile.connectSeconds, TimeUnit.SECONDS)
                .readTimeout(profile.readSeconds, TimeUnit.SECONDS)
                .callTimeout(profile.callSeconds, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BackendApi::class.java)
        }
    }

    internal fun mapError(e: Throwable, url: HttpUrl, profile: CallProfile): RepositoryError {
        val origin = url.toString().trimEnd('/')
        return when (e) {
            is RepositoryError -> e
            is HttpException -> httpError(e, origin)
            // Before IOException: SocketTimeoutException is one, and so is OkHttp's call timeout.
            is SocketTimeoutException, is InterruptedIOException -> timeoutError(e, url, profile)
            is UnknownHostException -> RepositoryError.Unreachable(
                "Couldn't find the host \"${url.host}\". Check the backend URL in Settings.",
                e,
            )
            is ConnectException, is NoRouteToHostException -> RepositoryError.Unreachable(unreachableHint(url), e)
            is UnknownServiceException -> RepositoryError.Unreachable(
                if (e.message.orEmpty().contains("CLEARTEXT", ignoreCase = true)) {
                    "This build doesn't allow plain HTTP. Use an https:// backend URL."
                } else {
                    e.message ?: "The connection was refused by the device's network policy."
                },
                e,
            )
            is SSLException -> RepositoryError.Unreachable(
                "Secure connection to $origin failed: ${e.message ?: "TLS error"}. " +
                    "Check the server certificate, or use http:// for a local development server.",
                e,
            )
            is SerializationException, is IllegalArgumentException, is NullPointerException ->
                RepositoryError.MalformedResponse(
                    "The backend's response didn't match what the app expects " +
                        "(${e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e::class.simpleName}). " +
                        "The app and backend versions may be out of sync.",
                    e,
                )
            is IOException -> RepositoryError.Unreachable(
                "Network error talking to $origin: ${e.message ?: e::class.simpleName}",
                e,
            )
            else -> RepositoryError.MalformedResponse("Unexpected error: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun timeoutError(e: Throwable, url: HttpUrl, profile: CallProfile): RepositoryError {
        val origin = url.toString().trimEnd('/')
        val connecting = e.message.orEmpty().contains("connect", ignoreCase = true)
        return if (connecting) {
            RepositoryError.Timeout(
                "Couldn't connect to $origin within ${profile.connectSeconds}s. The address may be wrong, " +
                    "the server may be off, or a firewall may be blocking port ${url.port}.",
                e,
            )
        } else {
            RepositoryError.Timeout(
                "The backend didn't answer within ${profile.callSeconds}s. " +
                    if (profile == CallProfile.LLM) {
                        "The local model may be loading or the computer may be busy. Try again."
                    } else {
                        "Check that the server is responsive."
                    },
                e,
            )
        }
    }

    private fun unreachableHint(url: HttpUrl): String = buildString {
        append("Nothing answered at ${url.toString().trimEnd('/')}. ")
        append("Start the backend with: uvicorn backend.main:app --host 0.0.0.0 --port 8000. ")
        when (url.host) {
            "localhost", "127.0.0.1", "::1" -> append(
                "On Android, localhost is the device itself, not your computer. " +
                    "Use 10.0.2.2 on the emulator or your computer's LAN IP on a phone.",
            )
            "10.0.2.2" -> append("10.0.2.2 only works on the Android emulator; a phone needs your computer's LAN IP.")
            else -> append("A phone must be on the same Wi-Fi as the computer, and the firewall must allow the port.")
        }
    }

    private fun httpError(e: HttpException, origin: String): RepositoryError {
        val code = e.code()
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val detail = parseErrorBody(body)
        return when {
            code == 503 && detail.error.equals("LLM timeout", ignoreCase = true) ->
                RepositoryError.Timeout(detail.text() ?: "The model did not respond in time.")
            code == 503 -> RepositoryError.OllamaUnavailable(
                detail.text() ?: "The backend can't reach Ollama. Start it with 'ollama serve' on the computer.",
            )
            code == 422 -> RepositoryError.Rejected(detail.text() ?: "The backend rejected the request.")
            code == 401 -> RepositoryError.Unauthorized(detail.text() ?: "The session was not accepted.")
            code == 403 -> RepositoryError.Forbidden(detail.text() ?: "This session can't access that data.")
            code == 409 -> RepositoryError.UserIdTaken(detail.text() ?: "This user ID is registered to another device.")
            // The backend's own "not found" (e.g. a deleted history item) vs. a missing route.
            code == 404 && detail.error.equals("Not found", ignoreCase = true) ->
                RepositoryError.NotFound(detail.text() ?: "Not found.")
            code == 404 -> RepositoryError.Server(
                code,
                "$origin doesn't have this endpoint. Check that the URL points at the Subscription Optimizer backend.",
            )
            else -> RepositoryError.Server(code, detail.text() ?: "The backend returned HTTP $code.")
        }
    }

    internal data class ErrorDetail(val error: String?, val messages: List<String>) {
        fun text(): String? = messages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ") { if (it.last() in ".!?") it else "$it." }
            .ifBlank { null }
    }

    /**
     * Understands the shapes the backend produces:
     * `{"detail": "..."}`, `{"detail": {"error", "message", "hint"}}` from HTTPException,
     * `{"detail": [{"loc", "msg"}]}` from request validation, and `{"error", "detail"}` from ErrorResponse.
     */
    internal fun parseErrorBody(body: String): ErrorDetail {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return ErrorDetail(null, listOfNotNull(body.trim().take(300).ifBlank { null }))
        return when (val detail = root["detail"]) {
            is JsonPrimitive -> ErrorDetail(root.str("error"), listOfNotNull(root.str("error"), detail.contentOrNull))
            is JsonObject -> ErrorDetail(
                detail.str("error"),
                listOfNotNull(detail.str("message") ?: detail.str("error"), detail.str("hint")),
            )
            is JsonArray -> ErrorDetail(null, detail.mapNotNull { (it as? JsonObject)?.validationMessage() })
            else -> ErrorDetail(root.str("error"), listOfNotNull(root.str("error")))
        }
    }

    private fun JsonObject.validationMessage(): String? {
        val msg = str("msg") ?: return null
        val field = (this["loc"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filterNot { it == "body" }
            ?.joinToString(".")
            .orEmpty()
        return if (field.isEmpty()) "$msg." else "$field: $msg."
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
