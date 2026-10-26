package com.subscriptionoptimizer.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.repository.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard figures come only from the user's latest real comparison.
 * With no comparison yet, every figure is null and the UI shows an empty state.
 */
data class DashboardFigures(
    val planAName: String,
    val planBName: String,
    val recommendedPlanName: String?,
    /** Discounted monthly cost of the recommended plan. */
    val monthlyCost: Double?,
    /** First-year cost of the recommended plan, including any intro period. */
    val annualCost: Double?,
    /** First-year savings, only when the recommended plan is also the cheaper one. */
    val estimatedSavings: Double?,
)

data class HomeUiState(
    val figures: DashboardFigures? = null,
    val recent: List<SavedComparison> = emptyList(),
    val totalComparisons: Int = 0,
    val isLoading: Boolean = false,
    /** Why the last refresh failed, if it did. */
    val historyError: String? = null,
)

class HomeViewModel(private val history: HistoryRepository) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = history.state
        .map { state ->
            HomeUiState(
                figures = state.entries.firstOrNull()?.let(::figuresFor),
                recent = state.entries.take(RECENT_COUNT),
                totalComparisons = state.total,
                isLoading = state.isLoading,
                historyError = state.error?.let { "${it.title}. ${it.message}" },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(isLoading = true))

    init {
        refresh()
    }

    /** Called on creation and whenever Home is shown again, so new comparisons and settings changes appear. */
    fun refresh() {
        viewModelScope.launch { history.refresh() }
    }

    private fun figuresFor(saved: SavedComparison): DashboardFigures {
        val result = saved.result
        val recommended = result.recommendedCost
        val savings = result.absoluteSavingsFirstYear.takeIf {
            recommended != null && it > 0.0 && result.cheaperPlanFirstYear == recommended.planName
        }
        return DashboardFigures(
            planAName = result.costA.planName,
            planBName = result.costB.planName,
            recommendedPlanName = recommended?.planName,
            monthlyCost = recommended?.discountedMonthly,
            annualCost = recommended?.firstYearCost,
            estimatedSavings = savings,
        )
    }

    companion object {
        private const val RECENT_COUNT = 3
        val Factory = appViewModelFactory { HomeViewModel(it.historyRepository) }
    }
}
