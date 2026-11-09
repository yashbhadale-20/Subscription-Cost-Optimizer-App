"""
Subscription Optimization Agent.

A small LangChain agent (qwen2.5:3b via Ollama) that *chooses and prioritises*
among candidate actions the deterministic engine built. It never does
arithmetic: its tools take subscription/action ids, read the portfolio from a
request-scoped context, and return engine figures.

``run_optimizer`` is the supervisor, mirroring the core module's
``run_supervisor`` and ``routers/compare.py``:

  1. guardrail() on the free-text goal
  2. recall() the profile + the saved portfolio
  3. deterministic candidates (optimizer_engine)
  4. policy retrieval for the action types found
  5. the agent, in the model thread pool, under an asyncio timeout
  6. tolerant JSON parsing; unknown or conflicting action ids are dropped
  7. deterministic fallback when the agent is down, slow or unusable
  8. apply_plan + verify, then citation and rupee-figure checks on the summary
  9. persistence (portfolio, profile)

Every step goes into a TraceRecorder. Traces hold outcomes only, never prompts
or model reasoning.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import threading
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from langchain.agents import create_agent
from langchain.tools import tool

from backend import db, policy_rag
from backend.comparison_engine import BillingCycle
from backend.config import settings
from backend.dependencies import _app, in_model_thread
from backend.models import (
    BudgetResponse,
    OptimizeActionResponse,
    OptimizeRequest,
    OptimizeResponse,
    PolicyEvidence,
    RenewalAlertResponse,
    SubscriptionInput,
    TraceEvent,
)
from backend.optimizer_engine import (
    Action,
    CandidateAction,
    CheaperTier,
    OptimizedPlan,
    PlanError,
    RenewalAlert,
    Subscription,
    apply_plan,
    build_candidates,
    default_plan_ids,
    fit_budget,
    known_figures,
    normalize,
    renewal_alerts,
    resolve_conflicts,
    verify,
)
from backend.tracing import TraceRecorder

logger = logging.getLogger(__name__)

DEFAULT_GOAL = "Lower my total subscription cost while keeping the services I use."
AGENT_NAME = f"optimizer_agent ({settings.llm_model})"


# ─────────────────────────────────────────────────────────────────────────────
# Request-scoped context read by the tools
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class OptimizerContext:
    subs: list[Subscription]
    candidates: list[CandidateAction]
    monthly_budget: float | None
    store: Any
    evidence: dict[int, policy_rag.Evidence] = field(default_factory=dict)
    tool_log: list[dict[str, Any]] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def log(self, name: str, args: dict[str, Any], result: str) -> None:
        with self.lock:
            self.tool_log.append({"name": name, "args": args, "result": result[:300]})

    def snapshot(self) -> tuple[list[dict[str, Any]], dict[int, policy_rag.Evidence]]:
        with self.lock:
            return list(self.tool_log), dict(self.evidence)


_CTX: ContextVar[OptimizerContext | None] = ContextVar("optimizer_context", default=None)


def _ctx() -> OptimizerContext:
    ctx = _CTX.get()
    if ctx is None:
        raise RuntimeError("No optimization is in progress.")
    return ctx


def _compact(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _ids(action_ids: Any) -> list[str]:
    if isinstance(action_ids, str):
        action_ids = [action_ids]
    return [str(a) for a in (action_ids or [])]


# ─────────────────────────────────────────────────────────────────────────────
# Tools (thin wrappers over the engine; no prices in, only ids)
# ─────────────────────────────────────────────────────────────────────────────

@tool
def list_subscriptions() -> str:
    """List the user's subscriptions: id, name, category, monthly cost, usage and must_keep."""
    ctx = _ctx()
    summary = {i.id: i for i in normalize(ctx.subs).items}
    rows = [
        {"id": s.id, "name": s.name, "category": s.category.value,
         "monthly_cost": summary[s.id].as_dict()["recurring_monthly"],
         "uses_per_month": s.uses_per_month, "last_used_days_ago": s.last_used_days_ago,
         "must_keep": s.must_keep, "shared_with": s.shared_with}
        for s in ctx.subs
    ]
    result = _compact(rows)
    ctx.log("list_subscriptions", {}, result)
    return result


@tool
def get_candidates() -> str:
    """List every action you may recommend, with its action_id and the savings the engine calculated."""
    ctx = _ctx()
    rows = [
        {"action_id": c.action_id, "action": c.action.value, "subscription_id": c.subscription_id,
         "monthly_savings": c.as_dict()["monthly_savings"], "annual_savings": c.as_dict()["annual_savings"],
         "reason": c.reason}
        for c in ctx.candidates
    ]
    result = _compact(rows)
    ctx.log("get_candidates", {}, result)
    return result


def _plan_for(ctx: OptimizerContext, ids: list[str]) -> OptimizedPlan:
    return apply_plan(ctx.subs, ids, ctx.candidates)


@tool
def estimate_plan(action_ids: list[str]) -> str:
    """Totals if the given action_ids were applied. Returns an error for unknown or conflicting ids."""
    ctx = _ctx()
    ids = _ids(action_ids)
    try:
        d = _plan_for(ctx, ids).as_dict()
        result = _compact({k: d[k] for k in (
            "current_monthly_total", "optimized_monthly_total", "monthly_savings",
            "annual_savings", "percentage_savings")})
    except PlanError as exc:
        result = f"Error: {exc}"
    ctx.log("estimate_plan", {"action_ids": ids}, result)
    return result


@tool
def check_budget(action_ids: list[str]) -> str:
    """Whether the given action_ids bring the monthly total under the user's budget, and the gap if not."""
    ctx = _ctx()
    ids = _ids(action_ids)
    if ctx.monthly_budget is None:
        result = "The user did not give a monthly budget."
    else:
        try:
            plan = _plan_for(ctx, ids)
            fit = fit_budget(ctx.subs, ctx.candidates, ctx.monthly_budget)
            gap = max(0.0, plan.optimized_monthly_total - ctx.monthly_budget)
            result = _compact({
                "budget": round(ctx.monthly_budget, 2),
                "optimized_monthly_total": round(plan.optimized_monthly_total, 2),
                "met": gap <= 0.005, "gap": round(gap, 2),
                "budget_achievable_with_candidates": fit.achievable,
            })
        except PlanError as exc:
            result = f"Error: {exc}"
    ctx.log("check_budget", {"action_ids": ids}, result)
    return result


@tool
def search_policy(query: str) -> str:
    """Search the subscription policy. Returns [Chunk N] passages you may cite."""
    ctx = _ctx()
    query = str(query)[:300]
    if ctx.store is None:
        result = "Policy search is unavailable."
    else:
        try:
            found = policy_rag.retrieve_evidence(ctx.store, query, k=3, min_relevance=settings.policy_min_relevance)
        except Exception as exc:  # the agent continues without policy
            logger.warning("search_policy failed: %r", exc)
            found = []
            result = f"Policy search failed ({type(exc).__name__})."
        else:
            with ctx.lock:
                for e in found:
                    ctx.evidence.setdefault(e.chunk, e)
            result = ("\n\n".join(f"[Chunk {e.chunk}] {e.text[:600]}" for e in found)
                      or "No relevant policy text found.")
    ctx.log("search_policy", {"query": query[:120]}, result)
    return result


TOOLS = [list_subscriptions, get_candidates, estimate_plan, check_budget, search_policy]

SYSTEM_PROMPT = (
    "You are a subscription optimization agent. You choose which actions to take on a user's "
    "subscriptions and explain why.\n"
    "Rules:\n"
    "- Call get_candidates first. Only recommend action_ids that get_candidates returned.\n"
    "- Never do arithmetic and never state a number that a tool did not return.\n"
    "- Never cancel or downgrade a subscription marked must_keep.\n"
    "- Never pick two actions for the same subscription.\n"
    "- Prefer the fewest actions that reach the user's goal or budget. Use estimate_plan or "
    "check_budget to check a plan.\n"
    "- Cite policy as [Chunk N] only from search_policy results.\n"
    "- Finish with ONLY a JSON object, no other text:\n"
    '{"chosen_action_ids": ["..."], "priority_order": ["..."], "summary": "...", '
    '"per_action_reasons": {"<action_id>": "..."}}'
)

# Same model object as the core module: qwen2.5:3b, temperature 0, its client timeout.
# Compiling the graph makes no network calls.
optimizer_agent = create_agent(model=_app.model, tools=TOOLS, system_prompt=SYSTEM_PROMPT)


# ─────────────────────────────────────────────────────────────────────────────
# Output parsing
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class AgentChoice:
    chosen_action_ids: list[str]
    priority_order: list[str]
    summary: str
    per_action_reasons: dict[str, str]


def parse_agent_output(raw: str) -> AgentChoice | None:
    """
    The first JSON object in the reply (after removing <think> blocks) that has
    a ``chosen_action_ids`` list. None when there isn't one.
    """
    text = policy_rag.strip_reasoning(raw or "")
    decoder = json.JSONDecoder()
    for match in re.finditer(r"\{", text):
        try:
            obj, _ = decoder.raw_decode(text, match.start())
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict) or not isinstance(obj.get("chosen_action_ids"), list):
            continue
        order = obj.get("priority_order")
        reasons = obj.get("per_action_reasons")
        summary = obj.get("summary")
        return AgentChoice(
            chosen_action_ids=[str(a) for a in obj["chosen_action_ids"] if isinstance(a, (str, int))],
            priority_order=[str(a) for a in order] if isinstance(order, list) else [],
            summary=summary.strip() if isinstance(summary, str) else "",
            per_action_reasons={str(k): str(v) for k, v in reasons.items()} if isinstance(reasons, dict) else {},
        )
    return None


def _id_key(action_id: str) -> str:
    return re.sub(r"[^a-z0-9]", "", action_id.lower())


def match_action_ids(
    ids: list[str], candidates: list[CandidateAction]
) -> tuple[list[str], list[str], dict[str, str]]:
    """
    Maps the agent's ids onto candidate ids. Exact matches first; otherwise a
    unique match ignoring case and punctuation ("Cancel_spotify" → "cancel:spotify").
    Returns (matched ids in order, dropped ids, {agent id: matched id} for rewrites).
    """
    exact = {c.action_id for c in candidates}
    loose: dict[str, list[str]] = {}
    for c in candidates:
        loose.setdefault(_id_key(c.action_id), []).append(c.action_id)
    matched: list[str] = []
    dropped: list[str] = []
    rewritten: dict[str, str] = {}
    for raw in ids:
        if raw in exact:
            target = raw
        elif len(loose.get(_id_key(raw), [])) == 1:
            target = loose[_id_key(raw)][0]
            rewritten[raw] = target
        else:
            dropped.append(raw)
            continue
        if target not in matched:
            matched.append(target)
    return matched, dropped, rewritten


# ─────────────────────────────────────────────────────────────────────────────
# Supervisor
# ─────────────────────────────────────────────────────────────────────────────

class GuardrailBlocked(Exception):
    def __init__(self, message: str, trace: list[dict[str, Any]]):
        super().__init__(message)
        self.message = message
        self.trace = trace


@dataclass
class OptimizeResult:
    response: OptimizeResponse
    recorder: TraceRecorder
    subscriptions: list[dict[str, Any]]


def to_engine(s: SubscriptionInput) -> Subscription:
    tier = None
    if s.cheaper_tier is not None:
        tier = CheaperTier(s.cheaper_tier.name, s.cheaper_tier.price,
                           BillingCycle(s.cheaper_tier.billing_cycle), list(s.cheaper_tier.features_lost))
    return Subscription(
        id=s.id, name=s.name, provider=s.provider, category=s.category, price=s.price,
        billing_cycle=BillingCycle(s.billing_cycle), discount_percent=s.discount_percent,
        intro_months=s.intro_months, post_intro_price=s.post_intro_price,
        uses_per_month=s.uses_per_month, last_used_days_ago=s.last_used_days_ago,
        renewal_date=s.renewal_date, cheaper_tier=tier, annual_price=s.annual_price,
        shared_with=s.shared_with, must_keep=s.must_keep,
    )


# Policy query per action type, used to ground actions even when the agent doesn't run.
POLICY_QUERIES: dict[Action, str] = {
    Action.CANCEL: "unused subscriptions and overlapping services in the same category",
    Action.SWITCH_TO_ANNUAL: "switching to annual billing upfront charge and refunds",
    Action.DOWNGRADE: "cheaper plan features and usage limits",
    Action.REVIEW_BEFORE_RENEWAL: "renewal price changes after an introductory period",
}

_ACTION_VERBS = {
    Action.CANCEL: "cancel {name}",
    Action.DOWNGRADE: "downgrade {name}",
    Action.SWITCH_TO_ANNUAL: "switch {name} to annual billing",
    Action.REVIEW_BEFORE_RENEWAL: "review {name} before it renews",
    Action.KEEP: "keep {name}",
}


def _money(v: float) -> str:
    return f"₹{round(v, 2):.2f}"


def totals_sentence(plan: OptimizedPlan) -> str:
    return (f"Engine totals: {_money(plan.current_monthly_total)} → {_money(plan.optimized_monthly_total)} a month, "
            f"saving {_money(plan.annual_savings)} a year ({plan.percentage_savings:.1f}%).")


def template_summary(
    plan: OptimizedPlan, names: dict[str, str], budget: BudgetResponse | None,
    alerts: list[RenewalAlert], evidence_by_action: dict[Action, list[int]],
) -> str:
    """A summary built only from engine figures (used for the fallback and to replace unsupported text)."""
    parts = [f"You currently spend {_money(plan.current_monthly_total)} a month "
             f"({_money(plan.current_annual_total)} a year)."]
    steps = [_ACTION_VERBS[a.action].format(name=names[a.subscription_id])
             for a in plan.actions if a.action != Action.KEEP]
    if steps:
        parts.append("Recommended: " + "; ".join(steps) + ".")
    if plan.monthly_savings > 0.005:
        parts.append(f"This brings you to {_money(plan.optimized_monthly_total)} a month and saves "
                     f"{_money(plan.annual_savings)} a year ({plan.percentage_savings:.1f}%).")
    elif not steps:
        parts.append("No change saves money with the details given.")
    if budget is not None:
        parts.append(f"Your budget of {_money(budget.target)} a month is met." if budget.met else
                     f"Your budget of {_money(budget.target)} a month is not met: "
                     f"{_money(budget.gap)} above it.")
    if alerts:
        parts.append("Check before renewal: " + ", ".join(f"{a.name} ({a.renewal_date.isoformat()})" for a in alerts) + ".")
    cites = sorted({chunks[0] for act, chunks in evidence_by_action.items()
                    if chunks and any(a.action == act for a in plan.actions)})
    if cites:
        parts.append("Policy: " + " ".join(f"[Chunk {c}]" for c in cites))
    return " ".join(parts)


def _assumptions(subs: list[Subscription], plan: OptimizedPlan) -> list[str]:
    out = ["Prices are in INR and exclude taxes and fees.",
           "Totals and savings use the price after any introductory offer ends."]
    if any(s.billing_cycle != BillingCycle.MONTHLY for s in subs):
        out.append("Non-monthly prices were converted to monthly equivalents.")
    if any(a.action == Action.SWITCH_TO_ANNUAL for a in plan.actions):
        out.append("Annual billing is charged upfront; refunds for annual plans may be limited.")
    if any(s.must_keep for s in subs):
        out.append("Subscriptions marked must-keep are never cancelled or downgraded.")
    if any(s.shared_with > 0 for s in subs):
        out.append("Shared subscriptions are not flagged as unused, since others may use them.")
    out.append("Each category keeps one service; overlap suggestions don't know which content you need.")
    return out


def _missing_info(subs: list[Subscription]) -> list[str]:
    out: list[str] = []
    no_usage = [s.name for s in subs if s.uses_per_month is None and s.last_used_days_ago is None]
    if no_usage:
        out.append(f"No usage given for {', '.join(no_usage)}, so they can't be checked for being unused.")
    for s in subs:
        if s.has_intro and s.renewal_date is None:
            out.append(f"{s.name} has an introductory offer but no renewal date, so the price change can't be dated.")
    return out


async def run_optimizer(
    user_id: str,
    request: OptimizeRequest,
    store: Any,
    *,
    today: date | None = None,
    persist: bool = True,
) -> OptimizeResult:
    """
    Builds an optimization plan for every subscription in ``request``.
    Never raises for an Ollama problem: the deterministic plan is returned with
    ``used_fallback=True``. Raises GuardrailBlocked when the goal is refused.
    """
    rec = TraceRecorder()
    today = today or date.today()
    goal = request.goal or DEFAULT_GOAL

    # ── 1. Guardrail ───────────────────────────────────────────────────────
    with rec.timed() as t:
        approved, guard_msg = _app.guardrail(goal)
    rec.add("input_guardrail", "check", "ta2_subscription_optimizer.guardrail",
            "passed" if approved else "failed", guard_msg, t["ms"])
    if not approved:
        raise GuardrailBlocked(guard_msg, rec.events)

    # ── 2. Recall profile + saved portfolio ────────────────────────────────
    with rec.timed() as t:
        profile = await asyncio.to_thread(_app.recall, user_id)
    rec.add("profile_recall", "function", "ta2_subscription_optimizer.recall", "ok",
            f"{len(profile)} saved profile key(s).", t["ms"], {"keys": sorted(profile)})
    try:
        with rec.timed() as t:
            saved = await asyncio.to_thread(db.get_portfolio, user_id)
        rec.add("portfolio_recall", "function", "db.get_portfolio", "ok",
                f"Saved portfolio: {len(saved['subscriptions'])} subscription(s)." if saved["updated_at"]
                else "No saved portfolio.", t["ms"])
    except Exception as exc:
        logger.warning("Portfolio recall failed for %s: %r", user_id, exc)
        rec.add("portfolio_recall", "function", "db.get_portfolio", "error", f"{type(exc).__name__}; continuing.")

    # ── 3. Deterministic candidates ────────────────────────────────────────
    with rec.timed() as t:
        subs = [to_engine(s) for s in request.subscriptions]
        names = {s.id: s.name for s in subs}
        summary = normalize(subs)
        candidates = build_candidates(subs, today)
        alerts = renewal_alerts(subs, today, settings.renewal_window_days)
        fit = fit_budget(subs, candidates, request.monthly_budget) if request.monthly_budget is not None else None
    counts: dict[str, int] = {}
    for c in candidates:
        counts[c.action.value] = counts.get(c.action.value, 0) + 1
    rec.add("candidate_actions", "function", "optimizer_engine.build_candidates", "ok",
            f"{len(subs)} subscription(s), {_money(summary.monthly_total)}/month; "
            f"{len(candidates)} candidate action(s); {len(alerts)} renewal alert(s)"
            + (f"; budget {'achievable' if fit.achievable else 'not achievable'}" if fit else "") + ".",
            t["ms"], {"actions": counts, "budget_achievable": fit.achievable if fit else None})

    # ── 4. Is Ollama there? ────────────────────────────────────────────────
    fallback_reason: str | None = None
    with rec.timed() as t:
        try:
            ollama_ok, ollama_msg = await asyncio.to_thread(_app.check_ollama, settings.llm_model, settings.embedding_model)
        except Exception as exc:
            ollama_ok, ollama_msg = False, type(exc).__name__
    rec.add("ollama_check", "check", "ta2_subscription_optimizer.check_ollama",
            "passed" if ollama_ok else "failed", "Ollama and models available." if ollama_ok else
            "Ollama unavailable; using the deterministic plan.", t["ms"])
    if not ollama_ok:
        fallback_reason = "Ollama is unavailable."

    # ── 5. Policy evidence for each action type present ────────────────────
    ctx = OptimizerContext(subs, candidates, request.monthly_budget, store)
    evidence_by_action: dict[Action, list[int]] = {}
    action_types = [a for a in POLICY_QUERIES if any(c.action == a for c in candidates)]
    retrieval_name = "Chroma.similarity_search_with_relevance_scores"
    if not action_types:
        rec.add("policy_retrieval", "retrieval", retrieval_name, "skipped", "No actions to ground.")
    elif store is None or not ollama_ok:
        rec.add("policy_retrieval", "retrieval", retrieval_name, "skipped",
                "Vector store unavailable." if store is None else "Embeddings need Ollama.")
    else:
        def _retrieve() -> dict[Action, list[policy_rag.Evidence]]:
            return {a: policy_rag.retrieve_evidence(store, POLICY_QUERIES[a], 3, settings.policy_min_relevance)
                    for a in action_types}
        try:
            with rec.timed() as t:
                found = await asyncio.wait_for(in_model_thread(_retrieve), timeout=settings.retrieval_timeout_seconds)
            for act, items in found.items():
                evidence_by_action[act] = [e.chunk for e in items]
                for e in items:
                    ctx.evidence.setdefault(e.chunk, e)
            rec.add("policy_retrieval", "retrieval", retrieval_name, "ok",
                    f"{len(ctx.evidence)} chunk(s) at or above relevance {settings.policy_min_relevance} "
                    f"for {len(action_types)} action type(s).", t["ms"],
                    {"chunks": {a.value: evidence_by_action[a] for a in action_types}})
        except asyncio.TimeoutError:
            rec.add("policy_retrieval", "retrieval", retrieval_name, "error",
                    f"Timed out after {settings.retrieval_timeout_seconds}s; continuing without policy evidence.")
        except Exception as exc:
            logger.warning("Policy retrieval for optimize failed: %r", exc)
            rec.add("policy_retrieval", "retrieval", retrieval_name, "error",
                    f"{type(exc).__name__}; continuing without policy evidence.")

    # ── 6. Agent ───────────────────────────────────────────────────────────
    choice: AgentChoice | None = None
    if fallback_reason is None and not candidates:
        rec.add("agent", "llm", AGENT_NAME, "skipped", "No candidate actions; nothing to choose.")
    elif fallback_reason is None:
        prompt = (
            f"User goal: {goal}\n"
            + (f"Monthly budget: ₹{request.monthly_budget:.2f}\n" if request.monthly_budget is not None else "")
            + f"The user has {len(subs)} subscription(s). Call get_candidates, choose the actions, "
              "then answer with the JSON object only."
        )

        def _run() -> str:
            token = _CTX.set(ctx)  # the tools read the portfolio from here
            try:
                result = optimizer_agent.invoke(
                    {"messages": [{"role": "user", "content": prompt}]},
                    config={"recursion_limit": settings.optimizer_recursion_limit},
                )
                return str(result["messages"][-1].content)
            finally:
                _CTX.reset(token)

        raw = ""
        with rec.timed() as t:
            try:
                raw = await asyncio.wait_for(in_model_thread(_run), timeout=settings.optimizer_timeout_seconds)
                status, detail = "ok", "Agent finished."
            except asyncio.TimeoutError:
                status, detail = "error", f"Timed out after {settings.optimizer_timeout_seconds}s."
                fallback_reason = "The agent timed out."
            except Exception as exc:
                logger.warning("Optimizer agent failed for %s: %r", user_id, exc)
                status, detail = "error", f"{type(exc).__name__}; see server logs."
                fallback_reason = f"The agent failed ({type(exc).__name__})."
        tool_log, _ = ctx.snapshot()
        rec.add("agent", "llm", AGENT_NAME, status, f"{detail} The model made {len(tool_log)} tool call(s).",
                t["ms"], {"tool_calls": len(tool_log)})
        for call in tool_log:
            rec.add("agent", "tool_call", call["name"], "ok", f"args: {call['args']}", data={"result": call["result"]})

        if fallback_reason is None:
            choice = parse_agent_output(raw)
            if choice is None:
                rec.add("output_parse", "validation", "parse_agent_output", "failed",
                        "No JSON object with chosen_action_ids in the agent's reply.")
                fallback_reason = "The agent's reply had no usable JSON plan."
            else:
                rec.add("output_parse", "validation", "parse_agent_output", "passed",
                        f"Agent chose {len(choice.chosen_action_ids)} action id(s).")

    # ── 7. Validate the agent's choice, or fall back ───────────────────────
    chosen: list[str] = []
    if choice is not None:
        matched, dropped, rewritten = match_action_ids(choice.chosen_action_ids, candidates)
        rec.add("action_id_check", "validation", "match_action_ids", "warning" if dropped else "passed",
                (f"Dropped id(s) not among the candidates: {dropped}. " if dropped else "Every id is a candidate. ")
                + (f"Normalised: {rewritten}." if rewritten else ""),
                data={"dropped": dropped, "rewritten": rewritten})
        chosen, conflicts = resolve_conflicts(candidates, matched)
        if conflicts:
            rec.add("conflict_check", "validation", "optimizer_engine.resolve_conflicts", "warning",
                    "Dropped conflicting action(s): " + ", ".join(f"{d} (conflicts with {k})" for d, k in conflicts) + ".",
                    data={"dropped": [d for d, _ in conflicts]})
        if not chosen:
            fallback_reason = "None of the agent's actions were valid."
            choice = None
        else:
            # Map priority order and reasons through the same id normalisation.
            order, _, _ = match_action_ids(choice.priority_order, candidates)
            choice.priority_order = [i for i in order if i in chosen]
            reasons: dict[str, str] = {}
            for k, v in choice.per_action_reasons.items():
                m, _, _ = match_action_ids([k], candidates)
                if m and m[0] in chosen:
                    reasons[m[0]] = v
            choice.per_action_reasons = reasons

    used_fallback = fallback_reason is not None
    if used_fallback:
        with rec.timed() as t:
            chosen = default_plan_ids(subs, candidates, request.monthly_budget)
        rec.add("fallback_plan", "function", "optimizer_engine.default_plan_ids", "ok",
                f"{fallback_reason} Using the deterministic plan "
                + ("(budget fit)" if request.monthly_budget is not None else "(unused, overlaps, annual switches)")
                + f": {len(chosen)} action(s).", t["ms"], {"reason": fallback_reason, "chosen": chosen})

    # ── 8. Apply + verify ──────────────────────────────────────────────────
    order = (choice.priority_order if choice else []) + chosen
    ordered = list(dict.fromkeys(i for i in order if i in chosen))
    with rec.timed() as t:
        plan = apply_plan(subs, ordered, candidates)
        passed, notes = verify(plan)
    rec.add("engine_verification", "validation", "optimizer_engine.verify", "passed" if passed else "failed",
            "Every total re-adds from the per-subscription figures." if passed else "; ".join(notes),
            t["ms"], {"notes": notes})

    budget: BudgetResponse | None = None
    if request.monthly_budget is not None and fit is not None:
        gap = max(0.0, plan.optimized_monthly_total - request.monthly_budget)
        budget = BudgetResponse(target=round(request.monthly_budget, 2), achievable=fit.achievable,
                                met=gap <= 0.005, gap=round(gap, 2) if gap > 0.005 else 0.0)

    # ── 9. Summary checks ──────────────────────────────────────────────────
    _, evidence_map = ctx.snapshot()
    evidence = sorted(evidence_map.values(), key=lambda e: e.chunk)
    figures = known_figures(subs, candidates, plan, alerts,
                            extra=[request.monthly_budget] if request.monthly_budget is not None else [])
    if budget is not None:
        figures.append(budget.gap)
    fallback_summary = template_summary(plan, names, budget, alerts, evidence_by_action)
    by_id = {c.action_id: c for c in candidates}
    reasons: dict[str, str] = {}
    for a in plan.actions:
        agent_reason = choice.per_action_reasons.get(a.action_id) if choice else None
        if agent_reason and not policy_rag.unmatched_amounts(agent_reason, figures):
            reasons[a.action_id] = agent_reason.strip()
        else:
            reasons[a.action_id] = by_id[a.action_id].reason

    # The figure check only proves an amount exists among the engine figures, not that it is the
    # right one (a 3B model may call one action's saving "the" saving), so the engine's totals are
    # always appended to the agent's text.
    summary_text = (f"{choice.summary} {totals_sentence(plan)}" if (choice and choice.summary)
                    else fallback_summary)
    stray = policy_rag.unmatched_amounts(summary_text, figures)
    if stray:
        rec.add("figure_check", "validation", "policy_rag.unmatched_amounts", "warning",
                f"Summary had amounts not in the engine figures ({', '.join(stray)}); replaced with a "
                "summary built from engine numbers.", data={"unmatched": stray})
        summary_text = fallback_summary
    else:
        rec.add("figure_check", "validation", "policy_rag.unmatched_amounts", "passed",
                "Every rupee amount in the summary matches an engine figure.")
    cited, unsupported = policy_rag.check_citations(summary_text + "\n" + "\n".join(reasons.values()), evidence)
    rec.add("citation_check", "validation", "policy_rag.check_citations", "warning" if unsupported else "passed",
            (f"Cites chunk(s) that were not retrieved: {unsupported}. " if unsupported else "")
            + (f"Cited retrieved chunk(s): {cited}." if cited else "No policy citations."),
            data={"cited": cited, "unsupported": unsupported})

    # ── 10. Persistence ────────────────────────────────────────────────────
    sub_dicts = [s.model_dump(mode="json") for s in request.subscriptions]
    if persist and request.save_portfolio:
        try:
            with rec.timed() as t:
                await asyncio.to_thread(db.save_portfolio, user_id, sub_dicts)
            rec.add("portfolio_save", "persistence", "db.save_portfolio", "ok",
                    f"Saved {len(sub_dicts)} subscription(s) as the portfolio.", t["ms"])
        except Exception as exc:
            logger.warning("Portfolio save failed for %s: %r", user_id, exc)
            rec.add("portfolio_save", "persistence", "db.save_portfolio", "error", f"{type(exc).__name__}; not saved.")
    if persist:
        try:
            with rec.timed() as t:
                await asyncio.to_thread(db.remember_profile, user_id, {
                    "currency": "INR",
                    "last_optimized_monthly": f"{plan.optimized_monthly_total:.2f}",
                    "last_optimization_annual_savings": f"{plan.annual_savings:.2f}",
                })
            rec.add("profile_update", "persistence", "db.remember_profile", "ok",
                    "Saved currency and the optimized totals to the profile.", t["ms"])
        except Exception as exc:
            logger.warning("Profile update failed for %s: %r", user_id, exc)
            rec.add("profile_update", "persistence", "db.remember_profile", "error", f"{type(exc).__name__}.")

    # ── Response ───────────────────────────────────────────────────────────
    chunk_set = {e.chunk for e in evidence}
    actions = []
    for a in plan.actions:
        cited_here = {int(n) for n in re.findall(r"\[\s*chunk\s*(\d+)\s*\]", reasons[a.action_id], re.I)}
        chunks = sorted(set(evidence_by_action.get(a.action, [])) | (cited_here & chunk_set))
        d = a.as_dict()
        actions.append(OptimizeActionResponse(
            action_id=a.action_id, subscription_id=a.subscription_id, name=names[a.subscription_id],
            action=a.action.value, reason=reasons[a.action_id],
            monthly_savings=d["monthly_savings"], annual_savings=d["annual_savings"], policy_chunks=chunks,
        ))
    p = plan.as_dict()
    response = OptimizeResponse(
        current_monthly_total=p["current_monthly_total"], current_annual_total=p["current_annual_total"],
        actions=actions,
        optimized_monthly_total=p["optimized_monthly_total"], optimized_annual_total=p["optimized_annual_total"],
        total_monthly_savings=p["monthly_savings"], total_annual_savings=p["annual_savings"],
        percentage_savings=p["percentage_savings"], budget=budget,
        renewal_alerts=[RenewalAlertResponse(**a.as_dict()) for a in alerts],
        ai_summary=summary_text,
        policy_evidence=[PolicyEvidence(**e.to_dict()) for e in evidence],
        cited_chunks=cited, unsupported_citations=unsupported,
        assumptions=_assumptions(subs, plan), missing_info=_missing_info(subs),
        verification_passed=passed, verification_notes=notes,
        used_fallback=used_fallback, fallback_reason=fallback_reason, guardrail_passed=True,
    )
    response.elapsed_seconds = round(rec.elapsed_seconds, 3)
    response.trace_events = [TraceEvent(**e) for e in rec.events]
    return OptimizeResult(response=response, recorder=rec, subscriptions=sub_dicts)
