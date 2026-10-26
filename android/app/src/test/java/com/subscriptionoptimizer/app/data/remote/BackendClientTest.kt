package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.ConnectionTestResult
import com.subscriptionoptimizer.app.data.model.DiscountInput
import com.subscriptionoptimizer.app.data.model.PlanInput
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.Recommendation
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.repository.HttpChatRepository
import com.subscriptionoptimizer.app.data.repository.HttpComparisonRepository
import com.subscriptionoptimizer.app.data.repository.HttpConnectionTester
import com.subscriptionoptimizer.app.data.repository.HttpHistoryRepository
import com.subscriptionoptimizer.app.data.repository.HttpPolicyRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.data.repository.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException

class BackendClientTest {

    private lateinit var server: MockWebServer
    private val client = BackendClient(allowCleartext = true)
    private lateinit var settings: FakeSettings
    private lateinit var tokens: MemoryTokens

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        settings = FakeSettings(server.url("/").toString())
        // Most tests start with a session already issued, as after the app's first call.
        tokens = MemoryTokens(mutableMapOf("${settings.url.value}|student_1" to "tok-1"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sessions() = SessionManager(client, settings, tokens)
    private fun comparisons() = HttpComparisonRepository(sessions())
    private fun chat() = HttpChatRepository(sessions())

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readText()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `compare sends backend field names and parses a real backend response`() = runBlocking {
        server.enqueue(json(200, fixture("compare_response.json")))

        val saved = comparisons().compare(sampleRequest).getOrThrow()
        val result = saved.result

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/compare", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        // The fixture predates saving, so it has no comparison_id: the result is marked unsaved.
        assertFalse(saved.isSaved)
        val body = BackendClient.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("student_1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("student_budget", body["priority"]!!.jsonPrimitive.content)
        val planA = body["plan_a"]!!.jsonObject
        assertEquals(199.0, planA["monthly_price"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("monthly", planA["billing_cycle"]!!.jsonPrimitive.content)
        assertEquals("3", (planA["discount"] as JsonObject)["intro_months"]!!.jsonPrimitive.content)
        assertEquals("1", (planA["usage_limits"] as JsonObject)["screens"]!!.jsonPrimitive.content)
        // Empty optional collections are omitted rather than sent as [] or null.
        assertFalse(body["plan_b"]!!.jsonObject.containsKey("features"))

        assertEquals(Recommendation.TIE, result.recommendation)
        assertEquals(Priority.STUDENT_BUDGET, result.priorityUsed)
        assertEquals("Stream Basic", result.costA.planName)
        assertEquals(2089.5, result.costA.firstYearCost, 0.001)
        assertEquals(BillingCycle.ANNUAL, result.costB.billingCycle)
        assertEquals(listOf("HD"), result.featureComparison.shared)
        assertTrue(result.trace.first().startsWith("Input guardrail"))
        assertTrue(result.assumptions.isNotEmpty())
        assertTrue(result.aiExplanation.isNotBlank())
    }

    @Test
    fun `503 from require_ollama becomes OllamaUnavailable with the backend's hint`() = runBlocking {
        server.enqueue(
            json(
                503,
                """{"detail":{"error":"Ollama service unavailable","message":"Ollama is not running.",""" +
                    """"hint":"Start Ollama with 'ollama serve' and ensure models are pulled."}}""",
            ),
        )
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue(error is RepositoryError.OllamaUnavailable)
        assertTrue(error!!.message!!.contains("Ollama is not running."))
        assertTrue(error.message!!.contains("ollama serve"))
    }

    @Test
    fun `503 LLM timeout becomes Timeout`() = runBlocking {
        server.enqueue(json(503, """{"detail":{"error":"LLM timeout","message":"Chat agent did not respond within 180s."}}"""))
        val error = chat().send("t", "hi").exceptionOrNull()
        assertTrue(error is RepositoryError.Timeout)
        assertEquals("Chat agent did not respond within 180s.", error!!.message)
    }

    @Test
    fun `422 request validation lists the failing fields`() = runBlocking {
        server.enqueue(
            json(
                422,
                """{"detail":[{"type":"greater_than","loc":["body","plan_a","monthly_price"],""" +
                    """"msg":"Input should be greater than 0","input":0}]}""",
            ),
        )
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue(error is RepositoryError.Rejected)
        assertEquals("plan_a.monthly_price: Input should be greater than 0.", error!!.message)
    }

    @Test
    fun `500 ErrorResponse envelope is shown as a server error`() = runBlocking {
        server.enqueue(json(500, """{"error":"Internal server error","detail":"An unexpected error occurred. Check server logs."}"""))
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue(error is RepositoryError.Server)
        assertEquals(500, (error as RepositoryError.Server).code)
        assertTrue(error.message!!.contains("Check server logs"))
    }

    @Test
    fun `non-JSON body and missing required fields are malformed responses`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>proxy login</html>"))
        assertTrue(comparisons().compare(sampleRequest).exceptionOrNull() is RepositoryError.MalformedResponse)

        server.enqueue(json(200, """{"recommendation":"plan_a"}"""))
        assertTrue(comparisons().compare(sampleRequest).exceptionOrNull() is RepositoryError.MalformedResponse)
    }

    @Test
    fun `unknown enum value is a malformed response, not a silent default`() = runBlocking {
        val body = fixture("compare_response.json").replace("\"recommendation\": \"tie\"", "\"recommendation\": \"maybe\"")
            .replace("\"recommendation\":\"tie\"", "\"recommendation\":\"maybe\"")
        server.enqueue(json(200, body))
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue(error is RepositoryError.MalformedResponse)
        assertTrue(error!!.message!!.contains("maybe"))
    }

    @Test
    fun `blank chat reply is reported, not shown as an empty bubble`() = runBlocking {
        server.enqueue(json(200, """{"reply":"  ","thread_id":"t","user_id":"u"}"""))
        assertTrue(chat().send("t", "hi").exceptionOrNull() is RepositoryError.MalformedResponse)
    }

    @Test
    fun `refused connection is Unreachable and explains localhost on Android`() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        settings.url.value = "http://localhost:$freePort"
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue("was $error", error is RepositoryError.Unreachable)
        assertTrue(error!!.message!!.contains("localhost is the device itself"))
    }

    @Test
    fun `read timeout maps to Timeout`() {
        val error = client.mapError(SocketTimeoutException("timeout"), "http://10.0.2.2:8000/".toHttpUrl(), CallProfile.LLM)
        assertTrue(error is RepositoryError.Timeout)
        assertTrue(error.message!!.contains("${CallProfile.LLM.callSeconds}s"))
    }

    @Test
    fun `release builds refuse plain HTTP before any request is made`() = runBlocking {
        val httpsOnly = BackendClient(allowCleartext = false)
        val error = HttpComparisonRepository(SessionManager(httpsOnly, settings, tokens)).compare(sampleRequest).exceptionOrNull()
        assertTrue(error is RepositoryError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `empty backend URL is NotConfigured`() = runBlocking {
        settings.url.value = ""
        assertTrue(comparisons().compare(sampleRequest).exceptionOrNull() is RepositoryError.NotConfigured)
    }

    @Test
    fun `connection test reports API version and Ollama status separately`() = runBlocking {
        server.enqueue(json(200, """{"status":"ok","version":"1.0.0","timestamp":"x"}"""))
        server.enqueue(
            json(
                200,
                """{"ollama_ok":false,"message":"Model qwen2.5:3b not pulled","llm_model":"qwen2.5:3b",""" +
                    """"embedding_model":"nomic-embed-text","timestamp":"x"}""",
            ),
        )
        val result = HttpConnectionTester(client).test(server.url("/").toString())
        assertTrue(result is ConnectionTestResult.Success)
        result as ConnectionTestResult.Success
        assertEquals("1.0.0", result.apiVersion)
        assertEquals(false, result.ollamaOk)
        assertTrue(result.ollamaMessage!!.contains("not pulled"))
        assertEquals("/health", server.takeRequest().path)
        assertEquals("/health/ollama", server.takeRequest().path)
    }

    @Test
    fun `base URL with a path prefix is respected`() = runBlocking {
        server.enqueue(json(200, """{"status":"ok","version":"1.0.0"}"""))
        val result = HttpConnectionTester(client).test(server.url("/api/").toString().trimEnd('/'))
        assertTrue(result is ConnectionTestResult.Success)
        assertEquals("/api/health", server.takeRequest().path)
    }

    private class FakeSettings(initialUrl: String) : SettingsRepository {
        val url = MutableStateFlow(initialUrl)
        override val settings: Flow<AppSettings> = url.map { AppSettings(backendUrl = it, userId = "student_1") }
        override suspend fun setBackendUrl(url: String) { this.url.value = url }
        override suspend fun setUserId(userId: String) = Unit
        override suspend fun setTheme(theme: ThemePreference) = Unit
    }

    private class MemoryTokens(val map: MutableMap<String, String> = mutableMapOf()) : TokenStore {
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, token: String) { map[key] = token }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Test
    fun `first user-scoped call creates a session and stores the token`() = runBlocking {
        tokens.map.clear()
        server.enqueue(json(200, """{"user_id":"student_1","token":"new-token"}"""))
        server.enqueue(json(200, """{"items":[],"total":0,"limit":50,"offset":0}"""))

        val history = HttpHistoryRepository(sessions(), settings)
        history.refresh()

        val session = server.takeRequest()
        assertEquals("/auth/session", session.path)
        assertTrue(session.body.readUtf8().contains("\"user_id\":\"student_1\""))
        assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("new-token", tokens.map["${settings.url.value}|student_1"])
        assertTrue(history.state.value.loaded)
    }

    @Test
    fun `a rejected token is replaced once, then the call is retried`() = runBlocking {
        server.enqueue(json(401, """{"detail":{"error":"Invalid session","message":"Sign in again."}}"""))
        server.enqueue(json(200, """{"user_id":"student_1","token":"tok-2"}"""))
        server.enqueue(json(200, """{"items":[],"total":0,"limit":50,"offset":0}"""))

        val history = HttpHistoryRepository(sessions(), settings)
        history.refresh()

        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/session", server.takeRequest().path)
        assertEquals("Bearer tok-2", server.takeRequest().getHeader("Authorization"))
        assertTrue(history.state.value.loaded)
        assertEquals(null, history.state.value.error)
    }

    @Test
    fun `a user ID owned by another device is reported, not retried`() = runBlocking {
        tokens.map.clear()
        server.enqueue(
            json(409, """{"detail":{"error":"User ID already registered","message":"'student_1' is registered to another device."}}"""),
        )
        val error = comparisons().compare(sampleRequest).exceptionOrNull()
        assertTrue("was $error", error is RepositoryError.UserIdTaken)
        assertTrue(error!!.message!!.contains("another device"))
        assertEquals(1, server.requestCount)
    }

    // ── History, traces and policy answers ───────────────────────────────────

    @Test
    fun `saved comparison from the real backend parses with plans, evidence and trace`() = runBlocking {
        server.enqueue(json(200, fixture("history_item.json")))
        val history = HttpHistoryRepository(sessions(), settings)

        val saved = history.get("2217661ac3da4749b391b760a6d5490d").getOrThrow()

        assertEquals("/history/2217661ac3da4749b391b760a6d5490d", server.takeRequest().path)
        assertTrue(saved.isSaved)
        assertTrue(saved.createdAtMillis > 0)
        assertEquals("Stream Basic", saved.planA!!.name)
        assertEquals(3, saved.planA!!.introMonths)
        assertEquals("1", saved.planA!!.usageLimits["screens"]) // an int on the wire
        assertEquals(BillingCycle.ANNUAL, saved.planB!!.billingCycle)
        assertEquals(listOf(8, 7, 3), saved.result.policyEvidence.map { it.chunk })
        // Chunk 8 is indexed from an older version of the policy file.
        assertFalse(saved.result.policyEvidence.first { it.chunk == 8 }.matchesCurrentPolicy)
        val stages = saved.result.traceEvents.map { it.stage }
        assertEquals("input_guardrail", stages.first())
        assertEquals("save_comparison", stages.last())
        assertEquals("comparison_engine.run_comparison", saved.result.traceEvents.first { it.stage == "cost_calculation" }.name)

        // Cached: a second get makes no request.
        history.get(saved.id).getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing history item is NotFound, not a server error`() = runBlocking {
        server.enqueue(json(404, """{"detail":{"error":"Not found","message":"No such saved comparison."}}"""))
        val error = HttpHistoryRepository(sessions(), settings).get("0".repeat(32)).exceptionOrNull()
        assertTrue("was $error", error is RepositoryError.NotFound)
    }

    @Test
    fun `history load failure keeps the error in state`() = runBlocking {
        server.enqueue(json(500, """{"error":"Internal server error","detail":"Check server logs."}"""))
        val history = HttpHistoryRepository(sessions(), settings)
        history.refresh()
        assertTrue(history.state.value.error is RepositoryError.Server)
        assertFalse(history.state.value.loaded)
        assertFalse(history.state.value.isLoading)
    }

    @Test
    fun `policy answer from the real backend parses`() = runBlocking {
        server.enqueue(json(200, fixture("policy_ask.json")))
        val answer = HttpPolicyRepository(client, settings).ask("Are taxes included in the price?").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("/policy/ask", recorded.path)
        assertEquals(null, recorded.getHeader("Authorization"))
        assertTrue(answer.answeredFromPolicy)
        assertEquals(4, answer.evidence.first().chunk)
        assertEquals("policy_retrieval", answer.traceEvents.first().stage)
    }

    private companion object {
        val sampleRequest = CompareRequest(
            userId = "student_1",
            question = "Which plan is better value for a student?",
            priority = Priority.STUDENT_BUDGET,
            planA = PlanInput(
                name = "Stream Basic",
                provider = "StreamCo",
                price = 199.0,
                billingCycle = BillingCycle.MONTHLY,
                discount = DiscountInput(percent = 50.0, introMonths = 3, postIntroPrice = 199.0),
                features = listOf("HD", "1 screen", "Ads"),
                usageLimits = mapOf("screens" to "1"),
                cancellationTerms = "Cancel anytime",
                renewalNote = null,
            ),
            planB = PlanInput(
                name = "Stream Plus Annual",
                provider = null,
                price = 1999.0,
                billingCycle = BillingCycle.ANNUAL,
                discount = DiscountInput(),
                features = emptyList(),
                usageLimits = emptyMap(),
                cancellationTerms = null,
                renewalNote = null,
            ),
        )
    }
}
