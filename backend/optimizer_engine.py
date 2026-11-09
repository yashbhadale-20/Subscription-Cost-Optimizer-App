"""
Deterministic Subscription Portfolio Optimizer.

Looks at every subscription a user has and produces candidate actions (cancel,
downgrade, switch to annual billing, review before renewal, keep) with their
savings. Pure Python: no LLM, no I/O. The optimization agent only *chooses*
among the candidates built here; every rupee figure comes from this module.

Conventions
  * ``price`` is the charge per ``billing_cycle``. ``post_intro_price`` and
    ``cheaper_tier.price`` are per their own billing cycle too.
  * ``discount_percent`` applies for the whole year, or only during the intro
    period when ``intro_months`` is set (same rule as comparison_engine).
  * Totals and savings use the *recurring* cost: the price after any
    introductory offer ends. Renewal alerts show the jump from today's price.
  * Money is kept exact internally and rounded to 2 dp only in ``as_dict()``.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum
from typing import Any, Iterable

from backend.comparison_engine import BillingCycle, apply_discount, to_monthly


# ─────────────────────────────────────────────────────────────────────────────
# Inputs
# ─────────────────────────────────────────────────────────────────────────────

class Category(str, Enum):
    VIDEO_STREAMING = "video_streaming"
    MUSIC = "music"
    CLOUD_STORAGE = "cloud_storage"
    NEWS = "news"
    GAMING = "gaming"
    PRODUCTIVITY = "productivity"
    FITNESS = "fitness"
    EDUCATION = "education"
    OTHER = "other"


class Action(str, Enum):
    CANCEL = "cancel"
    DOWNGRADE = "downgrade"
    SWITCH_TO_ANNUAL = "switch_to_annual"
    REVIEW_BEFORE_RENEWAL = "review_before_renewal"
    KEEP = "keep"


@dataclass
class CheaperTier:
    name: str
    price: float
    billing_cycle: BillingCycle = BillingCycle.MONTHLY
    features_lost: list[str] = field(default_factory=list)


@dataclass
class Subscription:
    id: str
    name: str
    price: float
    billing_cycle: BillingCycle = BillingCycle.MONTHLY
    category: Category = Category.OTHER
    provider: str | None = None
    discount_percent: float = 0.0
    intro_months: int | None = None
    post_intro_price: float | None = None
    uses_per_month: int | None = None
    last_used_days_ago: int | None = None
    renewal_date: date | None = None
    cheaper_tier: CheaperTier | None = None
    annual_price: float | None = None
    shared_with: int = 0
    must_keep: bool = False

    def __post_init__(self) -> None:
        self.billing_cycle = BillingCycle(self.billing_cycle)
        self.category = Category(self.category)
        if isinstance(self.renewal_date, str):
            self.renewal_date = date.fromisoformat(self.renewal_date)
        if self.price < 0:
            raise ValueError(f"{self.name}: price cannot be negative.")
        apply_discount(self.price, self.discount_percent)  # validates the discount range
        if self.post_intro_price is not None and self.post_intro_price < 0:
            raise ValueError(f"{self.name}: post-intro price cannot be negative.")
        if self.annual_price is not None and self.annual_price <= 0:
            raise ValueError(f"{self.name}: annual price must be positive.")
        if self.shared_with < 0:
            raise ValueError(f"{self.name}: shared_with cannot be negative.")

    # Exact monthly figures (unrounded), used for every total.
    @property
    def base_monthly(self) -> float:
        return self.price / self.billing_cycle.months

    @property
    def current_monthly(self) -> float:
        """What a month costs today (the intro price while an offer runs)."""
        return self.base_monthly * (1 - self.discount_percent / 100)

    @property
    def recurring_monthly(self) -> float:
        """What a month costs once any introductory offer has ended."""
        if self.intro_months is not None:
            if self.post_intro_price is not None:
                return self.post_intro_price / self.billing_cycle.months
            return self.base_monthly
        return self.current_monthly

    @property
    def has_intro(self) -> bool:
        return self.intro_months is not None


def _r(value: float) -> float:
    """Round money for output (avoids -0.0)."""
    return round(value, 2) + 0.0


# ─────────────────────────────────────────────────────────────────────────────
# Normalisation
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class NormalizedSubscription:
    id: str
    name: str
    category: str
    billing_cycle: str
    listed_monthly: float          # price ÷ cycle months, before discount (display, via to_monthly)
    current_monthly: float
    recurring_monthly: float
    recurring_annual: float
    must_keep: bool

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.id, "name": self.name, "category": self.category,
            "billing_cycle": self.billing_cycle, "listed_monthly": self.listed_monthly,
            "current_monthly": _r(self.current_monthly), "recurring_monthly": _r(self.recurring_monthly),
            "recurring_annual": _r(self.recurring_annual), "must_keep": self.must_keep,
        }


@dataclass
class PortfolioSummary:
    items: list[NormalizedSubscription]
    monthly_total: float
    annual_total: float

    def as_dict(self) -> dict[str, Any]:
        return {
            "items": [i.as_dict() for i in self.items],
            "monthly_total": _r(self.monthly_total),
            "annual_total": _r(self.annual_total),
        }


def normalize(subs: list[Subscription]) -> PortfolioSummary:
    """
    Monthly equivalent and recurring annual cost for each subscription, plus totals.

    Totals use the recurring (post-intro) cost; annual = 12 × monthly.
    """
    items = [
        NormalizedSubscription(
            id=s.id, name=s.name, category=s.category.value, billing_cycle=s.billing_cycle.value,
            listed_monthly=to_monthly(s.price, s.billing_cycle),
            current_monthly=s.current_monthly,
            recurring_monthly=s.recurring_monthly,
            recurring_annual=s.recurring_monthly * 12,
            must_keep=s.must_keep,
        )
        for s in subs
    ]
    monthly = sum(i.recurring_monthly for i in items)
    return PortfolioSummary(items=items, monthly_total=monthly, annual_total=monthly * 12)


# ─────────────────────────────────────────────────────────────────────────────
# Detectors
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UnusedFinding:
    subscription_id: str
    uses_per_month: int | None
    last_used_days_ago: int | None
    monthly_cost: float


def find_unused(subs: list[Subscription], max_uses: int = 2, stale_days: int = 30) -> list[UnusedFinding]:
    """
    Cancel candidates: used at most ``max_uses`` times a month, or not used for
    ``stale_days`` days or more. Subscriptions with unknown usage are never
    flagged. ``must_keep`` subscriptions are skipped, and so are shared ones
    (``shared_with`` > 0): other people may be the ones using them.
    """
    found: list[UnusedFinding] = []
    for s in subs:
        if s.must_keep or s.shared_with > 0:
            continue
        rarely = s.uses_per_month is not None and s.uses_per_month <= max_uses
        stale = s.last_used_days_ago is not None and s.last_used_days_ago >= stale_days
        if rarely or stale:
            found.append(UnusedFinding(s.id, s.uses_per_month, s.last_used_days_ago, s.recurring_monthly))
    return found


@dataclass
class OverlapGroup:
    category: str
    subscription_ids: list[str]
    keep_ids: list[str]
    cancel_ids: list[str]
    basis: str                      # "must_keep", "cost_per_use" or "cheapest"
    cost_per_use: dict[str, float | None] = field(default_factory=dict)


def _cost_per_use(s: Subscription) -> float | None:
    if s.uses_per_month is None:
        return None
    if s.uses_per_month <= 0:
        return float("inf")
    return s.recurring_monthly / s.uses_per_month


def find_overlaps(subs: list[Subscription]) -> list[OverlapGroup]:
    """
    Groups of two or more subscriptions in the same category ("other" is too
    broad to count as overlap). Keeps the ``must_keep`` ones if any; otherwise
    the one with the lowest cost per use when usage is known for the whole
    group, else the cheapest. The rest become cancel suggestions.
    """
    by_category: dict[Category, list[Subscription]] = {}
    for s in subs:
        if s.category != Category.OTHER:
            by_category.setdefault(s.category, []).append(s)

    groups: list[OverlapGroup] = []
    for category, members in by_category.items():
        if len(members) < 2:
            continue
        cpu = {s.id: _cost_per_use(s) for s in members}
        pinned = [s for s in members if s.must_keep]
        # An unused member is never the one kept (that would cancel the whole category).
        unused_ids = {u.subscription_id for u in find_unused(members)}
        pool = [s for s in members if s.id not in unused_ids] or members
        if pinned:
            keep, basis = pinned, "must_keep"
        elif all(cpu[s.id] is not None for s in pool):
            keep, basis = [min(pool, key=lambda s: (cpu[s.id], s.recurring_monthly))], "cost_per_use"
        else:
            keep, basis = [min(pool, key=lambda s: s.recurring_monthly)], "cheapest"
        keep_ids = [s.id for s in keep]
        groups.append(OverlapGroup(
            category=category.value,
            subscription_ids=[s.id for s in members],
            keep_ids=keep_ids,
            cancel_ids=[s.id for s in members if s.id not in keep_ids and not s.must_keep],
            basis=basis,
            cost_per_use={k: (None if v is None else (None if v == float("inf") else _r(v))) for k, v in cpu.items()},
        ))
    return groups


@dataclass
class AnnualSwitch:
    subscription_id: str
    current_annual: float      # 12 × recurring monthly
    annual_price: float
    annual_savings: float


def annual_switch_savings(subs: list[Subscription]) -> list[AnnualSwitch]:
    """Monthly-billed subscriptions whose yearly price is below 12 × the recurring monthly price."""
    found: list[AnnualSwitch] = []
    for s in subs:
        if s.billing_cycle != BillingCycle.MONTHLY or s.annual_price is None:
            continue
        current = s.recurring_monthly * 12
        if s.annual_price < current:
            found.append(AnnualSwitch(s.id, current, s.annual_price, current - s.annual_price))
    return found


@dataclass
class DowngradeOption:
    subscription_id: str
    tier_name: str
    current_monthly: float
    tier_monthly: float
    monthly_savings: float
    features_lost: list[str]


def downgrade_candidates(subs: list[Subscription]) -> list[DowngradeOption]:
    """Subscriptions with a cheaper tier that really is cheaper. ``must_keep`` ones are skipped."""
    found: list[DowngradeOption] = []
    for s in subs:
        tier = s.cheaper_tier
        if tier is None or s.must_keep:
            continue
        tier_monthly = tier.price / BillingCycle(tier.billing_cycle).months
        if tier_monthly < s.recurring_monthly:
            found.append(DowngradeOption(
                s.id, tier.name, s.recurring_monthly, tier_monthly,
                s.recurring_monthly - tier_monthly, list(tier.features_lost),
            ))
    return found


@dataclass
class RenewalAlert:
    subscription_id: str
    name: str
    kind: str                   # "intro_ending" or "renewal"
    renewal_date: date
    days_until: int
    current_monthly: float
    after_monthly: float
    price_jump_monthly: float
    charge_amount: float        # what the next charge for one billing cycle will be

    def as_dict(self) -> dict[str, Any]:
        return {
            "subscription_id": self.subscription_id, "name": self.name, "kind": self.kind,
            "renewal_date": self.renewal_date.isoformat(), "days_until": self.days_until,
            "current_monthly": _r(self.current_monthly), "after_monthly": _r(self.after_monthly),
            "price_jump_monthly": _r(self.price_jump_monthly), "charge_amount": _r(self.charge_amount),
        }


def renewal_alerts(subs: list[Subscription], today: date, window_days: int = 14) -> list[RenewalAlert]:
    """
    Renewals in the next ``window_days`` days (inclusive). For a subscription
    with an intro offer, the renewal date is taken as the day the offer ends,
    and the alert shows the monthly price jump.
    """
    alerts: list[RenewalAlert] = []
    for s in subs:
        if s.renewal_date is None:
            continue
        days = (s.renewal_date - today).days
        if not 0 <= days <= window_days:
            continue
        after = s.recurring_monthly
        alerts.append(RenewalAlert(
            subscription_id=s.id, name=s.name,
            kind="intro_ending" if s.has_intro else "renewal",
            renewal_date=s.renewal_date, days_until=days,
            current_monthly=s.current_monthly, after_monthly=after,
            price_jump_monthly=max(0.0, after - s.current_monthly),
            charge_amount=after * s.billing_cycle.months,
        ))
    alerts.sort(key=lambda a: a.days_until)
    return alerts


# ─────────────────────────────────────────────────────────────────────────────
# Candidate actions
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class CandidateAction:
    action_id: str
    subscription_id: str
    action: Action
    monthly_savings: float
    annual_savings: float
    reason_code: str
    evidence: dict[str, Any] = field(default_factory=dict)
    reason: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "action_id": self.action_id, "subscription_id": self.subscription_id,
            "action": self.action.value, "monthly_savings": _r(self.monthly_savings),
            "annual_savings": _r(self.annual_savings), "reason_code": self.reason_code,
            "reason": self.reason, "evidence": self.evidence,
        }


def action_id(action: Action, subscription_id: str) -> str:
    return f"{action.value}:{subscription_id}"


def _money(v: float) -> str:
    return f"₹{_r(v):.2f}"


def build_candidates(subs: list[Subscription], today: date) -> list[CandidateAction]:
    """
    Every action the optimizer may recommend, with savings computed here.
    One cancel per subscription (an unused one that is also an overlap gets
    reason ``unused``). Ordered by annual savings, highest first; review and
    keep actions (no savings) come last.
    """
    by_id = {s.id: s for s in subs}
    out: list[CandidateAction] = []

    unused = {u.subscription_id: u for u in find_unused(subs)}
    overlaps = find_overlaps(subs)
    overlap_cancel: dict[str, OverlapGroup] = {}
    overlap_keep: dict[str, OverlapGroup] = {}
    for g in overlaps:
        for sid in g.cancel_ids:
            overlap_cancel.setdefault(sid, g)
        for sid in g.keep_ids:
            overlap_keep.setdefault(sid, g)

    for s in subs:
        if s.must_keep:
            continue
        u, g = unused.get(s.id), overlap_cancel.get(s.id)
        if u is None and g is None:
            continue
        monthly = s.recurring_monthly
        evidence: dict[str, Any] = {"recurring_monthly": _r(monthly), "recurring_annual": _r(monthly * 12)}
        if u is not None:
            evidence.update(uses_per_month=u.uses_per_month, last_used_days_ago=u.last_used_days_ago)
            usage = (f"used {u.uses_per_month} time(s) a month" if u.uses_per_month is not None
                     else f"last used {u.last_used_days_ago} days ago")
            reason, code = f"{s.name} is rarely used ({usage}); cancelling saves {_money(monthly)} a month.", "unused"
        else:
            keep_names = ", ".join(by_id[k].name for k in g.keep_ids)
            basis = {"cost_per_use": "a lower cost per use", "cheapest": "a lower price",
                     "must_keep": "been marked must-keep"}[g.basis]
            reason, code = (f"{s.name} overlaps with {keep_names} ({g.category.replace('_', ' ')}), which has "
                            f"{basis}; cancelling saves {_money(monthly)} a month."), "overlap"
        if g is not None:
            evidence.update(overlap_category=g.category, overlap_keep_ids=g.keep_ids, overlap_basis=g.basis)
        out.append(CandidateAction(action_id(Action.CANCEL, s.id), s.id, Action.CANCEL,
                                   monthly, monthly * 12, code, evidence, reason))

    for d in downgrade_candidates(subs):
        s = by_id[d.subscription_id]
        lost = f" You would lose: {', '.join(d.features_lost)}." if d.features_lost else ""
        out.append(CandidateAction(
            action_id(Action.DOWNGRADE, s.id), s.id, Action.DOWNGRADE,
            d.monthly_savings, d.monthly_savings * 12, "cheaper_tier",
            {"tier_name": d.tier_name, "current_monthly": _r(d.current_monthly),
             "tier_monthly": _r(d.tier_monthly), "features_lost": d.features_lost},
            f"Moving {s.name} to {d.tier_name} saves {_money(d.monthly_savings)} a month.{lost}",
        ))

    for a in annual_switch_savings(subs):
        s = by_id[a.subscription_id]
        out.append(CandidateAction(
            action_id(Action.SWITCH_TO_ANNUAL, s.id), s.id, Action.SWITCH_TO_ANNUAL,
            a.annual_savings / 12, a.annual_savings, "annual_cheaper",
            {"current_annual": _r(a.current_annual), "annual_price": _r(a.annual_price)},
            f"Paying {s.name} yearly ({_money(a.annual_price)}) instead of monthly "
            f"({_money(a.current_annual)} a year) saves {_money(a.annual_savings)} a year, "
            f"charged upfront.",
        ))

    no_savings: list[CandidateAction] = []
    for alert in renewal_alerts(subs, today):
        s = by_id[alert.subscription_id]
        if alert.kind == "intro_ending":
            reason = (f"{s.name}'s introductory offer ends on {alert.renewal_date.isoformat()}; the price goes "
                      f"from {_money(alert.current_monthly)} to {_money(alert.after_monthly)} a month.")
        else:
            reason = (f"{s.name} renews on {alert.renewal_date.isoformat()} with a charge of "
                      f"{_money(alert.charge_amount)}; decide before then.")
        no_savings.append(CandidateAction(
            action_id(Action.REVIEW_BEFORE_RENEWAL, s.id), s.id, Action.REVIEW_BEFORE_RENEWAL,
            0.0, 0.0, alert.kind, alert.as_dict(), reason,
        ))

    for s in subs:
        if s.must_keep:
            code, reason = "must_keep", f"{s.name} is marked must-keep."
        elif s.id in overlap_keep and s.id not in unused:
            g = overlap_keep[s.id]
            code, reason = "best_in_category", f"{s.name} is the one to keep among your {g.category.replace('_', ' ')} services."
        else:
            continue
        no_savings.append(CandidateAction(action_id(Action.KEEP, s.id), s.id, Action.KEEP, 0.0, 0.0, code,
                                          {"recurring_monthly": _r(s.recurring_monthly)}, reason))

    out.sort(key=lambda c: -c.annual_savings)
    return out + no_savings


# ─────────────────────────────────────────────────────────────────────────────
# Applying a plan
# ─────────────────────────────────────────────────────────────────────────────

# Actions that can't both apply to one subscription. "review_before_renewal" only
# conflicts with cancel; switch_to_annual prices the current tier, so it can't
# be combined with a downgrade.
_CONFLICTS: dict[Action, set[Action]] = {
    Action.CANCEL: {Action.DOWNGRADE, Action.SWITCH_TO_ANNUAL, Action.KEEP, Action.REVIEW_BEFORE_RENEWAL},
    Action.DOWNGRADE: {Action.CANCEL, Action.SWITCH_TO_ANNUAL, Action.KEEP},
    Action.SWITCH_TO_ANNUAL: {Action.CANCEL, Action.DOWNGRADE, Action.KEEP},
    Action.KEEP: {Action.CANCEL, Action.DOWNGRADE, Action.SWITCH_TO_ANNUAL},
    Action.REVIEW_BEFORE_RENEWAL: {Action.CANCEL},
}


class PlanError(ValueError):
    """An unknown action id or two conflicting actions on the same subscription."""


def conflicts(a: CandidateAction, b: CandidateAction) -> bool:
    return a.subscription_id == b.subscription_id and b.action in _CONFLICTS[a.action]


def resolve_conflicts(
    candidates: list[CandidateAction], chosen_ids: Iterable[str]
) -> tuple[list[str], list[tuple[str, str]]]:
    """
    Keeps the chosen ids in order, dropping any that conflicts with one kept
    earlier. Returns (kept ids, [(dropped id, the kept id it conflicts with)]).
    Unknown ids are not handled here; filter them first.
    """
    by_id = {c.action_id: c for c in candidates}
    kept: list[str] = []
    dropped: list[tuple[str, str]] = []
    for cid in chosen_ids:
        if cid in kept:
            continue
        clash = next((k for k in kept if conflicts(by_id[k], by_id[cid])), None)
        if clash is None:
            kept.append(cid)
        else:
            dropped.append((cid, clash))
    return kept, dropped


@dataclass
class SubscriptionOutcome:
    subscription_id: str
    name: str
    action: str
    current_monthly: float
    optimized_monthly: float

    def as_dict(self) -> dict[str, Any]:
        return {
            "subscription_id": self.subscription_id, "name": self.name, "action": self.action,
            "current_monthly": _r(self.current_monthly), "optimized_monthly": _r(self.optimized_monthly),
            "current_annual": _r(self.current_monthly * 12), "optimized_annual": _r(self.optimized_monthly * 12),
        }


@dataclass
class OptimizedPlan:
    actions: list[CandidateAction]
    outcomes: list[SubscriptionOutcome]
    current_monthly_total: float
    optimized_monthly_total: float
    must_keep_ids: list[str] = field(default_factory=list)

    @property
    def current_annual_total(self) -> float:
        return self.current_monthly_total * 12

    @property
    def optimized_annual_total(self) -> float:
        return self.optimized_monthly_total * 12

    @property
    def monthly_savings(self) -> float:
        return self.current_monthly_total - self.optimized_monthly_total

    @property
    def annual_savings(self) -> float:
        return self.monthly_savings * 12

    @property
    def percentage_savings(self) -> float:
        if self.current_monthly_total <= 0:
            return 0.0
        return round(self.monthly_savings / self.current_monthly_total * 100, 1) + 0.0

    def as_dict(self) -> dict[str, Any]:
        return {
            "actions": [a.as_dict() for a in self.actions],
            "outcomes": [o.as_dict() for o in self.outcomes],
            "current_monthly_total": _r(self.current_monthly_total),
            "current_annual_total": _r(self.current_annual_total),
            "optimized_monthly_total": _r(self.optimized_monthly_total),
            "optimized_annual_total": _r(self.optimized_annual_total),
            "monthly_savings": _r(self.monthly_savings),
            "annual_savings": _r(self.annual_savings),
            "percentage_savings": self.percentage_savings,
        }


def apply_plan(
    subs: list[Subscription],
    chosen_action_ids: Iterable[str],
    candidates: list[CandidateAction] | None = None,
    today: date | None = None,
) -> OptimizedPlan:
    """
    Totals after applying the chosen actions. ``candidates`` defaults to
    ``build_candidates(subs, today or date.today())``.

    Raises PlanError for an unknown action id or two conflicting actions on the
    same subscription (e.g. cancel + downgrade).
    """
    if candidates is None:
        candidates = build_candidates(subs, today or date.today())
    by_id = {c.action_id: c for c in candidates}
    chosen: list[CandidateAction] = []
    for cid in dict.fromkeys(chosen_action_ids):  # de-duplicate, keep order
        if cid not in by_id:
            raise PlanError(f"Unknown action id: {cid}")
        candidate = by_id[cid]
        for other in chosen:
            if conflicts(other, candidate):
                raise PlanError(f"{other.action_id} and {cid} conflict on subscription {candidate.subscription_id}")
        chosen.append(candidate)

    effect = {c.subscription_id: c for c in chosen if c.action in (Action.CANCEL, Action.DOWNGRADE, Action.SWITCH_TO_ANNUAL)}
    outcomes: list[SubscriptionOutcome] = []
    for s in subs:
        c = effect.get(s.id)
        optimized = s.recurring_monthly - (c.monthly_savings if c else 0.0)
        outcomes.append(SubscriptionOutcome(s.id, s.name, c.action.value if c else "keep",
                                            s.recurring_monthly, optimized))
    return OptimizedPlan(
        actions=chosen,
        outcomes=outcomes,
        current_monthly_total=sum(o.current_monthly for o in outcomes),
        optimized_monthly_total=sum(o.optimized_monthly for o in outcomes),
        must_keep_ids=[s.id for s in subs if s.must_keep],
    )


# ─────────────────────────────────────────────────────────────────────────────
# Budget
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class BudgetFit:
    target: float
    achievable: bool
    chosen_action_ids: list[str]
    optimized_monthly: float
    gap: float                       # how far above the target, 0 when met

    def as_dict(self) -> dict[str, Any]:
        return {"target": _r(self.target), "achievable": self.achievable,
                "chosen_action_ids": self.chosen_action_ids,
                "optimized_monthly": _r(self.optimized_monthly), "gap": _r(self.gap)}


def budget_gap(monthly_total: float, monthly_budget: float) -> float:
    return max(0.0, monthly_total - monthly_budget)


def fit_budget(subs: list[Subscription], candidates: list[CandidateAction], monthly_budget: float) -> BudgetFit:
    """
    Greedy: take candidates in order of annual savings (highest first),
    skipping ones that save nothing or conflict with one already taken, until
    the monthly total is at or under ``monthly_budget``. ``achievable`` is
    False when even every compatible saving leaves it above the budget.
    """
    if monthly_budget < 0:
        raise ValueError("Budget cannot be negative.")
    total = sum(s.recurring_monthly for s in subs)
    chosen: list[CandidateAction] = []
    tolerance = 0.005  # half a paisa: a total that rounds to the budget meets it
    for c in sorted(candidates, key=lambda c: -c.annual_savings):
        if total <= monthly_budget + tolerance:
            break
        if c.monthly_savings <= 0 or any(conflicts(k, c) for k in chosen):
            continue
        chosen.append(c)
        total -= c.monthly_savings
    achievable = total <= monthly_budget + tolerance
    return BudgetFit(monthly_budget, achievable, [c.action_id for c in chosen], total,
                     0.0 if achievable else budget_gap(total, monthly_budget))


# ─────────────────────────────────────────────────────────────────────────────
# Default plan and verification
# ─────────────────────────────────────────────────────────────────────────────

def default_plan_ids(subs: list[Subscription], candidates: list[CandidateAction],
                     monthly_budget: float | None = None) -> list[str]:
    """
    The deterministic plan used when the agent gives nothing usable: the budget
    fit when there is a budget, else every cancel (unused or overlap) and every
    annual switch that saves money, without conflicts.
    """
    if monthly_budget is not None:
        return fit_budget(subs, candidates, monthly_budget).chosen_action_ids
    wanted = [c.action_id for c in candidates
              if c.annual_savings > 0 and c.action in (Action.CANCEL, Action.SWITCH_TO_ANNUAL)]
    return resolve_conflicts(candidates, wanted)[0]


_TOLERANCE = 0.005


def verify(plan: OptimizedPlan) -> tuple[bool, list[str]]:
    """
    Re-adds every figure. Returns (passed, notes), like
    comparison_engine._verify_consistency.
    """
    notes: list[str] = []
    cur = sum(o.current_monthly for o in plan.outcomes)
    opt = sum(o.optimized_monthly for o in plan.outcomes)
    if abs(cur - plan.current_monthly_total) > _TOLERANCE:
        notes.append(f"Current monthly total {_money(plan.current_monthly_total)} != sum of subscriptions {_money(cur)}")
    if abs(opt - plan.optimized_monthly_total) > _TOLERANCE:
        notes.append(f"Optimized monthly total {_money(plan.optimized_monthly_total)} != sum of subscriptions {_money(opt)}")
    action_monthly = sum(a.monthly_savings for a in plan.actions)
    if abs(action_monthly - plan.monthly_savings) > _TOLERANCE:
        notes.append(f"Monthly savings {_money(plan.monthly_savings)} != sum of action savings {_money(action_monthly)}")
    action_annual = sum(a.annual_savings for a in plan.actions)
    if abs(action_annual - plan.annual_savings) > _TOLERANCE * 12:
        notes.append(f"Annual savings {_money(plan.annual_savings)} != sum of action savings {_money(action_annual)}")
    # Rounded figures shown to the user must add up too (each rounding is at most half a paisa).
    shown = sum(_r(a.annual_savings) for a in plan.actions)
    if abs(shown - _r(plan.annual_savings)) > 0.005 * (len(plan.actions) + 1) + 1e-9:
        notes.append(f"Displayed action savings {_money(shown)} don't add up to {_money(plan.annual_savings)}")
    for o in plan.outcomes:
        if o.optimized_monthly < -_TOLERANCE:
            notes.append(f"{o.name}: optimized cost is negative ({_money(o.optimized_monthly)})")
        if o.optimized_monthly > o.current_monthly + _TOLERANCE:
            notes.append(f"{o.name}: optimized cost {_money(o.optimized_monthly)} exceeds current {_money(o.current_monthly)}")
    for a in plan.actions:
        if a.subscription_id in plan.must_keep_ids and a.action in (Action.CANCEL, Action.DOWNGRADE):
            notes.append(f"{a.action_id} changes a must-keep subscription")
        if a.monthly_savings < -_TOLERANCE:
            notes.append(f"{a.action_id} has negative savings")
    for i, a in enumerate(plan.actions):
        for b in plan.actions[i + 1:]:
            if conflicts(a, b):
                notes.append(f"{a.action_id} conflicts with {b.action_id}")
    return not notes, notes


def known_figures(subs: list[Subscription], candidates: list[CandidateAction],
                  plan: OptimizedPlan | None = None, alerts: list[RenewalAlert] | None = None,
                  extra: Iterable[float] = ()) -> list[float]:
    """Every rupee amount the engine produced or the user entered, for the figure check (rounded)."""
    figs: list[float] = list(extra)
    for s in subs:
        figs += [s.price, s.base_monthly, s.current_monthly, s.recurring_monthly, s.recurring_monthly * 12]
        if s.post_intro_price is not None:
            figs.append(s.post_intro_price)
        if s.annual_price is not None:
            figs.append(s.annual_price)
        if s.cheaper_tier is not None:
            figs.append(s.cheaper_tier.price)
    for c in candidates:
        figs += [c.monthly_savings, c.annual_savings]
        figs += [v for v in c.evidence.values() if isinstance(v, (int, float)) and not isinstance(v, bool)]
    if plan is not None:
        figs += [plan.current_monthly_total, plan.current_annual_total, plan.optimized_monthly_total,
                 plan.optimized_annual_total, plan.monthly_savings, plan.annual_savings]
    for a in alerts or []:
        figs += [a.current_monthly, a.after_monthly, a.price_jump_monthly, a.charge_amount]
    return [_r(f) for f in figs if f is not None]
