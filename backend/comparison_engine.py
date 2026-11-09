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
import re
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


def _rounding_note(monthly: float, months: int, total: float) -> str:
    """Flag explanations whose displayed monthly figure was rounded (e.g. ₹1999/year ÷ 12)."""
    return " (monthly figure rounded)" if abs(round(monthly, 2) * months - total) >= 0.005 else ""


def compute_first_year_cost(
    monthly_price: float,
    discount: DiscountInfo,
) -> tuple[float, str]:
    """
    Calculate first-year cost accounting for introductory period expiry.

    ``monthly_price`` may carry more than two decimals (an annual price divided
    by 12); totals are computed from it and rounded once, so ₹1999/year costs
    ₹1999.00, not 12 × ₹166.58 = ₹1998.96.

    Returns (cost, explanation_string).
    """
    apply_discount(monthly_price, discount.percent)  # validates the inputs
    discounted_exact = monthly_price * (1 - discount.percent / 100)
    discounted_monthly = round(discounted_exact, 2)

    if discount.intro_months is None:
        # Ongoing discount — full 12 months at discounted rate
        cost = round(discounted_exact * 12, 2)
        explanation = (
            f"12 months × ₹{discounted_monthly:.2f} = ₹{cost:.2f} "
            f"(ongoing {discount.percent:.1f}% discount)"
            + _rounding_note(discounted_exact, 12, cost)
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

    total = round(discounted_exact * intro + post_price * remaining, 2)

    explanation = (
        f"{intro} months × ₹{discounted_monthly:.2f} (intro) + "
        f"{remaining} months × ₹{post_price:.2f} (regular) = ₹{total:.2f}"
        + _rounding_note(discounted_exact, intro, total - post_price * remaining)
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
        # Intro expired — use post-intro or full price. A post-intro price of 0 is a
        # real price (free after the offer), so test for None, not falsiness.
        post_price = discount.post_intro_price if discount.post_intro_price is not None else monthly_price
        cost = round(post_price * 12, 2)
        explanation = (
            f"12 months × ₹{post_price:.2f} (post-intro rate) = ₹{cost:.2f}"
            + _rounding_note(post_price, 12, cost)
        )
    else:
        # Ongoing discount
        apply_discount(monthly_price, discount.percent)  # validates the inputs
        discounted = monthly_price * (1 - discount.percent / 100)
        cost = round(discounted * 12, 2)
        explanation = (
            f"12 months × ₹{discounted:.2f} "
            f"(ongoing {discount.percent:.1f}% discount) = ₹{cost:.2f}"
            + _rounding_note(discounted, 12, cost)
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


_FLEXIBLE_TERMS = re.compile(r"\b(?:free|anytime|no fee|no penalty|flexible)\b")
_NEGATORS = {"no", "not", "non", "never", "without", "isn't", "isnt", "cannot", "can't"}


def _flexible_cancellation(terms: str) -> bool:
    """
    True if the terms promise flexible cancellation. A match preceded within two words by a
    negation ("No free cancellation", "not cancellable anytime") doesn't count.
    """
    low = terms.lower()
    for match in _FLEXIBLE_TERMS.finditer(low):
        preceding = re.findall(r"[a-z']+", low[: match.start()])[-2:]
        if not _NEGATORS.intersection(preceding):
            return True
    return False


_LIMIT_VALUE = re.compile(r"^(\d+(?:\.\d+)?)\s*([a-z]*)$")
_UNLIMITED = {"unlimited", "infinite", "no limit", "∞"}
_DATA_UNITS = {"kb": 1 / 1024 ** 2, "mb": 1 / 1024, "gb": 1.0, "tb": 1024.0}


def _parse_limit(value: Any) -> tuple[float, str] | None:
    """(amount, unit) for a usage limit such as 4, "4", "100 GB" or "unlimited"; None if it isn't one."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value), ""
    text = str(value).strip().lower()
    if text in _UNLIMITED:
        return math.inf, ""
    match = _LIMIT_VALUE.match(text)
    return (float(match.group(1)), match.group(2)) if match else None


def _limit_share(a: Any, b: Any) -> float:
    """Plan A's share (0–1) of one shared limit, higher being better. 0.5 when not comparable."""
    left, right = _parse_limit(a), _parse_limit(b)
    if left is None or right is None:
        return 0.5
    if math.isinf(left[0]) or math.isinf(right[0]):
        return 0.5 if left[0] == right[0] else (1.0 if math.isinf(left[0]) else 0.0)
    amount_a, amount_b = left[0], right[0]
    if left[1] != right[1]:
        if left[1] not in _DATA_UNITS or right[1] not in _DATA_UNITS:
            return 0.5
        amount_a, amount_b = amount_a * _DATA_UNITS[left[1]], amount_b * _DATA_UNITS[right[1]]
    total = amount_a + amount_b
    return amount_a / total if total > 0 else 0.5


def _usage_scores(limits_a: dict[str, Any], limits_b: dict[str, Any]) -> tuple[float, float] | None:
    """Scores from the limits both plans state; None if they have none in common."""
    a = {str(k).strip().lower(): v for k, v in limits_a.items()}
    b = {str(k).strip().lower(): v for k, v in limits_b.items()}
    shared = a.keys() & b.keys()
    if not shared:
        return None
    share = sum(_limit_share(a[k], b[k]) for k in shared) / len(shared)
    return round(share * 100, 1), round((1 - share) * 100, 1)


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

    # Feature scores, on distinct features (as compare_features counts them), so
    # ["HD", "hd", " HD "] counts once and can't outscore a plan with two real features.
    fa = {f.strip().lower() for f in (features_a or []) if f.strip()}
    fb = {f.strip().lower() for f in (features_b or []) if f.strip()}
    # A plan with no features listed is unknown, not featureless, so it isn't scored against.
    features_known = bool(fa) and bool(fb)
    feat_a = _features_score(len(fa), len(fb)) if features_known else 50.0
    feat_b = _features_score(len(fb), len(fa)) if features_known else 50.0

    # Usage score — only if both plans provide usage limits
    usage = _usage_scores(usage_limits_a or {}, usage_limits_b or {})
    usage_a, usage_b = usage if usage else (None, None)

    # Cancellation score — only if both plans provide terms
    has_cancel = bool(cancellation_a) and bool(cancellation_b)
    if has_cancel:
        # "Free cancellation"-style terms get a bonus.
        cancel_a = 70.0 if _flexible_cancellation(cancellation_a or "") else 40.0
        cancel_b = 70.0 if _flexible_cancellation(cancellation_b or "") else 40.0
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
    suff_a = suff_a and features_known
    suff_b = suff_b and features_known

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
    if listed_price < 0:
        raise ValueError("Price cannot be negative.")
    # Exact monthly price for the totals; the rounded one is only for display.
    monthly_exact = listed_price / billing_cycle.months
    monthly_eq = round(monthly_exact, 2)
    discounted_monthly = apply_discount(monthly_eq, discount.percent)
    first_year, fy_explanation = compute_first_year_cost(monthly_exact, discount)
    recurring, rec_explanation = compute_recurring_annual_cost(monthly_exact, discount)

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


# Priorities under which a cheaper plan is preferred unless the pricier one wins by a wide margin.
_COST_PRIORITIES = (UserPriority.LOWEST_COST, UserPriority.STUDENT_BUDGET)
_COST_OVERRIDE_GAP = 15.0
# 12 months × up to half a paisa of rounding in the displayed monthly price.
_DISPLAY_ROUNDING_TOLERANCE = 0.07


def _verify_consistency(
    cost_a: CostBreakdown,
    cost_b: CostBreakdown,
    recommendation: Recommendation,
    reason: str,
    score_a: ScoreBreakdown | None,
    score_b: ScoreBreakdown | None,
    priority: UserPriority | None = None,
) -> tuple[bool, list[str]]:
    """
    Verify that the recommendation is consistent with the calculated numbers.
    Returns (passed, notes).
    """
    notes: list[str] = []

    # Under cost-focused priorities _determine_recommendation deliberately picks the
    # cheaper plan when the pricier one wins on score by less than the override gap.
    def _cost_override(recommended: CostBreakdown, other: CostBreakdown, gap: float) -> bool:
        return (
            priority in _COST_PRIORITIES
            and recommended.first_year_cost < other.first_year_cost
            and gap < _COST_OVERRIDE_GAP
        )

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
        gap = abs(score_a.weighted_total - score_b.weighted_total)
        if (recommendation == Recommendation.PLAN_A and score_b.weighted_total > score_a.weighted_total + 0.5
                and not _cost_override(cost_a, cost_b, gap)):
            notes.append(
                f"Recommendation is Plan A but Plan B scores higher "
                f"({score_b.weighted_total} > {score_a.weighted_total})"
            )
        if (recommendation == Recommendation.PLAN_B and score_a.weighted_total > score_b.weighted_total + 0.5
                and not _cost_override(cost_b, cost_a, gap)):
            notes.append(
                f"Recommendation is Plan B but Plan A scores higher "
                f"({score_a.weighted_total} > {score_b.weighted_total})"
            )

    # Verify annual cost = monthly * 12 for non-intro plans
    if not cost_a.has_intro_offer:
        expected = round(cost_a.discounted_monthly * 12, 2)
        if abs(cost_a.first_year_cost - expected) > _DISPLAY_ROUNDING_TOLERANCE:
            notes.append(
                f"Plan A first-year ₹{cost_a.first_year_cost} != "
                f"₹{cost_a.discounted_monthly} × 12 = ₹{expected}"
            )
    if not cost_b.has_intro_offer:
        expected = round(cost_b.discounted_monthly * 12, 2)
        if abs(cost_b.first_year_cost - expected) > _DISPLAY_ROUNDING_TOLERANCE:
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
    for cost in (cost_a, cost_b):
        if cost.intro_months is not None and cost.intro_months > 12:
            assumptions.append(
                f"{cost.plan_name}'s offer lasts {cost.intro_months} months, so its recurring cost "
                f"applies only after month {cost.intro_months}, not from the second year."
            )
    if plan_a_usage_limits and plan_b_usage_limits:
        assumptions.append("Usage limits both plans state are compared by value, with higher treated as better.")

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

    # The recommendation is based on first-year cost, so say so when that stops being the cheaper plan.
    if "tie" not in (cheaper_fy, cheaper_rec) and cheaper_fy != cheaper_rec:
        reason += (
            f" {cheaper_fy_name} is cheaper in the first year, but "
            f"{cheaper_rec_name} costs ₹{abs_rec:.2f} less each year after that."
        )

    # ── Verification ───────────────────────────────────────────────────────
    verified, v_notes = _verify_consistency(
        cost_a, cost_b, recommendation, reason,
        score_a, score_b, priority,
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
        # Close scores don't make a cheaper plan a tie for someone who asked for the lowest cost.
        if priority in _COST_PRIORITIES and cost_a.first_year_cost != cost_b.first_year_cost:
            a_cheaper = cost_a.first_year_cost < cost_b.first_year_cost
            cheaper_cost = cost_a if a_cheaper else cost_b
            return (
                Recommendation.PLAN_A if a_cheaper else Recommendation.PLAN_B,
                f"{cheaper_cost.plan_name} is recommended: it costs "
                f"₹{abs(cost_a.first_year_cost - cost_b.first_year_cost):.2f} less in the first year "
                f"and the plans otherwise score within 2 points "
                f"(A: {score_a.weighted_total}, B: {score_b.weighted_total}) "
                f"under the '{priority.value}' priority."
            )
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
    if priority in _COST_PRIORITIES:
        if winner_cost > loser_cost:
            # More expensive plan wins on score — switch to cheaper unless
            # the score gap is very large (>15 points) indicating clear value
            if abs(diff) < _COST_OVERRIDE_GAP:
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
