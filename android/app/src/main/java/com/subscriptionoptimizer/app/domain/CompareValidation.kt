package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.DiscountInput
import com.subscriptionoptimizer.app.data.model.PlanInput

/** Raw, user-typed state of one plan form. Numbers stay as text until validated. */
data class PlanForm(
    val name: String = "",
    val provider: String = "",
    val price: String = "",
    val billingCycle: BillingCycle = BillingCycle.MONTHLY,
    val discountPercent: String = "",
    val hasIntroOffer: Boolean = false,
    val introMonths: String = "",
    val postIntroPrice: String = "",
    val features: List<String> = emptyList(),
    val usageLimits: List<UsageLimitEntry> = emptyList(),
    val cancellationTerms: String = "",
    val renewalNote: String = "",
)

data class UsageLimitEntry(val id: Long, val name: String = "", val value: String = "")

enum class PlanField {
    NAME, PROVIDER, PRICE, DISCOUNT, INTRO_MONTHS, POST_INTRO_PRICE,
    FEATURES, USAGE_LIMITS, CANCELLATION, RENEWAL,
}

/**
 * Client-side checks that mirror the backend's Pydantic constraints (backend/models.py), so the
 * user sees problems before a request is sent. The backend remains the source of truth.
 */
object CompareValidation {
    const val MAX_NAME = 120
    const val MAX_PRICE = 1_000_000.0
    const val MAX_INTRO_MONTHS = 60
    const val MAX_FEATURES = 50
    const val MAX_FEATURE_LENGTH = 100
    const val MAX_USAGE_LIMITS = 20
    const val MAX_USAGE_FIELD = 40
    const val MAX_TERMS = 500
    const val MAX_QUESTION = 1_000

    /** Parses "1,299.50" or "₹ 499" into a finite number; null if not a number. */
    fun parseAmount(text: String): Double? =
        text.replace(",", "").replace("₹", "").trim()
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    fun validatePlan(form: PlanForm, otherPlanName: String? = null): Map<PlanField, String> {
        val errors = mutableMapOf<PlanField, String>()

        val name = form.name.trim()
        when {
            name.isEmpty() -> errors[PlanField.NAME] = "Enter a plan name"
            name.length > MAX_NAME -> errors[PlanField.NAME] = "Use $MAX_NAME characters or fewer"
            otherPlanName != null && name.equals(otherPlanName.trim(), ignoreCase = true) ->
                errors[PlanField.NAME] = "Give each plan a different name"
        }

        if (form.provider.trim().length > MAX_NAME) {
            errors[PlanField.PROVIDER] = "Use $MAX_NAME characters or fewer"
        }

        val price = parseAmount(form.price)
        when {
            form.price.isBlank() -> errors[PlanField.PRICE] = "Enter the price"
            price == null -> errors[PlanField.PRICE] = "Enter a number, e.g. 499"
            price <= 0.0 -> errors[PlanField.PRICE] = "Price must be greater than 0"
            price > MAX_PRICE -> errors[PlanField.PRICE] = "Price must be at most ₹10,00,000"
        }

        val discount = if (form.discountPercent.isBlank()) 0.0 else parseAmount(form.discountPercent)
        when {
            discount == null -> errors[PlanField.DISCOUNT] = "Enter a percentage, e.g. 10"
            discount < 0.0 || discount > 100.0 -> errors[PlanField.DISCOUNT] = "Discount must be between 0 and 100"
            form.hasIntroOffer && discount == 0.0 ->
                errors[PlanField.DISCOUNT] = "Enter the introductory discount"
        }

        if (form.hasIntroOffer) {
            val months = form.introMonths.trim().toIntOrNull()
            when {
                form.introMonths.isBlank() -> errors[PlanField.INTRO_MONTHS] = "Enter how many months the offer lasts"
                months == null -> errors[PlanField.INTRO_MONTHS] = "Enter whole months, e.g. 3"
                months !in 1..MAX_INTRO_MONTHS ->
                    errors[PlanField.INTRO_MONTHS] = "Must be between 1 and $MAX_INTRO_MONTHS months"
            }
            if (form.postIntroPrice.isNotBlank()) {
                val post = parseAmount(form.postIntroPrice)
                when {
                    post == null -> errors[PlanField.POST_INTRO_PRICE] = "Enter a number, e.g. 299"
                    post < 0.0 || post > MAX_PRICE ->
                        errors[PlanField.POST_INTRO_PRICE] = "Must be between ₹0 and ₹10,00,000"
                }
            }
        }

        if (form.features.size > MAX_FEATURES) {
            errors[PlanField.FEATURES] = "List at most $MAX_FEATURES features"
        }

        usageLimitsError(form.usageLimits)?.let { errors[PlanField.USAGE_LIMITS] = it }

        if (form.cancellationTerms.trim().length > MAX_TERMS) {
            errors[PlanField.CANCELLATION] = "Use $MAX_TERMS characters or fewer"
        }
        if (form.renewalNote.trim().length > MAX_TERMS) {
            errors[PlanField.RENEWAL] = "Use $MAX_TERMS characters or fewer"
        }
        return errors
    }

    /** Error for a feature the user is about to add, or null if it can be added. */
    fun newFeatureError(feature: String, existing: List<String>): String? {
        val trimmed = feature.trim()
        return when {
            trimmed.isEmpty() -> "Type a feature first"
            trimmed.length > MAX_FEATURE_LENGTH -> "Use $MAX_FEATURE_LENGTH characters or fewer"
            existing.any { it.equals(trimmed, ignoreCase = true) } -> "Already added"
            existing.size >= MAX_FEATURES -> "At most $MAX_FEATURES features"
            else -> null
        }
    }

    fun questionError(question: String): String? =
        if (question.trim().length > MAX_QUESTION) "Use $MAX_QUESTION characters or fewer" else null

    private fun usageLimitsError(limits: List<UsageLimitEntry>): String? {
        if (limits.size > MAX_USAGE_LIMITS) return "List at most $MAX_USAGE_LIMITS usage limits"
        if (limits.any { it.name.isBlank() || it.value.isBlank() }) {
            return "Fill in both the name and value of each limit, or remove it"
        }
        if (limits.any { it.name.trim().length > MAX_USAGE_FIELD || it.value.trim().length > MAX_USAGE_FIELD }) {
            return "Keep limit names and values to $MAX_USAGE_FIELD characters"
        }
        val names = limits.map { it.name.trim().lowercase() }
        if (names.size != names.toSet().size) return "Each usage limit needs a different name"
        return null
    }

    /** Converts a form that passed [validatePlan] into the request model. */
    fun toPlanInput(form: PlanForm): PlanInput {
        val discount = if (form.discountPercent.isBlank()) 0.0 else parseAmount(form.discountPercent) ?: 0.0
        return PlanInput(
            name = form.name.trim(),
            provider = form.provider.trim().ifEmpty { null },
            price = requireNotNull(parseAmount(form.price)) { "Validate the form before converting it" },
            billingCycle = form.billingCycle,
            discount = DiscountInput(
                percent = discount,
                introMonths = if (form.hasIntroOffer) form.introMonths.trim().toIntOrNull() else null,
                postIntroPrice = if (form.hasIntroOffer) parseAmount(form.postIntroPrice) else null,
            ),
            features = form.features,
            usageLimits = form.usageLimits.associate { it.name.trim() to it.value.trim() },
            cancellationTerms = form.cancellationTerms.trim().ifEmpty { null },
            renewalNote = form.renewalNote.trim().ifEmpty { null },
        )
    }
}
