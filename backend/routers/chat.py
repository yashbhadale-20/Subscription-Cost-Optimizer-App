"""
POST /chat

Wraps the existing conversation_agent (CompiledStateGraph with InMemorySaver).
Each unique thread_id maintains its own conversation history for the lifetime
of the server process.

Note: InMemorySaver does NOT persist across server restarts.  This is the
existing behaviour from ta2_subscription_optimizer.py and is documented.

Requires Ollama to be online — returns HTTP 503 otherwise.
"""
from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, Depends, HTTPException

from backend.config import settings
from backend.dependencies import require_ollama
from backend.models import ChatRequest, ChatResponse

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/chat", tags=["Chat"])


@router.post(
    "",
    response_model=ChatResponse,
    summary="Multi-turn conversational agent (InMemorySaver per thread_id)",
    dependencies=[Depends(require_ollama)],
)
async def chat(req: ChatRequest) -> ChatResponse:
    """
    Sends a message to the conversation_agent using the supplied thread_id
    as the LangGraph checkpoint key.  Conversation history accumulates within
    the thread until the server restarts.

    Requires Ollama daemon + qwen2.5:3b.
    """
    config = {"configurable": {"thread_id": req.thread_id}}

    def _invoke() -> str:
        result = _app.conversation_agent.invoke(
            {"messages": [{"role": "user", "content": req.message}]},
            config=config,
        )
        return result["messages"][-1].content

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

    return ChatResponse(
        reply=reply,
        thread_id=req.thread_id,
        user_id=req.user_id,
    )
