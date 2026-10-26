"""
Shared pytest fixtures.

API tests run against a temporary SQLite file, a fake vector store and fake
LangChain agents, so they need neither Ollama nor the real database. The
real database and ChromaDB are only ever opened read-only (live RAG tests) or
copied (migration tests).
"""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from langchain_core.documents import Document  # noqa: E402
from langchain_core.messages import AIMessage  # noqa: E402

from backend import db  # noqa: E402
from backend.config import settings  # noqa: E402

import ta2_subscription_optimizer as core  # noqa: E402

POLICY_CHUNKS = {
    1: "Monthly and annual billing\nA monthly subscription is billed each month.",
    4: "Taxes and fees\nTaxes and service fees can increase the amount paid beyond the advertised subscription price.",
    8: "Cancellation and refunds\nCancellation rules, refund eligibility, and early termination fees can affect the total cost.",
}


class FakeStore:
    """Mimics the parts of langchain_chroma.Chroma the backend uses."""

    def __init__(self, routes: dict[str, list[tuple[int, float]]] | None = None):
        # keyword in query -> [(chunk, relevance)]
        self.routes = routes if routes is not None else {
            "refund": [(8, 0.52), (1, 0.31), (4, 0.20)],
            "tax": [(4, 0.47), (1, 0.32)],
        }
        self.queries: list[str] = []
        self._collection = SimpleNamespace(get=self._get)

    def similarity_search_with_relevance_scores(self, query: str, k: int = 4):
        self.queries.append(query)
        for keyword, hits in self.routes.items():
            if keyword in query.lower():
                return [
                    (Document(page_content=POLICY_CHUNKS[c], metadata={"chunk": c, "source": "subscription_policy.txt"}), s)
                    for c, s in hits[:k]
                ]
        # Unrelated question: nearest chunks, all far below the threshold.
        return [(Document(page_content=POLICY_CHUNKS[1], metadata={"chunk": 1, "source": "subscription_policy.txt"}), 0.12)]

    def _get(self, include=None):
        return {
            "metadatas": [{"chunk": c, "source": "subscription_policy.txt"} for c in POLICY_CHUNKS],
            "documents": list(POLICY_CHUNKS.values()),
        }


class FakeReviewAgent:
    def __init__(self, reply: str = "Plan A is cheaper. [Chunk 8]", error: Exception | None = None):
        self.reply = reply
        self.error = error
        self.calls: list[str] = []

    def invoke(self, payload: dict[str, Any], config: Any = None):
        self.calls.append(payload["messages"][-1]["content"])
        if self.error:
            raise self.error
        return {"messages": [AIMessage(content=self.reply)]}


class FakeConversationAgent:
    """Keeps per-thread message lists like InMemorySaver would."""

    def __init__(self, reply: str = "Noted."):
        self.reply = reply
        self.threads: dict[str, list[dict[str, str]]] = {}
        self.invocations: list[tuple[str, list[dict[str, str]]]] = []
        self.checkpointer = SimpleNamespace(delete_thread=lambda thread_id: self.threads.pop(thread_id, None))

    def get_state(self, config):
        thread = config["configurable"]["thread_id"]
        return SimpleNamespace(values={"messages": list(self.threads.get(thread, []))})

    def invoke(self, payload, config):
        thread = config["configurable"]["thread_id"]
        self.invocations.append((thread, payload["messages"]))
        history = self.threads.setdefault(thread, [])
        history.extend(payload["messages"])
        history.append({"role": "assistant", "content": self.reply})
        return {"messages": [AIMessage(content=self.reply)]}


@pytest.fixture
def temp_db(tmp_path, monkeypatch):
    path = tmp_path / "memory.sqlite"
    monkeypatch.setattr(settings, "db_path", path)
    monkeypatch.setattr(core, "DB_PATH", path)  # remember()/recall() in the core module
    db.migrate()
    return path


@pytest.fixture
def fake_store():
    return FakeStore()


@pytest.fixture
def review_agent(monkeypatch):
    agent = FakeReviewAgent()
    monkeypatch.setattr(core, "review_agent", agent)
    return agent


@pytest.fixture
def conversation_agent(monkeypatch):
    agent = FakeConversationAgent()
    monkeypatch.setattr(core, "conversation_agent", agent)
    return agent


@pytest.fixture
def client(temp_db, fake_store, review_agent, conversation_agent, monkeypatch):
    from fastapi.testclient import TestClient

    from backend.dependencies import get_store
    from backend.main import app

    monkeypatch.setattr(core, "check_ollama", lambda *a, **k: (True, "ok"))
    monkeypatch.setattr(core, "save_trace", lambda *a, **k: None)  # keep traces/ untouched
    app.dependency_overrides[get_store] = lambda: fake_store
    # Not used as a context manager, so the lifespan (real Chroma/Ollama startup) doesn't run.
    yield TestClient(app)
    app.dependency_overrides.clear()


def session(client, user_id: str) -> dict[str, str]:
    response = client.post("/auth/session", json={"user_id": user_id})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['token']}"}


def compare_payload(user_id: str, **overrides) -> dict[str, Any]:
    payload = {
        "user_id": user_id,
        "question": "Which plan is cheaper, and what about refunds?",
        "priority": "lowest_cost",
        "plan_a": {"name": "Basic", "monthly_price": 199, "features": ["HD"]},
        "plan_b": {"name": "Premium", "monthly_price": 499, "features": ["HD", "4K"]},
    }
    payload.update(overrides)
    return payload
