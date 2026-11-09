"""
SQLite schema migrations and data access for the backend's own tables.

The core module (ta2_subscription_optimizer.py) owns the original ``user_memory``
table and is not modified. Everything here is additive:

  * Migrations are numbered and tracked with ``PRAGMA user_version``.
  * Each migration only creates new tables/indexes (``IF NOT EXISTS``); no
    existing table is altered, renamed, or dropped, so ``user_memory`` rows
    are preserved.
  * Before the first migration runs against a database that already has data,
    a full copy is written next to it (``<db>.pre-migration-v<N>.bak``) using
    SQLite's online backup API.

All queries are parameterised and every read/write is scoped by ``user_id``.
"""
from __future__ import annotations

import hashlib
import json
import logging
import secrets
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from backend.config import settings

logger = logging.getLogger(__name__)


def _utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


# ─────────────────────────────────────────────────────────────────────────────
# Migrations
# ─────────────────────────────────────────────────────────────────────────────

# Index i holds the statements that take the schema from version i to i + 1.
MIGRATIONS: list[list[str]] = [
    # v1 — sessions, saved comparisons, preferences, chat transcripts
    [
        # Same definition the core module uses, so a brand-new database works too.
        """
        CREATE TABLE IF NOT EXISTS user_memory (
            user_id TEXT NOT NULL,
            namespace TEXT NOT NULL,
            memory_key TEXT NOT NULL,
            memory_value TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, namespace, memory_key)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS api_sessions (
            token_hash   TEXT PRIMARY KEY,
            user_id      TEXT NOT NULL,
            created_at   TEXT NOT NULL,
            last_used_at TEXT
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_api_sessions_user ON api_sessions(user_id)",
        """
        CREATE TABLE IF NOT EXISTS comparisons (
            id             TEXT PRIMARY KEY,
            user_id        TEXT NOT NULL,
            created_at     TEXT NOT NULL,
            question       TEXT NOT NULL,
            priority       TEXT NOT NULL,
            plan_a_json    TEXT NOT NULL,
            plan_b_json    TEXT NOT NULL,
            recommendation TEXT NOT NULL,
            result_json    TEXT NOT NULL,
            trace_json     TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_comparisons_user_created ON comparisons(user_id, created_at DESC)",
        """
        CREATE TABLE IF NOT EXISTS user_preferences (
            user_id          TEXT PRIMARY KEY,
            default_priority TEXT,
            updated_at       TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS chat_messages (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id       TEXT NOT NULL,
            thread_id     TEXT NOT NULL,
            role          TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
            content       TEXT NOT NULL,
            evidence_json TEXT NOT NULL DEFAULT '[]',
            created_at    TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_chat_messages_thread ON chat_messages(user_id, thread_id, id)",
    ],
    # v2 — subscription optimization runs and each user's saved subscription list
    [
        """
        CREATE TABLE IF NOT EXISTS optimizations (
            id                   TEXT PRIMARY KEY,
            user_id              TEXT NOT NULL,
            created_at           TEXT NOT NULL,
            goal                 TEXT,
            monthly_budget       REAL,
            subscriptions_json   TEXT NOT NULL,
            result_json          TEXT NOT NULL,
            trace_json           TEXT NOT NULL,
            total_annual_savings REAL NOT NULL,
            used_fallback        INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_optimizations_user_created ON optimizations(user_id, created_at DESC)",
        """
        CREATE TABLE IF NOT EXISTS user_portfolio (
            id                 INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id            TEXT NOT NULL UNIQUE,
            subscriptions_json TEXT NOT NULL,
            updated_at         TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_user_portfolio_user ON user_portfolio(user_id)",
    ],
]

SCHEMA_VERSION = len(MIGRATIONS)


def _db_path() -> Path:
    return Path(settings.db_path)


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(_db_path(), timeout=10)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def _has_user_data(conn: sqlite3.Connection) -> bool:
    tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    for table in tables:
        if conn.execute(f'SELECT 1 FROM "{table}" LIMIT 1').fetchone():
            return True
    return False


def _backup(conn: sqlite3.Connection, from_version: int) -> Path:
    path = _db_path()
    target = path.with_name(f"{path.name}.pre-migration-v{from_version}.bak")
    if not target.exists():
        with sqlite3.connect(target) as dest:
            conn.backup(dest)
        logger.info("Backed up %s to %s before migrating", path.name, target.name)
    return target


def migrate() -> dict[str, Any]:
    """Bring the database up to SCHEMA_VERSION. Safe to call repeatedly."""
    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10)
    try:
        current = conn.execute("PRAGMA user_version").fetchone()[0]
        report: dict[str, Any] = {"from_version": current, "to_version": current, "backup": None}
        if current > SCHEMA_VERSION:
            raise RuntimeError(
                f"Database schema v{current} is newer than this backend supports (v{SCHEMA_VERSION})."
            )
        if current == SCHEMA_VERSION:
            return report
        if _has_user_data(conn):
            report["backup"] = str(_backup(conn, current))
        for version in range(current, SCHEMA_VERSION):
            # One transaction per migration: either all of it applies or none of it.
            conn.execute("BEGIN")
            try:
                for statement in MIGRATIONS[version]:
                    conn.execute(statement)
                conn.execute(f"PRAGMA user_version = {version + 1}")
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            logger.info("Applied database migration v%d -> v%d", version, version + 1)
        report["to_version"] = SCHEMA_VERSION
        return report
    finally:
        conn.close()


# ─────────────────────────────────────────────────────────────────────────────
# Sessions
# ─────────────────────────────────────────────────────────────────────────────

class UserAlreadyClaimed(Exception):
    """A session token already exists for this user ID on another device."""


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_session(user_id: str, existing_token: str | None = None) -> str:
    """
    Issue a bearer token for ``user_id``.

    The first device to ask for a user ID claims it. Later requests for the same
    user ID must present one of that user's current tokens. Only the SHA-256 of a
    token is stored; the token itself is returned once.
    """
    with connect() as conn:
        # Take the write lock before checking, so two first-time claims can't both see "unclaimed".
        conn.execute("BEGIN IMMEDIATE")
        claimed = conn.execute(
            "SELECT 1 FROM api_sessions WHERE user_id = ? LIMIT 1", (user_id,)
        ).fetchone()
        if claimed:
            owner = None
            if existing_token:
                owner = conn.execute(
                    "SELECT user_id FROM api_sessions WHERE token_hash = ?", (_hash_token(existing_token),)
                ).fetchone()
            if owner is None or owner["user_id"] != user_id:
                raise UserAlreadyClaimed(user_id)
        token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO api_sessions(token_hash, user_id, created_at) VALUES (?, ?, ?)",
            (_hash_token(token), user_id, _utcnow()),
        )
        return token


def user_for_token(token: str) -> str | None:
    with connect() as conn:
        row = conn.execute(
            "SELECT user_id FROM api_sessions WHERE token_hash = ?", (_hash_token(token),)
        ).fetchone()
        if row is None:
            return None
        conn.execute(
            "UPDATE api_sessions SET last_used_at = ? WHERE token_hash = ?", (_utcnow(), _hash_token(token))
        )
        return row["user_id"]


# ─────────────────────────────────────────────────────────────────────────────
# Long-term profile (the core module's user_memory table)
# ─────────────────────────────────────────────────────────────────────────────

_REMEMBER = """
    INSERT INTO user_memory(user_id, namespace, memory_key, memory_value) VALUES (?, ?, ?, ?)
    ON CONFLICT(user_id, namespace, memory_key)
    DO UPDATE SET memory_value = excluded.memory_value, updated_at = CURRENT_TIMESTAMP
"""


def remember(user_id: str, namespace: str, key: str, value: str) -> None:
    """Like the core module's remember(), except that a failure raises instead of only printing."""
    with connect() as conn:
        conn.execute(_REMEMBER, (user_id, namespace, key, value))


def remember_profile(user_id: str, values: dict[str, str]) -> None:
    """Writes several profile keys in one transaction: all of them or none."""
    with connect() as conn:
        conn.executemany(_REMEMBER, [(user_id, "profile", k, v) for k, v in values.items()])


def recall(user_id: str, namespace: str = "profile") -> dict[str, str]:
    """Like the core module's recall(), except that a failure raises instead of returning {}."""
    with connect() as conn:
        rows = conn.execute(
            "SELECT memory_key, memory_value FROM user_memory WHERE user_id = ? AND namespace = ? ORDER BY memory_key",
            (user_id, namespace),
        ).fetchall()
    return {r["memory_key"]: r["memory_value"] for r in rows}


# ─────────────────────────────────────────────────────────────────────────────
# Saved comparisons
# ─────────────────────────────────────────────────────────────────────────────

def save_comparison(
    user_id: str,
    question: str,
    priority: str,
    plan_a: dict[str, Any],
    plan_b: dict[str, Any],
    recommendation: str,
    result: dict[str, Any],
    trace_events: list[dict[str, Any]],
) -> tuple[str, str]:
    comparison_id = uuid.uuid4().hex
    created_at = _utcnow()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO comparisons(id, user_id, created_at, question, priority, plan_a_json,
                                    plan_b_json, recommendation, result_json, trace_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                comparison_id, user_id, created_at, question, priority,
                json.dumps(plan_a), json.dumps(plan_b), recommendation,
                json.dumps(result), json.dumps(trace_events),
            ),
        )
    return comparison_id, created_at


def _comparison_row(row: sqlite3.Row, include_trace: bool) -> dict[str, Any]:
    item = {
        "id": row["id"],
        "created_at": row["created_at"],
        "question": row["question"],
        "priority": row["priority"],
        "plan_a": json.loads(row["plan_a_json"]),
        "plan_b": json.loads(row["plan_b_json"]),
        "recommendation": row["recommendation"],
        "result": json.loads(row["result_json"]),
    }
    if include_trace:
        item["trace_events"] = json.loads(row["trace_json"])
    return item


def list_comparisons(user_id: str, limit: int, offset: int) -> tuple[list[dict[str, Any]], int]:
    with connect() as conn:
        total = conn.execute("SELECT COUNT(*) FROM comparisons WHERE user_id = ?", (user_id,)).fetchone()[0]
        rows = conn.execute(
            "SELECT * FROM comparisons WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (user_id, limit, offset),
        ).fetchall()
    return [_comparison_row(r, include_trace=False) for r in rows], total


def get_comparison(user_id: str, comparison_id: str) -> dict[str, Any] | None:
    """Returns None both when the id doesn't exist and when another user owns it."""
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM comparisons WHERE id = ? AND user_id = ?", (comparison_id, user_id)
        ).fetchone()
    return _comparison_row(row, include_trace=True) if row else None


def delete_comparison(user_id: str, comparison_id: str) -> bool:
    with connect() as conn:
        cur = conn.execute("DELETE FROM comparisons WHERE id = ? AND user_id = ?", (comparison_id, user_id))
    return cur.rowcount > 0


def delete_all_comparisons(user_id: str) -> int:
    with connect() as conn:
        cur = conn.execute("DELETE FROM comparisons WHERE user_id = ?", (user_id,))
    return cur.rowcount


# ─────────────────────────────────────────────────────────────────────────────
# Optimizations and the saved portfolio
# ─────────────────────────────────────────────────────────────────────────────

def save_optimization(
    user_id: str,
    goal: str | None,
    monthly_budget: float | None,
    subscriptions: list[dict[str, Any]],
    result: dict[str, Any],
    trace_events: list[dict[str, Any]],
) -> tuple[str, str]:
    optimization_id = uuid.uuid4().hex
    created_at = _utcnow()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO optimizations(id, user_id, created_at, goal, monthly_budget, subscriptions_json,
                                      result_json, trace_json, total_annual_savings, used_fallback)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                optimization_id, user_id, created_at, goal, monthly_budget, json.dumps(subscriptions),
                json.dumps(result), json.dumps(trace_events),
                float(result.get("total_annual_savings", 0.0)), int(bool(result.get("used_fallback"))),
            ),
        )
    return optimization_id, created_at


def _optimization_row(row: sqlite3.Row, full: bool) -> dict[str, Any]:
    subscriptions = json.loads(row["subscriptions_json"])
    item: dict[str, Any] = {
        "id": row["id"],
        "created_at": row["created_at"],
        "goal": row["goal"],
        "monthly_budget": row["monthly_budget"],
        "subscription_count": len(subscriptions),
        "total_annual_savings": row["total_annual_savings"],
        "used_fallback": bool(row["used_fallback"]),
    }
    if full:
        item["subscriptions"] = subscriptions
        item["result"] = json.loads(row["result_json"])
        item["trace_events"] = json.loads(row["trace_json"])
    return item


def list_optimizations(user_id: str, limit: int, offset: int) -> tuple[list[dict[str, Any]], int]:
    with connect() as conn:
        total = conn.execute("SELECT COUNT(*) FROM optimizations WHERE user_id = ?", (user_id,)).fetchone()[0]
        rows = conn.execute(
            "SELECT * FROM optimizations WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (user_id, limit, offset),
        ).fetchall()
    return [_optimization_row(r, full=False) for r in rows], total


def get_optimization(user_id: str, optimization_id: str) -> dict[str, Any] | None:
    """Returns None both when the id doesn't exist and when another user owns it."""
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM optimizations WHERE id = ? AND user_id = ?", (optimization_id, user_id)
        ).fetchone()
    return _optimization_row(row, full=True) if row else None


def delete_optimization(user_id: str, optimization_id: str) -> bool:
    with connect() as conn:
        cur = conn.execute("DELETE FROM optimizations WHERE id = ? AND user_id = ?", (optimization_id, user_id))
    return cur.rowcount > 0


def get_portfolio(user_id: str) -> dict[str, Any]:
    with connect() as conn:
        row = conn.execute(
            "SELECT subscriptions_json, updated_at FROM user_portfolio WHERE user_id = ?", (user_id,)
        ).fetchone()
    if row is None:
        return {"subscriptions": [], "updated_at": None}
    return {"subscriptions": json.loads(row["subscriptions_json"]), "updated_at": row["updated_at"]}


def save_portfolio(user_id: str, subscriptions: list[dict[str, Any]]) -> dict[str, Any]:
    now = _utcnow()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO user_portfolio(user_id, subscriptions_json, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET subscriptions_json = excluded.subscriptions_json,
                                               updated_at = excluded.updated_at
            """,
            (user_id, json.dumps(subscriptions), now),
        )
    return {"subscriptions": subscriptions, "updated_at": now}


# ─────────────────────────────────────────────────────────────────────────────
# Preferences
# ─────────────────────────────────────────────────────────────────────────────

def get_preferences(user_id: str) -> dict[str, Any]:
    with connect() as conn:
        row = conn.execute(
            "SELECT default_priority, updated_at FROM user_preferences WHERE user_id = ?", (user_id,)
        ).fetchone()
    if row is None:
        return {"default_priority": None, "updated_at": None}
    return {"default_priority": row["default_priority"], "updated_at": row["updated_at"]}


def set_preferences(user_id: str, default_priority: str | None) -> dict[str, Any]:
    now = _utcnow()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO user_preferences(user_id, default_priority, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET default_priority = excluded.default_priority,
                                               updated_at = excluded.updated_at
            """,
            (user_id, default_priority, now),
        )
    return {"default_priority": default_priority, "updated_at": now}


# ─────────────────────────────────────────────────────────────────────────────
# Chat transcripts
# ─────────────────────────────────────────────────────────────────────────────

def add_chat_message(
    user_id: str, thread_id: str, role: str, content: str, evidence: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    now = _utcnow()
    with connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO chat_messages(user_id, thread_id, role, content, evidence_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (user_id, thread_id, role, content, json.dumps(evidence or []), now),
        )
    return {"id": cur.lastrowid, "role": role, "content": content, "evidence": evidence or [], "created_at": now}


def chat_messages(user_id: str, thread_id: str, limit: int | None = None) -> list[dict[str, Any]]:
    """Oldest first. With ``limit``, the most recent ``limit`` messages."""
    with connect() as conn:
        if limit is None:
            rows = conn.execute(
                "SELECT * FROM chat_messages WHERE user_id = ? AND thread_id = ? ORDER BY id",
                (user_id, thread_id),
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM (SELECT * FROM chat_messages WHERE user_id = ? AND thread_id = ? "
                "ORDER BY id DESC LIMIT ?) ORDER BY id",
                (user_id, thread_id, limit),
            ).fetchall()
    return [
        {
            "id": r["id"],
            "role": r["role"],
            "content": r["content"],
            "evidence": json.loads(r["evidence_json"]),
            "created_at": r["created_at"],
        }
        for r in rows
    ]


def list_threads(user_id: str, limit: int) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT thread_id,
                   COUNT(*)        AS message_count,
                   MIN(created_at) AS started_at,
                   MAX(created_at) AS updated_at,
                   (SELECT content FROM chat_messages f
                     WHERE f.user_id = m.user_id AND f.thread_id = m.thread_id AND f.role = 'user'
                     ORDER BY f.id LIMIT 1) AS first_message
            FROM chat_messages m
            WHERE user_id = ?
            GROUP BY thread_id
            ORDER BY updated_at DESC
            LIMIT ?
            """,
            (user_id, limit),
        ).fetchall()
    return [dict(r) for r in rows]


def delete_thread(user_id: str, thread_id: str) -> int:
    with connect() as conn:
        cur = conn.execute(
            "DELETE FROM chat_messages WHERE user_id = ? AND thread_id = ?", (user_id, thread_id)
        )
    return cur.rowcount
