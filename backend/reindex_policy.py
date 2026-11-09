"""
Rebuilds the policy vector index from data/subscription_policy.txt.

    python -m backend.reindex_policy            # rebuild
    python -m backend.reindex_policy --check    # only report stale or missing chunks

GET /policy/status reports when the index and the policy file have drifted apart; the API itself
never writes to the index. This command is the explicit step that does. Requires Ollama (it embeds
every chunk). New chunks are added before the old ones are deleted, so a failure part-way leaves
the existing index able to answer.
"""
from __future__ import annotations

import argparse
import sys
from typing import Any

from backend import dependencies, policy_rag


def rebuild(store: Any, documents: list[Any]) -> int:
    """Replaces everything in ``store`` with ``documents``; returns how many chunks were indexed."""
    old_ids = store._collection.get(include=[])["ids"]
    store.add_documents(documents)
    if old_ids:
        store._collection.delete(ids=old_ids)
    return len(documents)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="report the index status without changing it")
    args = parser.parse_args()

    store = dependencies._app.get_vectorstore()
    if store is None:
        print("Could not open the vector store. Is Ollama running with nomic-embed-text?", file=sys.stderr)
        return 1

    before = policy_rag.index_status(store)
    print(f"Before: {before}")
    if args.check:
        return 0 if not before["stale_chunks"] and not before["missing_chunks"] else 2

    count = rebuild(store, dependencies._app.load_corpus())
    after = policy_rag.index_status(store)
    print(f"Indexed {count} chunks. After: {after}")
    return 0 if not after["stale_chunks"] and not after["missing_chunks"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
