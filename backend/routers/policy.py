"""
GET /policy/search?q=<query>&k=<1..10>

Performs a ChromaDB similarity search against the subscription_policy collection
(11 pre-embedded documents).  Requires Ollama for embedding the query.
Returns HTTP 503 if Ollama is unavailable, HTTP 503 if the vectorstore was not
initialised.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query

from backend.config import settings
from backend.dependencies import get_store, require_ollama
from backend.models import PolicySearchResponse

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/policy", tags=["Policy"])


@router.get(
    "/search",
    response_model=PolicySearchResponse,
    summary="Semantic search over the subscription policy corpus",
    dependencies=[Depends(require_ollama)],
)
async def policy_search(
    q: str = Query(..., min_length=1, max_length=500,
                   description="Natural-language query about subscription policy"),
    k: int = Query(default=3, ge=1, le=settings.max_policy_k,
                   description="Number of chunks to return"),
    store: Any = Depends(get_store),
) -> PolicySearchResponse:
    """
    Returns up to *k* policy chunks relevant to query *q*.
    Chunk text is taken directly from the vectorstore — no LLM generation.
    """
    if store is None:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "Vectorstore unavailable",
                "message": "ChromaDB vectorstore was not initialised. Ensure Ollama is running.",
            },
        )

    def _search() -> str:
        return _app.retrieve_policy(store, q, k=k)

    try:
        raw = await asyncio.wait_for(
            asyncio.to_thread(_search),
            timeout=30,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=503,
            detail={"error": "Search timeout", "message": "Embedding query took too long."},
        )
    except Exception as exc:
        logger.error("Policy search error: %s", exc)
        raise HTTPException(
            status_code=502,
            detail={"error": "Policy search failed", "message": str(exc)},
        )

    # Parse "[Chunk N] text\n\n[Chunk M] text" into a list
    chunks: list[str] = [
        seg.strip() for seg in raw.split("\n\n") if seg.strip()
    ] if raw else []

    return PolicySearchResponse(
        query=q,
        k=k,
        chunks=chunks,
        chunk_count=len(chunks),
    )
