"""
GET  /memory/{user_id}           — read saved profile from SQLite
POST /memory/{user_id}           — write a single allowed key-value pair
GET  /memory/{user_id}/history   — read full user_memory rows (all namespaces)

All three require a session token for the same user_id (403 otherwise).
Only the keys listed in models._ALLOWED_MEMORY_KEYS may be written via the API.
Raw SQL access and arbitrary namespace writes are not exposed.
"""
from __future__ import annotations

import logging
from typing import Any

import sqlite3

from fastapi import APIRouter, Depends, HTTPException, Path, Query

from backend.config import settings
from backend.dependencies import current_user, ensure_same_user
from backend.models import MemoryResponse, MemoryWriteRequest

import ta2_subscription_optimizer as _app

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/memory", tags=["Memory"])

# maximum rows returned in history to prevent bulk data dumps
_MAX_HISTORY_ROWS = 50


@router.get(
    "/{user_id}",
    response_model=MemoryResponse,
    summary="Read saved profile for a user",
)
async def get_memory(
    user_id: str = Path(..., min_length=1, max_length=64, pattern=r"^[a-zA-Z0-9_\-]+$"),
    session_user: str = Depends(current_user),
) -> MemoryResponse:
    """
    Returns the user's *profile* namespace from the SQLite long-term memory.
    Empty dict if no data has been saved for this user.
    """
    ensure_same_user(session_user, user_id)
    profile = _app.recall(user_id, "profile")
    return MemoryResponse(user_id=user_id, profile=profile)


@router.post(
    "/{user_id}",
    response_model=MemoryResponse,
    summary="Write a single allowed key-value pair to user profile",
)
async def set_memory(
    body: MemoryWriteRequest,
    user_id: str = Path(..., min_length=1, max_length=64, pattern=r"^[a-zA-Z0-9_\-]+$"),
    session_user: str = Depends(current_user),
) -> MemoryResponse:
    """
    Writes a key-value pair to the user's *profile* namespace.
    Only explicitly allowed keys are accepted (see models._ALLOWED_MEMORY_KEYS).
    """
    ensure_same_user(session_user, user_id)
    try:
        _app.remember(user_id, "profile", body.key, body.value)
    except Exception as exc:
        logger.error("Memory write error for user %s: %s", user_id, exc)
        raise HTTPException(status_code=500, detail={"error": "Memory write failed", "message": str(exc)})

    profile = _app.recall(user_id, "profile")
    return MemoryResponse(user_id=user_id, profile=profile)


@router.get(
    "/{user_id}/history",
    summary="Read recent interaction history for a user (all namespaces)",
)
async def get_history(
    user_id: str = Path(..., min_length=1, max_length=64, pattern=r"^[a-zA-Z0-9_\-]+$"),
    session_user: str = Depends(current_user),
    limit: int = Query(default=20, ge=1, le=_MAX_HISTORY_ROWS),
) -> dict[str, Any]:
    """
    Returns all stored key-value rows for the user across all namespaces,
    ordered by most recently updated, capped at *limit* rows (max 50).
    """
    ensure_same_user(session_user, user_id)
    try:
        with sqlite3.connect(settings.db_path) as conn:
            rows = conn.execute(
                """
                SELECT namespace, memory_key, memory_value, updated_at
                FROM user_memory
                WHERE user_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """,
                (user_id, limit),
            ).fetchall()
        history = [
            {"namespace": r[0], "key": r[1], "value": r[2], "updated_at": r[3]}
            for r in rows
        ]
    except sqlite3.Error as exc:
        raise HTTPException(
            status_code=500,
            detail={"error": "Database read error", "message": str(exc)},
        )

    return {"user_id": user_id, "history": history, "count": len(history)}
