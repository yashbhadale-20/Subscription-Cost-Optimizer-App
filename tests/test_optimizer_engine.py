"""Deterministic portfolio optimizer (no Ollama needed)."""
from __future__ import annotations

from datetime import date, timedelta

import pytest

from backend.comparison_engine import BillingCycle
from backend.optimizer_engine import (
    Action,
    CheaperTier,
    PlanError,
    Subscription,
    annual_switch_savings,
    apply_plan,
    build_candidates,
    default_plan_ids,
    downgrade_candidates,
    find_overlaps,
    find_unused,
    fit_budget,
    known_figures,
    normalize,
    renewal_alerts,
    resolve_conflicts,
    verify,
)

TODAY = date(2026, 11, 9)


def sub(id: str, price: float, **kw) -> Subscription:
    kw.setdefault("name", id.title())
    return Subscription(id=id, price=price, **kw)


def demo_portfolio() -> list[Subscription]:
    return [
        sub("netflix", 649, category="video_streaming", uses_per_month=20),
        sub("prime", 299, category="video_streaming", uses_per_month=3),
        sub("hotstar", 299, category="video_streaming", uses_per_month=1),
        sub("spotify", 119, category="music", uses_per_month=1, last_used_days_ago=45),
        sub("google_one", 130, category="cloud_storage", annual_price=1300),
        sub("youtube", 149, category="other", uses_per_month=25, shared_with=3,
            cheaper_tier=CheaperTier("Lite", 89, BillingCycle.MONTHLY, ["Background play"])),
    ]


def ids(candidates, action=None):
    return [c.action_id for c in candidates if action is None or c.action == action]


# ── normalize ────────────────────────────────────────────────────────────────

def test_normalize_converts_cycles_and_uses_post_intro_price():
    subs = [
        sub("a", 1200, billing_cycle="annual"),
        sub("q", 300, billing_cycle="quarterly", discount_percent=10),
        sub("i", 100, intro_months=3, discount_percent=50, post_intro_price=150),
    ]
    summary = normalize(subs).as_dict()
    by = {i["id"]: i for i in summary["items"]}
    assert by["a"]["recurring_monthly"] == 100.0 and by["a"]["recurring_annual"] == 1200.0
    assert by["q"]["recurring_monthly"] == 90.0
    assert by["i"]["current_monthly"] == 50.0 and by["i"]["recurring_monthly"] == 150.0
    assert summary["monthly_total"] == 340.0
    assert summary["annual_total"] == 4080.0


def test_annual_price_divided_by_12_is_not_rounded_before_totals():
    summary = normalize([sub("x", 1999, billing_cycle="annual")])
    assert summary.as_dict()["annual_total"] == 1999.0


# ── unused ───────────────────────────────────────────────────────────────────

def test_unused_detection_by_uses_or_staleness():
    subs = [
        sub("rare", 100, uses_per_month=2),
        sub("busy", 100, uses_per_month=3),
        sub("stale", 100, uses_per_month=10, last_used_days_ago=30),
        sub("unknown", 100),
    ]
    assert [u.subscription_id for u in find_unused(subs)] == ["rare", "stale"]


def test_unused_skips_must_keep_and_shared():
    subs = [
        sub("pinned", 100, uses_per_month=0, must_keep=True),
        sub("family", 100, uses_per_month=0, shared_with=2),
    ]
    assert find_unused(subs) == []


# ── overlaps ─────────────────────────────────────────────────────────────────

def test_overlap_keeps_best_cost_per_use():
    subs = [
        sub("netflix", 649, category="video_streaming", uses_per_month=30),   # 21.63/use
        sub("prime", 299, category="video_streaming", uses_per_month=5),      # 59.80/use
    ]
    [group] = find_overlaps(subs)
    assert group.basis == "cost_per_use"
    assert group.keep_ids == ["netflix"] and group.cancel_ids == ["prime"]


def test_overlap_keeps_cheapest_when_usage_unknown():
    subs = [
        sub("a", 500, category="music", uses_per_month=10),
        sub("b", 200, category="music"),
    ]
    [group] = find_overlaps(subs)
    assert group.basis == "cheapest" and group.keep_ids == ["b"] and group.cancel_ids == ["a"]


def test_overlap_never_keeps_an_unused_member():
    subs = [
        sub("cheap_unused", 100, category="news", uses_per_month=0),
        sub("used", 300, category="news"),
    ]
    [group] = find_overlaps(subs)
    assert group.keep_ids == ["used"]
    cands = build_candidates(subs, TODAY)
    assert ids(cands, Action.CANCEL) == ["cancel:cheap_unused"]


def test_overlap_respects_must_keep_and_ignores_other_category():
    subs = [
        sub("a", 100, category="gaming", must_keep=True),
        sub("b", 50, category="gaming"),
        sub("c", 10, category="other"),
        sub("d", 10, category="other"),
    ]
    [group] = find_overlaps(subs)
    assert group.basis == "must_keep" and group.keep_ids == ["a"] and group.cancel_ids == ["b"]


# ── annual switch / downgrade ────────────────────────────────────────────────

def test_annual_switch_math():
    [switch] = annual_switch_savings([sub("g1", 130, annual_price=1300), sub("dear", 100, annual_price=1300)])
    assert switch.subscription_id == "g1"
    assert switch.current_annual == pytest.approx(1560)
    assert switch.annual_savings == pytest.approx(260)
    cand = next(c for c in build_candidates([sub("g1", 130, annual_price=1300)], TODAY))
    assert cand.action == Action.SWITCH_TO_ANNUAL
    assert cand.as_dict()["annual_savings"] == 260.0
    assert cand.as_dict()["monthly_savings"] == 21.67


def test_annual_switch_ignores_non_monthly_billing():
    assert annual_switch_savings([sub("q", 300, billing_cycle="quarterly", annual_price=100)]) == []


def test_downgrade_lists_features_lost_and_skips_must_keep():
    tier = CheaperTier("Lite", 89, BillingCycle.MONTHLY, ["Background play"])
    [d] = downgrade_candidates([sub("yt", 149, cheaper_tier=tier), sub("yt2", 149, cheaper_tier=tier, must_keep=True)])
    assert d.subscription_id == "yt" and d.monthly_savings == pytest.approx(60)
    assert d.features_lost == ["Background play"]


# ── renewals ─────────────────────────────────────────────────────────────────

def test_intro_offer_renewal_alert_shows_price_jump():
    subs = [
        sub("promo", 200, intro_months=3, discount_percent=50, post_intro_price=250,
            renewal_date=TODAY + timedelta(days=5)),
        sub("later", 100, renewal_date=TODAY + timedelta(days=30)),
        sub("past", 100, renewal_date=TODAY - timedelta(days=1)),
        sub("yearly", 1200, billing_cycle="annual", renewal_date=TODAY + timedelta(days=14)),
    ]
    alerts = renewal_alerts(subs, TODAY)
    assert [a.subscription_id for a in alerts] == ["promo", "yearly"]
    promo = alerts[0].as_dict()
    assert promo["kind"] == "intro_ending"
    assert promo["current_monthly"] == 100.0 and promo["after_monthly"] == 250.0
    assert promo["price_jump_monthly"] == 150.0
    assert alerts[1].kind == "renewal" and alerts[1].charge_amount == pytest.approx(1200)
    review = [c for c in build_candidates(subs, TODAY) if c.action == Action.REVIEW_BEFORE_RENEWAL]
    assert [c.subscription_id for c in review] == ["promo", "yearly"]
    assert all(c.annual_savings == 0 for c in review)


# ── candidates ───────────────────────────────────────────────────────────────

def test_candidates_for_demo_portfolio():
    cands = build_candidates(demo_portfolio(), TODAY)
    assert set(ids(cands, Action.CANCEL)) == {"cancel:prime", "cancel:hotstar", "cancel:spotify"}
    assert ids(cands, Action.SWITCH_TO_ANNUAL) == ["switch_to_annual:google_one"]
    assert ids(cands, Action.DOWNGRADE) == ["downgrade:youtube"]
    assert "keep:netflix" in ids(cands, Action.KEEP)
    hotstar = next(c for c in cands if c.action_id == "cancel:hotstar")
    assert hotstar.reason_code == "unused" and hotstar.evidence["overlap_category"] == "video_streaming"
    prime = next(c for c in cands if c.action_id == "cancel:prime")
    assert prime.reason_code == "overlap"
    # Savings first, highest first; no-savings actions last.
    savings = [c.annual_savings for c in cands]
    assert savings == sorted(savings, reverse=True)


def test_must_keep_is_never_cancelled_or_downgraded():
    subs = [sub("pinned", 999, uses_per_month=0, category="news", must_keep=True,
                cheaper_tier=CheaperTier("Basic", 10)),
            sub("other", 50, category="news")]
    cands = build_candidates(subs, TODAY)
    assert not [c for c in cands if c.subscription_id == "pinned" and c.action in (Action.CANCEL, Action.DOWNGRADE)]
    assert "keep:pinned" in ids(cands)
    assert ids(cands, Action.CANCEL) == ["cancel:other"]


# ── apply_plan / conflicts ───────────────────────────────────────────────────

def test_apply_plan_totals_and_verification():
    subs = demo_portfolio()
    cands = build_candidates(subs, TODAY)
    plan = apply_plan(subs, ["cancel:spotify", "switch_to_annual:google_one", "downgrade:youtube"], cands)
    d = plan.as_dict()
    assert d["current_monthly_total"] == 1645.0
    # 119 + 21.666… + 60 saved a month
    assert d["monthly_savings"] == 200.67
    assert d["annual_savings"] == 2408.0
    assert d["optimized_annual_total"] == 1645.0 * 12 - 2408.0
    assert d["percentage_savings"] == 12.2
    passed, notes = verify(plan)
    assert passed, notes


def test_conflicting_actions_are_rejected():
    subs = [sub("yt", 149, uses_per_month=0, annual_price=1490, cheaper_tier=CheaperTier("Lite", 89))]
    cands = build_candidates(subs, TODAY)
    with pytest.raises(PlanError, match="conflict"):
        apply_plan(subs, ["cancel:yt", "downgrade:yt"], cands)
    with pytest.raises(PlanError, match="conflict"):
        apply_plan(subs, ["downgrade:yt", "switch_to_annual:yt"], cands)
    with pytest.raises(PlanError, match="Unknown"):
        apply_plan(subs, ["cancel:nope"], cands)
    kept, dropped = resolve_conflicts(cands, ["cancel:yt", "downgrade:yt", "switch_to_annual:yt"])
    assert kept == ["cancel:yt"]
    assert dropped == [("downgrade:yt", "cancel:yt"), ("switch_to_annual:yt", "cancel:yt")]


def test_verify_catches_tampered_totals():
    subs = demo_portfolio()
    plan = apply_plan(subs, ["cancel:spotify"], build_candidates(subs, TODAY))
    plan.optimized_monthly_total -= 50
    passed, notes = verify(plan)
    assert not passed and any("Optimized monthly total" in n for n in notes)


def test_verify_flags_must_keep_change():
    subs = [sub("a", 100, uses_per_month=0)]
    plan = apply_plan(subs, ["cancel:a"], build_candidates(subs, TODAY))
    plan.must_keep_ids = ["a"]
    passed, notes = verify(plan)
    assert not passed and "must-keep" in notes[0]


# ── budget ───────────────────────────────────────────────────────────────────

def test_budget_achievable_with_fewest_large_savings():
    subs = demo_portfolio()
    cands = build_candidates(subs, TODAY)
    fit = fit_budget(subs, cands, 1300)
    # 1645 - 299 (prime) = 1346 > 1300, then - 299 (hotstar) = 1047 ≤ 1300
    assert fit.achievable and fit.gap == 0
    assert len(fit.chosen_action_ids) == 2
    assert fit.optimized_monthly <= 1300


def test_budget_unachievable_reports_gap():
    subs = [sub("a", 500, uses_per_month=0), sub("b", 400, must_keep=True)]
    fit = fit_budget(subs, build_candidates(subs, TODAY), 100)
    assert not fit.achievable
    assert fit.chosen_action_ids == ["cancel:a"]
    assert fit.as_dict()["gap"] == 300.0


def test_budget_already_met_needs_no_actions():
    subs = [sub("a", 100, uses_per_month=0)]
    fit = fit_budget(subs, build_candidates(subs, TODAY), 500)
    assert fit.achievable and fit.chosen_action_ids == []


def test_default_plan_without_budget():
    subs = demo_portfolio()
    cands = build_candidates(subs, TODAY)
    assert set(default_plan_ids(subs, cands)) == {
        "cancel:prime", "cancel:hotstar", "cancel:spotify", "switch_to_annual:google_one"}


# ── edge cases ───────────────────────────────────────────────────────────────

def test_empty_portfolio():
    assert normalize([]).as_dict() == {"items": [], "monthly_total": 0.0, "annual_total": 0.0}
    assert build_candidates([], TODAY) == []
    plan = apply_plan([], [], [])
    assert plan.percentage_savings == 0.0
    assert verify(plan) == (True, [])
    assert fit_budget([], [], 0).achievable


def test_single_subscription():
    subs = [sub("only", 199, category="music", uses_per_month=8)]
    cands = build_candidates(subs, TODAY)
    assert cands == []
    plan = apply_plan(subs, [], cands)
    assert plan.as_dict()["optimized_monthly_total"] == 199.0
    assert verify(plan)[0]


def test_invalid_inputs_raise():
    with pytest.raises(ValueError):
        sub("x", -1)
    with pytest.raises(ValueError):
        sub("x", 100, discount_percent=150)


def test_known_figures_cover_engine_numbers():
    subs = demo_portfolio()
    cands = build_candidates(subs, TODAY)
    plan = apply_plan(subs, ["cancel:spotify"], cands)
    figs = known_figures(subs, cands, plan)
    assert {649.0, 119.0, 1428.0, 1300.0, 260.0, 1645.0, 19740.0} <= set(figs)
