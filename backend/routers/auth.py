"""
POST /auth/session — obtain a bearer token for a user ID

A local, trust-on-first-use scheme suited to this single-host prototype: the
first client to ask for a user ID claims it and receives a token. Anyone else
asking for that user ID later is refused unless they present one of its
current tokens (which re-issues a fresh token for the same user).

Only SHA-256 hashes of tokens are stored. Production deployments should put
the API behind HTTPS and replace this with real accounts (e.g. OAuth/OIDC).
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Header, HTTPException

from backend import db
from backend.models import SessionRequest, SessionResponse

router = APIRouter(prefix="/auth", tags=["Auth"])


@router.post("/session", response_model=SessionResponse, summary="Claim a user ID or refresh its token")
async def create_session(
    req: SessionRequest,
    authorization: str | None = Header(default=None),
) -> SessionResponse:
    existing = authorization[7:].strip() if authorization and authorization.lower().startswith("bearer ") else None
    try:
        token = await asyncio.to_thread(db.create_session, req.user_id, existing)
    except db.UserAlreadyClaimed:
        raise HTTPException(
            status_code=409,
            detail={
                "error": "User ID already registered",
                "message": f"'{req.user_id}' is registered to another device. "
                           "Use that device, or choose a different user ID.",
            },
        )
    return SessionResponse(user_id=req.user_id, token=token)
