package com.subscriptionoptimizer.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire format of the FastAPI backend, field for field with backend/models.py.
 * Required fields have no default, so a response missing one fails to decode and is reported as
 * malformed instead of being filled in. Fields the backend declares with a default mirror that default.
 */

// ── /health ──────────────────────────────────────────────────────────────────

@Serializable
data class HealthDto(
    val status: String,
    val version: String,
    val timestamp: String? = null,
)

@Serializable
data class OllamaHealthDto(
    @SerialName("ollama_ok") val ollamaOk: Boolean,
    val message: String,
    @SerialName("llm_model") val llmModel: String,
    @SerialName("embedding_model") val embeddingModel: String,
    val timestamp: String? = null,
)

// ── /compare request ─────────────────────────────────────────────────────────

@Serializable
data class DiscountDto(
    val percent: Double,
    @SerialName("intro_months") val introMonths: Int? = null,
    @SerialName("post_intro_price") val postIntroPrice: Double? = null,
)

@Serializable
data class PlanInputDto(
    val name: String,
    val provider: String? = null,
    /** Price per billing period, despite the name. */
    @SerialName("monthly_price") val monthlyPrice: Double,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("discount_percent") val discountPercent: Double,
    /** Overrides [discountPercent] on the backend when present. */
    val discount: DiscountDto? = null,
    val features: List<String>? = null,
    @SerialName("usage_limits") val usageLimits: Map<String, String>? = null,
    @SerialName("cancellation_terms") val cancellationTerms: String? = null,
    @SerialName("renewal_note") val renewalNote: String? = null,
)

@Serializable
data class CompareRequestDto(
    @SerialName("user_id") val userId: String,
    val question: String,
    @SerialName("plan_a") val planA: PlanInputDto,
    @SerialName("plan_b") val planB: PlanInputDto,
    val priority: String,
)

// ── /compare response ────────────────────────────────────────────────────────

@Serializable
data class CostBreakdownDto(
    @SerialName("plan_name") val planName: String,
    @SerialName("listed_price") val listedPrice: Double,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("monthly_equivalent") val monthlyEquivalent: Double,
    @SerialName("discount_percent") val discountPercent: Double,
    @SerialName("discounted_monthly") val discountedMonthly: Double,
    @SerialName("first_year_cost") val firstYearCost: Double,
    @SerialName("first_year_explanation") val firstYearExplanation: String,
    @SerialName("recurring_annual_cost") val recurringAnnualCost: Double,
    @SerialName("recurring_annual_explanation") val recurringAnnualExplanation: String,
    @SerialName("has_intro_offer") val hasIntroOffer: Boolean,
    @SerialName("intro_months") val introMonths: Int? = null,
    @SerialName("post_intro_monthly") val postIntroMonthly: Double? = null,
    @SerialName("renewal_cost_note") val renewalCostNote: String? = null,
)

@Serializable
data class FeatureComparisonDto(
    @SerialName("only_in_a") val onlyInA: List<String>,
    @SerialName("only_in_b") val onlyInB: List<String>,
    val shared: List<String>,
    @SerialName("a_count") val aCount: Int,
    @SerialName("b_count") val bCount: Int,
)

@Serializable
data class ScoreBreakdownDto(
    @SerialName("cost_score") val costScore: Double,
    @SerialName("features_score") val featuresScore: Double,
    @SerialName("usage_score") val usageScore: Double? = null,
    @SerialName("cancellation_score") val cancellationScore: Double? = null,
    @SerialName("weighted_total") val weightedTotal: Double,
    @SerialName("weights_used") val weightsUsed: Map<String, Double>,
    @SerialName("sufficient_data") val sufficientData: Boolean,
)

@Serializable
data class PolicyEvidenceDto(
    val chunk: Int,
    val title: String,
    val text: String,
    val source: String,
    val relevance: Double,
    @SerialName("matches_current_policy") val matchesCurrentPolicy: Boolean,
    val cited: Boolean = false,
)

@Serializable
data class TraceEventDto(
    val step: Int,
    val stage: String,
    val kind: String,
    val name: String,
    val status: String,
    val detail: String,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CompareResponseDto(
    val recommendation: String,
    @SerialName("recommendation_reason") val recommendationReason: String,
    @SerialName("priority_used") val priorityUsed: String,
    @SerialName("cost_a") val costA: CostBreakdownDto,
    @SerialName("cost_b") val costB: CostBreakdownDto,
    @SerialName("absolute_savings_first_year") val absoluteSavingsFirstYear: Double,
    @SerialName("percentage_savings_first_year") val percentageSavingsFirstYear: Double,
    @SerialName("absolute_savings_recurring") val absoluteSavingsRecurring: Double,
    @SerialName("percentage_savings_recurring") val percentageSavingsRecurring: Double,
    @SerialName("cheaper_plan_first_year") val cheaperPlanFirstYear: String,
    @SerialName("cheaper_plan_recurring") val cheaperPlanRecurring: String,
    @SerialName("feature_comparison") val featureComparison: FeatureComparisonDto,
    @SerialName("score_a") val scoreA: ScoreBreakdownDto? = null,
    @SerialName("score_b") val scoreB: ScoreBreakdownDto? = null,
    @SerialName("ai_explanation") val aiExplanation: String = "",
    @SerialName("policy_context") val policyContext: String = "",
    val trace: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    @SerialName("missing_info") val missingInfo: List<String> = emptyList(),
    @SerialName("verification_passed") val verificationPassed: Boolean = true,
    @SerialName("verification_notes") val verificationNotes: List<String> = emptyList(),
    @SerialName("elapsed_seconds") val elapsedSeconds: Double = 0.0,
    @SerialName("guardrail_passed") val guardrailPassed: Boolean = true,
    @SerialName("policy_evidence") val policyEvidence: List<PolicyEvidenceDto> = emptyList(),
    @SerialName("cited_chunks") val citedChunks: List<Int> = emptyList(),
    @SerialName("unsupported_citations") val unsupportedCitations: List<Int> = emptyList(),
    @SerialName("trace_events") val traceEvents: List<TraceEventDto> = emptyList(),
    /** Null when the backend could not save the comparison to history. */
    @SerialName("comparison_id") val comparisonId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ── /chat ────────────────────────────────────────────────────────────────────

@Serializable
data class ChatRequestDto(
    @SerialName("user_id") val userId: String,
    @SerialName("thread_id") val threadId: String,
    val message: String,
)

@Serializable
data class ChatResponseDto(
    val reply: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("user_id") val userId: String,
    val timestamp: String? = null,
    val evidence: List<PolicyEvidenceDto> = emptyList(),
    @SerialName("cited_chunks") val citedChunks: List<Int> = emptyList(),
    @SerialName("unsupported_citations") val unsupportedCitations: List<Int> = emptyList(),
    @SerialName("restored_history") val restoredHistory: Boolean = false,
)

@Serializable
data class ChatMessageDto(
    val id: Long,
    val role: String,
    val content: String,
    val evidence: List<PolicyEvidenceDto> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ConversationSummaryDto(
    @SerialName("thread_id") val threadId: String,
    val title: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ConversationListDto(val conversations: List<ConversationSummaryDto>)

@Serializable
data class ConversationDto(
    @SerialName("thread_id") val threadId: String,
    val messages: List<ChatMessageDto>,
)

// ── /auth ────────────────────────────────────────────────────────────────────

@Serializable
data class SessionRequestDto(@SerialName("user_id") val userId: String)

@Serializable
data class SessionResponseDto(
    @SerialName("user_id") val userId: String,
    val token: String,
    @SerialName("created_at") val createdAt: String? = null,
)

// ── /history ─────────────────────────────────────────────────────────────────

/** A plan as the backend stored it (`PlanInput.model_dump()`); usage limit values may be any JSON. */
@Serializable
data class SavedPlanDto(
    val name: String,
    val provider: String? = null,
    @SerialName("monthly_price") val monthlyPrice: Double,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("discount_percent") val discountPercent: Double = 0.0,
    val discount: DiscountDto? = null,
    val features: List<String>? = null,
    @SerialName("usage_limits") val usageLimits: Map<String, JsonElement>? = null,
    @SerialName("cancellation_terms") val cancellationTerms: String? = null,
    @SerialName("renewal_note") val renewalNote: String? = null,
)

@Serializable
data class SavedComparisonDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val question: String,
    val priority: String,
    @SerialName("plan_a") val planA: SavedPlanDto,
    @SerialName("plan_b") val planB: SavedPlanDto,
    val recommendation: String,
    val result: CompareResponseDto,
)

@Serializable
data class HistoryListDto(
    val items: List<SavedComparisonDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class TraceResponseDto(
    @SerialName("comparison_id") val comparisonId: String,
    @SerialName("created_at") val createdAt: String,
    val events: List<TraceEventDto>,
)

// ── /preferences ─────────────────────────────────────────────────────────────

@Serializable
data class PreferencesRequestDto(@SerialName("default_priority") val defaultPriority: String?)

@Serializable
data class PreferencesDto(
    @SerialName("user_id") val userId: String,
    @SerialName("default_priority") val defaultPriority: String? = null,
    val currency: String = "INR",
    @SerialName("updated_at") val updatedAt: String? = null,
)

// ── /policy ──────────────────────────────────────────────────────────────────

@Serializable
data class PolicyAskRequestDto(val question: String, val k: Int = 3)

@Serializable
data class PolicyAskResponseDto(
    val question: String,
    val answer: String,
    @SerialName("answered_from_policy") val answeredFromPolicy: Boolean,
    val evidence: List<PolicyEvidenceDto>,
    @SerialName("cited_chunks") val citedChunks: List<Int>,
    @SerialName("unsupported_citations") val unsupportedCitations: List<Int>,
    @SerialName("trace_events") val traceEvents: List<TraceEventDto>,
    @SerialName("elapsed_seconds") val elapsedSeconds: Double,
)

@Serializable
data class PolicyStatusDto(
    val available: Boolean,
    @SerialName("indexed_chunks") val indexedChunks: Int,
    @SerialName("file_chunks") val fileChunks: Int,
    @SerialName("stale_chunks") val staleChunks: List<Int>,
    @SerialName("missing_chunks") val missingChunks: List<Int>,
    @SerialName("min_relevance") val minRelevance: Double,
)

// ── /memory ──────────────────────────────────────────────────────────────────

@Serializable
data class MemoryResponseDto(
    @SerialName("user_id") val userId: String,
    /** `dict[str, Any]` on the backend; values are rendered as text. */
    val profile: JsonObject,
    val timestamp: String? = null,
)
