package com.subscriptionoptimizer.app.data.remote

import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.BudgetResult
import com.subscriptionoptimizer.app.data.model.CheaperTier
import com.subscriptionoptimizer.app.data.model.OptimizationResult
import com.subscriptionoptimizer.app.data.model.OptimizationSummary
import com.subscriptionoptimizer.app.data.model.OptimizeAction
import com.subscriptionoptimizer.app.data.model.OptimizeActionType
import com.subscriptionoptimizer.app.data.model.OptimizeRequest
import com.subscriptionoptimizer.app.data.model.Portfolio
import com.subscriptionoptimizer.app.data.model.RenewalAlert
import com.subscriptionoptimizer.app.data.model.SavedOptimization
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.SubscriptionCategory
import com.subscriptionoptimizer.app.data.repository.RepositoryError

fun OptimizeRequest.toDto() = OptimizeRequestDto(
    userId = userId,
    goal = goal?.trim()?.ifEmpty { null },
    monthlyBudget = monthlyBudget,
    subscriptions = subscriptions.map { it.toDto() },
    savePortfolio = savePortfolio,
)

fun Subscription.toDto() = SubscriptionDto(
    id = id,
    name = name,
    provider = provider,
    category = category.apiValue,
    price = price,
    billingCycle = billingCycle.apiValue,
    discountPercent = discountPercent,
    introMonths = introMonths,
    postIntroPrice = postIntroPrice,
    usesPerMonth = usesPerMonth,
    lastUsedDaysAgo = lastUsedDaysAgo,
    renewalDate = renewalDate,
    cheaperTier = cheaperTier?.let {
        CheaperTierDto(it.name, it.price, it.billingCycle.apiValue, it.featuresLost)
    },
    annualPrice = annualPrice,
    sharedWith = sharedWith,
    mustKeep = mustKeep,
)

fun SubscriptionDto.toDomain() = Subscription(
    id = id,
    name = name,
    provider = provider,
    category = SubscriptionCategory.fromApi(category) ?: unknown("category", category),
    price = price,
    billingCycle = BillingCycle.fromApi(billingCycle) ?: unknown("billing_cycle", billingCycle),
    discountPercent = discountPercent,
    introMonths = introMonths,
    postIntroPrice = postIntroPrice,
    usesPerMonth = usesPerMonth,
    lastUsedDaysAgo = lastUsedDaysAgo,
    renewalDate = renewalDate,
    cheaperTier = cheaperTier?.let {
        CheaperTier(
            name = it.name,
            price = it.price,
            billingCycle = BillingCycle.fromApi(it.billingCycle) ?: unknown("billing_cycle", it.billingCycle),
            featuresLost = it.featuresLost,
        )
    },
    annualPrice = annualPrice,
    sharedWith = sharedWith,
    mustKeep = mustKeep,
)

/** @throws RepositoryError.MalformedResponse for an action type the app doesn't know. */
fun OptimizeResponseDto.toDomain() = OptimizationResult(
    currentMonthlyTotal = currentMonthlyTotal,
    currentAnnualTotal = currentAnnualTotal,
    actions = actions.map { it.toDomain() },
    optimizedMonthlyTotal = optimizedMonthlyTotal,
    optimizedAnnualTotal = optimizedAnnualTotal,
    totalMonthlySavings = totalMonthlySavings,
    totalAnnualSavings = totalAnnualSavings,
    percentageSavings = percentageSavings,
    budget = budget?.let { BudgetResult(it.target, it.achievable, it.met, it.gap) },
    renewalAlerts = renewalAlerts.map {
        RenewalAlert(
            subscriptionId = it.subscriptionId,
            name = it.name,
            kind = it.kind,
            renewalDate = it.renewalDate,
            daysUntil = it.daysUntil,
            currentMonthly = it.currentMonthly,
            afterMonthly = it.afterMonthly,
            priceJumpMonthly = it.priceJumpMonthly,
            chargeAmount = it.chargeAmount,
        )
    },
    aiSummary = aiSummary,
    policyEvidence = policyEvidence.map { it.toDomain() },
    citedChunks = citedChunks,
    unsupportedCitations = unsupportedCitations,
    assumptions = assumptions,
    missingInfo = missingInfo,
    verificationPassed = verificationPassed,
    verificationNotes = verificationNotes,
    usedFallback = usedFallback,
    fallbackReason = fallbackReason,
    traceEvents = traceEvents.map { it.toDomain() },
    elapsedSeconds = elapsedSeconds,
)

private fun OptimizeActionDto.toDomain() = OptimizeAction(
    actionId = actionId,
    subscriptionId = subscriptionId,
    name = name,
    action = OptimizeActionType.fromApi(action) ?: unknown("action", action),
    reason = reason,
    monthlySavings = monthlySavings,
    annualSavings = annualSavings,
    policyChunks = policyChunks,
)

/** An optimization `POST /optimize` just returned, in the shape the result screen and history use. */
fun OptimizeResponseDto.toSaved(request: OptimizeRequest, unsavedId: () -> String) = SavedOptimization(
    id = optimizationId ?: unsavedId(),
    createdAtMillis = createdAt?.let(::parseTimestamp) ?: System.currentTimeMillis(),
    goal = request.goal?.trim()?.ifEmpty { null },
    monthlyBudget = request.monthlyBudget,
    subscriptions = request.subscriptions,
    result = toDomain(),
    isSaved = optimizationId != null,
)

fun SavedOptimizationDto.toDomain() = SavedOptimization(
    id = id,
    createdAtMillis = parseTimestamp(createdAt) ?: unknown("created_at", createdAt),
    goal = goal,
    monthlyBudget = monthlyBudget,
    subscriptions = subscriptions.map { it.toDomain() },
    result = result.toDomain(),
)

fun OptimizationSummaryDto.toDomain() = OptimizationSummary(
    id = id,
    createdAtMillis = parseTimestamp(createdAt) ?: unknown("created_at", createdAt),
    goal = goal,
    monthlyBudget = monthlyBudget,
    subscriptionCount = subscriptionCount,
    totalAnnualSavings = totalAnnualSavings,
    usedFallback = usedFallback,
)

fun PortfolioDto.toDomain() = Portfolio(
    subscriptions = subscriptions.map { it.toDomain() },
    updatedAtMillis = updatedAt?.let(::parseTimestamp),
)

private fun unknown(field: String, value: String): Nothing =
    throw RepositoryError.MalformedResponse("The backend sent an unknown $field value \"$value\".")
