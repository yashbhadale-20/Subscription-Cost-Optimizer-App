"""
Comprehensive tests for the comparison engine.

Tests:
  - Billing cycle normalisation (monthly, quarterly, semi-annual, annual)
  - Discount application and expiry (intro offers)
  - First-year cost with intro period
  - Recurring annual cost post-intro
  - Feature comparison (shared, unique, empty)
  - Tie detection
  - Invalid inputs (negative prices, out-of-range discounts)
  - Scoring with different priorities
  - Recommendation consistency
  - Missing data handling
  - Verification pass

All tests are pure Python — no LLM or Ollama required.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Fix Windows encoding for ₹ and box-drawing characters
if sys.platform == "win32":
    try:
        if sys.stdout and hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if sys.stderr and hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# Ensure project root is on path
_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from backend.comparison_engine import (
    BillingCycle,
    ComparisonResult,
    DiscountInfo,
    FeatureComparison,
    Recommendation,
    ScoreBreakdown,
    UserPriority,
    apply_discount,
    compare_features,
    compute_first_year_cost,
    compute_recurring_annual_cost,
    compute_scores,
    run_comparison,
    to_monthly,
)

results: list[tuple[str, str, str]] = []  # (status, name, detail)


def check(name: str, passed: bool, detail: str = ""):
    status = "PASS" if passed else "FAIL"
    results.append((status, name, detail))
    mark = "✓" if passed else "✗"
    print(f"  {mark} {name}" + (f"  ({detail})" if detail else ""))
    return passed


def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


# ═════════════════════════════════════════════════════════════════════════════
# 1. BILLING CYCLE NORMALISATION
# ═════════════════════════════════════════════════════════════════════════════
section("1. Billing Cycle Normalisation")

check(
    "Monthly → monthly (no-op)",
    to_monthly(499.0, BillingCycle.MONTHLY) == 499.0,
    f"got {to_monthly(499.0, BillingCycle.MONTHLY)}"
)

check(
    "Quarterly → monthly (÷3)",
    to_monthly(1500.0, BillingCycle.QUARTERLY) == 500.0,
    f"got {to_monthly(1500.0, BillingCycle.QUARTERLY)}"
)

check(
    "Semi-annual → monthly (÷6)",
    to_monthly(3000.0, BillingCycle.SEMI_ANNUAL) == 500.0,
    f"got {to_monthly(3000.0, BillingCycle.SEMI_ANNUAL)}"
)

check(
    "Annual → monthly (÷12)",
    to_monthly(6000.0, BillingCycle.ANNUAL) == 500.0,
    f"got {to_monthly(6000.0, BillingCycle.ANNUAL)}"
)

check(
    "Annual ₹5988 → ₹499/mo",
    to_monthly(5988.0, BillingCycle.ANNUAL) == 499.0,
    f"got {to_monthly(5988.0, BillingCycle.ANNUAL)}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 2. DISCOUNT APPLICATION
# ═════════════════════════════════════════════════════════════════════════════
section("2. Discount Application")

check(
    "10% off ₹500 = ₹450",
    apply_discount(500.0, 10.0) == 450.0,
    f"got {apply_discount(500.0, 10.0)}"
)

check(
    "0% off = same price",
    apply_discount(999.0, 0.0) == 999.0,
    f"got {apply_discount(999.0, 0.0)}"
)

check(
    "100% off = ₹0",
    apply_discount(500.0, 100.0) == 0.0,
    f"got {apply_discount(500.0, 100.0)}"
)

check(
    "50% off ₹1000 = ₹500",
    apply_discount(1000.0, 50.0) == 500.0,
    f"got {apply_discount(1000.0, 50.0)}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 3. INVALID INPUTS
# ═════════════════════════════════════════════════════════════════════════════
section("3. Invalid Inputs")

try:
    to_monthly(-100.0, BillingCycle.MONTHLY)
    check("Negative price rejected", False, "no exception raised")
except ValueError:
    check("Negative price rejected", True)

try:
    apply_discount(500.0, 110.0)
    check("Discount > 100% rejected", False, "no exception raised")
except ValueError:
    check("Discount > 100% rejected", True)

try:
    apply_discount(500.0, -5.0)
    check("Negative discount rejected", False, "no exception raised")
except ValueError:
    check("Negative discount rejected", True)

try:
    apply_discount(-100.0, 10.0)
    check("Negative price in discount rejected", False, "no exception raised")
except ValueError:
    check("Negative price in discount rejected", True)

# ═════════════════════════════════════════════════════════════════════════════
# 4. FIRST-YEAR COST WITH INTRO OFFERS
# ═════════════════════════════════════════════════════════════════════════════
section("4. First-Year Cost with Intro Offers")

# No intro — ongoing 10% discount
cost_ongoing, _ = compute_first_year_cost(500.0, DiscountInfo(percent=10.0))
check(
    "Ongoing 10% discount: 12 × ₹450 = ₹5400",
    cost_ongoing == 5400.0,
    f"got {cost_ongoing}"
)

# 3-month intro at 50%, then full price
cost_intro_3, expl_3 = compute_first_year_cost(
    500.0, DiscountInfo(percent=50.0, intro_months=3)
)
# 3 months × ₹250 + 9 months × ₹500 = ₹750 + ₹4500 = ₹5250
check(
    "3-month 50% intro: 3×₹250 + 9×₹500 = ₹5250",
    cost_intro_3 == 5250.0,
    f"got {cost_intro_3}, explanation: {expl_3}"
)

# 6-month intro at 20%, then custom post-intro price
cost_intro_6, expl_6 = compute_first_year_cost(
    1000.0, DiscountInfo(percent=20.0, intro_months=6, post_intro_price=900.0)
)
# 6 months × ₹800 + 6 months × ₹900 = ₹4800 + ₹5400 = ₹10200
check(
    "6-month 20% intro + ₹900 post-intro: ₹10200",
    cost_intro_6 == 10200.0,
    f"got {cost_intro_6}, explanation: {expl_6}"
)

# 12-month intro (covers full year)
cost_intro_12, _ = compute_first_year_cost(
    500.0, DiscountInfo(percent=30.0, intro_months=12)
)
# 12 × ₹350 = ₹4200
check(
    "12-month intro covers full year: 12 × ₹350 = ₹4200",
    cost_intro_12 == 4200.0,
    f"got {cost_intro_12}"
)

# Intro longer than 12 months (capped at 12 for first year)
cost_intro_24, _ = compute_first_year_cost(
    500.0, DiscountInfo(percent=40.0, intro_months=24)
)
# first-year: 12 × ₹300 = ₹3600
check(
    "24-month intro capped to 12 for first year: 12 × ₹300 = ₹3600",
    cost_intro_24 == 3600.0,
    f"got {cost_intro_24}"
)

# No discount at all
cost_none, _ = compute_first_year_cost(400.0, DiscountInfo(percent=0.0))
check(
    "No discount: 12 × ₹400 = ₹4800",
    cost_none == 4800.0,
    f"got {cost_none}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 5. RECURRING ANNUAL COST (POST-INTRO)
# ═════════════════════════════════════════════════════════════════════════════
section("5. Recurring Annual Cost (Post-Intro)")

# Ongoing discount → same as first year
rec_ongoing, _ = compute_recurring_annual_cost(500.0, DiscountInfo(percent=10.0))
check(
    "Ongoing discount recurring = first year (₹5400)",
    rec_ongoing == 5400.0,
    f"got {rec_ongoing}"
)

# After intro, revert to full price
rec_post, _ = compute_recurring_annual_cost(
    500.0, DiscountInfo(percent=50.0, intro_months=3)
)
# 12 × ₹500 = ₹6000
check(
    "Post-intro recurring at full price: 12 × ₹500 = ₹6000",
    rec_post == 6000.0,
    f"got {rec_post}"
)

# After intro, custom post-intro price
rec_custom, _ = compute_recurring_annual_cost(
    1000.0, DiscountInfo(percent=20.0, intro_months=6, post_intro_price=900.0)
)
# 12 × ₹900 = ₹10800
check(
    "Post-intro recurring at ₹900/mo: 12 × ₹900 = ₹10800",
    rec_custom == 10800.0,
    f"got {rec_custom}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 6. FEATURE COMPARISON
# ═════════════════════════════════════════════════════════════════════════════
section("6. Feature Comparison")

fc1 = compare_features(
    ["1080p", "2 screens", "Ad-free"],
    ["4K", "4 screens", "Ad-free", "Downloads"],
)
check("Shared features detected", len(fc1.shared) == 1 and fc1.shared[0].lower() == "ad-free", f"got {fc1.shared}")
check("Only-in-A features", sorted(fc1.only_in_a) == ["1080p", "2 screens"], f"got {fc1.only_in_a}")
check("Only-in-B features", len(fc1.only_in_b) == 3 and set(x.lower() for x in fc1.only_in_b) == {"4 screens", "4k", "downloads"}, f"got {fc1.only_in_b}")

fc_empty = compare_features(None, None)
check("Empty features → all empty lists", fc_empty.a_count == 0 and fc_empty.b_count == 0)

fc_one = compare_features(["Feature1"], None)
check("One-sided features", fc_one.only_in_a == ["Feature1"] and fc_one.b_count == 0)

# Duplicate handling / case insensitivity
fc_dup = compare_features(["HD", "hd", "HD"], ["HD"])
check("Deduplication and case insensitivity", len(fc_dup.shared) == 1, f"shared={fc_dup.shared}")

# ═════════════════════════════════════════════════════════════════════════════
# 7. TIE DETECTION
# ═════════════════════════════════════════════════════════════════════════════
section("7. Tie Detection")

tie_result = run_comparison(
    plan_a_name="Plan X",
    plan_a_price=499.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=0.0),
    plan_a_features=["Feature1"],
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Plan Y",
    plan_b_price=499.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=["Feature1"],
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.BEST_VALUE,
)
check(
    "Equal plans → tie recommendation",
    tie_result.recommendation == Recommendation.TIE,
    f"got {tie_result.recommendation.value}"
)
check(
    "Tie → ₹0 savings",
    tie_result.absolute_savings_first_year == 0.0,
    f"got {tie_result.absolute_savings_first_year}"
)
check(
    "Tie → cheaper_plan is 'tie'",
    tie_result.cheaper_plan_first_year == "tie",
    f"got {tie_result.cheaper_plan_first_year}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 8. SCORING AND RECOMMENDATIONS
# ═════════════════════════════════════════════════════════════════════════════
section("8. Scoring and Recommendations")

# Cheap plan A vs expensive plan B with more features
scored = run_comparison(
    plan_a_name="Basic",
    plan_a_price=199.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=0.0),
    plan_a_features=["720p", "1 screen"],
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Premium",
    plan_b_price=799.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=["4K", "4 screens", "Downloads", "HDR", "Spatial Audio"],
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.LOWEST_COST,
)
check(
    "Lowest cost priority → recommends cheaper plan",
    scored.recommendation == Recommendation.PLAN_A,
    f"got {scored.recommendation.value} "
    f"(A={scored.score_a.weighted_total}, B={scored.score_b.weighted_total})"
)
check(
    "Cost score: Plan A higher than B",
    scored.score_a.cost_score > scored.score_b.cost_score,
    f"A={scored.score_a.cost_score}, B={scored.score_b.cost_score}"
)

# Same plans with most_features priority → should recommend feature-rich plan
scored_feat = run_comparison(
    plan_a_name="Basic",
    plan_a_price=199.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=0.0),
    plan_a_features=["720p", "1 screen"],
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Premium",
    plan_b_price=799.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=["4K", "4 screens", "Downloads", "HDR", "Spatial Audio"],
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.MOST_FEATURES,
)
check(
    "Most features priority → recommends feature-rich plan",
    scored_feat.recommendation == Recommendation.PLAN_B,
    f"got {scored_feat.recommendation.value} "
    f"(A={scored_feat.score_a.weighted_total}, B={scored_feat.score_b.weighted_total})"
)

# Student budget → should pick cheaper even if less features
scored_student = run_comparison(
    plan_a_name="Student Plan",
    plan_a_price=99.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=50.0),
    plan_a_features=["720p"],
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Pro Plan",
    plan_b_price=499.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=10.0),
    plan_b_features=["4K", "Unlimited downloads", "Family sharing"],
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.STUDENT_BUDGET,
)
check(
    "Student budget → picks cheapest plan",
    scored_student.recommendation == Recommendation.PLAN_A,
    f"got {scored_student.recommendation.value}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 9. DISCOUNT EXPIRY SCENARIO
# ═════════════════════════════════════════════════════════════════════════════
section("9. Discount Expiry Scenario")

# Plan A: 50% off for 3 months, then full ₹500
# Plan B: no discount, flat ₹400
expiry = run_comparison(
    plan_a_name="Promo Plan",
    plan_a_price=500.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=50.0, intro_months=3),
    plan_a_features=None,
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Flat Plan",
    plan_b_price=400.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=None,
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.LOWEST_COST,
)
# Plan A first year: 3 × ₹250 + 9 × ₹500 = ₹5250
# Plan B first year: 12 × ₹400 = ₹4800
check(
    "Promo plan first year = ₹5250",
    expiry.cost_a.first_year_cost == 5250.0,
    f"got {expiry.cost_a.first_year_cost}"
)
check(
    "Flat plan first year = ₹4800",
    expiry.cost_b.first_year_cost == 4800.0,
    f"got {expiry.cost_b.first_year_cost}"
)
check(
    "After promo expires, flat plan is cheaper recurring",
    expiry.cost_a.recurring_annual_cost > expiry.cost_b.recurring_annual_cost,
    f"A recurring={expiry.cost_a.recurring_annual_cost}, B recurring={expiry.cost_b.recurring_annual_cost}"
)
check(
    "Recommends cheaper flat plan under lowest_cost",
    expiry.recommendation == Recommendation.PLAN_B,
    f"got {expiry.recommendation.value}"
)
check(
    "First-year savings correct",
    expiry.absolute_savings_first_year == 450.0,
    f"got {expiry.absolute_savings_first_year}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 10. MIXED BILLING CYCLES
# ═════════════════════════════════════════════════════════════════════════════
section("10. Mixed Billing Cycles")

mixed = run_comparison(
    plan_a_name="Monthly Plan",
    plan_a_price=500.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=0.0),
    plan_a_features=None,
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Annual Plan",
    plan_b_price=5400.0,
    plan_b_billing=BillingCycle.ANNUAL,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=None,
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.LOWEST_COST,
)
# Monthly: ₹500/mo → ₹6000/yr
# Annual: ₹5400/yr → ₹450/mo equivalent
check(
    "Monthly plan normalised to ₹500/mo",
    mixed.cost_a.monthly_equivalent == 500.0,
    f"got {mixed.cost_a.monthly_equivalent}"
)
check(
    "Annual plan normalised to ₹450/mo",
    mixed.cost_b.monthly_equivalent == 450.0,
    f"got {mixed.cost_b.monthly_equivalent}"
)
check(
    "Annual plan is cheaper → recommended",
    mixed.recommendation == Recommendation.PLAN_B,
    f"got {mixed.recommendation.value}"
)
check(
    "Savings = ₹600/year",
    mixed.absolute_savings_first_year == 600.0,
    f"got {mixed.absolute_savings_first_year}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 11. MISSING DATA HANDLING
# ═════════════════════════════════════════════════════════════════════════════
section("11. Missing Data Handling")

missing = run_comparison(
    plan_a_name="Plan A",
    plan_a_price=300.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=5.0),
    plan_a_features=None,
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Plan B",
    plan_b_price=400.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=None,
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.BEST_VALUE,
)
check(
    "Missing features noted",
    any("features" in m.lower() for m in missing.missing_info),
    f"missing_info={missing.missing_info}"
)
check(
    "Missing usage limits noted",
    any("usage" in m.lower() for m in missing.missing_info),
    f"missing_info={missing.missing_info}"
)
check(
    "Missing cancellation noted",
    any("cancellation" in m.lower() for m in missing.missing_info),
    f"missing_info={missing.missing_info}"
)
check(
    "Scoring notes insufficient data",
    missing.score_a is not None and not missing.score_a.sufficient_data,
    f"sufficient_data={missing.score_a.sufficient_data if missing.score_a else 'None'}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 12. VERIFICATION
# ═════════════════════════════════════════════════════════════════════════════
section("12. Verification Pass")

# Normal case should pass verification
normal = run_comparison(
    plan_a_name="Good Plan",
    plan_a_price=499.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=10.0),
    plan_a_features=["HD"],
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Better Plan",
    plan_b_price=699.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=15.0),
    plan_b_features=["4K", "Downloads"],
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.BEST_VALUE,
)
check(
    "Normal comparison passes verification",
    normal.verification_passed,
    f"notes={normal.verification_notes}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 13. CANCELLATION SCORING
# ═════════════════════════════════════════════════════════════════════════════
section("13. Cancellation Scoring")

cancel = run_comparison(
    plan_a_name="Flexible Plan",
    plan_a_price=500.0,
    plan_a_billing=BillingCycle.MONTHLY,
    plan_a_discount=DiscountInfo(percent=0.0),
    plan_a_features=["HD"],
    plan_a_usage_limits=None,
    plan_a_cancellation="Cancel anytime, no fee, full refund",
    plan_a_renewal_note=None,
    plan_b_name="Locked Plan",
    plan_b_price=450.0,
    plan_b_billing=BillingCycle.MONTHLY,
    plan_b_discount=DiscountInfo(percent=0.0),
    plan_b_features=["HD"],
    plan_b_usage_limits=None,
    plan_b_cancellation="12-month commitment, early termination fee ₹2000",
    plan_b_renewal_note=None,
    priority=UserPriority.FLEXIBLE_CANCELLATION,
)
check(
    "Flexible cancellation priority → prefers flexible plan",
    cancel.recommendation == Recommendation.PLAN_A,
    f"got {cancel.recommendation.value} "
    f"(A={cancel.score_a.weighted_total}, B={cancel.score_b.weighted_total})"
)
check(
    "Cancellation scores available",
    cancel.score_a.cancellation_score is not None,
    f"A cancel={cancel.score_a.cancellation_score}, B cancel={cancel.score_b.cancellation_score}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 14. ASSUMPTIONS TRACKING
# ═════════════════════════════════════════════════════════════════════════════
section("14. Assumptions and Transparency")

check(
    "Assumptions list is non-empty",
    len(normal.assumptions) > 0,
    f"count={len(normal.assumptions)}"
)
check(
    "INR assumption present",
    any("INR" in a for a in normal.assumptions),
    f"assumptions={normal.assumptions}"
)
check(
    "Tax disclaimer present",
    any("tax" in a.lower() for a in normal.assumptions),
)

# Mixed billing triggers assumption
check(
    "Mixed billing triggers assumption",
    any("monthly equivalents" in a.lower() for a in mixed.assumptions),
    f"assumptions={mixed.assumptions}"
)

# ═════════════════════════════════════════════════════════════════════════════
# 15. EDGE CASE: QUARTERLY vs ANNUAL COMPARISON
# ═════════════════════════════════════════════════════════════════════════════
section("15. Quarterly vs Annual Billing")

qa = run_comparison(
    plan_a_name="Quarterly Plan",
    plan_a_price=1200.0,
    plan_a_billing=BillingCycle.QUARTERLY,
    plan_a_discount=DiscountInfo(percent=10.0),
    plan_a_features=None,
    plan_a_usage_limits=None,
    plan_a_cancellation=None,
    plan_a_renewal_note=None,
    plan_b_name="Annual Plan",
    plan_b_price=4200.0,
    plan_b_billing=BillingCycle.ANNUAL,
    plan_b_discount=DiscountInfo(percent=5.0),
    plan_b_features=None,
    plan_b_usage_limits=None,
    plan_b_cancellation=None,
    plan_b_renewal_note=None,
    priority=UserPriority.LOWEST_COST,
)
# Quarterly ₹1200/3mo = ₹400/mo, 10% off = ₹360/mo → ₹4320/yr
# Annual ₹4200/12mo = ₹350/mo, 5% off = ₹332.50/mo → ₹3990/yr
check(
    "Quarterly normalised correctly",
    qa.cost_a.monthly_equivalent == 400.0,
    f"got {qa.cost_a.monthly_equivalent}"
)
check(
    "Annual normalised correctly",
    qa.cost_b.monthly_equivalent == 350.0,
    f"got {qa.cost_b.monthly_equivalent}"
)


# ═════════════════════════════════════════════════════════════════════════════
# SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
print(f"\n{'═' * 60}")
passed = sum(1 for r in results if r[0] == "PASS")
failed = sum(1 for r in results if r[0] == "FAIL")
total = len(results)
print(f"  RESULTS: {passed} passed, {failed} failed out of {total} tests")
print(f"{'═' * 60}")

if failed > 0:
    print("\nFAILED TESTS:")
    for status, name, detail in results:
        if status == "FAIL":
            print(f"  ✗ {name}" + (f"  ({detail})" if detail else ""))

sys.exit(1 if failed > 0 else 0)
