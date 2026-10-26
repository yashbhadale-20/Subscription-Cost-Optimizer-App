package com.subscriptionoptimizer.app.data.model

/**
 * App-side mirror of the backend `POST /compare` contract (see backend/models.py).
 * Field names and constraints follow the Pydantic models so a network layer can map them 1:1.
 */

enum class BillingCycle(val apiValue: String, val label: String, val months: Int) {
    MONTHLY("monthly", "Monthly", 1),
    QUARTERLY("quarterly", "Quarterly", 3),
    SEMI_ANNUAL("semi_annual", "6 months", 6),
    ANNUAL("annual", "Annual", 12),
    ;

    companion object {
        fun fromApi(value: String): BillingCycle? = entries.firstOrNull { it.apiValue == value }
    }
}

enum class Priority(val apiValue: String, val label: String, val description: String) {
    BEST_VALUE("best_value", "Best value", "Balances cost, features and flexibility"),
    LOWEST_COST("lowest_cost", "Lowest cost", "Cost matters most"),
    STUDENT_BUDGET("student_budget", "Student budget", "Strongly favours the cheaper plan"),
    MOST_FEATURES("most_features", "Most features", "Feature coverage matters most"),
    HEAVY_USAGE("heavy_usage", "Heavy usage", "Usage limits matter most"),
    FLEXIBLE_CANCELLATION("flexible_cancellation", "Flexible cancellation", "Easy cancellation matters most"),
    ;

    companion object {
        fun fromApi(value: String): Priority? = entries.firstOrNull { it.apiValue == value }
    }
}

data class DiscountInput(
    val percent: Double = 0.0,
    /** Null means the discount is ongoing. */
    val introMonths: Int? = null,
    /** Monthly price after the intro period ends; null means the regular price. */
    val postIntroPrice: Double? = null,
)

data class PlanInput(
    val name: String,
    val provider: String?,
    /** Price per billing period (the backend calls this `monthly_price`). */
    val price: Double,
    val billingCycle: BillingCycle,
    val discount: DiscountInput,
    val features: List<String>,
    val usageLimits: Map<String, String>,
    val cancellationTerms: String?,
    val renewalNote: String?,
)

data class CompareRequest(
    val userId: String,
    val question: String,
    val priority: Priority,
    val planA: PlanInput,
    val planB: PlanInput,
)

enum class Recommendation(val apiValue: String) {
    PLAN_A("plan_a"),
    PLAN_B("plan_b"),
    TIE("tie"),
    NEITHER("neither"),
    ;

    companion object {
        fun fromApi(value: String): Recommendation? = entries.firstOrNull { it.apiValue == value }
    }
}

data class CostBreakdown(
    val planName: String,
    val listedPrice: Double,
    val billingCycle: BillingCycle,
    val monthlyEquivalent: Double,
    val discountPercent: Double,
    val discountedMonthly: Double,
    val firstYearCost: Double,
    val firstYearExplanation: String,
    val recurringAnnualCost: Double,
    val recurringAnnualExplanation: String,
    val hasIntroOffer: Boolean,
    val introMonths: Int? = null,
    val postIntroMonthly: Double? = null,
    val renewalCostNote: String? = null,
)

data class FeatureComparison(
    val onlyInA: List<String>,
    val onlyInB: List<String>,
    val shared: List<String>,
)

data class ScoreBreakdown(
    val costScore: Double,
    val featuresScore: Double,
    val usageScore: Double?,
    val cancellationScore: Double?,
    val weightedTotal: Double,
    val sufficientData: Boolean,
)

data class ComparisonResult(
    val recommendation: Recommendation,
    val recommendationReason: String,
    val priorityUsed: Priority?,
    val costA: CostBreakdown,
    val costB: CostBreakdown,
    val absoluteSavingsFirstYear: Double,
    val percentageSavingsFirstYear: Double,
    val absoluteSavingsRecurring: Double,
    val percentageSavingsRecurring: Double,
    /** Plan name, or "tie". */
    val cheaperPlanFirstYear: String,
    val cheaperPlanRecurring: String,
    val featureComparison: FeatureComparison,
    val scoreA: ScoreBreakdown?,
    val scoreB: ScoreBreakdown?,
    val aiExplanation: String,
    val policyContext: String,
    val trace: List<String>,
    val assumptions: List<String>,
    val missingInfo: List<String>,
    val verificationPassed: Boolean,
    val verificationNotes: List<String>,
    val elapsedSeconds: Double,
    /** Policy chunks the backend actually retrieved for the question, above its relevance threshold. */
    val policyEvidence: List<PolicyEvidence> = emptyList(),
    val citedChunks: List<Int> = emptyList(),
    /** Chunks the explanation cites that were never retrieved. */
    val unsupportedCitations: List<Int> = emptyList(),
    /** Present on fresh results and detail fetches; history listings leave it empty. */
    val traceEvents: List<TraceEvent> = emptyList(),
) {
    /** The cost breakdown of the recommended plan, or null for a tie / no recommendation. */
    val recommendedCost: CostBreakdown?
        get() = when (recommendation) {
            Recommendation.PLAN_A -> costA
            Recommendation.PLAN_B -> costB
            Recommendation.TIE, Recommendation.NEITHER -> null
        }
}

/** A plan as it was submitted, returned by the backend with a saved comparison. */
data class SavedPlan(
    val name: String,
    val provider: String?,
    val price: Double,
    val billingCycle: BillingCycle?,
    val discountPercent: Double,
    val introMonths: Int?,
    val postIntroPrice: Double?,
    val features: List<String>,
    val usageLimits: Map<String, String>,
    val cancellationTerms: String?,
    val renewalNote: String?,
)

/**
 * A comparison saved on the backend (`GET /history`). [isSaved] is false only when the backend
 * returned a result but could not store it. [isSample] is true only for @Preview fixtures.
 */
data class SavedComparison(
    val id: String,
    val createdAtMillis: Long,
    val result: ComparisonResult,
    val question: String = "",
    val planA: SavedPlan? = null,
    val planB: SavedPlan? = null,
    val isSaved: Boolean = true,
    val isSample: Boolean = false,
)

/** One step of a backend execution trace. Kinds and statuses are the backend's own labels. */
data class TraceEvent(
    val step: Int,
    val stage: String,
    /** check, function, retrieval, llm, tool_call, validation or persistence. */
    val kind: String,
    /** The function, agent or tool that ran. */
    val name: String,
    /** ok, passed, failed, warning, error or skipped. */
    val status: String,
    val detail: String,
    val durationMs: Double?,
    val data: Map<String, String> = emptyMap(),
)
