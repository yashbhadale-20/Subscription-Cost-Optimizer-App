package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.CheaperTier
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.SubscriptionCategory
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Raw, user-typed state of one subscription in the editor sheet. Numbers stay as text until validated. */
data class SubscriptionForm(
    /** Blank for a new subscription; an id is assigned when it is added. */
    val id: String = "",
    val name: String = "",
    val provider: String = "",
    val category: SubscriptionCategory = SubscriptionCategory.OTHER,
    val price: String = "",
    val billingCycle: BillingCycle = BillingCycle.MONTHLY,
    val discountPercent: String = "",
    val hasIntroOffer: Boolean = false,
    val introMonths: String = "",
    val postIntroPrice: String = "",
    val usesPerMonth: String = "",
    val lastUsedDaysAgo: String = "",
    /** YYYY-MM-DD. */
    val renewalDate: String = "",
    val annualPrice: String = "",
    val sharedWith: String = "",
    val mustKeep: Boolean = false,
    val hasCheaperTier: Boolean = false,
    val tierName: String = "",
    val tierPrice: String = "",
    val tierBillingCycle: BillingCycle = BillingCycle.MONTHLY,
    /** Comma-separated. */
    val tierFeaturesLost: String = "",
)

enum class SubscriptionField {
    NAME, PROVIDER, PRICE, DISCOUNT, INTRO_MONTHS, POST_INTRO_PRICE, USES, LAST_USED,
    RENEWAL_DATE, ANNUAL_PRICE, SHARED_WITH, TIER_NAME, TIER_PRICE, TIER_FEATURES,
}

/**
 * Client-side checks that mirror `SubscriptionInput` and `OptimizeRequest` in backend/models.py.
 * The backend remains the source of truth.
 */
object OptimizeValidation {
    const val MAX_SUBSCRIPTIONS = 30
    const val MAX_NAME = 120
    const val MAX_ID = 40
    const val MAX_PRICE = 1_000_000.0
    const val MAX_BUDGET = 10_000_000.0
    const val MAX_INTRO_MONTHS = 60
    const val MAX_USES = 10_000
    const val MAX_DAYS = 36_500
    const val MAX_SHARED = 20
    const val MAX_TIER_FEATURES = 20
    const val MAX_FEATURE_LENGTH = 100
    const val MAX_GOAL = 1_000

    private fun parseAmount(text: String) = CompareValidation.parseAmount(text)

    fun validate(form: SubscriptionForm, otherNames: List<String> = emptyList()): Map<SubscriptionField, String> {
        val errors = mutableMapOf<SubscriptionField, String>()

        val name = form.name.trim()
        when {
            name.isEmpty() -> errors[SubscriptionField.NAME] = "Enter a name"
            name.length > MAX_NAME -> errors[SubscriptionField.NAME] = "Use $MAX_NAME characters or fewer"
            otherNames.any { it.trim().equals(name, ignoreCase = true) } ->
                errors[SubscriptionField.NAME] = "You already have a subscription with this name"
        }
        if (form.provider.trim().length > MAX_NAME) errors[SubscriptionField.PROVIDER] = "Use $MAX_NAME characters or fewer"

        amountError(form.price, required = true, allowZero = false, max = MAX_PRICE)?.let { errors[SubscriptionField.PRICE] = it }

        if (form.discountPercent.isNotBlank()) {
            val d = parseAmount(form.discountPercent)
            if (d == null || d < 0.0 || d > 100.0) errors[SubscriptionField.DISCOUNT] = "Enter a percentage between 0 and 100"
        }

        if (form.hasIntroOffer) {
            intError(form.introMonths, 1, MAX_INTRO_MONTHS, required = true, unit = "months")
                ?.let { errors[SubscriptionField.INTRO_MONTHS] = it }
            amountError(form.postIntroPrice, required = false, allowZero = true, max = MAX_PRICE)
                ?.let { errors[SubscriptionField.POST_INTRO_PRICE] = it }
        }

        intError(form.usesPerMonth, 0, MAX_USES, required = false, unit = "times")?.let { errors[SubscriptionField.USES] = it }
        intError(form.lastUsedDaysAgo, 0, MAX_DAYS, required = false, unit = "days")?.let { errors[SubscriptionField.LAST_USED] = it }
        intError(form.sharedWith, 0, MAX_SHARED, required = false, unit = "people")?.let { errors[SubscriptionField.SHARED_WITH] = it }

        if (form.renewalDate.isNotBlank() && parseDate(form.renewalDate) == null) {
            errors[SubscriptionField.RENEWAL_DATE] = "Use the format YYYY-MM-DD, e.g. 2026-12-01"
        }

        if (form.billingCycle == BillingCycle.MONTHLY) {
            amountError(form.annualPrice, required = false, allowZero = false, max = MAX_PRICE)
                ?.let { errors[SubscriptionField.ANNUAL_PRICE] = it }
        }

        if (form.hasCheaperTier) {
            val tier = form.tierName.trim()
            when {
                tier.isEmpty() -> errors[SubscriptionField.TIER_NAME] = "Enter the cheaper plan's name"
                tier.length > MAX_NAME -> errors[SubscriptionField.TIER_NAME] = "Use $MAX_NAME characters or fewer"
            }
            amountError(form.tierPrice, required = true, allowZero = false, max = MAX_PRICE)
                ?.let { errors[SubscriptionField.TIER_PRICE] = it }
            val features = splitFeatures(form.tierFeaturesLost)
            when {
                features.size > MAX_TIER_FEATURES -> errors[SubscriptionField.TIER_FEATURES] = "List at most $MAX_TIER_FEATURES"
                features.any { it.length > MAX_FEATURE_LENGTH } ->
                    errors[SubscriptionField.TIER_FEATURES] = "Keep each feature to $MAX_FEATURE_LENGTH characters"
            }
        }
        return errors
    }

    /** Error for the whole list, or null when it can be sent. */
    fun listError(subscriptions: List<Subscription>): String? = when {
        subscriptions.isEmpty() -> "Add at least one subscription"
        subscriptions.size > MAX_SUBSCRIPTIONS -> "At most $MAX_SUBSCRIPTIONS subscriptions"
        subscriptions.map { it.id }.toSet().size != subscriptions.size -> "Each subscription needs a different id"
        else -> null
    }

    fun budgetError(text: String): String? {
        if (text.isBlank()) return null
        val value = parseAmount(text) ?: return "Enter a number, e.g. 1200"
        return if (value < 0.0 || value > MAX_BUDGET) "Budget must be between ₹0 and ₹1,00,00,000" else null
    }

    fun parseBudget(text: String): Double? = if (text.isBlank()) null else parseAmount(text)

    fun goalError(text: String): String? =
        if (text.trim().length > MAX_GOAL) "Use $MAX_GOAL characters or fewer" else null

    fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    }

    fun splitFeatures(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * A stable id from the name ("Prime Video" -> "prime_video"), unique among [existingIds] and
     * matching the backend's `^[a-zA-Z0-9_-]+$`, at most [MAX_ID] characters.
     */
    fun newId(name: String, existingIds: Collection<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(MAX_ID - 4).ifEmpty { "sub" }
        if (base !in existingIds) return base
        var n = 2
        while ("${base}_$n" in existingIds) n++
        return "${base}_$n"
    }

    /** Converts a form that passed [validate]. A blank [SubscriptionForm.id] must be filled in first. */
    fun toSubscription(form: SubscriptionForm): Subscription {
        require(form.id.isNotBlank()) { "Assign an id before converting" }
        val monthly = form.billingCycle == BillingCycle.MONTHLY
        return Subscription(
            id = form.id,
            name = form.name.trim(),
            provider = form.provider.trim().ifEmpty { null },
            category = form.category,
            price = requireNotNull(parseAmount(form.price)) { "Validate the form before converting it" },
            billingCycle = form.billingCycle,
            discountPercent = if (form.discountPercent.isBlank()) 0.0 else parseAmount(form.discountPercent) ?: 0.0,
            introMonths = if (form.hasIntroOffer) form.introMonths.trim().toIntOrNull() else null,
            postIntroPrice = if (form.hasIntroOffer && form.postIntroPrice.isNotBlank()) parseAmount(form.postIntroPrice) else null,
            usesPerMonth = form.usesPerMonth.trim().toIntOrNull(),
            lastUsedDaysAgo = form.lastUsedDaysAgo.trim().toIntOrNull(),
            renewalDate = parseDate(form.renewalDate)?.toString(),
            cheaperTier = if (form.hasCheaperTier) {
                CheaperTier(
                    name = form.tierName.trim(),
                    price = requireNotNull(parseAmount(form.tierPrice)),
                    billingCycle = form.tierBillingCycle,
                    featuresLost = splitFeatures(form.tierFeaturesLost),
                )
            } else {
                null
            },
            annualPrice = if (monthly && form.annualPrice.isNotBlank()) parseAmount(form.annualPrice) else null,
            sharedWith = form.sharedWith.trim().toIntOrNull() ?: 0,
            mustKeep = form.mustKeep,
        )
    }

    fun fromSubscription(s: Subscription): SubscriptionForm = SubscriptionForm(
        id = s.id,
        name = s.name,
        provider = s.provider.orEmpty(),
        category = s.category,
        price = s.price.plain(),
        billingCycle = s.billingCycle,
        discountPercent = if (s.discountPercent == 0.0) "" else s.discountPercent.plain(),
        hasIntroOffer = s.introMonths != null,
        introMonths = s.introMonths?.toString().orEmpty(),
        postIntroPrice = s.postIntroPrice?.plain().orEmpty(),
        usesPerMonth = s.usesPerMonth?.toString().orEmpty(),
        lastUsedDaysAgo = s.lastUsedDaysAgo?.toString().orEmpty(),
        renewalDate = s.renewalDate.orEmpty(),
        annualPrice = s.annualPrice?.plain().orEmpty(),
        sharedWith = if (s.sharedWith == 0) "" else s.sharedWith.toString(),
        mustKeep = s.mustKeep,
        hasCheaperTier = s.cheaperTier != null,
        tierName = s.cheaperTier?.name.orEmpty(),
        tierPrice = s.cheaperTier?.price?.plain().orEmpty(),
        tierBillingCycle = s.cheaperTier?.billingCycle ?: BillingCycle.MONTHLY,
        tierFeaturesLost = s.cheaperTier?.featuresLost?.joinToString(", ").orEmpty(),
    )

    /** "649" rather than "649.0"; keeps decimals when there are any. */
    private fun Double.plain(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private fun amountError(text: String, required: Boolean, allowZero: Boolean, max: Double): String? {
        if (text.isBlank()) return if (required) "Enter the price" else null
        val value = parseAmount(text) ?: return "Enter a number, e.g. 499"
        return when {
            !allowZero && value <= 0.0 -> "Must be greater than 0"
            value < 0.0 -> "Can't be negative"
            value > max -> "Must be at most ₹10,00,000"
            else -> null
        }
    }

    private fun intError(text: String, min: Int, max: Int, required: Boolean, unit: String): String? {
        if (text.isBlank()) return if (required) "Enter a number of $unit" else null
        val value = text.trim().toIntOrNull() ?: return "Enter a whole number"
        return if (value !in min..max) "Must be between $min and $max" else null
    }
}
