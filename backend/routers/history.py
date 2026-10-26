"""
GET    /history                 — the caller's saved comparisons, newest first
GET    /history/{id}            — one saved comparison
GET    /history/{id}/trace      — its structured execution trace
DELETE /history/{id}            — delete one
DELETE /history                 — delete all of the caller's comparisons

Scoped to the session's user. Another user's comparison id answers 404, the
same as an id that doesn't exist, so ids can't be probed.
Traces come only from the database row they were saved with; trace files on
disk are never read or served.
"""
from __future__ import annotations

import asyncio
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response

from backend import db
from backend.dependencies import current_user
from backend.models import HistoryListResponse, SavedComparisonResponse, TraceResponse

router = APIRouter(prefix="/history", tags=["History"])

_ID = Path(..., min_length=32, max_length=32, pattern=r"^[0-9a-f]{32}$")


def _not_found() -> HTTPException:
    return HTTPException(status_code=404, detail={"error": "Not found", "message": "No such saved comparison."})


def _with_ids(item: dict[str, Any]) -> dict[str, Any]:
    # Saved results predate their own id; fill it in so clients get one shape everywhere.
    item["result"] = {**item["result"], "comparison_id": item["id"], "created_at": item["created_at"]}
    if "trace_events" in item:
        item["result"]["trace_events"] = item["trace_events"]
    return item


@router.get("", response_model=HistoryListResponse, summary="The caller's saved comparisons")
async def list_history(
    limit: int = Query(default=20, ge=1, le=50),
    offset: int = Query(default=0, ge=0),
    user_id: str = Depends(current_user),
) -> HistoryListResponse:
    items, total = await asyncio.to_thread(db.list_comparisons, user_id, limit, offset)
    return HistoryListResponse(items=[_with_ids(i) for i in items], total=total, limit=limit, offset=offset)


@router.get("/{comparison_id}", response_model=SavedComparisonResponse, summary="One saved comparison")
async def get_history_item(comparison_id: str = _ID, user_id: str = Depends(current_user)) -> SavedComparisonResponse:
    item = await asyncio.to_thread(db.get_comparison, user_id, comparison_id)
    if item is None:
        raise _not_found()
    return SavedComparisonResponse(**_with_ids(item))


@router.get("/{comparison_id}/trace", response_model=TraceResponse, summary="Execution trace of a saved comparison")
async def get_trace(comparison_id: str = _ID, user_id: str = Depends(current_user)) -> TraceResponse:
    item = await asyncio.to_thread(db.get_comparison, user_id, comparison_id)
    if item is None:
        raise _not_found()
    return TraceResponse(comparison_id=item["id"], created_at=item["created_at"], events=item["trace_events"])


@router.delete("/{comparison_id}", status_code=204, response_class=Response, response_model=None, summary="Delete one saved comparison")
async def delete_history_item(comparison_id: str = _ID, user_id: str = Depends(current_user)) -> None:
    if not await asyncio.to_thread(db.delete_comparison, user_id, comparison_id):
        raise _not_found()


@router.delete("", summary="Delete all of the caller's saved comparisons")
async def clear_history(user_id: str = Depends(current_user)) -> dict[str, int]:
    return {"deleted": await asyncio.to_thread(db.delete_all_comparisons, user_id)}
