package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.BillingCycle
import com.subscriptionoptimizer.app.data.model.CheaperTier
import com.subscriptionoptimizer.app.data.model.Subscription
import com.subscriptionoptimizer.app.data.model.SubscriptionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeValidationTest {

    private val valid = SubscriptionForm(name = "Netflix", price = "649")

    @Test
    fun `minimal form is valid`() {
        assertTrue(OptimizeValidation.validate(valid).isEmpty())
    }

    @Test
    fun `name and price are required`() {
        val errors = OptimizeValidation.validate(SubscriptionForm())
        assertTrue(SubscriptionField.NAME in errors)
        assertTrue(SubscriptionField.PRICE in errors)
    }

    @Test
    fun `duplicate name is refused, ignoring case`() {
        val errors = OptimizeValidation.validate(valid, otherNames = listOf("netflix "))
        assertEquals("You already have a subscription with this name", errors[SubscriptionField.NAME])
    }

    @Test
    fun `numbers are range-checked like the backend`() {
        val errors = OptimizeValidation.validate(
            valid.copy(
                price = "0", discountPercent = "120", usesPerMonth = "-1", lastUsedDaysAgo = "abc",
                sharedWith = "21", renewalDate = "next week",
            ),
        )
        assertEquals(
            setOf(
                SubscriptionField.PRICE, SubscriptionField.DISCOUNT, SubscriptionField.USES,
                SubscriptionField.LAST_USED, SubscriptionField.SHARED_WITH, SubscriptionField.RENEWAL_DATE,
            ),
            errors.keys,
        )
    }

    @Test
    fun `comma-decimal amounts are refused`() {
        assertTrue(SubscriptionField.PRICE in OptimizeValidation.validate(valid.copy(price = "649,50")))
        assertTrue(OptimizeValidation.validate(valid.copy(price = "1,299.50")).isEmpty())
    }

    @Test
    fun `intro offer needs its length, cheaper tier needs a name and price`() {
        val errors = OptimizeValidation.validate(valid.copy(hasIntroOffer = true, hasCheaperTier = true))
        assertTrue(SubscriptionField.INTRO_MONTHS in errors)
        assertTrue(SubscriptionField.TIER_NAME in errors)
        assertTrue(SubscriptionField.TIER_PRICE in errors)
    }

    @Test
    fun `annual price only checked and sent for monthly plans`() {
        assertTrue(SubscriptionField.ANNUAL_PRICE in OptimizeValidation.validate(valid.copy(annualPrice = "x")))
        val yearly = valid.copy(id = "n", billingCycle = BillingCycle.ANNUAL, annualPrice = "x")
        assertTrue(OptimizeValidation.validate(yearly).isEmpty())
        assertNull(OptimizeValidation.toSubscription(yearly).annualPrice)
    }

    @Test
    fun `hidden intro fields are not sent`() {
        val form = valid.copy(id = "n", hasIntroOffer = false, introMonths = "3", postIntroPrice = "199")
        val sub = OptimizeValidation.toSubscription(form)
        assertNull(sub.introMonths)
        assertNull(sub.postIntroPrice)
    }

    @Test
    fun `form converts to a subscription and back`() {
        val sub = Subscription(
            id = "yt", name = "YouTube Premium", provider = "Google", category = SubscriptionCategory.VIDEO_STREAMING,
            price = 149.0, discountPercent = 10.0, introMonths = 3, postIntroPrice = 199.5, usesPerMonth = 25,
            lastUsedDaysAgo = 2, renewalDate = "2026-12-01", annualPrice = 1490.0, sharedWith = 3, mustKeep = true,
            cheaperTier = CheaperTier("Lite", 89.0, BillingCycle.MONTHLY, listOf("Background play", "Downloads")),
        )
        val form = OptimizeValidation.fromSubscription(sub)
        assertEquals("149", form.price)
        assertEquals("Background play, Downloads", form.tierFeaturesLost)
        assertTrue(OptimizeValidation.validate(form).isEmpty())
        assertEquals(sub, OptimizeValidation.toSubscription(form))
    }

    @Test
    fun `ids are slugs, unique and within the backend's pattern`() {
        assertEquals("prime_video", OptimizeValidation.newId("Prime Video!", emptyList()))
        assertEquals("prime_video_2", OptimizeValidation.newId("Prime Video", listOf("prime_video")))
        assertEquals("prime_video_3", OptimizeValidation.newId("Prime Video", listOf("prime_video", "prime_video_2")))
        assertEquals("sub", OptimizeValidation.newId("₹₹₹", emptyList()))
        val long = OptimizeValidation.newId("x".repeat(100), emptyList())
        assertTrue(long.length <= OptimizeValidation.MAX_ID && Regex("^[a-zA-Z0-9_-]+$").matches(long))
    }

    @Test
    fun `list, budget and goal checks`() {
        assertEquals("Add at least one subscription", OptimizeValidation.listError(emptyList()))
        val many = (1..31).map { Subscription(id = "s$it", name = "S$it", price = 10.0) }
        assertEquals("At most 30 subscriptions", OptimizeValidation.listError(many))
        assertNull(OptimizeValidation.listError(many.take(30)))
        assertNull(OptimizeValidation.budgetError(""))
        assertNull(OptimizeValidation.budgetError("1,200"))
        assertTrue(OptimizeValidation.budgetError("lots") != null)
        assertEquals(1200.0, OptimizeValidation.parseBudget("1,200")!!, 0.0)
        assertNull(OptimizeValidation.parseBudget(" "))
        assertTrue(OptimizeValidation.goalError("x".repeat(1001)) != null)
    }
}
