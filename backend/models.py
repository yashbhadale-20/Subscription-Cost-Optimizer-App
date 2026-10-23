"""
Pydantic request / response models for every endpoint.
No business logic lives here — only data shapes and validators.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator


# ─────────────────────────────────────────────────────────────────────────────
# Shared primitives
# ─────────────────────────────────────────────────────────────────────────────

def _utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


# ─────────────────────────────────────────────────────────────────────────────
# /health
# ─────────────────────────────────────────────────────────────────────────────

class HealthResponse(BaseModel):
    status: str = "ok"
    version: str
    timestamp: str = Field(default_factory=_utcnow)


class OllamaHealthResponse(BaseModel):
    ollama_ok: bool
    message: str
    llm_model: str
    embedding_model: str
    timestamp: str = Field(default_factory=_utcnow)


# ─────────────────────────────────────────────────────────────────────────────
# /compare
# ─────────────────────────────────────────────────────────────────────────────

class PlanInput(BaseModel):
    name: str = Field(default="Plan", min_length=1, max_length=120)
    monthly_price: float = Field(..., gt=0, le=1_000_000.0,
                                 description="Monthly price in INR, must be > 0")
    discount_percent: float = Field(default=0.0, ge=0.0, le=100.0,
                                    description="Percentage discount, 0–100")

    @field_validator("name")
    @classmethod
    def name_no_injections(cls, v: str) -> str:
        blocked = ["ignore all previous", "reveal", "exfiltrate", "delete the database"]
        low = v.lower()
        for phrase in blocked:
            if phrase in low:
                raise ValueError(f"Plan name contains disallowed content: {phrase!r}")
        return v.strip()


class CompareRequest(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=64,
                         pattern=r"^[a-zA-Z0-9_\-]+$",
                         description="Alphanumeric user identifier")
    question: str = Field(..., min_length=1, max_length=1_000,
                          description="Natural-language question about the comparison")
    plan_a: PlanInput
    plan_b: PlanInput

    @field_validator("question")
    @classmethod
    def question_not_adversarial(cls, v: str) -> str:
        # Mirror the existing guardrail; the supervisor re-checks anyway
        blocked = [
            "ignore all previous instructions",
            "reveal your system prompt",
            "delete the database",
            "exfiltrate",
            "steal credentials",
        ]
        low = v.lower()
        for phrase in blocked:
            if phrase in low:
                raise ValueError(f"Question contains disallowed content: {phrase!r}")
        return v.strip()


class CompareResponse(BaseModel):
    answer: str
    calc_result: str
    policy_result: str
    trace: list[str]
    elapsed_seconds: float
    guardrail_passed: bool
    plan_a_name: str
    plan_b_name: str


# ─────────────────────────────────────────────────────────────────────────────
# /chat
# ─────────────────────────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=64,
                         pattern=r"^[a-zA-Z0-9_\-]+$")
    thread_id: str = Field(..., min_length=1, max_length=128,
                           pattern=r"^[a-zA-Z0-9_\-]+$",
                           description="Conversation thread identifier for memory isolation")
    message: str = Field(..., min_length=1, max_length=1_000)

    @field_validator("message")
    @classmethod
    def message_safe(cls, v: str) -> str:
        blocked = [
            "ignore all previous instructions",
            "reveal your system prompt",
            "delete the database",
            "exfiltrate",
        ]
        low = v.lower()
        for phrase in blocked:
            if phrase in low:
                raise ValueError(f"Message contains disallowed content: {phrase!r}")
        return v.strip()


class ChatResponse(BaseModel):
    reply: str
    thread_id: str
    user_id: str
    timestamp: str = Field(default_factory=_utcnow)


# ─────────────────────────────────────────────────────────────────────────────
# /memory
# ─────────────────────────────────────────────────────────────────────────────

# Only these keys may be written via the API — prevents arbitrary storage
_ALLOWED_MEMORY_KEYS: set[str] = {
    "last_plan_a_monthly",
    "last_plan_b_monthly",
    "last_plan_a_discount",
    "last_plan_b_discount",
    "currency",
    "preferred_plan_name",
    "notes",
}


class MemoryWriteRequest(BaseModel):
    key: str = Field(..., description=f"One of: {sorted(_ALLOWED_MEMORY_KEYS)}")
    value: str = Field(..., min_length=0, max_length=256)

    @field_validator("key")
    @classmethod
    def key_allowed(cls, v: str) -> str:
        if v not in _ALLOWED_MEMORY_KEYS:
            raise ValueError(
                f"Key {v!r} is not allowed. Permitted keys: {sorted(_ALLOWED_MEMORY_KEYS)}"
            )
        return v


class MemoryResponse(BaseModel):
    user_id: str
    profile: dict[str, Any]
    timestamp: str = Field(default_factory=_utcnow)


# ─────────────────────────────────────────────────────────────────────────────
# /policy/search
# ─────────────────────────────────────────────────────────────────────────────

class PolicySearchResponse(BaseModel):
    query: str
    k: int
    chunks: list[str]
    chunk_count: int
    timestamp: str = Field(default_factory=_utcnow)


# ─────────────────────────────────────────────────────────────────────────────
# Error envelope (used by exception handlers)
# ─────────────────────────────────────────────────────────────────────────────

class ErrorResponse(BaseModel):
    error: str
    detail: str | None = None
    timestamp: str = Field(default_factory=_utcnow)
