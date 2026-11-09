package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.CheaperTier
import com.subscriptionoptimizer.app.data.model.OptimizeActionType
import com.subscriptionoptimizer.app.data.model.OptimizeRequest
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.SubscriptionCategory
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.repository.HttpOptimizeRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.data.repository.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OptimizeMappersTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: HttpOptimizeRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        val settings = object : SettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings(backendUrl = url, userId = "student_1"))
            override suspend fun setBackendUrl(url: String) = Unit
            override suspend fun setUserId(userId: String) = Unit
            override suspend fun setTheme(theme: ThemePreference) = Unit
        }
        val tokens = object : TokenStore {
            val map = mutableMapOf("$url|student_1" to "tok-1")
            override suspend fun get(key: String) = map[key]
            override suspend fun put(key: String, token: String) { map[key] = token }
            override suspend fun remove(key: String) { map.remove(key) }
        }
        repo = HttpOptimizeRepository(SessionManager(BackendClient(allowCleartext = true), settings, tokens), settings)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readText()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val request = OptimizeRequest(
        userId = "someone_else", // replaced by the session's user
        goal = "  Keep Netflix  ",
        monthlyBudget = 1500.0,
        subscriptions = listOf(
            Subscription(id = "netflix", name = "Netflix", category = SubscriptionCategory.VIDEO_STREAMING,
                price = 649.0, usesPerMonth = 20, mustKeep = true),
            Subscription(id = "youtube", name = "YouTube Premium", price = 149.0, annualPrice = 1490.0,
                cheaperTier = CheaperTier("Lite", 89.0, BillingCycle.MONTHLY, listOf("Background play"))),
        ),
        savePortfolio = true,
    )

    @Test
    fun `optimize sends backend field names and parses a real backend response`() = runBlocking {
        server.enqueue(json(200, fixture("optimize_response.json")))

        val saved = repo.optimize(request).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/optimize", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        val body = BackendClient.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("student_1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("Keep Netflix", body["goal"]!!.jsonPrimitive.content)
        assertEquals(1500.0, body["monthly_budget"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertTrue(body["save_portfolio"]!!.jsonPrimitive.boolean)
        val subs = body["subscriptions"]!!.jsonArray
        val netflix = subs[0].jsonObject
        assertEquals("video_streaming", netflix["category"]!!.jsonPrimitive.content)
        // Values equal to the backend's own defaults (monthly, 0 %, not shared) are left out.
        assertFalse(netflix.containsKey("billing_cycle"))
        assertFalse(netflix.containsKey("shared_with"))
        assertTrue(netflix["must_keep"]!!.jsonPrimitive.boolean)
        assertFalse("nulls are omitted", netflix.containsKey("annual_price"))
        val tier = subs[1].jsonObject["cheaper_tier"] as JsonObject
        assertEquals("Background play", tier["features_lost"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(1490.0, subs[1].jsonObject["annual_price"]!!.jsonPrimitive.content.toDouble(), 0.0)

        assertTrue(saved.isSaved)
        assertEquals(32, saved.id.length)
        val r = saved.result
        assertFalse(r.usedFallback)
        assertNull(r.fallbackReason)
        assertEquals(1246.0, r.currentMonthlyTotal, 0.001)
        assertEquals(2408.0, r.totalAnnualSavings, 0.001)
        assertEquals(
            listOf(OptimizeActionType.CANCEL, OptimizeActionType.SWITCH_TO_ANNUAL, OptimizeActionType.DOWNGRADE),
            r.actions.map { it.action },
        )
        assertEquals(listOf(4), r.actions.first().policyChunks)
        assertTrue(r.budget!!.met && r.budget!!.achievable)
        assertEquals("intro_ending", r.renewalAlerts.single().kind)
        assertEquals(149.0, r.renewalAlerts.single().priceJumpMonthly, 0.001)
        assertEquals(listOf(4, 8), r.citedChunks)
        assertTrue(r.policyEvidence.any { it.chunk == 8 && it.cited })
        assertTrue(r.verificationPassed)
        assertTrue(r.traceEvents.any { it.kind == "tool_call" && it.name == "get_candidates" })
        assertEquals("save_optimization", r.traceEvents.last().stage)

        // Cached: opening the result makes no request.
        repo.get(saved.id).getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unknown action type is a malformed response`() = runBlocking {
        val body = fixture("optimize_response.json").replace("\"action\": \"downgrade\"", "\"action\": \"pause\"")
        server.enqueue(json(200, body))
        val error = repo.optimize(request).exceptionOrNull()
        assertTrue("was $error", error is RepositoryError.MalformedResponse)
    }

    @Test
    fun `a result the backend could not save stays available from the cache`() = runBlocking {
        val body = fixture("optimize_response.json").replace(Regex("\"optimization_id\": \"[0-9a-f]+\""), "\"optimization_id\": null")
        server.enqueue(json(200, body))

        val saved = repo.optimize(request).getOrThrow()

        assertFalse(saved.isSaved)
        assertTrue(saved.id.startsWith(HttpOptimizeRepository.UNSAVED_PREFIX))
        assertEquals(saved.result.traceEvents, repo.trace(saved.id).getOrThrow())
        assertEquals("trace served from the cache", 1, server.requestCount)
    }

    @Test
    fun `portfolio round trip`() = runBlocking {
        server.enqueue(
            json(200, """{"user_id":"student_1","updated_at":"2026-11-09T10:00:00+00:00","subscriptions":[
                {"id":"g1","name":"Google One","category":"cloud_storage","price":130.0,"billing_cycle":"monthly",
                 "discount_percent":0.0,"annual_price":1300.0,"shared_with":2,"must_keep":false,
                 "renewal_date":"2026-12-01","cheaper_tier":null}]}"""),
        )
        val portfolio = repo.portfolio().getOrThrow()
        assertEquals("/portfolio", server.takeRequest().path)
        val g1 = portfolio.subscriptions.single()
        assertEquals(SubscriptionCategory.CLOUD_STORAGE, g1.category)
        assertEquals(1300.0, g1.annualPrice!!, 0.0)
        assertEquals(2, g1.sharedWith)
        assertEquals("2026-12-01", g1.renewalDate)
        assertTrue(portfolio.updatedAtMillis!! > 0)
    }

    @Test
    fun `other user's optimization is NotFound`() = runBlocking {
        server.enqueue(json(404, """{"detail":{"error":"Not found","message":"No such saved optimization."}}"""))
        val error = repo.get("0".repeat(32)).exceptionOrNull()
        assertTrue("was $error", error is RepositoryError.NotFound)
    }
}
