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

| Method | Path | Auth | Requires Ollama | Description |
|---|---|---|---|---|
| GET | `/health` | – | No | Liveness probe |
| GET | `/health/ollama` | – | No | Ollama + model availability |
| POST | `/auth/session` | – | No | Claim a user ID / get a bearer token |
| POST | `/compare` | Bearer | Yes | Comparison; saved to history with its trace |
| GET | `/history` | Bearer | No | Caller's saved comparisons (newest first, `limit` ≤ 50) |
| GET | `/history/{id}` | Bearer | No | One saved comparison (plans, result, trace) |
| GET | `/history/{id}/trace` | Bearer | No | Structured execution trace |
| DELETE | `/history/{id}`, `/history` | Bearer | No | Delete one / all of the caller's comparisons |
| GET / PUT | `/preferences` | Bearer | No | Default comparison priority |
| POST | `/chat` | Bearer | Yes | Conversational agent, with policy evidence |
| GET | `/conversations` | Bearer | No | Caller's saved chat threads |
| GET / DELETE | `/conversations/{thread_id}` | Bearer | No | Transcript / delete it |
| GET / POST | `/memory/{user_id}` | Bearer (same user) | No | Profile key-values (allow-listed keys) |
| GET | `/memory/{user_id}/history` | Bearer (same user) | No | Raw profile rows (capped) |
| GET | `/policy/search?q=...&k=3` | – | Yes | Raw similarity search |
| POST | `/policy/ask` | – | Yes | Answer from the policy only, with evidence + citation check |
| GET | `/policy/status` | – | No | Index vs. policy file (read-only) |

---

## Sessions and access control

`POST /auth/session {"user_id": "alice"}` returns a bearer token. The first client to ask for a
user ID claims it; later requests for that ID get **409** unless they send one of its current
tokens (`Authorization: Bearer …`), which issues a fresh one. Only SHA-256 hashes of tokens are
stored (`api_sessions`).

Every user-scoped endpoint takes the user from the token. A body or path `user_id` that differs
gives **403**; a missing or unknown token gives **401**. Another user's comparison or thread
answers **404**, exactly like a missing one. LangGraph chat threads are keyed
`"<user_id>::<thread_id>"`, so users who pick the same thread id never share memory.

This trust-on-first-use scheme suits a single-host prototype. For production, serve the API over
HTTPS and replace it with real accounts (e.g. OAuth/OIDC). Never expose Ollama's port.

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/auth/session \
  -H "Content-Type: application/json" -d '{"user_id":"demo_user"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
```

---

## POST /compare — Example

```bash
curl -X POST http://127.0.0.1:8000/compare \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{
    "user_id": "demo_user",
    "question": "Compare the plans after discounts and check the policy.",
    "plan_a": {"name": "Basic", "monthly_price": 499.0, "discount_percent": 10.0},
    "plan_b": {"name": "Premium", "monthly_price": 999.0, "discount_percent": 20.0}
  }'
```

When `priority` is omitted, the user's saved `default_priority` (if any) is used.

The response adds, to the earlier fields:

- `policy_evidence` — chunks ChromaDB actually returned with relevance ≥ `POLICY_MIN_RELEVANCE`
  (default 0.30), each with `matches_current_policy` and `cited`.
- `cited_chunks` / `unsupported_citations` — `[Chunk N]` citations in the explanation that were /
  were not among the retrieved chunks.
- `trace_events` — see below. `trace` (strings) is kept for compatibility.
- `comparison_id`, `created_at` — the saved record; `comparison_id` is null if saving failed.

## Execution traces

Each event: `step`, `stage`, `kind`, `name`, `status`, `detail`, `duration_ms`, `data`.
`kind` is what the step really was: `check` (guardrail), `function` (code the backend called
directly — the comparison engine is *not* an agent tool), `retrieval`, `llm`, `tool_call` (only
if the model itself called a tool; the review agent has none), `validation`, `persistence`.
Validation steps: `engine_verification`, `citation_check`, `figure_check` (rupee amounts in the
explanation that match no calculated figure; a heuristic that does not check feature claims).
Traces never contain prompts, model reasoning (`<think>` blocks are stripped from replies) or
secrets, and are served only from the database row of a comparison the caller owns — files in
`traces/` are still written but never served.

## Policy questions

`POST /policy/ask {"question": "...", "k": 3}`. When no chunk reaches the relevance threshold,
the model is not called and `answered_from_policy` is false. `GET /policy/status` compares the
vector index with `data/subscription_policy.txt`; the shipped index holds older text for chunks
3, 8 and 11. The backend never re-embeds automatically. To rebuild the index from the current
file, stop the server, move `chroma_db/` aside (keep it as a backup) and start again;
`get_vectorstore()` re-embeds the 11 paragraphs into an empty collection.

---

## Database migrations

On startup the backend runs `backend/db.py:migrate()`. Versions are tracked with
`PRAGMA user_version`. Migrations only add tables and indexes (`api_sessions`, `comparisons`,
`user_preferences`, `chat_messages`); the core module's `user_memory` table is never altered. Before
the first migration of a database that already holds data, a full copy is written to
`subscription_memory.sqlite.pre-migration-v<N>.bak` (git-ignored). Each migration runs in one
transaction. A database newer than the code is refused rather than modified.

## Conversation memory

The core module's `conversation_agent` keeps working memory in `InMemorySaver` (lost on
restart). Every message and reply is also saved to `chat_messages`. If a thread's working memory
is gone, the last `CHAT_RESTORE_MESSAGES` (default 20) saved messages are replayed with the next
message, and the response sets `restored_history: true`.

---

## Tests

```powershell
python -m pytest tests/test_persistence.py tests/test_api_access.py tests/test_rag.py -q
python tests/test_comparison_engine.py
python tests/test_live_api.py        # needs the server running with Ollama
```

The API tests use a temporary database and fakes for Ollama, the agents and ChromaDB. The
`test_rag.py` live tests read the real ChromaDB collection (read-only) and skip without Ollama.

---

## Android / Emulator Access

The Android emulator maps `10.0.2.2` to the host machine's localhost.
Use `http://10.0.2.2:8000` in your Retrofit base URL.
CORS is pre-configured to allow this origin. The app obtains and stores its session token itself.

---

## Configuration

Override any setting via environment variable or a `.env` file in the `ta2/` root:

| Variable | Default | Description |
|---|---|---|
| `LLM_MODEL` | `qwen2.5:3b` | Ollama model name |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `LLM_TIMEOUT_SECONDS` | `180` | Max wait for LLM response |
| `POLICY_MIN_RELEVANCE` | `0.30` | Minimum relevance (0-1) for a chunk to count as evidence |
| `CHAT_RESTORE_MESSAGES` | `20` | Saved messages replayed to rebuild a lost chat thread |
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
    ├── db.py            ← migrations + sessions, history, preferences, chat storage
    ├── policy_rag.py    ← evidence retrieval, citation/figure checks, index status
    ├── tracing.py       ← structured execution traces
    ├── comparison_engine.py ← deterministic calculations
    ├── README.md        ← this file
    └── routers/
        ├── health.py      ← GET /health, GET /health/ollama
        ├── auth.py        ← POST /auth/session
        ├── compare.py     ← POST /compare
        ├── history.py     ← /history, /history/{id}, /history/{id}/trace
        ├── preferences.py ← GET/PUT /preferences
        ├── chat.py        ← POST /chat, /conversations
        ├── memory.py      ← GET/POST /memory/{user_id}
        └── policy.py      ← /policy/search, /policy/ask, /policy/status
```
