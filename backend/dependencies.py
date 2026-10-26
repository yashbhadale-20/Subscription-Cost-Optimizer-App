"""
Shared application state and FastAPI dependency providers.

Lifecycle:
  - startup_state()  called once in the FastAPI lifespan context manager
  - get_store()      FastAPI dependency — returns the vectorstore or None
  - llm_available()  FastAPI dependency — raises 503 if Ollama is unreachable

The three LangGraph agents (calculator_agent, review_agent, conversation_agent)
are already compiled at import time by ta2_subscription_optimizer; the compilation
makes no network calls. Actual LLM calls only happen on agent.invoke(), which
must be wrapped in asyncio.to_thread() to avoid blocking the event loop.
"""
from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path
from typing import Any

from fastapi import Header, HTTPException

# ── ensure the project root is on the Python path so ta2_subscription_optimizer
#    can be imported from the backend/ sub-package ───────────────────────────
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

import ta2_subscription_optimizer as _app  # noqa: E402  (must come after sys.path)

from backend import db  # noqa: E402
from backend.config import settings  # noqa: E402

logger = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# Module-level mutable state (initialized once during lifespan startup)
# ─────────────────────────────────────────────────────────────────────────────
_vectorstore: Any = None          # langchain_chroma.Chroma or None
_startup_complete: bool = False   # prevents double-init


# ─────────────────────────────────────────────────────────────────────────────
# Startup initialisation
# ─────────────────────────────────────────────────────────────────────────────

async def startup_state() -> None:
    """
    Called once from main.py lifespan.  Initialises SQLite and the Chroma
    vectorstore.  The vectorstore already has 11 embedded documents, so
    get_vectorstore() will NOT call OllamaEmbeddings at startup.
    If Ollama is unavailable the store is set to None and the server still
    starts — /health/ollama will report the status.
    """
    global _vectorstore, _startup_complete

    if _startup_complete:
        return

    # 1. Ensure SQLite table exists (pure Python, no Ollama)
    try:
        _app.init_memory()
        logger.info("SQLite long-term memory initialized.")
    except Exception as exc:
        logger.warning("SQLite init warning: %s", exc)

    # 1b. Backend tables (additive migrations; backs up the file before the first one)
    report = db.migrate()
    if report["from_version"] != report["to_version"]:
        logger.info(
            "Database migrated v%s -> v%s (backup: %s)",
            report["from_version"], report["to_version"], report["backup"] or "not needed, database was empty",
        )

    # 2. Initialise vectorstore in a thread (Chroma + local disk; safe without Ollama
    #    because the collection already contains 11 documents)
    try:
        _vectorstore = await asyncio.to_thread(_app.get_vectorstore)
        if _vectorstore is not None:
            logger.info("ChromaDB vectorstore ready.")
        else:
            logger.warning(
                "ChromaDB vectorstore is None — RAG queries will be skipped. "
                "Ensure Ollama is running with: ollama serve"
            )
    except Exception as exc:
        logger.error("ChromaDB init failed: %s", exc)
        _vectorstore = None

    _startup_complete = True


async def shutdown_state() -> None:
    """No persistent connections to close, but log cleanly."""
    logger.info("Backend shutting down.")


# ─────────────────────────────────────────────────────────────────────────────
# FastAPI dependency providers
# ─────────────────────────────────────────────────────────────────────────────

def get_store() -> Any:
    """FastAPI dependency: returns the Chroma vectorstore (may be None)."""
    return _vectorstore


def get_app_module():
    """FastAPI dependency: returns the imported ta2 module for direct access."""
    return _app


async def require_ollama() -> None:
    """
    FastAPI dependency that raises HTTP 503 if Ollama is unreachable.
    Use on endpoints that MUST call the LLM.
    """
    ok, msg = await asyncio.to_thread(
        _app.check_ollama,
        settings.llm_model,
        settings.embedding_model,
    )
    if not ok:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "Ollama service unavailable",
                "message": msg,
                "hint": "Start Ollama with 'ollama serve' and ensure models are pulled.",
            },
        )


def current_user(authorization: str | None = Header(default=None)) -> str:
    """
    FastAPI dependency: resolves the bearer token from ``POST /auth/session`` to a user ID.
    Every user-scoped endpoint takes the user ID from here, never from the request alone.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=401,
            detail={"error": "Not signed in", "message": "Send 'Authorization: Bearer <token>' from POST /auth/session."},
            headers={"WWW-Authenticate": "Bearer"},
        )
    user_id = db.user_for_token(authorization[7:].strip())
    if user_id is None:
        raise HTTPException(
            status_code=401,
            detail={"error": "Invalid session", "message": "This session token is not recognised. Sign in again."},
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user_id


def ensure_same_user(token_user: str, claimed_user: str) -> None:
    """403 when a request names a user other than the one its token belongs to."""
    if token_user != claimed_user:
        raise HTTPException(
            status_code=403,
            detail={"error": "Forbidden", "message": "This session can only access its own user's data."},
        )
