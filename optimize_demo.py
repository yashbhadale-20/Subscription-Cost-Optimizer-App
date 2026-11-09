"""
Subscription Optimization Agent demo.

Runs backend.optimizer_agent.run_optimizer directly (no HTTP server) on a sample
INR portfolio and prints the plan and its execution trace. With Ollama running
(qwen2.5:3b + nomic-embed-text) the agent chooses the actions; without it the
deterministic plan is printed with used_fallback=True.

    python optimize_demo.py
    python optimize_demo.py --budget 1200
    python optimize_demo.py --goal "Keep Netflix, cut what I don't use"

Nothing is saved: the demo passes persist=False. Like starting the API, it
applies any pending (additive) database migration first, with a backup.
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from datetime import date, timedelta

from backend import db, dependencies
from backend.models import OptimizeRequest
from backend.optimizer_agent import GuardrailBlocked, run_optimizer

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")  # ₹ on the Windows console


def sample_portfolio() -> list[dict]:
    soon = (date.today() + timedelta(days=6)).isoformat()
    return [
        {"id": "netflix", "name": "Netflix", "category": "video_streaming", "price": 649,
         "uses_per_month": 20, "must_keep": True},
        {"id": "prime", "name": "Prime Video", "category": "video_streaming", "price": 299, "uses_per_month": 4},
        {"id": "hotstar", "name": "Disney+ Hotstar", "category": "video_streaming", "price": 299,
         "uses_per_month": 2, "renewal_date": soon},
        {"id": "spotify", "name": "Spotify", "category": "music", "price": 119,
         "uses_per_month": 1, "last_used_days_ago": 40},
        {"id": "google_one", "name": "Google One", "category": "cloud_storage", "price": 130, "annual_price": 1300},
        # YouTube Premium includes YouTube Music, so it shares the music category with Spotify.
        {"id": "youtube", "name": "YouTube Premium", "category": "music", "price": 149, "uses_per_month": 25,
         "cheaper_tier": {"name": "Premium Lite", "price": 89, "features_lost": ["Background play", "Downloads"]}},
    ]


def money(v: float) -> str:
    return f"₹{v:,.2f}"


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--budget", type=float, default=None, help="target monthly total in INR")
    parser.add_argument("--goal", default="Cut what I don't use and avoid paying twice for the same thing.")
    args = parser.parse_args()

    report = db.migrate()
    if report["from_version"] != report["to_version"]:
        print(f"Database migrated v{report['from_version']} -> v{report['to_version']} (backup: {report['backup']})")

    store = await asyncio.to_thread(dependencies._app.get_vectorstore)
    request = OptimizeRequest(user_id="optimize_demo", goal=args.goal, monthly_budget=args.budget,
                              subscriptions=sample_portfolio())
    try:
        result = await run_optimizer("optimize_demo", request, store, persist=False)
    except GuardrailBlocked as exc:
        print(f"Blocked by the guardrail: {exc.message}")
        return 1
    r = result.response

    print("\nSUBSCRIPTION OPTIMIZATION PLAN")
    print("=" * 60)
    print(f"Current:    {money(r.current_monthly_total)}/month  ({money(r.current_annual_total)}/year)")
    print(f"Optimized:  {money(r.optimized_monthly_total)}/month  ({money(r.optimized_annual_total)}/year)")
    print(f"Saves:      {money(r.total_annual_savings)}/year ({r.percentage_savings:.1f}%)")
    if r.budget:
        b = r.budget
        print(f"Budget:     {money(b.target)}/month: " + ("met" if b.met else f"not met, {money(b.gap)} over")
              + ("" if b.achievable else " (not reachable with these subscriptions)"))
    print(f"Source:     {'deterministic fallback: ' + (r.fallback_reason or '') if r.used_fallback else 'agent (qwen2.5:3b)'}")
    print(f"Verified:   {r.verification_passed} {'; '.join(r.verification_notes)}")

    print("\nACTIONS")
    for a in r.actions:
        chunks = f"  [policy chunks {', '.join(map(str, a.policy_chunks))}]" if a.policy_chunks else ""
        print(f"- {a.action.upper():22} {a.name:18} {money(a.annual_savings):>12}/yr{chunks}")
        print(f"  {a.reason}")
    if r.renewal_alerts:
        print("\nRENEWALS")
        for al in r.renewal_alerts:
            print(f"- {al.name}: {al.renewal_date} (in {al.days_until} days), next charge {money(al.charge_amount)}")
    print("\nSUMMARY")
    print(r.ai_summary)
    if r.unsupported_citations:
        print(f"(cites chunks that were not retrieved: {r.unsupported_citations})")
    if r.missing_info:
        print("\nMISSING INFORMATION")
        for m in r.missing_info:
            print(f"- {m}")

    print("\nTRACE")
    for line in result.recorder.as_lines():
        print(f"  {line}")
    print(f"\nElapsed: {r.elapsed_seconds:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
