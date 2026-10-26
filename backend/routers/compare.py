"""
POST /compare

Enhanced AI-powered two-subscription comparison endpoint.

Flow:
  1. Input guardrail (keyword blocklist) — checked first, 422 if blocked
  2. Deterministic cost calculations via comparison_engine (no LLM)
  3. Priority-based scoring and recommendation
  4. RAG policy retrieval from ChromaDB
  5. LLM-generated explanation grounded in policy + calculations
  6. Verification pass, citation check, figure check
  7. Persist user profile to SQLite
  8. Save the comparison and its structured trace (GET /history/{id})

The LLM never invents prices, policies, or features.
Unknown data remains unknown.

Requires Ollama to be online — returns HTTP 503 otherwise.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import asdict
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from backend import db, policy_rag
from backend.config import settings
from backend.dependencies import current_user, ensure_same_user, get_store, require_ollama
from backend.models import (
    CompareRequest,
    CompareResponse,
    CostBreakdownResponse,
    FeatureComparisonResponse,
    ScoreBreakdownResponse,
    TraceEvent,
)
from backend.comparison_engine import (
    BillingCycle,
    ComparisonResult,
    DiscountInfo,
    UserPriority,
    run_comparison,
)

from backend.tracing import TraceRecorder, model_tool_calls

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/compare", tags=["Compare"])


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _to_billing_cycle(val: str) -> BillingCycle:
    """Convert request string to BillingCycle enum."""
    return BillingCycle(val.lower())


def _to_discount_info(plan) -> DiscountInfo:
    """Extract discount info from a PlanInput, preferring the detailed field."""
    if plan.discount is not None:
        return DiscountInfo(
            percent=plan.discount.percent,
            intro_months=plan.discount.intro_months,
            post_intro_price=plan.discount.post_intro_price,
        )
    return DiscountInfo(percent=plan.discount_percent)


def _cost_to_response(cost) -> CostBreakdownResponse:
    """Convert a CostBreakdown dataclass to a Pydantic response model."""
    return CostBreakdownResponse(
        plan_name=cost.plan_name,
        listed_price=cost.listed_price,
        billing_cycle=cost.billing_cycle,
        monthly_equivalent=cost.monthly_equivalent,
        discount_percent=cost.discount_percent,
        discounted_monthly=cost.discounted_monthly,
        first_year_cost=cost.first_year_cost,
        first_year_explanation=cost.first_year_explanation,
        recurring_annual_cost=cost.recurring_annual_cost,
        recurring_annual_explanation=cost.recurring_annual_explanation,
        has_intro_offer=cost.has_intro_offer,
        intro_months=cost.intro_months,
        post_intro_monthly=cost.post_intro_monthly,
        renewal_cost_note=cost.renewal_cost_note,
    )


def _feat_to_response(feat) -> FeatureComparisonResponse:
    return FeatureComparisonResponse(
        only_in_a=feat.only_in_a,
        only_in_b=feat.only_in_b,
        shared=feat.shared,
        a_count=feat.a_count,
        b_count=feat.b_count,
    )


def _score_to_response(score) -> ScoreBreakdownResponse | None:
    if score is None:
        return None
    return ScoreBreakdownResponse(
        cost_score=score.cost_score,
        features_score=score.features_score,
        usage_score=score.usage_score,
        cancellation_score=score.cancellation_score,
        weighted_total=score.weighted_total,
        weights_used=score.weights_used,
        sufficient_data=score.sufficient_data,
    )


def _build_calc_summary(result: ComparisonResult) -> str:
    """Build a human-readable calculation summary for the LLM prompt."""
    ca = result.cost_a
    cb = result.cost_b
    lines = [
        f"=== {ca.plan_name} ===",
        f"Listed price: ₹{ca.listed_price:.2f}/{ca.billing_cycle}",
        f"Monthly equivalent: ₹{ca.monthly_equivalent:.2f}",
        f"After {ca.discount_percent:.1f}% discount: ₹{ca.discounted_monthly:.2f}/month",
        f"First-year cost: {ca.first_year_explanation}",
        f"Recurring annual cost: {ca.recurring_annual_explanation}",
        "",
        f"=== {cb.plan_name} ===",
        f"Listed price: ₹{cb.listed_price:.2f}/{cb.billing_cycle}",
        f"Monthly equivalent: ₹{cb.monthly_equivalent:.2f}",
        f"After {cb.discount_percent:.1f}% discount: ₹{cb.discounted_monthly:.2f}/month",
        f"First-year cost: {cb.first_year_explanation}",
        f"Recurring annual cost: {cb.recurring_annual_explanation}",
        "",
        f"Cheaper first year: {result.cheaper_plan_first_year} "
        f"(saves ₹{result.absolute_savings_first_year:.2f}, {result.percentage_savings_first_year:.1f}%)",
        f"Cheaper recurring: {result.cheaper_plan_recurring} "
        f"(saves ₹{result.absolute_savings_recurring:.2f}, {result.percentage_savings_recurring:.1f}%)",
    ]

    # Features
    fc = result.feature_comparison
    if fc.shared or fc.only_in_a or fc.only_in_b:
        lines.append("")
        lines.append("=== Features ===")
        if fc.shared:
            lines.append(f"Shared: {', '.join(fc.shared)}")
        if fc.only_in_a:
            lines.append(f"Only in {ca.plan_name}: {', '.join(fc.only_in_a)}")
        if fc.only_in_b:
            lines.append(f"Only in {cb.plan_name}: {', '.join(fc.only_in_b)}")

    # Scores
    if result.score_a and result.score_b:
        lines.append("")
        lines.append("=== Scores ===")
        lines.append(f"{ca.plan_name}: {result.score_a.weighted_total}/100")
        lines.append(f"{cb.plan_name}: {result.score_b.weighted_total}/100")

    lines.append("")
    lines.append(f"Recommendation: {result.recommendation.value} — {result.recommendation_reason}")

    return "\n".join(lines)


def _known_figures(result: ComparisonResult) -> list[float]:
    """Every rupee amount the deterministic engine produced, for the figure check."""
    figures: list[float] = [result.absolute_savings_first_year, result.absolute_savings_recurring]
    for cost in (result.cost_a, result.cost_b):
        figures += [
            cost.listed_price, cost.monthly_equivalent, cost.discounted_monthly,
            cost.first_year_cost, cost.recurring_annual_cost,
        ]
        if cost.post_intro_monthly is not None:
            figures.append(cost.post_intro_monthly)
    # Differences between the two plans' monthly figures are also legitimate amounts to quote.
    figures.append(abs(result.cost_a.discounted_monthly - result.cost_b.discounted_monthly))
    figures.append(abs(result.cost_a.monthly_equivalent - result.cost_b.monthly_equivalent))
    return figures


def _save_trace_safe(trace_name: str, trace: list[str], answer: str) -> None:
    """Write trace file; silently ignore errors (non-critical)."""
    try:
        _app.save_trace(
            trace_name,
            "\n".join(trace) + "\n\nRESULT\n" + answer,
        )
    except Exception as exc:
        logger.warning("Could not save trace %s: %s", trace_name, exc)


# ─────────────────────────────────────────────────────────────────────────────
# Endpoint
# ─────────────────────────────────────────────────────────────────────────────

@router.post(
    "",
    response_model=CompareResponse,
    summary="Compare two subscription plans with AI-powered analysis",
    dependencies=[Depends(require_ollama)],
)
async def compare(
    req: CompareRequest,
    store: Any = Depends(get_store),
    user_id: str = Depends(current_user),
) -> CompareResponse:
    """
    Full comparison workflow:

    1. Guardrail check (pure Python)
    2. Deterministic cost calculations, feature comparison, scoring
    3. RAG policy retrieval from ChromaDB (only chunks above the relevance threshold)
    4. LLM explanation grounded in calculated facts + policy
    5. Validation of the engine's figures and of the explanation's citations and amounts
    6. Profile update, then the comparison is saved with its trace

    The LLM never invents prices, policies, or features.
    Requires a session token whose user matches ``user_id``.
    Returns HTTP 503 if Ollama is unavailable.
    """
    ensure_same_user(user_id, req.user_id)
    rec = TraceRecorder()

    # The user's saved default priority applies when the request doesn't name one.
    priority = req.priority
    if "priority" not in req.model_fields_set:
        priority = db.get_preferences(user_id)["default_priority"] or priority

    # ── 1. Pre-flight: guardrail (pure Python, no LLM) ─────────────────────
    with rec.timed() as t:
        approved, guard_msg = _app.guardrail(req.question)
    rec.add("input_guardrail", "check", "ta2_subscription_optimizer.guardrail",
            "passed" if approved else "failed", guard_msg, t["ms"])
    if not approved:
        raise HTTPException(
            status_code=422,
            detail={
                "error": "Input blocked by guardrail",
                "message": guard_msg,
            },
        )

    # ── 2. Deterministic comparison (pure Python) ──────────────────────────
    try:
        with rec.timed() as t:
            comparison = run_comparison(
                plan_a_name=req.plan_a.name,
                plan_a_price=req.plan_a.monthly_price,
                plan_a_billing=_to_billing_cycle(req.plan_a.billing_cycle),
                plan_a_discount=_to_discount_info(req.plan_a),
                plan_a_features=req.plan_a.features,
                plan_a_usage_limits=req.plan_a.usage_limits,
                plan_a_cancellation=req.plan_a.cancellation_terms,
                plan_a_renewal_note=req.plan_a.renewal_note,
                plan_b_name=req.plan_b.name,
                plan_b_price=req.plan_b.monthly_price,
                plan_b_billing=_to_billing_cycle(req.plan_b.billing_cycle),
                plan_b_discount=_to_discount_info(req.plan_b),
                plan_b_features=req.plan_b.features,
                plan_b_usage_limits=req.plan_b.usage_limits,
                plan_b_cancellation=req.plan_b.cancellation_terms,
                plan_b_renewal_note=req.plan_b.renewal_note,
                priority=UserPriority(priority),
            )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail={"error": "Invalid plan data", "message": str(exc)})

    rec.add(
        "cost_calculation", "function", "comparison_engine.run_comparison", "ok",
        f"{comparison.cost_a.plan_name} ₹{comparison.cost_a.first_year_cost:.2f}/yr vs "
        f"{comparison.cost_b.plan_name} ₹{comparison.cost_b.first_year_cost:.2f}/yr; "
        f"recommendation: {comparison.recommendation.value}",
        t["ms"],
        {
            "recommendation": comparison.recommendation.value,
            "priority": priority,
            "score_a": comparison.score_a.weighted_total if comparison.score_a else None,
            "score_b": comparison.score_b.weighted_total if comparison.score_b else None,
            "missing_info": comparison.missing_info,
        },
    )
    rec.add(
        "engine_verification", "validation", "comparison_engine verification",
        "passed" if comparison.verification_passed else "failed",
        "Recalculated totals and the recommendation are consistent."
        if comparison.verification_passed else "; ".join(comparison.verification_notes),
        data={"notes": comparison.verification_notes},
    )

    # ── 3. RAG policy retrieval ────────────────────────────────────────────
    retrieval_name = "Chroma.similarity_search_with_relevance_scores"
    evidence: list[policy_rag.Evidence] = []
    if store is None:
        rec.add("policy_retrieval", "retrieval", retrieval_name, "skipped", "Vector store unavailable.")
    else:
        try:
            with rec.timed() as t:
                evidence = await asyncio.to_thread(policy_rag.retrieve_evidence, store, req.question, 3)
            rec.add(
                "policy_retrieval", "retrieval", retrieval_name, "ok",
                f"{len(evidence)} chunk(s) at or above relevance {settings.policy_min_relevance}"
                + (f": {', '.join(str(e.chunk) for e in evidence)}" if evidence else ""),
                t["ms"],
                {"chunks": [e.chunk for e in evidence], "relevance": [e.relevance for e in evidence]},
            )
        except Exception as exc:
            rec.add("policy_retrieval", "retrieval", retrieval_name, "error", f"{type(exc).__name__}: {exc}")
    policy_context = policy_rag.format_for_prompt(evidence)

    # ── 4. LLM explanation grounded in facts ───────────────────────────────
    calc_summary = _build_calc_summary(comparison)
    explain_prompt = (
        f"You are a subscription cost advisor. The user asked: {req.question}\n\n"
        f"CALCULATED FACTS (verified, do not change these numbers):\n{calc_summary}\n\n"
        f"POLICY CONTEXT (use only claims supported by this text):\n"
        f"{policy_context or 'No relevant policy text found.'}\n\n"
        f"MISSING INFORMATION:\n"
        f"{chr(10).join(comparison.missing_info) if comparison.missing_info else 'None'}\n\n"
        f"ASSUMPTIONS:\n{chr(10).join(comparison.assumptions)}\n\n"
        "Write a clear explanation of the comparison results. Rules:\n"
        "- Use ONLY the calculated numbers above. Do NOT invent prices.\n"
        "- If information is missing, explicitly say it is unknown.\n"
        "- Mention the recommendation and why.\n"
        "- When you use a policy point, cite it as [Chunk N] with a chunk number from the policy context.\n"
        "- If there is no policy context, do not cite anything.\n"
        "- Mention trade-offs between the plans.\n"
        "- Keep it concise (under 300 words).\n"
        "- Do NOT recommend a more expensive plan unless its benefits justify the cost."
    )

    ai_explanation = ""
    explanation_ok = False
    agent_name = f"review_agent ({settings.llm_model})"
    try:
        def _explain() -> tuple[str, list[dict[str, Any]]]:
            result = _app.review_agent.invoke(
                {"messages": [{"role": "user", "content": explain_prompt}]}
            )
            return str(result["messages"][-1].content), model_tool_calls(result["messages"])

        with rec.timed() as t:
            raw, tool_calls = await asyncio.wait_for(
                asyncio.to_thread(_explain),
                timeout=settings.llm_timeout_seconds,
            )
        ai_explanation = policy_rag.strip_reasoning(raw)
        explanation_ok = True
        rec.add(
            "ai_explanation", "llm", agent_name, "ok",
            "Explanation generated. "
            + (f"The model made {len(tool_calls)} tool call(s)." if tool_calls else "The model made no tool calls."),
            t["ms"], {"tool_calls": len(tool_calls)},
        )
        for call in tool_calls:
            rec.add("ai_explanation", "tool_call", str(call["name"]), "ok", f"args: {call['args']}",
                    data={"result": call["result"]})
    except asyncio.TimeoutError:
        ai_explanation = "(Explanation generation timed out. See calculated results above.)"
        rec.add("ai_explanation", "llm", agent_name, "error", f"Timed out after {settings.llm_timeout_seconds}s.")
    except Exception as exc:
        ai_explanation = f"(Could not generate explanation: {exc})"
        rec.add("ai_explanation", "llm", agent_name, "error", f"{type(exc).__name__}: {exc}")

    # ── 5. Validate the explanation ────────────────────────────────────────
    cited: list[int] = []
    unsupported: list[int] = []
    if explanation_ok:
        cited, unsupported = policy_rag.check_citations(ai_explanation, evidence)
        rec.add(
            "citation_check", "validation", "policy_rag.check_citations",
            "warning" if unsupported else "passed",
            (f"Cites chunk(s) that were not retrieved: {unsupported}. " if unsupported else "")
            + (f"Cited retrieved chunk(s): {cited}." if cited else "No policy citations."),
            data={"cited": cited, "unsupported": unsupported},
        )
        stray = policy_rag.unmatched_amounts(ai_explanation, _known_figures(comparison))
        rec.add(
            "figure_check", "validation", "policy_rag.unmatched_amounts",
            "warning" if stray else "passed",
            f"Amounts not found in the calculated figures: {', '.join(stray)}."
            if stray else "Every rupee amount in the explanation matches a calculated figure.",
            data={"unmatched": stray},
        )
    else:
        rec.add("citation_check", "validation", "policy_rag.check_citations", "skipped",
                "No explanation to check.")

    # ── 6. Persist user profile to SQLite ──────────────────────────────────
    try:
        with rec.timed() as t:
            _app.remember(user_id, "profile", "plan_a_name", req.plan_a.name)
            _app.remember(user_id, "profile", "plan_b_name", req.plan_b.name)
            _app.remember(user_id, "profile", "last_plan_a_monthly", f"{comparison.cost_a.monthly_equivalent:.2f}")
            _app.remember(user_id, "profile", "last_plan_b_monthly", f"{comparison.cost_b.monthly_equivalent:.2f}")
            _app.remember(user_id, "profile", "last_plan_a_discount", f"{comparison.cost_a.discount_percent:.2f}")
            _app.remember(user_id, "profile", "last_plan_b_discount", f"{comparison.cost_b.discount_percent:.2f}")
            _app.remember(user_id, "profile", "currency", "INR")
            _app.remember(user_id, "profile", "last_priority", priority)
        rec.add("profile_update", "persistence", "ta2_subscription_optimizer.remember", "ok",
                "Saved plan names, monthly equivalents, discounts and priority to the profile.", t["ms"])
    except Exception as exc:
        rec.add("profile_update", "persistence", "ta2_subscription_optimizer.remember", "error", str(exc))
        logger.warning("Memory persist warning for user %s: %s", user_id, exc)

    # ── 7. Build the response, then save it with its trace ─────────────────
    response = CompareResponse(
        recommendation=comparison.recommendation.value,
        recommendation_reason=comparison.recommendation_reason,
        priority_used=priority,
        cost_a=_cost_to_response(comparison.cost_a),
        cost_b=_cost_to_response(comparison.cost_b),
        absolute_savings_first_year=comparison.absolute_savings_first_year,
        percentage_savings_first_year=comparison.percentage_savings_first_year,
        absolute_savings_recurring=comparison.absolute_savings_recurring,
        percentage_savings_recurring=comparison.percentage_savings_recurring,
        cheaper_plan_first_year=comparison.cheaper_plan_first_year,
        cheaper_plan_recurring=comparison.cheaper_plan_recurring,
        feature_comparison=_feat_to_response(comparison.feature_comparison),
        score_a=_score_to_response(comparison.score_a),
        score_b=_score_to_response(comparison.score_b),
        ai_explanation=ai_explanation,
        policy_context=policy_context,
        assumptions=comparison.assumptions,
        missing_info=comparison.missing_info,
        verification_passed=comparison.verification_passed,
        verification_notes=comparison.verification_notes,
        guardrail_passed=True,
        policy_evidence=[e.to_dict() for e in evidence],
        cited_chunks=cited,
        unsupported_citations=unsupported,
    )

    save_event = rec.add("save_comparison", "persistence", "db.save_comparison", "ok",
                         "Saved to history together with this trace.")
    response.elapsed_seconds = round(rec.elapsed_seconds, 3)
    response.trace_events = [TraceEvent(**e) for e in rec.events]
    response.trace = rec.as_lines()
    try:
        comparison_id, created_at = await asyncio.to_thread(
            db.save_comparison,
            user_id, req.question, priority,
            req.plan_a.model_dump(), req.plan_b.model_dump(),
            comparison.recommendation.value,
            response.model_dump(exclude={"trace_events", "comparison_id", "created_at"}),
            rec.events,
        )
        response.comparison_id = comparison_id
        response.created_at = created_at
    except Exception as exc:
        logger.error("Could not save comparison for %s: %s", user_id, exc)
        save_event.update(status="error", detail=f"Not saved to history: {exc}")
        response.trace_events = [TraceEvent(**e) for e in rec.events]
        response.trace = rec.as_lines()

    trace_name = f"compare_{user_id}_{int(time.time())}.txt"
    await asyncio.to_thread(_save_trace_safe, trace_name, response.trace, ai_explanation)
    return response
