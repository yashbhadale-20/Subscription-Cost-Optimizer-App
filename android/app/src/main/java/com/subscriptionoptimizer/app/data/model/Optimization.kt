package com.subscriptionoptimizer.app.data.model

/** Subscription categories the backend accepts (`backend/models.py:_CATEGORIES`). */
enum class SubscriptionCategory(val apiValue: String, val label: String) {
    VIDEO_STREAMING("video_streaming", "Video streaming"),
    MUSIC("music", "Music"),
    CLOUD_STORAGE("cloud_storage", "Cloud storage"),
    NEWS("news", "News"),
    GAMING("gaming", "Gaming"),
    PRODUCTIVITY("productivity", "Productivity"),
    FITNESS("fitness", "Fitness"),
    EDUCATION("education", "Education"),
    OTHER("other", "Other"),
    ;

    companion object {
        fun fromApi(value: String): SubscriptionCategory? = entries.firstOrNull { it.apiValue == value }
    }
}

data class CheaperTier(
    val name: String,
    /** Per [billingCycle]. */
    val price: Double,
    val billingCycle: BillingCycle = BillingCycle.MONTHLY,
    val featuresLost: List<String> = emptyList(),
)

/** One subscription the user typed in. No card, bank or account details. */
data class Subscription(
    val id: String,
    val name: String,
    val provider: String? = null,
    val category: SubscriptionCategory = SubscriptionCategory.OTHER,
    /** Charge per [billingCycle], INR. */
    val price: Double,
    val billingCycle: BillingCycle = BillingCycle.MONTHLY,
    val discountPercent: Double = 0.0,
    val introMonths: Int? = null,
    /** Per [billingCycle], after the intro offer. */
    val postIntroPrice: Double? = null,
    val usesPerMonth: Int? = null,
    val lastUsedDaysAgo: Int? = null,
    /** ISO date, YYYY-MM-DD. */
    val renewalDate: String? = null,
    val cheaperTier: CheaperTier? = null,
    /** Yearly price for a monthly plan that offers yearly billing. */
    val annualPrice: Double? = null,
    val sharedWith: Int = 0,
    val mustKeep: Boolean = false,
)

data class OptimizeRequest(
    val userId: String,
    val goal: String?,
    val monthlyBudget: Double?,
    val subscriptions: List<Subscription>,
    val savePortfolio: Boolean,
)

enum class OptimizeActionType(val apiValue: String, val label: String) {
    CANCEL("cancel", "Cancel"),
    DOWNGRADE("downgrade", "Downgrade"),
    SWITCH_TO_ANNUAL("switch_to_annual", "Switch to annual billing"),
    REVIEW_BEFORE_RENEWAL("review_before_renewal", "Review before renewal"),
    KEEP("keep", "Keep"),
    ;

    companion object {
        fun fromApi(value: String): OptimizeActionType? = entries.firstOrNull { it.apiValue == value }
    }
}

data class OptimizeAction(
    val actionId: String,
    val subscriptionId: String,
    val name: String,
    val action: OptimizeActionType,
    val reason: String,
    val monthlySavings: Double,
    val annualSavings: Double,
    val policyChunks: List<Int>,
)

data class BudgetResult(
    val target: Double,
    /** Whether any combination of candidate actions reaches the target. */
    val achievable: Boolean,
    /** Whether this plan reaches it. */
    val met: Boolean,
    val gap: Double,
)

data class RenewalAlert(
    val subscriptionId: String,
    val name: String,
    /** "intro_ending" or "renewal". */
    val kind: String,
    val renewalDate: String,
    val daysUntil: Int,
    val currentMonthly: Double,
    val afterMonthly: Double,
    val priceJumpMonthly: Double,
    val chargeAmount: Double,
)

data class OptimizationResult(
    val currentMonthlyTotal: Double,
    val currentAnnualTotal: Double,
    val actions: List<OptimizeAction>,
    val optimizedMonthlyTotal: Double,
    val optimizedAnnualTotal: Double,
    val totalMonthlySavings: Double,
    val totalAnnualSavings: Double,
    val percentageSavings: Double,
    val budget: BudgetResult?,
    val renewalAlerts: List<RenewalAlert>,
    val aiSummary: String,
    val policyEvidence: List<PolicyEvidence>,
    val citedChunks: List<Int>,
    val unsupportedCitations: List<Int>,
    val assumptions: List<String>,
    val missingInfo: List<String>,
    val verificationPassed: Boolean,
    val verificationNotes: List<String>,
    /** True when the deterministic plan was used because the model was down, slow or unusable. */
    val usedFallback: Boolean,
    val fallbackReason: String?,
    val traceEvents: List<TraceEvent>,
    val elapsedSeconds: Double,
)

data class SavedOptimization(
    val id: String,
    val createdAtMillis: Long,
    val goal: String?,
    val monthlyBudget: Double?,
    val subscriptions: List<Subscription>,
    val result: OptimizationResult,
    /** False when the backend couldn't save it; it then lives only in this session's cache. */
    val isSaved: Boolean = true,
)

/** A row of `GET /optimizations`. */
data class OptimizationSummary(
    val id: String,
    val createdAtMillis: Long,
    val goal: String?,
    val monthlyBudget: Double?,
    val subscriptionCount: Int,
    val totalAnnualSavings: Double,
    val usedFallback: Boolean,
)

data class Portfolio(
    val subscriptions: List<Subscription>,
    val updatedAtMillis: Long?,
)
