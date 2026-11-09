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
import functools
import logging
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable, TypeVar

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

T = TypeVar("T")
_model_executor = ThreadPoolExecutor(max_workers=settings.model_workers, thread_name_prefix="model")


async def in_model_thread(func: Callable[..., T], *args: Any) -> T:
    """
    Runs an Ollama-backed call (LLM or embedding) on its own small pool. asyncio.wait_for can stop
    waiting but can't stop the thread, so on the default pool hung calls would queue database
    routes behind them.
    """
    return await asyncio.get_running_loop().run_in_executor(_model_executor, functools.partial(func, *args))


# The core module hardcodes its paths. Point them at the configured ones so remember()/recall(),
# the backend's own tables (settings.db_path), Chroma and trace files all use the same locations;
# otherwise DB_PATH=... splits a user's profile and history across two databases.
_app.DB_PATH = Path(settings.db_path)
_app.TRACE_DIR = Path(settings.trace_dir)
_app.CHROMA_DIR = Path(settings.chroma_dir)
_app.DATA_DIR = Path(settings.data_dir)

# Model names and the Ollama URL are fixed inside the core module's compiled agents and
# check_ollama(). Overridden values would only make /health and traces report a model that
# isn't the one answering, so the effective values are put back and the override is logged.
_CORE_OLLAMA = {"ollama_base_url": "http://localhost:11434", "llm_model": "qwen2.5:3b",
                "embedding_model": "nomic-embed-text"}
_ignored_settings: list[tuple[str, str, str]] = []
for _name, _core_value in _CORE_OLLAMA.items():
    if getattr(settings, _name).rstrip("/") != _core_value:
        _ignored_settings.append((_name, getattr(settings, _name), _core_value))
        setattr(settings, _name, _core_value)

# ─────────────────────────────────────────────────────────────────────────────
# Module-level mutable state (initialized once during lifespan startup)
# ─────────────────────────────────────────────────────────────────────────────
_vectorstore: Any = None          # langchain_chroma.Chroma or None
_startup_complete: bool = False   # prevents double-init
_store_retry_at: float = 0.0      # time.monotonic() of the next attempt after a failed init
_store_lock = threading.Lock()
_STORE_RETRY_SECONDS = 30


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
    global _vectorstore, _startup_complete, _store_retry_at

    if _startup_complete:
        return

    for name, configured, core_value in _ignored_settings:
        logger.warning("%s=%r is ignored: the core module always uses %r.", name.upper(), configured, core_value)

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

    _store_retry_at = time.monotonic() + _STORE_RETRY_SECONDS
    _startup_complete = True


async def shutdown_state() -> None:
    """No persistent connections to close, but log cleanly."""
    logger.info("Backend shutting down.")


# ─────────────────────────────────────────────────────────────────────────────
# FastAPI dependency providers
# ─────────────────────────────────────────────────────────────────────────────

def get_store() -> Any:
    """
    FastAPI dependency: returns the Chroma vectorstore (may be None).

    If startup couldn't open it (typically an empty index while Ollama was down), it is retried
    at most every 30 s, so RAG recovers once Ollama is back without restarting the server.
    FastAPI runs this sync dependency in a worker thread.
    """
    global _vectorstore, _store_retry_at
    if _vectorstore is not None or time.monotonic() < _store_retry_at:
        return _vectorstore
    with _store_lock:
        if _vectorstore is None and time.monotonic() >= _store_retry_at:
            _store_retry_at = time.monotonic() + _STORE_RETRY_SECONDS
            _vectorstore = _app.get_vectorstore()
            if _vectorstore is not None:
                logger.info("ChromaDB vectorstore ready after retry.")
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
