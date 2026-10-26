"""
POST   /chat                            — send a message (policy evidence attached)
GET    /conversations                   — the caller's saved threads
GET    /conversations/{thread_id}       — one thread's transcript
DELETE /conversations/{thread_id}       — delete a transcript

Wraps the existing conversation_agent (CompiledStateGraph with InMemorySaver).

Memory model:
  * The agent's working memory is the core module's InMemorySaver, which lives
    only as long as the server process. It is keyed by "<user_id>::<thread_id>",
    so two users who pick the same thread_id never share a conversation.
  * Every user message and reply is also written to SQLite (chat_messages).
    If the agent has no state for a thread the user has a transcript for
    (typically after a restart), the last few saved messages are replayed into
    the agent with the new message, and the response says so.

Requires Ollama to be online — returns HTTP 503 otherwise.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response

from backend import db, policy_rag
from backend.config import settings
from backend.dependencies import current_user, ensure_same_user, get_store, require_ollama
from backend.models import (
    ChatRequest,
    ChatResponse,
    ConversationListResponse,
    ConversationResponse,
    ConversationSummary,
)

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Chat"])

_THREAD_ID = Path(..., min_length=1, max_length=128, pattern=r"^[a-zA-Z0-9_\-]+$")


def _agent_thread(user_id: str, thread_id: str) -> str:
    return f"{user_id}::{thread_id}"


def _agent_has_state(config: dict[str, Any]) -> bool:
    try:
        state = _app.conversation_agent.get_state(config)
        return bool(state and state.values.get("messages"))
    except Exception:
        return False


def _with_evidence(message: str, evidence: list[policy_rag.Evidence]) -> str:
    if not evidence:
        return message
    return (
        "Policy excerpts that may be relevant. Use them only if they answer the question, "
        "and cite them as [Chunk N] when you do:\n"
        f"{policy_rag.format_for_prompt(evidence)}\n\n"
        f"User message: {message}"
    )


@router.post(
    "/chat",
    response_model=ChatResponse,
    summary="Multi-turn conversational agent with policy evidence",
    dependencies=[Depends(require_ollama)],
)
async def chat(
    req: ChatRequest,
    store: Any = Depends(get_store),
    user_id: str = Depends(current_user),
) -> ChatResponse:
    ensure_same_user(user_id, req.user_id)
    config = {"configurable": {"thread_id": _agent_thread(user_id, req.thread_id)}}

    evidence: list[policy_rag.Evidence] = []
    if store is not None:
        try:
            evidence = await asyncio.to_thread(policy_rag.retrieve_evidence, store, req.message, 3)
        except Exception as exc:
            # Chat still works without evidence; the response simply carries none.
            logger.warning("Policy retrieval for chat failed: %s", exc)

    restored: list[dict[str, Any]] = []
    if not await asyncio.to_thread(_agent_has_state, config):
        restored = await asyncio.to_thread(
            db.chat_messages, user_id, req.thread_id, settings.chat_restore_messages
        )

    messages = [{"role": m["role"], "content": m["content"]} for m in restored]
    messages.append({"role": "user", "content": _with_evidence(req.message, evidence)})

    def _invoke() -> str:
        result = _app.conversation_agent.invoke({"messages": messages}, config=config)
        return str(result["messages"][-1].content)

    try:
        reply = await asyncio.wait_for(
            asyncio.to_thread(_invoke),
            timeout=settings.llm_timeout_seconds,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "LLM timeout",
                "message": f"Chat agent did not respond within {settings.llm_timeout_seconds}s.",
            },
        )
    except Exception as exc:
        logger.error("Chat agent error for thread %s: %s", req.thread_id, exc)
        raise HTTPException(
            status_code=502,
            detail={"error": "Chat agent failed", "message": str(exc)},
        )

    reply = policy_rag.strip_reasoning(reply)
    cited, unsupported = policy_rag.check_citations(reply, evidence)
    evidence_dicts = [e.to_dict() for e in evidence]

    try:
        await asyncio.to_thread(db.add_chat_message, user_id, req.thread_id, "user", req.message)
        await asyncio.to_thread(db.add_chat_message, user_id, req.thread_id, "assistant", reply, evidence_dicts)
    except Exception as exc:
        # The reply is real; only the transcript copy failed.
        logger.error("Could not save chat transcript for %s/%s: %s", user_id, req.thread_id, exc)

    return ChatResponse(
        reply=reply,
        thread_id=req.thread_id,
        user_id=user_id,
        evidence=evidence_dicts,
        cited_chunks=cited,
        unsupported_citations=unsupported,
        restored_history=bool(restored),
    )


@router.get("/conversations", response_model=ConversationListResponse, summary="The caller's saved chat threads")
async def list_conversations(
    limit: int = Query(default=20, ge=1, le=100),
    user_id: str = Depends(current_user),
) -> ConversationListResponse:
    rows = await asyncio.to_thread(db.list_threads, user_id, limit)
    return ConversationListResponse(
        conversations=[
            ConversationSummary(
                thread_id=r["thread_id"],
                title=(r["first_message"] or "Conversation")[:80],
                message_count=r["message_count"],
                started_at=r["started_at"],
                updated_at=r["updated_at"],
            )
            for r in rows
        ]
    )


@router.get(
    "/conversations/{thread_id}",
    response_model=ConversationResponse,
    summary="Transcript of one of the caller's threads",
)
async def get_conversation(
    thread_id: str = _THREAD_ID,
    user_id: str = Depends(current_user),
) -> ConversationResponse:
    messages = await asyncio.to_thread(db.chat_messages, user_id, thread_id)
    if not messages:
        raise HTTPException(status_code=404, detail={"error": "Not found", "message": "No such conversation."})
    return ConversationResponse(thread_id=thread_id, messages=messages)


@router.delete("/conversations/{thread_id}", status_code=204, response_class=Response, response_model=None, summary="Delete one of the caller's threads")
async def delete_conversation(
    thread_id: str = _THREAD_ID,
    user_id: str = Depends(current_user),
) -> None:
    deleted = await asyncio.to_thread(db.delete_thread, user_id, thread_id)
    if not deleted:
        raise HTTPException(status_code=404, detail={"error": "Not found", "message": "No such conversation."})
    # Also drop the agent's in-memory copy so a deleted thread can't be resumed.
    checkpointer = getattr(_app.conversation_agent, "checkpointer", None)
    if checkpointer is not None and hasattr(checkpointer, "delete_thread"):
        checkpointer.delete_thread(_agent_thread(user_id, thread_id))
