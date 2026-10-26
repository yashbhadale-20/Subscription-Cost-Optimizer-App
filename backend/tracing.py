"""
Structured execution traces.

A trace records what actually ran: each stage, the function or agent that ran
it, its outcome, and how long it took. It never stores prompts, model
reasoning, or secrets, only short outcome descriptions.

``kind`` describes what the step really was:
  check        input guardrail
  function     plain Python called directly by the backend (not an agent tool)
  retrieval    ChromaDB similarity search
  llm          a LangChain agent/model invocation
  tool_call    a tool call the model itself made (from the agent's messages)
  validation   a post-hoc check on results
  persistence  a database write
"""
from __future__ import annotations

import time
from contextlib import contextmanager
from typing import Any, Iterator

STATUSES = {"ok", "passed", "failed", "warning", "error", "skipped"}


class TraceRecorder:
    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []
        self._started = time.perf_counter()

    def add(
        self,
        stage: str,
        kind: str,
        name: str,
        status: str,
        detail: str,
        duration_ms: float | None = None,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        assert status in STATUSES, status
        event = {
            "step": len(self.events) + 1,
            "stage": stage,
            "kind": kind,
            "name": name,
            "status": status,
            "detail": detail,
            "duration_ms": round(duration_ms, 1) if duration_ms is not None else None,
            "data": data or {},
        }
        self.events.append(event)
        return event

    @contextmanager
    def timed(self) -> Iterator[dict[str, float]]:
        """``with rec.timed() as t: ...`` then ``t["ms"]``."""
        box = {"ms": 0.0}
        start = time.perf_counter()
        try:
            yield box
        finally:
            box["ms"] = (time.perf_counter() - start) * 1000

    @property
    def elapsed_seconds(self) -> float:
        return time.perf_counter() - self._started

    def as_lines(self) -> list[str]:
        """Plain-text form kept for the existing ``trace: list[str]`` field and trace files."""
        return [f"{e['stage']} [{e['status']}] {e['name']}: {e['detail']}" for e in self.events]


def model_tool_calls(messages: list[Any]) -> list[dict[str, Any]]:
    """Tool calls the model actually issued, read from a LangChain agent's message list."""
    calls: list[dict[str, Any]] = []
    results: dict[str, str] = {}
    for message in messages:
        if getattr(message, "type", "") == "tool":
            results[getattr(message, "tool_call_id", "")] = str(message.content)[:300]
    for message in messages:
        for call in getattr(message, "tool_calls", None) or []:
            calls.append(
                {
                    "name": call.get("name"),
                    "args": call.get("args"),
                    "result": results.get(call.get("id", "")),
                }
            )
    return calls
