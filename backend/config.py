"""
Backend configuration via pydantic-settings.
All settings can be overridden by environment variables or a .env file.
"""
from __future__ import annotations

from pathlib import Path
from pydantic_settings import BaseSettings


# Absolute path to the ta2/ project root (one level up from backend/)
PROJECT_ROOT: Path = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    # ── Ollama ─────────────────────────────────────────────────────────────
    ollama_base_url: str = "http://localhost:11434"
    llm_model: str = "qwen2.5:3b"
    embedding_model: str = "nomic-embed-text"

    # ── FastAPI ─────────────────────────────────────────────────────────────
    app_title: str = "Subscription Cost Optimizer API"
    app_version: str = "1.0.0"
    debug: bool = False

    # CORS — include Android emulator origin (10.0.2.2 maps to host localhost)
    cors_origins: list[str] = [
        "http://localhost:3000",
        "http://localhost:8080",
        "http://127.0.0.1:8000",
        "http://10.0.2.2:8000",
    ]

    # ── Request limits ───────────────────────────────────────────────────────
    max_question_length: int = 1_000          # characters
    max_monthly_price: float = 1_000_000.0    # INR upper bound
    max_discount_percent: float = 100.0
    max_policy_k: int = 10                    # max RAG chunks per request
    llm_timeout_seconds: int = 180            # asyncio.wait_for limit
    retrieval_timeout_seconds: int = 30       # embedding + Chroma search
    # LangGraph step cap per agent call. The API's agents have no tools, so a normal call
    # takes 1–2 steps; this stops a misbehaving graph long before the library default (25).
    agent_recursion_limit: int = 6
    # The optimization agent has tools (list, candidates, estimate, budget, policy search), so it
    # needs a few model→tool round trips: about 4 tool rounds plus the final answer.
    optimizer_recursion_limit: int = 10
    # Max wait for the optimization agent; on timeout the deterministic plan is returned.
    optimizer_timeout_seconds: int = 180
    # Renewal alerts cover renewals within this many days.
    renewal_window_days: int = 14
    # Threads for Ollama-backed calls. They're separate from the default pool because a timed-out
    # call keeps its thread until the Ollama client gives up; extra requests wait for a free one.
    model_workers: int = 4

    # ── RAG / memory ─────────────────────────────────────────────────────────
    # Minimum normalised relevance (0–1) for a chunk to count as evidence. Measured on
    # this corpus: on-topic questions score 0.45–0.60, unrelated ones 0.15 or less.
    policy_min_relevance: float = 0.30
    # Messages replayed from SQLite to rebuild a chat thread the in-memory agent lost
    # (e.g. after a server restart).
    chat_restore_messages: int = 20

    # ── Paths (derived from PROJECT_ROOT; override only if needed) ───────────
    db_path: Path = PROJECT_ROOT / "subscription_memory.sqlite"
    chroma_dir: Path = PROJECT_ROOT / "chroma_db"
    trace_dir: Path = PROJECT_ROOT / "traces"
    data_dir: Path = PROJECT_ROOT / "data"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "extra": "ignore"}


settings = Settings()
