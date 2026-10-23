# Subscription Cost Optimizer — FastAPI Backend

REST API that exposes the existing `ta2_subscription_optimizer.py` multi-agent
system over HTTP. The core module is **never modified** — the backend imports
and wraps it.

---

## Requirements

- Python 3.10 – 3.13
- Ollama running locally with `qwen2.5:3b` and `nomic-embed-text` pulled
- All Python dependencies installed via `pip install -r requirements.txt`

---

## Starting the Server

Run from the **ta2/** project root:

```powershell
# From ta2/
uvicorn backend.main:app --reload --port 8000
```

Interactive API docs are at: http://127.0.0.1:8000/docs

---

## Endpoints

| Method | Path | Requires Ollama | Description |
|---|---|---|---|
| GET | `/health` | No | Liveness probe |
| GET | `/health/ollama` | No | Ollama + model availability |
| POST | `/compare` | Yes | Full supervisor comparison workflow |
| POST | `/chat` | Yes | Multi-turn conversational agent |
| GET | `/memory/{user_id}` | No | Read user profile from SQLite |
| POST | `/memory/{user_id}` | No | Write a key-value pair to profile |
| GET | `/memory/{user_id}/history` | No | Full interaction history (capped) |
| GET | `/policy/search?q=...&k=3` | Yes | Semantic search over policy corpus |

---

## POST /compare — Example

```bash
curl -X POST http://127.0.0.1:8000/compare \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "demo_user",
    "question": "Compare the plans after discounts and check the policy.",
    "plan_a": {"name": "Basic", "monthly_price": 499.0, "discount_percent": 10.0},
    "plan_b": {"name": "Premium", "monthly_price": 999.0, "discount_percent": 20.0}
  }'
```

---

## POST /chat — Example

```bash
curl -X POST http://127.0.0.1:8000/chat \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "demo_user",
    "thread_id": "session-abc-001",
    "message": "I prefer monthly billing. Remember that."
  }'
```

> **Note**: Chat memory (`InMemorySaver`) is in-process only. It resets on server restart.
> To persist conversation state across restarts, migrate to `langgraph-checkpoint-sqlite`.

---

## Android / Emulator Access

The Android emulator maps `10.0.2.2` to the host machine's localhost.
Use `http://10.0.2.2:8000` in your Retrofit base URL.
CORS is pre-configured to allow this origin.

---

## Configuration

Override any setting via environment variable or a `.env` file in the `ta2/` root:

| Variable | Default | Description |
|---|---|---|
| `LLM_MODEL` | `qwen2.5:3b` | Ollama model name |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `LLM_TIMEOUT_SECONDS` | `180` | Max wait for LLM response |
| `DEBUG` | `false` | Enable verbose logging |

---

## File Structure

```
ta2/
├── ta2_subscription_optimizer.py   ← UNCHANGED core module
├── requirements.txt
└── backend/
    ├── __init__.py
    ├── main.py          ← FastAPI app + lifespan
    ├── config.py        ← pydantic-settings
    ├── dependencies.py  ← shared state + FastAPI deps
    ├── models.py        ← Pydantic request/response schemas
    ├── README.md        ← this file
    └── routers/
        ├── health.py    ← GET /health, GET /health/ollama
        ├── compare.py   ← POST /compare
        ├── chat.py      ← POST /chat
        ├── memory.py    ← GET/POST /memory/{user_id}
        └── policy.py    ← GET /policy/search
```
