"""
Deterministic Subscription Comparison Engine.

All cost calculations are pure Python — no LLM calls.
The LLM is only used downstream (by the supervisor) for natural-language
policy grounding and explanation.  This module can be tested entirely
without Ollama.

Reuses the existing StructuredTool functions from ta2_subscription_optimizer
for discount calculation, annual cost, and plan comparison.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


# ─────────────────────────────────────────────────────────────────────────────
# Billing-cycle normalisation
# ─────────────────────────────────────────────────────────────────────────────

class BillingCycle(str, Enum):
    MONTHLY = "monthly"
    QUARTERLY = "quarterly"
    SEMI_ANNUAL = "semi_annual"
    ANNUAL = "annual"

    @property
    def months(self) -> int:
        return {
            BillingCycle.MONTHLY: 1,
            BillingCycle.QUARTERLY: 3,
            BillingCycle.SEMI_ANNUAL: 6,
            BillingCycle.ANNUAL: 12,
        }[self]


def to_monthly(price: float, cycle: BillingCycle) -> float:
    """Convert any billing-cycle price to a monthly equivalent."""
    if price < 0:
        raise ValueError("Price cannot be negative.")
    return round(price / cycle.months, 2)


# ─────────────────────────────────────────────────────────────────────────────
# Discount & introductory offer handling
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class DiscountInfo:
    """Represents a discount or introductory offer."""
    percent: float = 0.0           # 0–100
    intro_months: int | None = None  # None = ongoing, N = expires after N months
    post_intro_price: float | None = None  # monthly price after intro expires


def apply_discount(monthly_price: float, discount_percent: float) -> float:
    """Apply a percentage discount, returning the discounted monthly price."""
    if monthly_price < 0:
        raise ValueError("Monthly price cannot be negative.")
    if not 0 <= discount_percent <= 100:
        raise ValueError("Discount must be between 0 and 100 percent.")
    return round(monthly_price * (1 - discount_percent / 100), 2)


def compute_first_year_cost(
    monthly_price: float,
    discount: DiscountInfo,
) -> tuple[float, str]:
    """
    Calculate first-year cost accounting for introductory period expiry.

    Returns (cost, explanation_string).
    """
    discounted_monthly = apply_discount(monthly_price, discount.percent)

    if discount.intro_months is None:
        # Ongoing discount — full 12 months at discounted rate
        cost = round(discounted_monthly * 12, 2)
        explanation = (
            f"12 months × ₹{discounted_monthly:.2f} = ₹{cost:.2f} "
            f"(ongoing {discount.percent:.1f}% discount)"
        )
        return cost, explanation

    # Intro period expires
    intro = min(discount.intro_months, 12)
    remaining = 12 - intro

    if discount.post_intro_price is not None:
        post_price = discount.post_intro_price
    else:
        # After intro, revert to the original price
        post_price = monthly_price

    intro_cost = round(discounted_monthly * intro, 2)
    remaining_cost = round(post_price * remaining, 2)
    total = round(intro_cost + remaining_cost, 2)

    explanation = (
        f"{intro} months × ₹{discounted_monthly:.2f} (intro) + "
        f"{remaining} months × ₹{post_price:.2f} (regular) = ₹{total:.2f}"
    )
    return total, explanation


def compute_recurring_annual_cost(
    monthly_price: float,
    discount: DiscountInfo,
) -> tuple[float, str]:
    """
    Calculate cost for year 2+ (post-intro if applicable).
    """
    if discount.intro_months is not None:
        # Intro expired — use post-intro or full price
        post_price = discount.post_intro_price or monthly_price
        cost = round(post_price * 12, 2)
        explanation = f"12 months × ₹{post_price:.2f} (post-intro rate) = ₹{cost:.2f}"
    else:
        # Ongoing discount
        discounted = apply_discount(monthly_price, discount.percent)
        cost = round(discounted * 12, 2)
        explanation = (
            f"12 months × ₹{discounted:.2f} "
            f"(ongoing {discount.percent:.1f}% discount) = ₹{cost:.2f}"
        )
    return cost, explanation


# ─────────────────────────────────────────────────────────────────────────────
# Feature comparison
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class FeatureComparison:
    """Result of comparing two feature sets."""
    only_in_a: list[str] = field(default_factory=list)
    only_in_b: list[str] = field(default_factory=list)
    shared: list[str] = field(default_factory=list)
    a_count: int = 0
    b_count: int = 0


def compare_features(
    features_a: list[str] | None,
    features_b: list[str] | None,
) -> FeatureComparison:
    """Compare two feature lists and classify shared / unique features."""
    set_a = set(f.strip().lower() for f in (features_a or []) if f.strip())
    set_b = set(f.strip().lower() for f in (features_b or []) if f.strip())

    # Preserve original casing using lookups
    orig_a = {f.strip().lower(): f.strip() for f in (features_a or []) if f.strip()}
    orig_b = {f.strip().lower(): f.strip() for f in (features_b or []) if f.strip()}

    shared_keys = set_a & set_b
    only_a_keys = set_a - set_b
    only_b_keys = set_b - set_a

    return FeatureComparison(
        only_in_a=sorted(orig_a[k] for k in only_a_keys),
        only_in_b=sorted(orig_b[k] for k in only_b_keys),
        shared=sorted(orig_a.get(k, orig_b.get(k, k)) for k in shared_keys),
        a_count=len(set_a),
        b_count=len(set_b),
    )


# ─────────────────────────────────────────────────────────────────────────────
# Priority-based scoring (0–100)
# ─────────────────────────────────────────────────────────────────────────────

class UserPriority(str, Enum):
    LOWEST_COST = "lowest_cost"
    BEST_VALUE = "best_value"
    MOST_FEATURES = "most_features"
    HEAVY_USAGE = "heavy_usage"
    STUDENT_BUDGET = "student_budget"
    FLEXIBLE_CANCELLATION = "flexible_cancellation"


# Weight profiles for each priority.
# Keys: cost_weight, features_weight, usage_weight, cancellation_weight
_PRIORITY_WEIGHTS: dict[UserPriority, dict[str, float]] = {
    UserPriority.LOWEST_COST:            {"cost": 0.80, "features": 0.05, "usage": 0.05, "cancellation": 0.10},
    UserPriority.BEST_VALUE:             {"cost": 0.40, "features": 0.30, "usage": 0.15, "cancellation": 0.15},
    UserPriority.MOST_FEATURES:          {"cost": 0.15, "features": 0.60, "usage": 0.15, "cancellation": 0.10},
    UserPriority.HEAVY_USAGE:            {"cost": 0.20, "features": 0.20, "usage": 0.50, "cancellation": 0.10},
    UserPriority.STUDENT_BUDGET:         {"cost": 0.70, "features": 0.10, "usage": 0.10, "cancellation": 0.10},
    UserPriority.FLEXIBLE_CANCELLATION:  {"cost": 0.20, "features": 0.10, "usage": 0.10, "cancellation": 0.60},
}


@dataclass
class ScoreBreakdown:
    """Transparent scoring result for a single plan."""
    cost_score: float = 0.0           # 0–100 (lower cost → higher score)
    features_score: float = 0.0       # 0–100 (more features → higher score)
    usage_score: float | None = None  # 0–100 or None if no usage data
    cancellation_score: float | None = None  # 0–100 or None if no data
    weighted_total: float = 0.0       # 0–100
    weights_used: dict[str, float] = field(default_factory=dict)
    sufficient_data: bool = True


def _cost_score_from_prices(
    this_annual: float,
    other_annual: float,
    max_price: float,
) -> float:
    """
    Score 0–100 for cost.  Cheaper → higher score.
    Uses relative positioning against the maximum price.
    """
    if max_price <= 0:
        return 50.0  # degenerate case
    # Normalize: 100 means cheapest possible, 0 means at max_price
    score = max(0.0, (1.0 - this_annual / max_price)) * 100
    return round(score, 1)


def _features_score(feature_count: int, other_count: int) -> float:
    """Score 0–100 based on feature count relative to the other plan."""
    total = feature_count + other_count
    if total == 0:
        return 50.0  # no features known for either
    return round((feature_count / total) * 100, 1)


def compute_scores(
    plan_a_annual: float,
    plan_b_annual: float,
    features_a: list[str] | None,
    features_b: list[str] | None,
    usage_limits_a: dict[str, Any] | None,
    usage_limits_b: dict[str, Any] | None,
    cancellation_a: str | None,
    cancellation_b: str | None,
    priority: UserPriority,
) -> tuple[ScoreBreakdown, ScoreBreakdown]:
    """
    Compute transparent scores for both plans based on the user's priority.

    Returns (score_a, score_b).

    Scoring is only applied when sufficient information is available.
    Unknown dimensions contribute equally to both plans (50/50).
    """
    weights = _PRIORITY_WEIGHTS[priority]
    max_price = max(plan_a_annual, plan_b_annual, 1.0)  # avoid div-by-zero

    # Cost scores (always available — we have prices)
    cost_a = _cost_score_from_prices(plan_a_annual, plan_b_annual, max_price)
    cost_b = _cost_score_from_prices(plan_b_annual, plan_a_annual, max_price)

    # Feature scores
    fa = features_a or []
    fb = features_b or []
    feat_a = _features_score(len(fa), len(fb))
    feat_b = _features_score(len(fb), len(fa))

    # Usage score — only if both plans provide usage limits
    has_usage = bool(usage_limits_a) and bool(usage_limits_b)
    if has_usage:
        # Simple heuristic: count of limits provided as a proxy
        ua_count = len(usage_limits_a or {})
        ub_count = len(usage_limits_b or {})
        total_u = ua_count + ub_count or 1
        usage_a = round((ua_count / total_u) * 100, 1)
        usage_b = round((ub_count / total_u) * 100, 1)
    else:
        usage_a = None
        usage_b = None

    # Cancellation score — only if both plans provide terms
    has_cancel = bool(cancellation_a) and bool(cancellation_b)
    if has_cancel:
        # If we have text, each gets 50 until LLM-based analysis.
        # We give "free cancellation" keywords a bonus.
        free_keywords = ["free", "anytime", "no fee", "no penalty", "flexible"]
        cancel_a = 70.0 if any(k in (cancellation_a or "").lower() for k in free_keywords) else 40.0
        cancel_b = 70.0 if any(k in (cancellation_b or "").lower() for k in free_keywords) else 40.0
    else:
        cancel_a = None
        cancel_b = None

    def _weighted(
        cost: float, feat: float,
        usage: float | None, cancel: float | None,
    ) -> tuple[float, bool]:
        """Calculate weighted total, redistributing weight from missing dimensions."""
        available_weight = weights["cost"] + weights["features"]
        total = cost * weights["cost"] + feat * weights["features"]
        sufficient = True

        if usage is not None:
            available_weight += weights["usage"]
            total += usage * weights["usage"]
        else:
            sufficient = False  # missing data

        if cancel is not None:
            available_weight += weights["cancellation"]
            total += cancel * weights["cancellation"]
        else:
            sufficient = False

        # Normalize to 0–100 if some weights are missing
        if available_weight > 0:
            total = total / available_weight
        else:
            total = 50.0

        return round(total, 1), sufficient

    total_a, suff_a = _weighted(cost_a, feat_a, usage_a, cancel_a)
    total_b, suff_b = _weighted(cost_b, feat_b, usage_b, cancel_b)

    score_a = ScoreBreakdown(
        cost_score=cost_a,
        features_score=feat_a,
        usage_score=usage_a,
        cancellation_score=cancel_a,
        weighted_total=total_a,
        weights_used=weights,
        sufficient_data=suff_a,
    )
    score_b = ScoreBreakdown(
        cost_score=cost_b,
        features_score=feat_b,
        usage_score=usage_b,
        cancellation_score=cancel_b,
        weighted_total=total_b,
        weights_used=weights,
        sufficient_data=suff_b,
    )
    return score_a, score_b


# ─────────────────────────────────────────────────────────────────────────────
# Recommendation engine
# ─────────────────────────────────────────────────────────────────────────────

class Recommendation(str, Enum):
    PLAN_A = "plan_a"
    PLAN_B = "plan_b"
    TIE = "tie"
    NEITHER = "neither"


@dataclass
class CostBreakdown:
    """Full cost analysis for a single plan."""
    plan_name: str
    listed_price: float
    billing_cycle: str
    monthly_equivalent: float
    discount_percent: float
    discounted_monthly: float
    first_year_cost: float
    first_year_explanation: str
    recurring_annual_cost: float
    recurring_annual_explanation: str
    has_intro_offer: bool
    intro_months: int | None = None
    post_intro_monthly: float | None = None
    renewal_cost_note: str | None = None


@dataclass
class ComparisonResult:
    """Complete structured comparison output."""
    recommendation: Recommendation
    recommendation_reason: str
    cost_a: CostBreakdown
    cost_b: CostBreakdown
    absolute_savings_first_year: float
    percentage_savings_first_year: float
    absolute_savings_recurring: float
    percentage_savings_recurring: float
    cheaper_plan_first_year: str  # plan name or "tie"
    cheaper_plan_recurring: str
    feature_comparison: FeatureComparison
    score_a: ScoreBreakdown | None = None
    score_b: ScoreBreakdown | None = None
    assumptions: list[str] = field(default_factory=list)
    missing_info: list[str] = field(default_factory=list)
    verification_passed: bool = True
    verification_notes: list[str] = field(default_factory=list)


def _build_cost_breakdown(
    name: str,
    listed_price: float,
    billing_cycle: BillingCycle,
    discount: DiscountInfo,
    renewal_note: str | None,
) -> CostBreakdown:
    """Build the full cost breakdown for one plan."""
    monthly_eq = to_monthly(listed_price, billing_cycle)
    discounted_monthly = apply_discount(monthly_eq, discount.percent)
    first_year, fy_explanation = compute_first_year_cost(monthly_eq, discount)
    recurring, rec_explanation = compute_recurring_annual_cost(monthly_eq, discount)

    return CostBreakdown(
        plan_name=name,
        listed_price=listed_price,
        billing_cycle=billing_cycle.value,
        monthly_equivalent=monthly_eq,
        discount_percent=discount.percent,
        discounted_monthly=discounted_monthly,
        first_year_cost=first_year,
        first_year_explanation=fy_explanation,
        recurring_annual_cost=recurring,
        recurring_annual_explanation=rec_explanation,
        has_intro_offer=discount.intro_months is not None,
        intro_months=discount.intro_months,
        post_intro_monthly=discount.post_intro_price,
        renewal_cost_note=renewal_note,
    )


def _compute_savings(cost_a: float, cost_b: float) -> tuple[float, float, str]:
    """
    Returns (absolute_savings, percentage_savings, cheaper_plan_name).
    Positive savings means the cheaper plan saves that much.
    """
    diff = abs(cost_a - cost_b)
    diff = round(diff, 2)
    if cost_a < cost_b:
        pct = round((diff / cost_b) * 100, 1) if cost_b > 0 else 0.0
        return diff, pct, "plan_a"
    elif cost_b < cost_a:
        pct = round((diff / cost_a) * 100, 1) if cost_a > 0 else 0.0
        return diff, pct, "plan_b"
    else:
        return 0.0, 0.0, "tie"


def _verify_consistency(
    cost_a: CostBreakdown,
    cost_b: CostBreakdown,
    recommendation: Recommendation,
    reason: str,
    score_a: ScoreBreakdown | None,
    score_b: ScoreBreakdown | None,
) -> tuple[bool, list[str]]:
    """
    Verify that the recommendation is consistent with the calculated numbers.
    Returns (passed, notes).
    """
    notes: list[str] = []

    # Verify discounted price is less than or equal to listed price
    if cost_a.discounted_monthly > cost_a.monthly_equivalent + 0.01:
        notes.append(f"Plan A discounted price ₹{cost_a.discounted_monthly} exceeds pre-discount ₹{cost_a.monthly_equivalent}")
    if cost_b.discounted_monthly > cost_b.monthly_equivalent + 0.01:
        notes.append(f"Plan B discounted price ₹{cost_b.discounted_monthly} exceeds pre-discount ₹{cost_b.monthly_equivalent}")

    # Verify first-year cost is non-negative
    if cost_a.first_year_cost < 0:
        notes.append(f"Plan A first-year cost is negative: ₹{cost_a.first_year_cost}")
    if cost_b.first_year_cost < 0:
        notes.append(f"Plan B first-year cost is negative: ₹{cost_b.first_year_cost}")

    # Cross-check: if recommendation is plan_a, plan_a should have higher score
    if score_a and score_b:
        if recommendation == Recommendation.PLAN_A and score_b.weighted_total > score_a.weighted_total + 0.5:
            notes.append(
                f"Recommendation is Plan A but Plan B scores higher "
                f"({score_b.weighted_total} > {score_a.weighted_total})"
            )
        if recommendation == Recommendation.PLAN_B and score_a.weighted_total > score_b.weighted_total + 0.5:
            notes.append(
                f"Recommendation is Plan B but Plan A scores higher "
                f"({score_a.weighted_total} > {score_b.weighted_total})"
            )

    # Verify annual cost = monthly * 12 for non-intro plans
    if not cost_a.has_intro_offer:
        expected = round(cost_a.discounted_monthly * 12, 2)
        if abs(cost_a.first_year_cost - expected) > 0.02:
            notes.append(
                f"Plan A first-year ₹{cost_a.first_year_cost} != "
                f"₹{cost_a.discounted_monthly} × 12 = ₹{expected}"
            )
    if not cost_b.has_intro_offer:
        expected = round(cost_b.discounted_monthly * 12, 2)
        if abs(cost_b.first_year_cost - expected) > 0.02:
            notes.append(
                f"Plan B first-year ₹{cost_b.first_year_cost} != "
                f"₹{cost_b.discounted_monthly} × 12 = ₹{expected}"
            )

    return len(notes) == 0, notes


def run_comparison(
    plan_a_name: str,
    plan_a_price: float,
    plan_a_billing: BillingCycle,
    plan_a_discount: DiscountInfo,
    plan_a_features: list[str] | None,
    plan_a_usage_limits: dict[str, Any] | None,
    plan_a_cancellation: str | None,
    plan_a_renewal_note: str | None,
    plan_b_name: str,
    plan_b_price: float,
    plan_b_billing: BillingCycle,
    plan_b_discount: DiscountInfo,
    plan_b_features: list[str] | None,
    plan_b_usage_limits: dict[str, Any] | None,
    plan_b_cancellation: str | None,
    plan_b_renewal_note: str | None,
    priority: UserPriority = UserPriority.BEST_VALUE,
) -> ComparisonResult:
    """
    Run a full deterministic comparison between two subscription plans.

    This function does NO LLM calls.  It produces a structured result
    that can be enriched with RAG policy context downstream.
    """
    assumptions: list[str] = []
    missing_info: list[str] = []

    # ── Cost breakdowns ────────────────────────────────────────────────────
    cost_a = _build_cost_breakdown(
        plan_a_name, plan_a_price, plan_a_billing,
        plan_a_discount, plan_a_renewal_note,
    )
    cost_b = _build_cost_breakdown(
        plan_b_name, plan_b_price, plan_b_billing,
        plan_b_discount, plan_b_renewal_note,
    )

    # ── Savings ────────────────────────────────────────────────────────────
    abs_fy, pct_fy, cheaper_fy = _compute_savings(cost_a.first_year_cost, cost_b.first_year_cost)
    abs_rec, pct_rec, cheaper_rec = _compute_savings(cost_a.recurring_annual_cost, cost_b.recurring_annual_cost)

    cheaper_fy_name = (
        cost_a.plan_name if cheaper_fy == "plan_a"
        else cost_b.plan_name if cheaper_fy == "plan_b"
        else "tie"
    )
    cheaper_rec_name = (
        cost_a.plan_name if cheaper_rec == "plan_a"
        else cost_b.plan_name if cheaper_rec == "plan_b"
        else "tie"
    )

    # ── Features ───────────────────────────────────────────────────────────
    feat_cmp = compare_features(plan_a_features, plan_b_features)
    if not plan_a_features and not plan_b_features:
        missing_info.append("No features provided for either plan.")
    elif not plan_a_features:
        missing_info.append(f"No features provided for {plan_a_name}.")
    elif not plan_b_features:
        missing_info.append(f"No features provided for {plan_b_name}.")

    # ── Usage limits ───────────────────────────────────────────────────────
    if not plan_a_usage_limits and not plan_b_usage_limits:
        missing_info.append("No usage limits provided for either plan.")
    elif not plan_a_usage_limits:
        missing_info.append(f"No usage limits provided for {plan_a_name}.")
    elif not plan_b_usage_limits:
        missing_info.append(f"No usage limits provided for {plan_b_name}.")

    # ── Cancellation ───────────────────────────────────────────────────────
    if not plan_a_cancellation and not plan_b_cancellation:
        missing_info.append("No cancellation terms provided for either plan.")
    elif not plan_a_cancellation:
        missing_info.append(f"No cancellation terms provided for {plan_a_name}.")
    elif not plan_b_cancellation:
        missing_info.append(f"No cancellation terms provided for {plan_b_name}.")

    # ── Assumptions ────────────────────────────────────────────────────────
    assumptions.append("Prices are in INR unless otherwise specified.")
    assumptions.append("Taxes and service fees are not included.")
    if cost_a.has_intro_offer or cost_b.has_intro_offer:
        assumptions.append("Introductory pricing reverts to regular rate after the stated period.")
    if plan_a_billing != BillingCycle.MONTHLY or plan_b_billing != BillingCycle.MONTHLY:
        assumptions.append("Non-monthly prices were converted to monthly equivalents for comparison.")

    # ── Scoring ────────────────────────────────────────────────────────────
    score_a, score_b = compute_scores(
        cost_a.first_year_cost, cost_b.first_year_cost,
        plan_a_features, plan_b_features,
        plan_a_usage_limits, plan_b_usage_limits,
        plan_a_cancellation, plan_b_cancellation,
        priority,
    )

    # ── Recommendation ─────────────────────────────────────────────────────
    recommendation, reason = _determine_recommendation(
        cost_a, cost_b,
        score_a, score_b,
        feat_cmp, priority,
        abs_fy, cheaper_fy,
    )

    # ── Verification ───────────────────────────────────────────────────────
    verified, v_notes = _verify_consistency(
        cost_a, cost_b, recommendation, reason,
        score_a, score_b,
    )

    return ComparisonResult(
        recommendation=recommendation,
        recommendation_reason=reason,
        cost_a=cost_a,
        cost_b=cost_b,
        absolute_savings_first_year=abs_fy,
        percentage_savings_first_year=pct_fy,
        absolute_savings_recurring=abs_rec,
        percentage_savings_recurring=pct_rec,
        cheaper_plan_first_year=cheaper_fy_name,
        cheaper_plan_recurring=cheaper_rec_name,
        feature_comparison=feat_cmp,
        score_a=score_a,
        score_b=score_b,
        assumptions=assumptions,
        missing_info=missing_info,
        verification_passed=verified,
        verification_notes=v_notes,
    )


def _determine_recommendation(
    cost_a: CostBreakdown,
    cost_b: CostBreakdown,
    score_a: ScoreBreakdown,
    score_b: ScoreBreakdown,
    features: FeatureComparison,
    priority: UserPriority,
    abs_savings: float,
    cheaper: str,
) -> tuple[Recommendation, str]:
    """
    Produce a recommendation based on scores and priority.

    Rules:
    1. If scores are very close (within 2 points), it's a tie.
    2. If a more expensive plan is recommended, the reason MUST justify it.
    3. Never recommend a more expensive plan under LOWEST_COST or STUDENT_BUDGET
       unless the cheaper plan is genuinely unsuitable.
    """
    diff = score_a.weighted_total - score_b.weighted_total

    # Tie threshold
    if abs(diff) <= 2.0:
        return (
            Recommendation.TIE,
            f"Both plans score within 2 points of each other "
            f"(A: {score_a.weighted_total}, B: {score_b.weighted_total}). "
            f"The difference is not significant enough for a clear recommendation."
        )

    winner = Recommendation.PLAN_A if diff > 0 else Recommendation.PLAN_B
    winner_name = cost_a.plan_name if winner == Recommendation.PLAN_A else cost_b.plan_name
    loser_name = cost_b.plan_name if winner == Recommendation.PLAN_A else cost_a.plan_name

    winner_cost = cost_a.first_year_cost if winner == Recommendation.PLAN_A else cost_b.first_year_cost
    loser_cost = cost_b.first_year_cost if winner == Recommendation.PLAN_A else cost_a.first_year_cost

    winner_score = score_a if winner == Recommendation.PLAN_A else score_b
    loser_score = score_b if winner == Recommendation.PLAN_A else score_a

    # Safety check: under cost-focused priorities, don't recommend more expensive
    # unless the score difference is substantial and justified.
    if priority in (UserPriority.LOWEST_COST, UserPriority.STUDENT_BUDGET):
        if winner_cost > loser_cost:
            # More expensive plan wins on score — switch to cheaper unless
            # the score gap is very large (>15 points) indicating clear value
            if abs(diff) < 15.0:
                return (
                    Recommendation.PLAN_A if cost_a.first_year_cost <= cost_b.first_year_cost else Recommendation.PLAN_B,
                    f"Under the '{priority.value}' priority, {loser_name} is recommended "
                    f"as it costs ₹{abs(winner_cost - loser_cost):.2f} less in the first year, "
                    f"and the score difference ({abs(diff):.1f} points) does not justify the higher cost."
                )

    # Build reason
    if winner_cost > loser_cost:
        reason = (
            f"{winner_name} is recommended despite costing ₹{winner_cost - loser_cost:.2f} more "
            f"in the first year, because it scores {abs(diff):.1f} points higher overall "
            f"(A: {score_a.weighted_total}, B: {score_b.weighted_total}) "
            f"under the '{priority.value}' priority."
        )
        if features.only_in_a and winner == Recommendation.PLAN_A:
            reason += f" {winner_name} offers {len(features.only_in_a)} exclusive feature(s)."
        elif features.only_in_b and winner == Recommendation.PLAN_B:
            reason += f" {winner_name} offers {len(features.only_in_b)} exclusive feature(s)."
    elif winner_cost < loser_cost:
        reason = (
            f"{winner_name} is recommended: it saves ₹{abs(winner_cost - loser_cost):.2f} "
            f"in the first year and scores {abs(diff):.1f} points higher "
            f"(A: {score_a.weighted_total}, B: {score_b.weighted_total}) "
            f"under the '{priority.value}' priority."
        )
    else:
        reason = (
            f"{winner_name} is recommended: same first-year cost but scores "
            f"{abs(diff):.1f} points higher "
            f"(A: {score_a.weighted_total}, B: {score_b.weighted_total}) "
            f"under the '{priority.value}' priority."
        )

    return winner, reason
