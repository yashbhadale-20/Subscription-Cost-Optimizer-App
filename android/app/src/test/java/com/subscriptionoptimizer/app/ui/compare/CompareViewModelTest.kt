package com.subscriptionoptimizer.app.ui.compare

import com.subscriptionoptimizer.app.data.model.AppSettings
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.model.ThemePreference
import com.subscriptionoptimizer.app.data.model.TraceEvent
import com.subscriptionoptimizer.app.data.repository.ComparisonRepository
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import com.subscriptionoptimizer.app.data.repository.HistoryState
import com.subscriptionoptimizer.app.data.repository.PreferencesRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import com.subscriptionoptimizer.app.data.repository.SettingsRepository
import com.subscriptionoptimizer.app.preview.PreviewFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.subscriptionoptimizer.app.domain.PlanForm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompareViewModelTest {

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(AppSettings(userId = "student_1"))
        override suspend fun setBackendUrl(url: String) = Unit
        override suspend fun setUserId(userId: String) = Unit
        override suspend fun setTheme(theme: ThemePreference) = Unit
    }

    private class ControlledRepository : ComparisonRepository {
        var lastRequest: CompareRequest? = null
        var calls = 0
        val response = CompletableDeferred<Result<SavedComparison>>()
        override suspend fun compare(request: CompareRequest): Result<SavedComparison> {
            calls++
            lastRequest = request
            return response.await()
        }
    }

    private class FakeHistory : HistoryRepository {
        val added = mutableListOf<SavedComparison>()
        override val state: StateFlow<HistoryState> = MutableStateFlow(HistoryState())
        override suspend fun refresh() = Unit
        override suspend fun get(id: String) = Result.failure<SavedComparison>(RepositoryError.NotFound(id))
        override suspend fun trace(id: String) = Result.success(emptyList<TraceEvent>())
        override fun add(saved: SavedComparison) { added += saved }
        override suspend fun delete(id: String) = Result.success(Unit)
        override suspend fun clear() = Result.success(Unit)
    }

    private class FakePreferences(private val saved: Result<Priority?> = Result.success(null)) : PreferencesRepository {
        override suspend fun defaultPriority() = saved
        override suspend fun setDefaultPriority(priority: Priority?) = Result.success(priority)
    }

    private fun viewModel(
        repo: ComparisonRepository,
        history: HistoryRepository = FakeHistory(),
        prefs: PreferencesRepository = FakePreferences(),
    ) = CompareViewModel(repo, history, settings, prefs)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun CompareViewModel.fillValidForm() {
        updatePlan(PlanSlot.A) { PlanForm(name = "Stream Basic", price = "199", features = listOf("HD")) }
        updatePlan(PlanSlot.B) { PlanForm(name = "Stream Plus", price = "299") }
        setQuestion("Which is cheaper?")
    }

    @Test
    fun `failed request keeps every input and shows the repository's error`() {
        val repo = ControlledRepository()
        val history = FakeHistory()
        val vm = viewModel(repo, history)
        vm.fillValidForm()
        val before = vm.uiState.value

        vm.submit()
        assertTrue(vm.uiState.value.isSubmitting)
        repo.response.complete(Result.failure(RepositoryError.OllamaUnavailable("Ollama is not running.")))

        val after = vm.uiState.value
        assertFalse(after.isSubmitting)
        assertEquals(SubmitError("AI model service unavailable", "Ollama is not running."), after.submitError)
        assertEquals(before.planA.form, after.planA.form)
        assertEquals(before.planB.form, after.planB.form)
        assertEquals(before.question, after.question)
        assertEquals(before.priority, after.priority)
        assertTrue("nothing is added on failure", history.added.isEmpty())
        assertEquals("student_1", repo.lastRequest?.userId)
    }

    @Test
    fun `success adds the backend's saved comparison and opens it`() {
        val repo = ControlledRepository()
        val history = FakeHistory()
        val vm = viewModel(repo, history)
        vm.fillValidForm()

        vm.submit()
        repo.response.complete(Result.success(PreviewFixtures.sampleSaved))

        assertEquals(listOf(PreviewFixtures.sampleSaved), history.added)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun `saved default priority preselects the form until the user picks one`() {
        val vm = viewModel(ControlledRepository(), prefs = FakePreferences(Result.success(Priority.STUDENT_BUDGET)))
        assertEquals(Priority.STUDENT_BUDGET, vm.uiState.value.priority)

        vm.setPriority(Priority.MOST_FEATURES)
        vm.reset()
        assertEquals("reset returns to the saved default", Priority.STUDENT_BUDGET, vm.uiState.value.priority)
    }

    @Test
    fun `preferences failure leaves the built-in default`() {
        val vm = viewModel(
            ControlledRepository(),
            prefs = FakePreferences(Result.failure(RepositoryError.Unreachable("down"))),
        )
        assertEquals(Priority.BEST_VALUE, vm.uiState.value.priority)
    }

    @Test
    fun `submit is ignored while a request is in flight`() {
        val repo = ControlledRepository()
        val vm = viewModel(repo)
        vm.fillValidForm()

        vm.submit()
        vm.submit()

        assertEquals(1, repo.calls)
    }

    @Test
    fun `invalid form never reaches the repository`() {
        val repo = ControlledRepository()
        val vm = viewModel(repo)

        vm.submit()

        assertEquals(0, repo.calls)
        assertFalse(vm.uiState.value.isSubmitting)
        assertNull(vm.uiState.value.submitError)
    }
}
