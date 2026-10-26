package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.BillingCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareValidationTest {

    private val valid = PlanForm(name = "Basic", price = "499")

    @Test
    fun `valid minimal plan has no errors`() {
        assertTrue(CompareValidation.validatePlan(valid).isEmpty())
    }

    @Test
    fun `missing name and price are reported`() {
        val errors = CompareValidation.validatePlan(PlanForm())
        assertTrue(PlanField.NAME in errors)
        assertTrue(PlanField.PRICE in errors)
    }

    @Test
    fun `price must be positive and within the backend limit`() {
        assertTrue(PlanField.PRICE in CompareValidation.validatePlan(valid.copy(price = "0")))
        assertTrue(PlanField.PRICE in CompareValidation.validatePlan(valid.copy(price = "abc")))
        assertTrue(PlanField.PRICE in CompareValidation.validatePlan(valid.copy(price = "1000001")))
        assertFalse(PlanField.PRICE in CompareValidation.validatePlan(valid.copy(price = "1,299.50")))
    }

    @Test
    fun `discount outside 0 to 100 is rejected`() {
        assertTrue(PlanField.DISCOUNT in CompareValidation.validatePlan(valid.copy(discountPercent = "101")))
        assertTrue(PlanField.DISCOUNT in CompareValidation.validatePlan(valid.copy(discountPercent = "-5")))
        assertFalse(PlanField.DISCOUNT in CompareValidation.validatePlan(valid.copy(discountPercent = "")))
    }

    @Test
    fun `intro offer needs a discount and a month count`() {
        val errors = CompareValidation.validatePlan(valid.copy(hasIntroOffer = true))
        assertTrue(PlanField.DISCOUNT in errors)
        assertTrue(PlanField.INTRO_MONTHS in errors)

        val ok = valid.copy(hasIntroOffer = true, discountPercent = "50", introMonths = "3")
        assertTrue(CompareValidation.validatePlan(ok).isEmpty())
    }

    @Test
    fun `plan B cannot reuse plan A's name`() {
        val errors = CompareValidation.validatePlan(valid, otherPlanName = " basic ")
        assertTrue(PlanField.NAME in errors)
    }

    @Test
    fun `incomplete or duplicate usage limits are rejected`() {
        val incomplete = valid.copy(usageLimits = listOf(UsageLimitEntry(1, "screens", "")))
        assertTrue(PlanField.USAGE_LIMITS in CompareValidation.validatePlan(incomplete))

        val duplicate = valid.copy(
            usageLimits = listOf(UsageLimitEntry(1, "screens", "2"), UsageLimitEntry(2, "Screens", "4")),
        )
        assertTrue(PlanField.USAGE_LIMITS in CompareValidation.validatePlan(duplicate))
    }

    @Test
    fun `duplicate features cannot be added`() {
        assertEquals("Already added", CompareValidation.newFeatureError("hd", listOf("HD")))
        assertNull(CompareValidation.newFeatureError("4K", listOf("HD")))
    }

    @Test
    fun `toPlanInput maps an intro offer and trims optional text`() {
        val input = CompareValidation.toPlanInput(
            valid.copy(
                billingCycle = BillingCycle.ANNUAL,
                discountPercent = "50",
                hasIntroOffer = true,
                introMonths = "3",
                provider = "  ",
                usageLimits = listOf(UsageLimitEntry(1, " screens ", " 4 ")),
            ),
        )
        assertEquals(499.0, input.price, 0.0)
        assertEquals(3, input.discount.introMonths)
        assertNull(input.discount.postIntroPrice)
        assertNull(input.provider)
        assertEquals(mapOf("screens" to "4"), input.usageLimits)
    }
}
