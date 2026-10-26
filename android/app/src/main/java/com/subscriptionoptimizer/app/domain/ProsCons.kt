package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.ComparisonResult
import com.subscriptionoptimizer.app.data.model.CostBreakdown
import com.subscriptionoptimizer.app.data.model.ScoreBreakdown

data class ProsCons(val pros: List<String>, val cons: List<String>)

/**
 * Builds pros and cons for each plan purely from figures already in the backend response.
 * Nothing here is generated or estimated; every line restates a calculated value.
 */
object ProsConsDeriver {

    fun derive(result: ComparisonResult): Pair<ProsCons, ProsCons> =
        forPlan(result, isA = true) to forPlan(result, isA = false)

    private fun forPlan(result: ComparisonResult, isA: Boolean): ProsCons {
        val pros = mutableListOf<String>()
        val cons = mutableListOf<String>()
        val self = if (isA) result.costA else result.costB
        val selfScore = if (isA) result.scoreA else result.scoreB
        val otherScore = if (isA) result.scoreB else result.scoreA
        val exclusive = if (isA) result.featureComparison.onlyInA else result.featureComparison.onlyInB

        compareCost(
            cheaper = result.cheaperPlanFirstYear,
            self = self,
            savings = result.absoluteSavingsFirstYear,
            period = "in the first year",
            pros = pros,
            cons = cons,
        )
        compareCost(
            cheaper = result.cheaperPlanRecurring,
            self = self,
            savings = result.absoluteSavingsRecurring,
            period = "per year after any intro offer",
            pros = pros,
            cons = cons,
        )

        if (self.hasIntroOffer && self.introMonths != null) {
            val after = self.postIntroMonthly ?: self.monthlyEquivalent
            cons += "Intro price lasts ${self.introMonths} months, then ${Formatters.money(after)}/month"
        } else if (self.discountPercent > 0.0) {
            pros += "Ongoing ${Formatters.percent(self.discountPercent)} discount"
        }

        if (exclusive.isNotEmpty()) {
            pros += "${exclusive.size} feature(s) the other plan doesn't list: ${exclusive.joinToString()}"
        }

        compareDimension(selfScore, otherScore, { it.cancellationScore }, "cancellation terms", pros, cons)
        compareDimension(selfScore, otherScore, { it.usageScore }, "usage limits", pros, cons)

        return ProsCons(pros, cons)
    }

    private fun compareCost(
        cheaper: String,
        self: CostBreakdown,
        savings: Double,
        period: String,
        pros: MutableList<String>,
        cons: MutableList<String>,
    ) {
        if (cheaper == "tie" || savings <= 0.0) return
        if (cheaper == self.planName) {
            pros += "${Formatters.money(savings)} cheaper $period"
        } else {
            cons += "${Formatters.money(savings)} more expensive $period"
        }
    }

    private fun compareDimension(
        self: ScoreBreakdown?,
        other: ScoreBreakdown?,
        pick: (ScoreBreakdown) -> Double?,
        label: String,
        pros: MutableList<String>,
        cons: MutableList<String>,
    ) {
        val mine = self?.let(pick) ?: return
        val theirs = other?.let(pick) ?: return
        when {
            mine > theirs -> pros += "Scores higher on $label"
            mine < theirs -> cons += "Scores lower on $label"
        }
    }
}
