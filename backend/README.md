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
| POST | `/optimize` | Bearer | No (degrades) | Optimization plan for all of a user's subscriptions |
| GET | `/optimizations` | Bearer | No | Caller's saved optimizations (newest first, `limit` ≤ 50) |
| GET / DELETE | `/optimizations/{id}` | Bearer | No | One saved optimization / delete it |
| GET | `/optimizations/{id}/trace` | Bearer | No | Its structured execution trace |
| GET / PUT | `/portfolio` | Bearer | No | The caller's saved subscription list (0–30) |

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

## POST /optimize — Subscription Optimization Agent

Looks at **all** of a user's subscriptions and returns a plan: which to cancel, downgrade, switch to
annual billing, review before renewal, or keep, with monthly and annual savings.

How the work is split:

1. `backend/optimizer_engine.py` (pure Python, no LLM) normalises every price to a monthly and
   recurring annual cost and builds **candidate actions** with their savings: unused subscriptions
   (≤ 2 uses a month or unused for 30+ days; must-keep and shared ones are skipped), overlaps in the
   same category (keeps the must-keep one, else the best cost per use, else the cheapest), cheaper
   tiers, annual billing that costs less than 12 monthly payments, and renewals in the next 14 days.
2. `backend/optimizer_agent.py` runs a LangChain `create_agent` on `qwen2.5:3b` (temperature 0). Its
   tools take **ids, never prices** and read the portfolio from a request-scoped context:
   `list_subscriptions`, `get_candidates`, `estimate_plan(action_ids)`, `check_budget(action_ids)`,
   `search_policy(query)`. It answers with JSON: `chosen_action_ids`, `priority_order`, `summary`,
   `per_action_reasons`.
3. The supervisor (`run_optimizer`) drops ids that aren't candidates and conflicting actions
   (e.g. cancel + downgrade on one subscription), re-adds every total (`verify`), checks `[Chunk N]`
   citations and every ₹ amount in the summary against the engine's figures. A summary with an
   amount the engine didn't produce is replaced with one built from engine numbers; the engine's
   totals are always appended to the agent's summary.
4. If Ollama is down, the agent errors or times out (`OPTIMIZER_TIMEOUT_SECONDS`), or its reply is
   unusable, the response is the deterministic plan with `used_fallback: true` and
   `fallback_reason`; the trace records why. It never answers 503 for that.

```bash
curl -X POST http://127.0.0.1:8000/optimize \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{
    "user_id": "demo_user",
    "goal": "Keep Netflix, cut what I don't use",
    "monthly_budget": 1200,
    "save_portfolio": true,
    "subscriptions": [
      {"id": "netflix", "name": "Netflix", "category": "video_streaming", "price": 649, "uses_per_month": 20, "must_keep": true},
      {"id": "prime", "name": "Prime Video", "category": "video_streaming", "price": 299, "uses_per_month": 4},
      {"id": "spotify", "name": "Spotify", "category": "music", "price": 119, "uses_per_month": 1},
      {"id": "google_one", "name": "Google One", "category": "cloud_storage", "price": 130, "annual_price": 1300},
      {"id": "youtube", "name": "YouTube Premium", "category": "music", "price": 149, "uses_per_month": 25,
       "cheaper_tier": {"name": "Premium Lite", "price": 89, "features_lost": ["Background play"]}},
      {"id": "gym", "name": "Gym App", "category": "fitness", "price": 100, "intro_months": 3,
       "discount_percent": 50, "post_intro_price": 199, "renewal_date": "2026-11-15"}
    ]
  }'
```

Subscription fields: `id` (`[A-Za-z0-9_-]`, unique), `name`, `provider`, `category` (`video_streaming`,
`music`, `cloud_storage`, `news`, `gaming`, `productivity`, `fitness`, `education`, `other`), `price`
(per `billing_cycle`), `billing_cycle`, `discount_percent`, `intro_months` / `post_intro_price`,
`uses_per_month`, `last_used_days_ago`, `renewal_date` (ISO), `cheaper_tier` `{name, price,
billing_cycle, features_lost[]}`, `annual_price` (monthly plans only), `shared_with`, `must_keep`.
No card, bank or password data is accepted.

Response (abridged):

```json
{
  "current_monthly_total": 1545.0,
  "current_annual_total": 18540.0,
  "actions": [
    {"action_id": "cancel:prime", "subscription_id": "prime", "name": "Prime Video", "action": "cancel",
     "reason": "Prime Video overlaps with Netflix, which is marked must-keep; cancelling saves ₹299.00 a month.",
     "monthly_savings": 299.0, "annual_savings": 3588.0, "policy_chunks": [13]}
  ],
  "optimized_monthly_total": 1127.0,
  "optimized_annual_total": 13524.0,
  "total_monthly_savings": 418.0,
  "total_annual_savings": 5016.0,
  "percentage_savings": 27.1,
  "budget": {"target": 1200.0, "achievable": true, "met": true, "gap": 0.0},
  "renewal_alerts": [{"subscription_id": "gym", "kind": "intro_ending", "renewal_date": "2026-11-15",
                      "current_monthly": 50.0, "after_monthly": 199.0, "price_jump_monthly": 149.0, "...": "..."}],
  "ai_summary": "...",
  "policy_evidence": [], "cited_chunks": [], "unsupported_citations": [],
  "assumptions": ["Prices are in INR and exclude taxes and fees.", "..."],
  "missing_info": ["No usage given for Google One, so they can't be checked for being unused."],
  "verification_passed": true, "verification_notes": [],
  "used_fallback": false, "fallback_reason": null, "guardrail_passed": true,
  "trace_events": [], "optimization_id": "…", "created_at": "…", "elapsed_seconds": 14.2
}
```

Totals and savings use the **recurring** price (after any introductory offer ends); renewal alerts
show the jump from today's price. `budget.achievable` says whether any combination of candidates
reaches the target; `met` and `gap` describe this plan. Without a budget, the fallback plan is every
unused/overlap cancel plus every annual switch that saves money; with one, a greedy fit by annual
savings. A trace file `traces/optimize_<user>_<ts>_<rand>.txt` is written like the compare one.

To try it without the server: `python optimize_demo.py [--budget 1200]`.

## Execution traces

Each event: `step`, `stage`, `kind`, `name`, `status`, `detail`, `duration_ms`, `data`.
`kind` is what the step really was: `check` (guardrail), `function` (code the backend called
directly — the comparison engine is *not* an agent tool), `retrieval`, `llm`, `tool_call` (only
if the model itself called a tool; the review agent has none), `validation`, `persistence`.
Validation steps: `engine_verification`, `citation_check`, `figure_check` (rupee amounts in the
explanation that match no calculated figure; a heuristic that does not check feature claims).
`/optimize` adds `ollama_check` (check), `output_parse`, `action_id_check`, `conflict_check`
(validation), `fallback_plan` (function), and one `tool_call` event per tool the agent called.
Traces never contain prompts, model reasoning (`<think>` blocks are stripped from replies) or
secrets, and are served only from the database row of a comparison the caller owns — files in
`traces/` are still written but never served.

## Policy questions

`POST /policy/ask {"question": "...", "k": 3}`. When no chunk reaches the relevance threshold,
the model is not called and `answered_from_policy` is false. `GET /policy/status` compares the
vector index with `data/subscription_policy.txt`; the shipped index holds older text for chunks
3, 8 and 11. The backend never re-embeds automatically. To rebuild the index from the current
file, stop the server, move `chroma_db/` aside (keep it as a backup) and start again;
`get_vectorstore()` re-embeds the paragraphs into an empty collection. The policy file has 14
paragraphs: chunks 12–14 (unused subscriptions, overlapping services, switching to annual billing)
were appended for the optimizer and are only searchable after `python -m backend.reindex_policy`.

---

## Database migrations

On startup the backend runs `backend/db.py:migrate()`. Versions are tracked with
`PRAGMA user_version`. Migrations only add tables and indexes (v2 adds `optimizations` and
`user_portfolio`, indexed on `user_id`; v1: `api_sessions`, `comparisons`,
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
pip install -r requirements-dev.txt
python -m pytest tests -q --ignore=tests/test_live_api.py
python tests/test_comparison_engine.py   # script-style checks
python tests/test_live_api.py            # needs a running server and Ollama
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
| `LLM_MODEL` | `qwen2.5:3b` | Fixed by the core module; another value is ignored with a startup warning |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Fixed by the core module, as above |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Fixed by the core module, as above |
| `DB_PATH` | `subscription_memory.sqlite` | SQLite file for the backend and the core module's `remember()`/`recall()` |
| `TRACE_DIR` | `traces/` | Where per-request trace files are written |
| `LLM_TIMEOUT_SECONDS` | `180` | Max wait for LLM response |
| `RETRIEVAL_TIMEOUT_SECONDS` | `30` | Max wait for embedding + Chroma search |
| `POLICY_MIN_RELEVANCE` | `0.30` | Minimum relevance (0-1) for a chunk to count as evidence |
| `CHAT_RESTORE_MESSAGES` | `20` | Saved messages replayed to rebuild a lost chat thread |
| `OPTIMIZER_RECURSION_LIMIT` | `10` | LangGraph step cap for the optimization agent (about 4 tool rounds) |
| `OPTIMIZER_TIMEOUT_SECONDS` | `180` | Max wait for the optimization agent before the deterministic plan is used |
| `RENEWAL_WINDOW_DAYS` | `14` | Renewals within this many days become alerts |
| `DEBUG` | `false` | Enable verbose logging |

---

## File Structure

```
ta2/
├── ta2_subscription_optimizer.py   ← UNCHANGED core module
├── optimize_demo.py                ← runs the optimization agent on a sample portfolio
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
    ├── optimizer_engine.py  ← deterministic portfolio optimizer (candidates, plans, budget, verify)
    ├── optimizer_agent.py   ← optimization agent (tools + supervisor run_optimizer)
    ├── README.md        ← this file
    └── routers/
        ├── health.py      ← GET /health, GET /health/ollama
        ├── auth.py        ← POST /auth/session
        ├── compare.py     ← POST /compare
        ├── history.py     ← /history, /history/{id}, /history/{id}/trace
        ├── preferences.py ← GET/PUT /preferences
        ├── chat.py        ← POST /chat, /conversations
        ├── memory.py      ← GET/POST /memory/{user_id}
        ├── policy.py      ← /policy/search, /policy/ask, /policy/status
        └── optimize.py    ← /optimize, /optimizations, /portfolio
```

## Rebuilding the policy index

`GET /policy/status` lists chunks whose indexed text differs from `data/subscription_policy.txt`.
The API never writes to the index; rebuild it explicitly (needs Ollama):

```powershell
python -m backend.reindex_policy --check   # report only
python -m backend.reindex_policy           # rebuild
```
