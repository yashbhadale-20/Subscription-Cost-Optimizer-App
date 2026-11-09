"""
POST   /optimize                    — optimization plan for all of the caller's subscriptions
GET    /optimizations               — the caller's saved optimizations, newest first
GET    /optimizations/{id}          — one saved optimization
GET    /optimizations/{id}/trace    — its structured execution trace
DELETE /optimizations/{id}          — delete one
GET    /portfolio                   — the caller's saved subscription list
PUT    /portfolio                   — replace it

/optimize does not depend on require_ollama: when Ollama is down, slow, or the
agent's reply is unusable, it returns the deterministic plan with
``used_fallback=true`` instead of a 503. Another user's optimization id answers
404, the same as one that doesn't exist.
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response

from backend import db
from backend.dependencies import _app, current_user, ensure_same_user, get_store
from backend.models import (
    OptimizationListResponse,
    OptimizationSummary,
    OptimizationTraceResponse,
    OptimizeRequest,
    OptimizeResponse,
    PortfolioRequest,
    PortfolioResponse,
    SavedOptimizationResponse,
    SubscriptionInput,
    TraceEvent,
)
from backend import optimizer_agent

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Optimize"])

_ID = Path(..., min_length=32, max_length=32, pattern=r"^[0-9a-f]{32}$")


def _not_found() -> HTTPException:
    return HTTPException(status_code=404, detail={"error": "Not found", "message": "No such saved optimization."})


def _save_trace_safe(trace_name: str, lines: list[str], summary: str) -> None:
    try:
        _app.save_trace(trace_name, "\n".join(lines) + "\n\nRESULT\n" + summary)
    except Exception as exc:
        logger.warning("Could not save trace %s: %s", trace_name, exc)


@router.post("/optimize", response_model=OptimizeResponse,
             summary="Optimization plan for all of the caller's subscriptions")
async def optimize(
    req: OptimizeRequest,
    store: Any = Depends(get_store),
    user_id: str = Depends(current_user),
) -> OptimizeResponse:
    ensure_same_user(user_id, req.user_id)
    try:
        result = await optimizer_agent.run_optimizer(user_id, req, store)
    except optimizer_agent.GuardrailBlocked as exc:
        raise HTTPException(status_code=422, detail={"error": "Input blocked by guardrail", "message": exc.message})

    response, rec = result.response, result.recorder
    save_event = rec.add("save_optimization", "persistence", "db.save_optimization", "ok",
                         "Saved with this trace.")
    response.trace_events = [TraceEvent(**e) for e in rec.events]
    response.elapsed_seconds = round(rec.elapsed_seconds, 3)
    try:
        optimization_id, created_at = await asyncio.to_thread(
            db.save_optimization, user_id, req.goal, req.monthly_budget, result.subscriptions,
            response.model_dump(exclude={"trace_events", "optimization_id", "created_at"}), rec.events,
        )
        response.optimization_id = optimization_id
        response.created_at = created_at
    except Exception as exc:
        logger.error("Could not save optimization for %s: %s", user_id, exc)
        save_event.update(status="error", detail=f"Not saved ({type(exc).__name__}).")
        response.trace_events = [TraceEvent(**e) for e in rec.events]

    trace_name = f"optimize_{user_id}_{int(time.time())}_{uuid.uuid4().hex[:8]}.txt"
    await asyncio.to_thread(_save_trace_safe, trace_name, rec.as_lines(), response.ai_summary)
    return response


def _saved_result(item: dict[str, Any]) -> dict[str, Any]:
    return {**item["result"], "optimization_id": item["id"], "created_at": item["created_at"],
            "trace_events": item["trace_events"]}


@router.get("/optimizations", response_model=OptimizationListResponse, summary="The caller's saved optimizations")
async def list_optimizations(
    limit: int = Query(default=20, ge=1, le=50),
    offset: int = Query(default=0, ge=0, le=1_000_000),
    user_id: str = Depends(current_user),
) -> OptimizationListResponse:
    items, total = await asyncio.to_thread(db.list_optimizations, user_id, limit, offset)
    return OptimizationListResponse(items=[OptimizationSummary(**i) for i in items],
                                    total=total, limit=limit, offset=offset)


@router.get("/optimizations/{optimization_id}", response_model=SavedOptimizationResponse,
            summary="One saved optimization")
async def get_optimization(optimization_id: str = _ID, user_id: str = Depends(current_user)) -> SavedOptimizationResponse:
    item = await asyncio.to_thread(db.get_optimization, user_id, optimization_id)
    if item is None:
        raise _not_found()
    return SavedOptimizationResponse(
        id=item["id"], created_at=item["created_at"], goal=item["goal"], monthly_budget=item["monthly_budget"],
        subscriptions=item["subscriptions"], result=OptimizeResponse(**_saved_result(item)),
    )


@router.get("/optimizations/{optimization_id}/trace", response_model=OptimizationTraceResponse,
            summary="Execution trace of a saved optimization")
async def get_optimization_trace(optimization_id: str = _ID,
                                 user_id: str = Depends(current_user)) -> OptimizationTraceResponse:
    item = await asyncio.to_thread(db.get_optimization, user_id, optimization_id)
    if item is None:
        raise _not_found()
    return OptimizationTraceResponse(optimization_id=item["id"], created_at=item["created_at"],
                                     events=item["trace_events"])


@router.delete("/optimizations/{optimization_id}", status_code=204, response_class=Response,
               response_model=None, summary="Delete one saved optimization")
async def delete_optimization(optimization_id: str = _ID, user_id: str = Depends(current_user)) -> None:
    if not await asyncio.to_thread(db.delete_optimization, user_id, optimization_id):
        raise _not_found()


@router.get("/portfolio", response_model=PortfolioResponse, summary="The caller's saved subscription list")
async def get_portfolio(user_id: str = Depends(current_user)) -> PortfolioResponse:
    saved = await asyncio.to_thread(db.get_portfolio, user_id)
    subs: list[SubscriptionInput] = []
    for raw in saved["subscriptions"]:
        try:
            subs.append(SubscriptionInput(**raw))
        except Exception as exc:  # a row saved under older rules is skipped, not a 500
            logger.warning("Skipping an invalid saved subscription for %s: %s", user_id, exc)
    return PortfolioResponse(user_id=user_id, subscriptions=subs, updated_at=saved["updated_at"])


@router.put("/portfolio", response_model=PortfolioResponse, summary="Replace the caller's saved subscription list")
async def put_portfolio(req: PortfolioRequest, user_id: str = Depends(current_user)) -> PortfolioResponse:
    saved = await asyncio.to_thread(
        db.save_portfolio, user_id, [s.model_dump(mode="json") for s in req.subscriptions])
    return PortfolioResponse(user_id=user_id, subscriptions=req.subscriptions, updated_at=saved["updated_at"])
