"""
GET /health        — lightweight liveness probe (no Ollama call)
GET /health/ollama — deep Ollama connectivity + model availability check
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter

from backend.config import settings
from backend.models import HealthResponse, OllamaHealthResponse

import ta2_subscription_optimizer as _app

router = APIRouter(prefix="/health", tags=["Health"])


@router.get("", response_model=HealthResponse, summary="Liveness probe")
async def health() -> HealthResponse:
    """
    Returns HTTP 200 as long as the Python process is running.
    Does NOT check Ollama or ChromaDB — use /health/ollama for that.
    """
    return HealthResponse(version=settings.app_version)


@router.get(
    "/ollama",
    response_model=OllamaHealthResponse,
    summary="Ollama service & model availability",
)
async def health_ollama() -> OllamaHealthResponse:
    """
    Checks that the Ollama daemon is reachable and that both required models
    (LLM + embedding) are pulled.  Always returns HTTP 200 — the *payload*
    field ``ollama_ok`` indicates actual status so clients can react without
    treating a false status as a server error.
    """
    ok, msg = await asyncio.to_thread(
        _app.check_ollama,
        settings.llm_model,
        settings.embedding_model,
    )
    return OllamaHealthResponse(
        ollama_ok=ok,
        message=msg,
        llm_model=settings.llm_model,
        embedding_model=settings.embedding_model,
    )
