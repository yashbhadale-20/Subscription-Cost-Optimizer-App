package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatReply
import com.subscriptionoptimizer.app.data.model.ChatRole
import com.subscriptionoptimizer.app.data.model.ConversationSummary
import com.subscriptionoptimizer.app.data.model.PolicyAnswer
import com.subscriptionoptimizer.app.data.model.PolicyEvidence
import com.subscriptionoptimizer.app.data.model.PolicyIndexStatus
import com.subscriptionoptimizer.app.data.model.SavedComparison
import com.subscriptionoptimizer.app.data.model.SavedPlan
import com.subscriptionoptimizer.app.data.model.TraceEvent
import com.subscriptionoptimizer.app.data.model.CompareRequest
import com.subscriptionoptimizer.app.data.model.ComparisonResult
import com.subscriptionoptimizer.app.data.model.CostBreakdown
import com.subscriptionoptimizer.app.data.model.FeatureComparison
import com.subscriptionoptimizer.app.data.model.PlanInput
import com.subscriptionoptimizer.app.data.model.Priority
import com.subscriptionoptimizer.app.data.model.Recommendation
import com.subscriptionoptimizer.app.data.model.ScoreBreakdown
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

fun CompareRequest.toDto() = CompareRequestDto(
    userId = userId,
    question = question,
    planA = planA.toDto(),
    planB = planB.toDto(),
    priority = priority.apiValue,
)

// Empty collections go out as null: the backend treats "not provided" and "empty" alike and reports
// the gap in `missing_info`.
private fun PlanInput.toDto() = PlanInputDto(
    name = name,
    provider = provider,
    monthlyPrice = price,
    billingCycle = billingCycle.apiValue,
    discountPercent = discount.percent,
    discount = DiscountDto(
        percent = discount.percent,
        introMonths = discount.introMonths,
        postIntroPrice = discount.postIntroPrice,
    ),
    features = features.ifEmpty { null },
    usageLimits = usageLimits.ifEmpty { null },
    cancellationTerms = cancellationTerms,
    renewalNote = renewalNote,
)

/** @throws RepositoryError.MalformedResponse if an enum value is one the app doesn't know. */
fun CompareResponseDto.toDomain() = ComparisonResult(
    recommendation = Recommendation.fromApi(recommendation) ?: malformed("recommendation", recommendation),
    recommendationReason = recommendationReason,
    priorityUsed = Priority.fromApi(priorityUsed),
    costA = costA.toDomain(),
    costB = costB.toDomain(),
    absoluteSavingsFirstYear = absoluteSavingsFirstYear,
    percentageSavingsFirstYear = percentageSavingsFirstYear,
    absoluteSavingsRecurring = absoluteSavingsRecurring,
    percentageSavingsRecurring = percentageSavingsRecurring,
    cheaperPlanFirstYear = cheaperPlanFirstYear,
    cheaperPlanRecurring = cheaperPlanRecurring,
    featureComparison = FeatureComparison(
        onlyInA = featureComparison.onlyInA,
        onlyInB = featureComparison.onlyInB,
        shared = featureComparison.shared,
    ),
    scoreA = scoreA?.toDomain(),
    scoreB = scoreB?.toDomain(),
    aiExplanation = aiExplanation,
    policyContext = policyContext,
    trace = trace,
    assumptions = assumptions,
    missingInfo = missingInfo,
    verificationPassed = verificationPassed,
    verificationNotes = verificationNotes,
    elapsedSeconds = elapsedSeconds,
    policyEvidence = policyEvidence.map { it.toDomain() },
    citedChunks = citedChunks,
    unsupportedCitations = unsupportedCitations,
    traceEvents = traceEvents.map { it.toDomain() },
)

/** A comparison the backend just returned, in the shape history uses. */
fun CompareResponseDto.toSaved(request: CompareRequest, unsavedId: () -> String): SavedComparison =
    SavedComparison(
        id = comparisonId ?: unsavedId(),
        createdAtMillis = createdAt?.let(::parseTimestamp) ?: System.currentTimeMillis(),
        result = toDomain(),
        question = request.question,
        planA = request.planA.toSaved(),
        planB = request.planB.toSaved(),
        isSaved = comparisonId != null,
    )

private fun PlanInput.toSaved() = SavedPlan(
    name = name,
    provider = provider,
    price = price,
    billingCycle = billingCycle,
    discountPercent = discount.percent,
    introMonths = discount.introMonths,
    postIntroPrice = discount.postIntroPrice,
    features = features,
    usageLimits = usageLimits,
    cancellationTerms = cancellationTerms,
    renewalNote = renewalNote,
)

fun SavedComparisonDto.toDomain() = SavedComparison(
    id = id,
    createdAtMillis = parseTimestamp(createdAt) ?: malformed("created_at", createdAt),
    result = result.toDomain(),
    question = question,
    planA = planA.toDomain(),
    planB = planB.toDomain(),
)

private fun SavedPlanDto.toDomain(): SavedPlan {
    val effectiveDiscount = discount?.percent ?: discountPercent
    return SavedPlan(
        name = name,
        provider = provider,
        price = monthlyPrice,
        billingCycle = BillingCycle.fromApi(billingCycle),
        discountPercent = effectiveDiscount,
        introMonths = discount?.introMonths,
        postIntroPrice = discount?.postIntroPrice,
        features = features.orEmpty(),
        usageLimits = usageLimits.orEmpty().mapValues { (_, v) -> v.asText() },
        cancellationTerms = cancellationTerms,
        renewalNote = renewalNote,
    )
}

fun PolicyEvidenceDto.toDomain() = PolicyEvidence(
    chunk = chunk,
    title = title,
    text = text,
    source = source,
    relevance = relevance,
    matchesCurrentPolicy = matchesCurrentPolicy,
    cited = cited,
)

fun TraceEventDto.toDomain() = TraceEvent(
    step = step,
    stage = stage,
    kind = kind,
    name = name,
    status = status,
    detail = detail,
    durationMs = durationMs,
    data = data.mapValues { (_, v) -> v.asText() },
)

fun ChatResponseDto.toDomain() = ChatReply(
    text = reply,
    evidence = evidence.map { it.toDomain() },
    citedChunks = citedChunks,
    unsupportedCitations = unsupportedCitations,
    restoredHistory = restoredHistory,
)

fun ChatMessageDto.toDomain() = ChatMessage(
    id = "server-$id",
    role = when (role) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.ASSISTANT
        else -> malformed("role", role)
    },
    text = content,
    timestampMillis = parseTimestamp(createdAt) ?: malformed("created_at", createdAt),
    evidence = evidence.map { it.toDomain() },
)

fun ConversationSummaryDto.toDomain() = ConversationSummary(
    threadId = threadId,
    title = title,
    messageCount = messageCount,
    updatedAtMillis = parseTimestamp(updatedAt) ?: malformed("updated_at", updatedAt),
)

fun PolicyAskResponseDto.toDomain() = PolicyAnswer(
    question = question,
    answer = answer,
    answeredFromPolicy = answeredFromPolicy,
    evidence = evidence.map { it.toDomain() },
    citedChunks = citedChunks,
    unsupportedCitations = unsupportedCitations,
    traceEvents = traceEvents.map { it.toDomain() },
    elapsedSeconds = elapsedSeconds,
)

fun PolicyStatusDto.toDomain() = PolicyIndexStatus(
    available = available,
    indexedChunks = indexedChunks,
    fileChunks = fileChunks,
    staleChunks = staleChunks,
    missingChunks = missingChunks,
)

/** ISO-8601 with offset, as the backend writes it; null if unparseable. */
fun parseTimestamp(value: String): Long? = try {
    OffsetDateTime.parse(value).toInstant().toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}

private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()

private fun CostBreakdownDto.toDomain() = CostBreakdown(
    planName = planName,
    listedPrice = listedPrice,
    billingCycle = BillingCycle.fromApi(billingCycle) ?: malformed("billing_cycle", billingCycle),
    monthlyEquivalent = monthlyEquivalent,
    discountPercent = discountPercent,
    discountedMonthly = discountedMonthly,
    firstYearCost = firstYearCost,
    firstYearExplanation = firstYearExplanation,
    recurringAnnualCost = recurringAnnualCost,
    recurringAnnualExplanation = recurringAnnualExplanation,
    hasIntroOffer = hasIntroOffer,
    introMonths = introMonths,
    postIntroMonthly = postIntroMonthly,
    renewalCostNote = renewalCostNote,
)

private fun ScoreBreakdownDto.toDomain() = ScoreBreakdown(
    costScore = costScore,
    featuresScore = featuresScore,
    usageScore = usageScore,
    cancellationScore = cancellationScore,
    weightedTotal = weightedTotal,
    sufficientData = sufficientData,
)

fun MemoryResponseDto.profileAsText(): Map<String, String> = profile.mapValues { (_, value) -> value.asText() }

private fun malformed(field: String, value: String): Nothing =
    throw RepositoryError.MalformedResponse("The backend sent an unknown $field value \"$value\".")
