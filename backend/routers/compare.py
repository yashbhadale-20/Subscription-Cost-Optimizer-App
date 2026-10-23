"""
POST /compare

Wraps the existing run_supervisor() workflow:
  1. Input guardrail (keyword blocklist) — checked first, 422 if blocked
  2. Persist plan inputs to SQLite long-term memory
  3. Run run_supervisor() in asyncio.to_thread (non-blocking)
  4. Parse answer into structured calc_result / policy_result fields
  5. Save trace file to traces/
  6. Return structured CompareResponse

Requires Ollama to be online — returns HTTP 503 otherwise.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from backend.config import settings
from backend.dependencies import get_store, require_ollama
from backend.models import CompareRequest, CompareResponse

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/compare", tags=["Compare"])


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _parse_supervisor_answer(answer: str) -> tuple[str, str]:
    """
    Split the raw supervisor answer into calc_result and policy_result.
    Returns ("", "") if the expected section headers are absent.
    """
    calc_result = ""
    policy_result = ""
    try:
        if "What the policy says:" in answer:
            parts = answer.split("What the policy says:", 1)
            calc_part = parts[0].replace("Here is the cost comparison:", "").strip()
            # Strip trailing disclaimer
            policy_part = parts[1].split(
                "The comparison uses the discounts", 1
            )[0].strip()
            calc_result = calc_part
            policy_result = policy_part
    except Exception:
        pass
    return calc_result, policy_result


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
    summary="Compare two subscription plans using the multi-agent supervisor",
    dependencies=[Depends(require_ollama)],
)
async def compare(
    req: CompareRequest,
    store: Any = Depends(get_store),
) -> CompareResponse:
    """
    Full supervisor workflow:
    * Guardrail → SQLite recall → RAG retrieval → Calculator Agent → Review Agent

    Requires Ollama daemon + qwen2.5:3b + nomic-embed-text.
    Returns HTTP 503 if Ollama is unavailable.
    """

    # ── 1. Pre-flight: guardrail (pure Python, no LLM) ─────────────────────
    approved, guard_msg = _app.guardrail(req.question)
    if not approved:
        raise HTTPException(
            status_code=422,
            detail={
                "error": "Input blocked by guardrail",
                "message": guard_msg,
            },
        )

    # ── 2. Persist plan inputs to SQLite long-term memory ──────────────────
    try:
        _app.remember(req.user_id, "profile", "last_plan_a_monthly", f"{req.plan_a.monthly_price:.2f}")
        _app.remember(req.user_id, "profile", "last_plan_b_monthly", f"{req.plan_b.monthly_price:.2f}")
        _app.remember(req.user_id, "profile", "last_plan_a_discount", f"{req.plan_a.discount_percent:.2f}")
        _app.remember(req.user_id, "profile", "last_plan_b_discount", f"{req.plan_b.discount_percent:.2f}")
        _app.remember(req.user_id, "profile", "currency", "INR")
        _app.remember(req.user_id, "profile", "plan_a_name", req.plan_a.name)
        _app.remember(req.user_id, "profile", "plan_b_name", req.plan_b.name)
    except Exception as exc:
        logger.warning("Memory persist warning for user %s: %s", req.user_id, exc)

    # ── 3. Run supervisor (blocking LLM calls) in a thread ─────────────────
    def _run() -> tuple[str, list[str], float]:
        return _app.run_supervisor(
            question=req.question,
            plan_a=req.plan_a.monthly_price,
            plan_b=req.plan_b.monthly_price,
            discount_a=req.plan_a.discount_percent,
            discount_b=req.plan_b.discount_percent,
            store=store,
            user_id=req.user_id,
        )

    try:
        answer, trace, elapsed = await asyncio.wait_for(
            asyncio.to_thread(_run),
            timeout=settings.llm_timeout_seconds,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "LLM timeout",
                "message": f"Supervisor did not respond within {settings.llm_timeout_seconds}s.",
            },
        )
    except Exception as exc:
        logger.error("Supervisor error for user %s: %s", req.user_id, exc)
        raise HTTPException(
            status_code=502,
            detail={"error": "Supervisor execution failed", "message": str(exc)},
        )

    # ── 4. Parse structured fields from the raw answer string ──────────────
    calc_result, policy_result = _parse_supervisor_answer(answer)

    # ── 5. Save trace (non-blocking best-effort) ────────────────────────────
    trace_name = f"api_{req.user_id}_{int(time.time())}.txt"
    await asyncio.to_thread(_save_trace_safe, trace_name, trace, answer)

    return CompareResponse(
        answer=answer,
        calc_result=calc_result,
        policy_result=policy_result,
        trace=trace,
        elapsed_seconds=round(elapsed, 3),
        guardrail_passed=True,
        plan_a_name=req.plan_a.name,
        plan_b_name=req.plan_b.name,
    )
