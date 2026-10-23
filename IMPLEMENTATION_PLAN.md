# IMPLEMENTATION PLAN

> Phase 1: Audit, Inventory and FastAPI Backend Design
> Generated: 2026-10-23

---

## 1. Repository Tree (Verified, as-found)

```
ta2/
|-- ta2_subscription_optimizer.py     (532 lines - core logic, agents, tools)
|-- verify_all.py                     (148 lines - 9-test verification suite)
|-- requirements.txt                  (6 runtime deps)
|-- README.md
|-- subscription_memory.sqlite        (12 KB - live SQLite long-term memory)
|-- .gitignore
|-- data/
|   `-- subscription_policy.txt       (34 lines / 11 paragraphs - RAG corpus)
|-- chroma_db/
|   |-- chroma.sqlite3                (252 KB - ChromaDB index)
|   `-- 9d77d682-27a0-41b0-9c7f-e0f2c15bb96b/ (Vector segment files)
|-- traces/
|   |-- run_1.txt                     (2.1 KB - Plan A vs B comparison run)
|   |-- run_2.txt                     (2.4 KB - Equal cost / tie demo)
|   |-- run_3.txt                     (200 B  - Guardrail block demo)
|   |-- run_4.txt                     (1.0 KB - Injected calculator failure demo)
|   |-- rag_k_comparison.txt          (932 B  - RAG k=1 vs k=3 demo)
|   `-- short_term_memory.txt         (460 B  - InMemorySaver thread demo)
`-- .venv/                            (Python 3.11.9)
```

Mac resource-fork artefacts (._*) are present throughout -- harmless, do not delete.

---

## 2. Installed Package Inventory (Verified via pip list)

| Package | Version | Role |
|---|---|---|
| langchain | 1.4.7 | Agent framework, create_agent, @tool decorator |
| langchain-core | 1.6.13 | Document, BaseChatModel, StructuredTool |
| langchain-ollama | 1.1.0 | ChatOllama, OllamaEmbeddings |
| langchain-chroma | 1.1.0 | Chroma vectorstore wrapper |
| langgraph | 1.2.16 | CompiledStateGraph, graph execution |
| langgraph-checkpoint | 4.3.0 | InMemorySaver |
| langgraph-prebuilt | 1.1.1 | Supporting prebuilts |
| chromadb | 1.5.9 | Persistent vector database |
| fastapi | 0.115.0 | [ALREADY INSTALLED] Ready for backend |
| uvicorn | 0.30.6 | [ALREADY INSTALLED] ASGI server |
| pydantic | 2.13.2 | Request/response models |
| pydantic-settings | 2.5.2 | Config management |
| aiosqlite | 0.22.1 | Async SQLite (bonus) |
| ollama | 0.6.3 | Direct Ollama Python SDK |

FastAPI and Uvicorn are already installed. No additional packages needed for Phase 2.

---

## 3. Reusable Function Inventory

### 3.1 LangChain @tool Decorated Tools (StructuredTool)

Type confirmed at runtime: langchain_core.tools.structured.StructuredTool
Can be .invoke()d directly from API handlers WITHOUT going through an agent.

| Function | Parameters | Returns | Reusable? |
|---|---|---|---|
| calculate_discounted_price | monthly_price: float, discount_percent: float | str | YES as-is |
| calculate_annual_cost | monthly_cost: float | str | YES as-is |
| compare_plans | plan_a_cost: float, plan_b_cost: float | str | YES as-is |

INJECT_FAILURE flag: Module-level bool at line 30.
Set to True to trigger demo failure in calculate_annual_cost.

### 3.2 Plain Python Functions (No LLM Required)

| Function | Signature | Role | Reusable? |
|---|---|---|---|
| check_ollama(model_name, embedding_model) | -> tuple[bool, str] | Health-check Ollama daemon | YES as health endpoint |
| init_memory() | -> None | CREATE TABLE IF NOT EXISTS | YES call once on app start |
| remember(user_id, namespace, key, value) | -> None | SQLite UPSERT | YES as-is |
| recall(user_id, namespace) | -> dict | SQLite SELECT | YES as-is |
| load_corpus() | -> list[Document] | Parse policy.txt into Documents | YES as-is |
| guardrail(text) | -> tuple[bool, str] | Keyword blocklist check | YES as-is |
| retrieve_policy(store, question, k) | -> str | Chroma similarity_search | YES as-is |
| save_trace(name, text) | -> None | Write to traces/ directory | YES as-is |

### 3.3 LangGraph Agents (CompiledStateGraph)

Type confirmed at runtime: langgraph.graph.state.CompiledStateGraph
Require Ollama online to invoke.

| Object | Tools | Checkpointer | Reusable? |
|---|---|---|---|
| calculator_agent | calculate_discounted_price, calculate_annual_cost, compare_plans | None | YES wrap in try/except |
| review_agent | (none) | None | YES wrap in try/except |
| conversation_agent | (none) | InMemorySaver() | YES pass config={"configurable": {"thread_id": id}} |

Invocation pattern for all agents:

```python
result = agent.invoke({"messages": [{"role": "user", "content": prompt}]})
last_message = result["messages"][-1].content
```

### 3.4 Supervisor Orchestrator

Function: run_supervisor
Signature: (question, plan_a, plan_b, discount_a, discount_b, store, user_id) -> tuple[str, list[str], float]
Returns: (answer_str, trace_lines_list, elapsed_seconds_float)

Execution flow (3 hops):
  Hop 0: guardrail check -> SQLite recall -> RAG retrieval
  Hop 1: Calculator Agent (discounts, annual costs, compare_plans)
  Hop 2: Review Agent (policy grounding)
  Hop 3: Finalize

### 3.5 Data Assets (Do NOT Modify)

| Asset | Location | State | Notes |
|---|---|---|---|
| subscription_policy.txt | data/ | Intact, 11 paragraphs | RAG corpus source |
| subscription_memory.sqlite | root | Live, 15+ user records | Contains real user data |
| ChromaDB | chroma_db/ | subscription_policy collection, 11 documents | Do not re-embed unless count==0 |

---

## 4. create_agent API Signature (langchain 1.4.7, verified at runtime)

```python
create_agent(
    model: str | BaseChatModel,
    tools: Sequence[BaseTool | Callable | dict] | None = None,
    *,
    system_prompt: str | SystemMessage | None = None,
    checkpointer: Checkpointer | None = None,
    store: BaseStore | None = None,
    response_format: ResponseFormat | None = None,
    state_schema: type | None = None,
    ...
) -> CompiledStateGraph
```

The existing code uses the correct parameter names -- no API mismatch detected.

---

## 5. Baseline Test Results

### Tests Run Without Ollama (Pure Logic) -- 5/5 Passed

| Test | Status | Notes |
|---|---|---|
| test_01_discount_calculator | PASS | Rs.850.00, Rs.150.00 correct |
| test_02_annual_cost_calculator | PASS | Rs.5988.00 correct |
| test_03_plan_comparison | PASS | Cheaper plan + tie case correct |
| test_04_sqlite_memory | PASS | UPSERT + recall correct |
| test_05_guardrail | PASS | Blocklist detection correct |
| test_06_injected_calculator_failure | NEEDS OLLAMA | Requires LLM agent invocation |
| test_07_rag_retrieval | NEEDS OLLAMA | Requires OllamaEmbeddings |
| test_08_short_term_conversation_memory | NEEDS OLLAMA | Requires LLM + InMemorySaver |
| test_09_end_to_end_supervisor | NEEDS OLLAMA | Requires full pipeline |

5/5 pure-logic tests pass. 4 tests require Ollama service and models.

### Known Issues and Blockers

| ID | Issue | Severity | Fix |
|---|---|---|---|
| B-01 | INJECT_FAILURE is a module-level global -- not thread-safe | Medium | Use a request-scoped flag in FastAPI |
| B-02 | get_vectorstore() calls OllamaEmbeddings -- blocks if Ollama is down | Medium | Call check_ollama() first; return 503 |
| B-03 | Agents are created at import time (lines 181-211) -- crash if Ollama offline | HIGH | Lazy-initialize agents inside a factory function |
| B-04 | short_term_memory.txt trace: fresh thread hallucinated "$500" -- LLM non-determinism | Low | Expected behavior; noted |
| B-05 | PRAGMA table_info returns 0-indexed columns in inspect script -- harmless | Low | Cosmetic only |
| B-06 | conversation_agent uses InMemorySaver -- memory lost on server restart | Medium | Use langgraph-checkpoint-sqlite or accept stateless per-call |

---

## 6. Component Reuse Assessment

| Component | Reuse Decision | Action Required |
|---|---|---|
| @tool functions (3) | Reuse as-is | Import directly into FastAPI handlers |
| guardrail() | Reuse as-is | Call as middleware step |
| init_memory() | Reuse as-is | Call in FastAPI lifespan startup |
| remember() / recall() | Reuse as-is | Expose via /memory endpoints |
| load_corpus() | Reuse as-is | Call once in startup |
| retrieve_policy() | Reuse as-is | Used inside /compare handler |
| save_trace() | Reuse as-is | Call after each supervisor run |
| check_ollama() | Reuse as-is | Expose as /health endpoint |
| run_supervisor() | Reuse as-is | Called by /compare endpoint |
| Agents (3) | Refactor initialization | Move from module-level to lazy factory |
| get_vectorstore() | Refactor initialization | Move to lazy startup with error handling |
| INJECT_FAILURE global | Refactor | Remove global; use request parameter |
| main() interactive loop | Not reused | Replaced by FastAPI route handlers |

---

## 7. Missing Functionality for Plan Comparison (API Gap Analysis)

The existing compare_plans tool accepts post-discount monthly prices only.
What is missing for a complete API:

| Missing Item | Description |
|---|---|
| Plan name labels | API should accept plan names not just costs |
| Feature comparison | Current system is price-only; no feature/value scoring |
| Multi-currency | Currently hardcoded to INR; no conversion layer |
| Annual billing plan support | No logic for upfront annual vs monthly billing |
| Structured JSON response | run_supervisor returns raw string; API needs structured JSON |
| Async execution | All agents are synchronous; FastAPI needs async wrappers |
| Per-request Ollama availability check | No guard if Ollama goes down between requests |
| Authentication/user auth | No auth layer -- user_id is caller-supplied |

---

## 8. Safe File Structure for FastAPI Backend

Constraint: Do NOT modify ta2_subscription_optimizer.py. Import from it.

```
ta2/
|-- ta2_subscription_optimizer.py     (UNCHANGED - source of truth)
|-- verify_all.py                     (UNCHANGED)
|-- requirements.txt                  (add fastapi, uvicorn -- already installed)
|-- IMPLEMENTATION_PLAN.md            (this file)
|-- api/                              (NEW - FastAPI application package)
|   |-- __init__.py
|   |-- main.py                       (FastAPI app, lifespan, CORS, router mounts)
|   |-- dependencies.py               (Shared state: store, agents, DB init)
|   |-- models.py                     (Pydantic request/response schemas)
|   `-- routers/
|       |-- __init__.py
|       |-- compare.py                (POST /compare  - run_supervisor wrapper)
|       |-- memory.py                 (GET /memory/{user_id}, POST /memory)
|       |-- health.py                 (GET /health  - check_ollama)
|       `-- policy.py                 (GET /policy/search?q=...&k=3)
|-- data/                             (UNCHANGED)
|-- chroma_db/                        (UNCHANGED)
|-- traces/                           (UNCHANGED)
`-- subscription_memory.sqlite        (UNCHANGED)
```

---

## 9. API Endpoint Design

### POST /compare

Request:
```json
{
  "user_id": "string",
  "question": "string",
  "plan_a": {
    "name": "string",
    "monthly_price": 499.0,
    "discount_percent": 10.0
  },
  "plan_b": {
    "name": "string",
    "monthly_price": 999.0,
    "discount_percent": 20.0
  }
}
```

Response:
```json
{
  "answer": "string",
  "trace": ["string"],
  "elapsed_seconds": 8.5,
  "guardrail_passed": true,
  "calc_result": "string",
  "policy_result": "string"
}
```

### GET /health

```json
{"ollama_ok": true, "message": "Ollama service and required models are verified."}
```

### GET /memory/{user_id}

```json
{"user_id": "demo_user", "profile": {"last_plan_a_monthly": "499.00", "currency": "INR"}}
```

### GET /policy/search?q=annual+billing&k=3

```json
{"query": "annual billing", "chunks": ["[Chunk 1] ...", "[Chunk 3] ..."]}
```

---

## 10. Implementation Order (Safe, Incremental)

### Phase 1A -- Infrastructure (No LLM calls) -- CURRENT PHASE

1. Create api/ package structure
2. Create api/models.py -- Pydantic schemas for all endpoints
3. Create api/dependencies.py -- lazy store + agent initialization
4. Create api/routers/health.py -- /health using existing check_ollama()
5. Create api/routers/memory.py -- /memory using existing remember()/recall()
6. Create api/main.py -- FastAPI app with lifespan + CORS
7. Run: uvicorn api.main:app --reload and verify /health and /memory

### Phase 1B -- Core Comparison Endpoint

8. Create api/routers/compare.py -- /compare wrapping run_supervisor()
9. Create api/routers/policy.py -- /policy/search wrapping retrieve_policy()
10. Test all endpoints with Postman/curl

### Phase 1C -- Hardening

11. Add async wrappers for agent calls (asyncio.to_thread)
12. Add per-request Ollama availability check with 503 response
13. Fix INJECT_FAILURE global -> request-scoped parameter
14. Add structured JSON splitting of run_supervisor output

### Phase 2 -- Android Frontend (Kotlin/Jetpack Compose)

15. Define Retrofit API client for all Phase 1 endpoints
16. Build CompareScreen composable with plan inputs
17. Build ResultScreen with trace accordion + policy section
18. Build MemoryScreen showing saved profile
19. Build HealthScreen showing Ollama status

---

## 11. Phase 1 Exit Criteria

All conditions must be true before Phase 2 (Android) begins:

- [ ] EX-01: GET /health returns {"ollama_ok": true/false, "message": "..."} with HTTP 200
- [ ] EX-02: POST /compare accepts the full JSON schema and returns structured JSON (not raw string)
- [ ] EX-03: GET /memory/{user_id} returns the user saved profile from SQLite
- [ ] EX-04: GET /policy/search returns relevant chunks from ChromaDB
- [ ] EX-05: run_supervisor() is NOT called at import time -- only on POST request
- [ ] EX-06: Agents are lazy-initialized -- server starts without Ollama if needed
- [ ] EX-07: Server returns HTTP 503 with JSON error when Ollama is offline (not a crash)
- [ ] EX-08: CORS is configured for Android emulator (http://10.0.2.2:8000)
- [ ] EX-09: All 5 pure-logic unit tests still pass after adding api/ package
- [ ] EX-10: A working curl or Postman demo of the full /compare flow is documented

---

## 12. Key Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Ollama offline during tests | High | High | All endpoints gracefully degrade; /health consulted first |
| Agent module-level init crash | High | High | Move to lazy factory -- fix in Phase 1A |
| Currency symbol encoding in API JSON | Low | Medium | FastAPI returns UTF-8 JSON natively; safe |
| ChromaDB re-embedding on cold start | Medium | Low | store._collection.count() == 0 guard already present |
| Thread-safety of INJECT_FAILURE global | Low | Medium | Remove global; use request body flag |
| Android network: localhost vs 10.0.2.2 | Low | High | Document emulator routing in README |

---

This report was generated from live code inspection, import verification, runtime tool testing,
SQLite introspection, and ChromaDB collection verification. No code was modified.
All databases, vector stores, policy files, and traces are intact.
