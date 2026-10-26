"""
GET /preferences — the caller's preferences
PUT /preferences — update them

``default_priority`` is used by POST /compare when a request doesn't name a
priority, and clients use it to preselect the priority on the compare form.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends

from backend import db
from backend.dependencies import current_user
from backend.models import PreferencesRequest, PreferencesResponse

router = APIRouter(prefix="/preferences", tags=["Preferences"])


@router.get("", response_model=PreferencesResponse, summary="The caller's preferences")
async def get_preferences(user_id: str = Depends(current_user)) -> PreferencesResponse:
    prefs = await asyncio.to_thread(db.get_preferences, user_id)
    return PreferencesResponse(user_id=user_id, **prefs)


@router.put("", response_model=PreferencesResponse, summary="Update the caller's preferences")
async def put_preferences(req: PreferencesRequest, user_id: str = Depends(current_user)) -> PreferencesResponse:
    prefs = await asyncio.to_thread(db.set_preferences, user_id, req.default_priority)
    return PreferencesResponse(user_id=user_id, **prefs)
