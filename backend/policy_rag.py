"""
Policy retrieval and answer validation on top of the existing ChromaDB store.

Evidence is always the text ChromaDB actually returned for a query. Nothing here
writes to the vector store. Answers are checked after generation:

  * citations of the form ``[Chunk N]`` are compared against the chunks that
    were really retrieved, and any other citation is reported as unsupported;
  * rupee amounts in comparison explanations are compared against the
    deterministic engine's figures.
"""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Iterable

from backend.config import settings

COLLECTION_NAME = "subscription_policy"
POLICY_FILE = "subscription_policy.txt"


@dataclass
class Evidence:
    chunk: int
    title: str
    text: str
    source: str
    relevance: float
    #: False when this stored chunk differs from the current policy file (the
    #: vector store was embedded from an older version of the document).
    matches_current_policy: bool
    cited: bool = False

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@lru_cache(maxsize=1)
def _policy_file_chunks(path: str, mtime: float) -> dict[int, str]:
    # Same paragraph split as ta2_subscription_optimizer.load_corpus().
    text = Path(path).read_text(encoding="utf-8")
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    return {i + 1: p for i, p in enumerate(paragraphs)}


def current_policy_chunks() -> dict[int, str]:
    path = Path(settings.data_dir) / POLICY_FILE
    if not path.exists():
        return {}
    return _policy_file_chunks(str(path), path.stat().st_mtime)


def retrieve_evidence(store: Any, query: str, k: int = 3, min_relevance: float | None = None) -> list[Evidence]:
    """
    Top-``k`` chunks for ``query`` whose relevance is at least ``min_relevance``.

    Relevance is LangChain's normalised score for the collection's L2 distance
    (higher is closer). Unrelated questions score well below the threshold, so
    they return no evidence instead of the nearest irrelevant paragraphs.
    """
    threshold = settings.policy_min_relevance if min_relevance is None else min_relevance
    results = store.similarity_search_with_relevance_scores(query, k=k)
    current = current_policy_chunks()
    evidence: list[Evidence] = []
    for doc, score in results:
        if score < threshold:
            continue
        chunk = int(doc.metadata.get("chunk", 0) or 0)
        text = doc.page_content.strip()
        evidence.append(
            Evidence(
                chunk=chunk,
                title=text.split("\n", 1)[0].strip(),
                text=text,
                source=str(doc.metadata.get("source", POLICY_FILE)),
                relevance=round(float(score), 3),
                matches_current_policy=current.get(chunk) == text,
            )
        )
    return evidence


def format_for_prompt(evidence: Iterable[Evidence]) -> str:
    return "\n\n".join(f"[Chunk {e.chunk}] {e.text}" for e in evidence)


_CITATION = re.compile(r"\[\s*chunk\s*(\d+)\s*\]", re.IGNORECASE)


def check_citations(answer: str, evidence: list[Evidence]) -> tuple[list[int], list[int]]:
    """
    Returns (cited chunks that were retrieved, cited chunks that were not).
    Marks the matching evidence items as ``cited``.
    """
    cited = [int(n) for n in _CITATION.findall(answer)]
    retrieved = {e.chunk for e in evidence}
    supported = sorted({n for n in cited if n in retrieved})
    unsupported = sorted({n for n in cited if n not in retrieved})
    for e in evidence:
        e.cited = e.chunk in supported
    return supported, unsupported


_THINK = re.compile(r"<think>.*?</think>", re.IGNORECASE | re.DOTALL)


def strip_reasoning(text: str) -> str:
    """Remove any <think>…</think> block a reasoning model may emit; only the answer is kept."""
    return _THINK.sub("", text).strip()


_RUPEES = re.compile(r"₹\s?([0-9][0-9,]*(?:\.[0-9]+)?)")


def unmatched_amounts(text: str, known_figures: Iterable[float]) -> list[str]:
    """
    Rupee amounts mentioned in ``text`` that don't equal any known figure
    (to the paisa). A heuristic: it flags, it doesn't prove an error.
    """
    known = {round(float(v), 2) for v in known_figures if v is not None}
    unmatched: list[str] = []
    for raw in _RUPEES.findall(text):
        try:
            value = round(float(raw.replace(",", "")), 2)
        except ValueError:
            continue
        if value not in known and f"₹{raw}" not in unmatched:
            unmatched.append(f"₹{raw}")
    return unmatched


def index_status(store: Any) -> dict[str, Any]:
    """Compare what the vector store holds with the policy file on disk (read-only)."""
    current = current_policy_chunks()
    if store is None:
        return {
            "available": False,
            "indexed_chunks": 0,
            "file_chunks": len(current),
            "stale_chunks": [],
            "missing_chunks": sorted(current),
        }
    data = store._collection.get(include=["metadatas", "documents"])
    stored = {
        int((meta or {}).get("chunk", 0) or 0): (doc or "").strip()
        for meta, doc in zip(data.get("metadatas") or [], data.get("documents") or [])
    }
    return {
        "available": True,
        "indexed_chunks": len(stored),
        "file_chunks": len(current),
        "stale_chunks": sorted(n for n, text in stored.items() if n in current and current[n] != text),
        "missing_chunks": sorted(n for n in current if n not in stored),
    }
