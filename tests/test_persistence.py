"""Schema migrations and the persistence layer (no Ollama needed)."""
from __future__ import annotations

import shutil
import sqlite3

import pytest

from backend import db
from backend.config import settings

from conftest import ROOT

LEGACY_SCHEMA = """
CREATE TABLE user_memory (
    user_id TEXT NOT NULL,
    namespace TEXT NOT NULL,
    memory_key TEXT NOT NULL,
    memory_value TEXT NOT NULL,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, namespace, memory_key)
)
"""


def _rows(path, table="user_memory"):
    with sqlite3.connect(path) as conn:
        return conn.execute(f"SELECT * FROM {table} ORDER BY 1, 2, 3").fetchall()


def _version(path):
    with sqlite3.connect(path) as conn:
        return conn.execute("PRAGMA user_version").fetchone()[0]


def test_fresh_database_gets_every_table(tmp_path, monkeypatch):
    path = tmp_path / "fresh.sqlite"
    monkeypatch.setattr(settings, "db_path", path)

    report = db.migrate()

    assert report == {"from_version": 0, "to_version": db.SCHEMA_VERSION, "backup": None}
    with sqlite3.connect(path) as conn:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"user_memory", "api_sessions", "comparisons", "user_preferences", "chat_messages"} <= tables
    assert not list(tmp_path.glob("*.bak")), "an empty database needs no backup"


def test_legacy_rows_are_preserved_and_backed_up(tmp_path, monkeypatch):
    path = tmp_path / "legacy.sqlite"
    with sqlite3.connect(path) as conn:
        conn.execute(LEGACY_SCHEMA)
        conn.executemany(
            "INSERT INTO user_memory(user_id, namespace, memory_key, memory_value, updated_at) VALUES (?,?,?,?,?)",
            [("u1", "profile", "currency", "INR", "2026-01-01 00:00:00"),
             ("u1", "profile", "plan_a_name", "Basic", "2026-01-01 00:00:00"),
             ("u2", "profile", "last_priority", "best_value", "2026-02-01 00:00:00")],
        )
    before = _rows(path)
    monkeypatch.setattr(settings, "db_path", path)

    report = db.migrate()

    assert report["from_version"] == 0 and report["to_version"] == db.SCHEMA_VERSION
    assert _rows(path) == before, "existing user_memory rows must be untouched"
    backup = tmp_path / "legacy.sqlite.pre-migration-v0.bak"
    assert report["backup"] == str(backup)
    assert _rows(backup) == before
    assert _version(backup) == 0, "the backup is the pre-migration database"

    # Idempotent: a second run changes nothing and makes no new backup.
    assert db.migrate() == {"from_version": 1, "to_version": 1, "backup": None}
    assert _rows(path) == before
    assert len(list(tmp_path.glob("*.bak"))) == 1


def test_migrating_a_copy_of_the_real_database_keeps_every_record(tmp_path, monkeypatch):
    real = ROOT / "subscription_memory.sqlite"
    if not real.exists():
        pytest.skip("no project database")
    copy = tmp_path / "copy.sqlite"
    shutil.copy(real, copy)
    before = _rows(copy)
    monkeypatch.setattr(settings, "db_path", copy)

    db.migrate()

    assert _rows(copy) == before
    assert len(before) > 0


def test_refuses_a_newer_schema(tmp_path, monkeypatch):
    path = tmp_path / "future.sqlite"
    with sqlite3.connect(path) as conn:
        conn.execute(f"PRAGMA user_version = {db.SCHEMA_VERSION + 1}")
    monkeypatch.setattr(settings, "db_path", path)
    with pytest.raises(RuntimeError, match="newer"):
        db.migrate()


def test_sessions_store_only_hashes_and_enforce_first_claim(temp_db):
    token = db.create_session("alice")

    assert db.user_for_token(token) == "alice"
    assert db.user_for_token("not-a-token") is None
    with sqlite3.connect(temp_db) as conn:
        stored = [r[0] for r in conn.execute("SELECT token_hash FROM api_sessions")]
    assert token not in stored and len(stored[0]) == 64

    with pytest.raises(db.UserAlreadyClaimed):
        db.create_session("alice")
    with pytest.raises(db.UserAlreadyClaimed):
        db.create_session("alice", existing_token=db.create_session("mallory"))
    refreshed = db.create_session("alice", existing_token=token)
    assert db.user_for_token(refreshed) == "alice"


def test_comparisons_round_trip_and_are_user_scoped(temp_db):
    cid, created = db.save_comparison(
        "alice", "q", "best_value", {"name": "A"}, {"name": "B"}, "plan_a",
        {"recommendation": "plan_a"}, [{"step": 1, "stage": "x"}],
    )
    db.save_comparison("bob", "q", "best_value", {"name": "C"}, {"name": "D"}, "tie", {}, [])

    items, total = db.list_comparisons("alice", 10, 0)
    assert total == 1 and items[0]["id"] == cid and items[0]["created_at"] == created
    assert items[0]["plan_a"] == {"name": "A"} and "trace_events" not in items[0]
    assert db.get_comparison("alice", cid)["trace_events"] == [{"step": 1, "stage": "x"}]
    assert db.get_comparison("bob", cid) is None
    assert db.delete_comparison("bob", cid) is False
    assert db.delete_comparison("alice", cid) is True
    assert db.list_comparisons("alice", 10, 0) == ([], 0)
    assert db.list_comparisons("bob", 10, 0)[1] == 1


def test_preferences_and_chat_transcripts(temp_db):
    assert db.get_preferences("alice") == {"default_priority": None, "updated_at": None}
    db.set_preferences("alice", "student_budget")
    assert db.get_preferences("alice")["default_priority"] == "student_budget"

    for i in range(5):
        db.add_chat_message("alice", "t1", "user", f"q{i}")
        db.add_chat_message("alice", "t1", "assistant", f"a{i}", [{"chunk": 8}])
    db.add_chat_message("bob", "t1", "user", "bob's message")

    assert [m["content"] for m in db.chat_messages("alice", "t1", limit=3)] == ["a3", "q4", "a4"]
    assert db.chat_messages("alice", "t1")[1]["evidence"] == [{"chunk": 8}]
    threads = db.list_threads("alice", 10)
    assert len(threads) == 1 and threads[0]["message_count"] == 10 and threads[0]["first_message"] == "q0"
    assert db.delete_thread("alice", "t1") == 10
    assert len(db.chat_messages("bob", "t1")) == 1
