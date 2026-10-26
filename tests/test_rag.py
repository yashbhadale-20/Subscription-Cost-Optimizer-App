"""
RAG retrieval and answer checks.

Unit tests use a fake store. The live tests open the real ChromaDB collection
read-only and need Ollama for query embeddings; they skip when it's down.
"""
from __future__ import annotations

import pytest

from backend import policy_rag

from conftest import FakeStore


def test_threshold_filters_weak_matches():
    evidence = policy_rag.retrieve_evidence(FakeStore(), "refund question", k=3, min_relevance=0.30)
    assert [(e.chunk, e.relevance) for e in evidence] == [(8, 0.52), (1, 0.31)]
    assert evidence[0].title == "Cancellation and refunds"
    assert policy_rag.retrieve_evidence(FakeStore(), "pizza", k=3, min_relevance=0.30) == []


def test_evidence_text_is_what_the_store_returned():
    evidence = policy_rag.retrieve_evidence(FakeStore(), "refund", k=1, min_relevance=0.0)
    from conftest import POLICY_CHUNKS
    assert evidence[0].text == POLICY_CHUNKS[8]
    # The fake chunk text is shorter than the real policy file paragraph, so it's flagged.
    assert evidence[0].matches_current_policy is False


def test_citation_check():
    evidence = policy_rag.retrieve_evidence(FakeStore(), "refund", k=3, min_relevance=0.0)
    cited, unsupported = policy_rag.check_citations("See [Chunk 8], [chunk 1] and [ Chunk 11 ].", evidence)
    assert cited == [1, 8] and unsupported == [11]
    assert {e.chunk: e.cited for e in evidence} == {8: True, 1: True, 4: False}
    assert policy_rag.check_citations("No citations here.", evidence) == ([], [])


def test_unmatched_amounts():
    assert policy_rag.unmatched_amounts("₹199 then ₹1,999.00 and ₹ 50", [199, 1999]) == ["₹50"]
    assert policy_rag.unmatched_amounts("No money mentioned.", [1]) == []


def test_reasoning_blocks_are_removed():
    assert policy_rag.strip_reasoning("<think>private steps</think>\nAnswer.") == "Answer."


def test_index_status_with_fake_store():
    status = policy_rag.index_status(FakeStore())
    assert status["available"] is True and status["indexed_chunks"] == 3
    assert set(status["stale_chunks"]) == {1, 4, 8}  # the fake texts are truncated paragraphs


# ── Live, read-only checks against the real ChromaDB collection ──────────────

@pytest.fixture(scope="module")
def real_store():
    import ta2_subscription_optimizer as core

    ok, message = core.check_ollama()
    if not ok:
        pytest.skip(f"Ollama unavailable: {message}")
    store = core.get_vectorstore()
    if store is None:
        pytest.skip("Chroma store unavailable")
    return store


def test_live_refund_question_retrieves_the_cancellation_chunk(real_store):
    before = real_store._collection.count()
    evidence = policy_rag.retrieve_evidence(real_store, "Is there a refund deadline?", k=3)
    assert evidence and evidence[0].chunk == 8
    assert evidence[0].title == "Cancellation and refunds"
    assert all(e.relevance >= 0.30 for e in evidence)
    assert real_store._collection.count() == before, "retrieval must not write to the store"


@pytest.mark.parametrize("question", ["What is the capital of France?", "Best pizza toppings"])
def test_live_unrelated_questions_return_no_evidence(real_store, question):
    assert policy_rag.retrieve_evidence(real_store, question, k=3) == []


def test_live_index_status_reports_drift_without_changing_anything(real_store):
    status = policy_rag.index_status(real_store)
    assert status["available"] is True
    assert status["indexed_chunks"] == status["file_chunks"] == 11
    # Chunks 3, 8 and 11 were embedded from an earlier version of the policy file.
    assert status["stale_chunks"] == [3, 8, 11]
    assert status["missing_chunks"] == []
