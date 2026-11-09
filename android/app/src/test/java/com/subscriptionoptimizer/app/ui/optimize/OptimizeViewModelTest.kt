package com.subscriptionoptimizer.app.ui.optimize

import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.OptimizationResult
import com.subscriptionoptimizer.app.data.model.OptimizationSummary
import com.subscriptionoptimizer.app.data.model.OptimizeRequest
import com.subscriptionoptimizer.app.data.model.Portfolio
import com.subscriptionoptimizer.app.data.model.SavedOptimization
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.model.TraceEvent
import com.subscriptionoptimizer.app.data.repository.OptimizeRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.domain.SubscriptionField
import com.subscriptionoptimizer.app.ui.compare.SubmitError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeViewModelTest {

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(AppSettings(userId = "student_1"))
        override suspend fun setBackendUrl(url: String) = Unit
        override suspend fun setUserId(userId: String) = Unit
        override suspend fun setTheme(theme: ThemePreference) = Unit
    }

    private class FakeRepo(
        val portfolioResult: CompletableDeferred<Result<Portfolio>> = CompletableDeferred(),
    ) : OptimizeRepository {
        val optimizeResponse = CompletableDeferred<Result<SavedOptimization>>()
        var lastRequest: OptimizeRequest? = null
        var calls = 0
        override suspend fun optimize(request: OptimizeRequest): Result<SavedOptimization> {
            calls++
            lastRequest = request
            return optimizeResponse.await()
        }
        override suspend fun list() = Result.success(emptyList<OptimizationSummary>())
        override suspend fun get(id: String) = Result.failure<SavedOptimization>(RepositoryError.NotFound(id))
        override suspend fun trace(id: String) = Result.success(emptyList<TraceEvent>())
        override suspend fun delete(id: String) = Result.success(Unit)
        override suspend fun portfolio() = portfolioResult.await()
        override suspend fun savePortfolio(subscriptions: List<Subscription>) = Result.success(Portfolio(subscriptions, null))
    }

    private val netflix = Subscription(id = "netflix", name = "Netflix", price = 649.0)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun OptimizeViewModel.addSubscription(name: String, price: String) {
        startAdd()
        updateEditor { it.copy(name = name, price = price) }
        saveEditor()
    }

    @Test
    fun `saved portfolio prefills the list`() {
        val repo = FakeRepo()
        val vm = OptimizeViewModel(repo, settings)
        assertTrue(vm.uiState.value.isLoadingPortfolio)

        repo.portfolioResult.complete(Result.success(Portfolio(listOf(netflix), 1L)))

        assertEquals(listOf(netflix), vm.uiState.value.subscriptions)
        assertFalse(vm.uiState.value.isLoadingPortfolio)
    }

    @Test
    fun `a late portfolio load doesn't overwrite the user's edits`() {
        val repo = FakeRepo()
        val vm = OptimizeViewModel(repo, settings)
        vm.addSubscription("Spotify", "119")

        repo.portfolioResult.complete(Result.success(Portfolio(listOf(netflix), 1L)))

        assertEquals(listOf("Spotify"), vm.uiState.value.subscriptions.map { it.name })
    }

    @Test
    fun `portfolio failure is shown and the form still works`() {
        val repo = FakeRepo(CompletableDeferred(Result.failure(RepositoryError.Unreachable("down"))))
        val vm = OptimizeViewModel(repo, settings)
        assertEquals(SubmitError("Can't reach the backend", "down"), vm.uiState.value.portfolioError)
        vm.addSubscription("Spotify", "119")
        assertEquals(1, vm.uiState.value.subscriptions.size)
    }

    @Test
    fun `editor shows errors only after a save attempt, then live`() {
        val vm = OptimizeViewModel(FakeRepo(), settings)
        vm.startAdd()
        vm.updateEditor { it.copy(name = "") }
        assertTrue(vm.uiState.value.editor!!.errors.isEmpty())

        vm.saveEditor()
        val editor = vm.uiState.value.editor!!
        assertTrue(SubscriptionField.NAME in editor.errors && SubscriptionField.PRICE in editor.errors)

        vm.updateEditor { it.copy(name = "Netflix", price = "649") }
        assertTrue(vm.uiState.value.editor!!.errors.isEmpty())
        vm.saveEditor()
        assertNull(vm.uiState.value.editor)
        assertEquals("netflix", vm.uiState.value.subscriptions.single().id)
    }

    @Test
    fun `editing keeps the id and delete removes it`() {
        val vm = OptimizeViewModel(FakeRepo(), settings)
        vm.addSubscription("Netflix", "649")
        vm.addSubscription("Netflix 4K", "799")
        vm.startEdit("netflix")
        vm.updateEditor { it.copy(price = "499", mustKeep = true) }
        vm.saveEditor()

        val edited = vm.uiState.value.subscriptions.first()
        assertEquals("netflix", edited.id)
        assertEquals(499.0, edited.price, 0.0)
        assertTrue(edited.mustKeep)

        vm.delete("netflix")
        assertEquals(listOf("netflix_4k"), vm.uiState.value.subscriptions.map { it.id })
    }

    @Test
    fun `run sends the list, budget and goal for the session user and opens the result`() = runTest {
        val repo = FakeRepo()
        val vm = OptimizeViewModel(repo, settings)
        vm.addSubscription("Netflix", "649")
        vm.setBudget("1,200")
        vm.setGoal("  keep netflix  ")
        vm.setSavePortfolio(false)

        vm.run()
        assertTrue(vm.uiState.value.isRunning)
        vm.run() // ignored while in flight
        assertEquals(1, repo.calls)

        val request = repo.lastRequest!!
        assertEquals("student_1", request.userId)
        assertEquals(1200.0, request.monthlyBudget!!, 0.0)
        assertEquals("keep netflix", request.goal)
        assertFalse(request.savePortfolio)

        repo.optimizeResponse.complete(Result.success(sample("abc")))
        assertFalse(vm.uiState.value.isRunning)
        assertEquals(OptimizeEvent.OpenResult("abc"), vm.events.first())
    }

    @Test
    fun `failure keeps the inputs and shows the error`() {
        val repo = FakeRepo()
        val vm = OptimizeViewModel(repo, settings)
        vm.addSubscription("Netflix", "649")
        vm.setBudget("1200")

        vm.run()
        repo.optimizeResponse.complete(Result.failure(RepositoryError.Timeout("The backend didn't answer within 300s.")))

        val s = vm.uiState.value
        assertFalse(s.isRunning)
        assertEquals("Request timed out", s.submitError?.title)
        assertEquals(1, s.subscriptions.size)
        assertEquals("1200", s.budget)
    }

    @Test
    fun `invalid inputs never reach the repository`() = runTest {
        val repo = FakeRepo()
        val vm = OptimizeViewModel(repo, settings)

        vm.run()
        assertEquals("Add at least one subscription", vm.uiState.value.listError)

        vm.addSubscription("Netflix", "649")
        vm.setBudget("lots")
        vm.run()
        assertNotNull(vm.uiState.value.budgetError)
        assertEquals(0, repo.calls)
    }

    private fun sample(id: String) = SavedOptimization(
        id = id, createdAtMillis = 0L, goal = null, monthlyBudget = null, subscriptions = emptyList(),
        result = OptimizationResult(
            currentMonthlyTotal = 649.0, currentAnnualTotal = 7788.0, actions = emptyList(),
            optimizedMonthlyTotal = 649.0, optimizedAnnualTotal = 7788.0, totalMonthlySavings = 0.0,
            totalAnnualSavings = 0.0, percentageSavings = 0.0, budget = null, renewalAlerts = emptyList(),
            aiSummary = "", policyEvidence = emptyList(), citedChunks = emptyList(), unsupportedCitations = emptyList(),
            assumptions = emptyList(), missingInfo = emptyList(), verificationPassed = true,
            verificationNotes = emptyList(), usedFallback = true, fallbackReason = "Ollama is unavailable.",
            traceEvents = emptyList(), elapsedSeconds = 0.1,
        ),
    )
}
