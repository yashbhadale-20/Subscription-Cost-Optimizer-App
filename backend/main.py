"""
FastAPI application entry point.

Run from the ta2/ directory with:
    uvicorn backend.main:app --reload --port 8000

Or from anywhere by supplying the full module path.
"""
from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from backend.config import settings
from backend.dependencies import startup_state, shutdown_state
from backend.models import ErrorResponse
from backend.routers import auth, chat, compare, health, history, memory, optimize, policy, preferences

# ── logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.DEBUG if settings.debug else logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger(__name__)

# Silence noisy third-party loggers unless debug mode is on
if not settings.debug:
    for _noisy in ("httpx", "httpcore", "chromadb", "langchain", "langgraph"):
        logging.getLogger(_noisy).setLevel(logging.WARNING)


# ─────────────────────────────────────────────────────────────────────────────
# Lifespan
# ─────────────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("=== Subscription Cost Optimizer Backend starting ===")
    await startup_state()
    logger.info("=== Startup complete. Serving requests. ===")
    yield
    await shutdown_state()
    logger.info("=== Backend stopped. ===")


# ─────────────────────────────────────────────────────────────────────────────
# Application
# ─────────────────────────────────────────────────────────────────────────────

app = FastAPI(
    title=settings.app_title,
    version=settings.app_version,
    description=(
        "REST API for the Subscription Cost Optimizer (TA2). "
        "Wraps the existing LangGraph multi-agent system, ChromaDB RAG, "
        "and SQLite long-term memory without modifying the core module."
    ),
    lifespan=lifespan,
    # Disable automatic 422 detail leakage in production
    docs_url="/docs" if settings.debug else "/docs",
    redoc_url="/redoc",
)

# ── CORS ─────────────────────────────────────────────────────────────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization"],
)


# ─────────────────────────────────────────────────────────────────────────────
# Global exception handlers
# ─────────────────────────────────────────────────────────────────────────────

@app.exception_handler(RequestValidationError)
async def request_validation_handler(request: Request, exc: RequestValidationError):
    # Same shape as FastAPI's default ({"detail": [{"loc", "msg", "type"}]}) minus the echoed
    # "input"/"ctx": echoing NaN or Infinity made the error response itself fail to serialise
    # (a 500 instead of a 422), and echoing bodies back is unnecessary anyway.
    errors = [
        {"loc": list(err.get("loc", ())), "msg": err.get("msg", ""), "type": err.get("type", "")}
        for err in exc.errors()
    ]
    return JSONResponse(status_code=422, content={"detail": errors})


@app.exception_handler(ValidationError)
async def pydantic_validation_handler(request: Request, exc: ValidationError):
    # Request bodies are validated by FastAPI (RequestValidationError → 422 with field errors).
    # A pydantic ValidationError reaching this handler was raised while building a response or
    # internal model, so it is a server fault: log the details, return nothing internal.
    logger.error("Internal validation error on %s: %s", request.url.path, exc)
    return JSONResponse(
        status_code=500,
        content=ErrorResponse(
            error="Internal server error",
            detail="The server produced an invalid result. Check server logs.",
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled exception on %s: %s", request.url.path, exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content=ErrorResponse(
            error="Internal server error",
            detail="An unexpected error occurred. Check server logs.",
        ).model_dump(),
    )


# ─────────────────────────────────────────────────────────────────────────────
# Routers
# ─────────────────────────────────────────────────────────────────────────────

app.include_router(health.router)
app.include_router(compare.router)
app.include_router(chat.router)
app.include_router(memory.router)
app.include_router(policy.router)
app.include_router(auth.router)
app.include_router(history.router)
app.include_router(preferences.router)
app.include_router(optimize.router)


# ─────────────────────────────────────────────────────────────────────────────
# Root
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/", include_in_schema=False)
async def root():
    return {
        "name": settings.app_title,
        "version": settings.app_version,
        "docs": "/docs",
        "endpoints": [
            "GET    /health",
            "GET    /health/ollama",
            "POST   /auth/session",
            "POST   /compare                      (bearer token)",
            "POST   /chat                         (bearer token)",
            "GET    /conversations                (bearer token)",
            "GET    /conversations/{thread_id}    (bearer token)",
            "DELETE /conversations/{thread_id}    (bearer token)",
            "GET    /history                      (bearer token)",
            "GET    /history/{id}                 (bearer token)",
            "GET    /history/{id}/trace           (bearer token)",
            "DELETE /history/{id}                 (bearer token)",
            "DELETE /history                      (bearer token)",
            "POST   /optimize                     (bearer token)",
            "GET    /optimizations                (bearer token)",
            "GET    /optimizations/{id}           (bearer token)",
            "GET    /optimizations/{id}/trace     (bearer token)",
            "DELETE /optimizations/{id}           (bearer token)",
            "GET    /portfolio                    (bearer token)",
            "PUT    /portfolio                    (bearer token)",
            "GET    /preferences                  (bearer token)",
            "PUT    /preferences                  (bearer token)",
            "GET    /memory/{user_id}             (bearer token)",
            "POST   /memory/{user_id}             (bearer token)",
            "GET    /memory/{user_id}/history     (bearer token)",
            "GET    /policy/search?q=...&k=3",
            "POST   /policy/ask",
            "GET    /policy/status",
        ],
    }
