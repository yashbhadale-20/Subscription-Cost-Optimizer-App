"""
GET  /policy/search?q=<query>&k=<1..10>  — raw similarity search
POST /policy/ask                         — grounded answer with evidence and citation check
GET  /policy/status                      — vector index vs. policy file (read-only)

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

from backend import policy_rag
from backend.config import settings
from backend.dependencies import get_store, require_ollama
from backend.models import PolicyAskRequest, PolicyAskResponse, PolicySearchResponse, PolicyStatusResponse
from backend.tracing import TraceRecorder, model_tool_calls

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


_NOT_IN_POLICY = (
    "The subscription policy doesn't contain information that answers this question, "
    "so I can't answer it from the policy."
)


@router.post(
    "/ask",
    response_model=PolicyAskResponse,
    summary="Answer a question from the policy corpus, with the evidence used",
    dependencies=[Depends(require_ollama)],
)
async def policy_ask(req: PolicyAskRequest, store: Any = Depends(get_store)) -> PolicyAskResponse:
    """
    Retrieves the most relevant policy chunks and has the review agent answer
    from them only, citing chunks as [Chunk N]. When no chunk is relevant
    enough, the model is not called and the answer says the policy doesn't
    cover the question. Citations of chunks that were not retrieved are
    reported in ``unsupported_citations``.
    """
    if store is None:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "Vectorstore unavailable",
                "message": "ChromaDB vectorstore was not initialised. Ensure Ollama is running.",
            },
        )
    rec = TraceRecorder()
    retrieval_name = "Chroma.similarity_search_with_relevance_scores"
    try:
        with rec.timed() as t:
            evidence = await asyncio.wait_for(
                asyncio.to_thread(policy_rag.retrieve_evidence, store, req.question, req.k), timeout=30,
            )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=503, detail={"error": "Search timeout", "message": "Embedding query took too long."},
        )
    except Exception as exc:
        logger.error("Policy retrieval error: %s", exc)
        raise HTTPException(status_code=502, detail={"error": "Policy search failed", "message": str(exc)})
    rec.add(
        "policy_retrieval", "retrieval", retrieval_name, "ok",
        f"{len(evidence)} of top {req.k} chunk(s) at or above relevance {settings.policy_min_relevance}",
        t["ms"], {"chunks": [e.chunk for e in evidence], "relevance": [e.relevance for e in evidence]},
    )

    agent_name = f"review_agent ({settings.llm_model})"
    if not evidence:
        rec.add("answer", "llm", agent_name, "skipped", "No relevant policy text, so the model was not called.")
        return PolicyAskResponse(
            question=req.question, answer=_NOT_IN_POLICY, answered_from_policy=False, evidence=[],
            cited_chunks=[], unsupported_citations=[], trace_events=rec.events,
            elapsed_seconds=round(rec.elapsed_seconds, 3),
        )

    prompt = (
        f"Question: {req.question}\n\nRetrieved policy text:\n{policy_rag.format_for_prompt(evidence)}\n\n"
        "Answer in at most three sentences using only this text, and cite every claim as [Chunk N] "
        "with a chunk number shown above. If the text does not contain the answer, say that the "
        "policy does not provide that information."
    )

    def _answer() -> tuple[str, list[dict[str, Any]]]:
        result = _app.review_agent.invoke({"messages": [{"role": "user", "content": prompt}]})
        return str(result["messages"][-1].content), model_tool_calls(result["messages"])

    try:
        with rec.timed() as t:
            raw, tool_calls = await asyncio.wait_for(
                asyncio.to_thread(_answer), timeout=settings.llm_timeout_seconds,
            )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=503,
            detail={"error": "LLM timeout", "message": f"No answer within {settings.llm_timeout_seconds}s."},
        )
    except Exception as exc:
        logger.error("Policy answer error: %s", exc)
        raise HTTPException(status_code=502, detail={"error": "Policy answer failed", "message": str(exc)})

    answer = policy_rag.strip_reasoning(raw)
    rec.add(
        "answer", "llm", agent_name, "ok",
        "Answer generated. "
        + (f"The model made {len(tool_calls)} tool call(s)." if tool_calls else "The model made no tool calls."),
        t["ms"],
    )
    cited, unsupported = policy_rag.check_citations(answer, evidence)
    rec.add(
        "citation_check", "validation", "policy_rag.check_citations",
        "warning" if unsupported or not cited else "passed",
        (f"Cites chunk(s) that were not retrieved: {unsupported}. " if unsupported else "")
        + (f"Cited retrieved chunk(s): {cited}." if cited else "The answer cites no chunk."),
        data={"cited": cited, "unsupported": unsupported},
    )
    return PolicyAskResponse(
        question=req.question, answer=answer, answered_from_policy=True,
        evidence=[e.to_dict() for e in evidence], cited_chunks=cited, unsupported_citations=unsupported,
        trace_events=rec.events, elapsed_seconds=round(rec.elapsed_seconds, 3),
    )


@router.get("/status", response_model=PolicyStatusResponse, summary="Vector index vs. policy file (read-only)")
async def policy_status(store: Any = Depends(get_store)) -> PolicyStatusResponse:
    """Reports chunks whose indexed text differs from data/subscription_policy.txt. Never re-indexes."""
    status = await asyncio.to_thread(policy_rag.index_status, store)
    return PolicyStatusResponse(**status, min_relevance=settings.policy_min_relevance)
