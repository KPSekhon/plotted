"""Enumerate what is actually streaming in Canada, from Watchmode.

The seed used to be a list of title *names* resolved through TMDB search at seed
time, which is a guess-and-check design: you write down what you think is on
Crave, and find out later whether it is. This inverts it. Watchmode's
`/v1/list-titles/` filters by source and returns titles in bulk, each carrying a
`tmdb_id` -- which is Plotted's actual join key -- so the seed can be *derived
from* availability rather than checked against it afterwards.

BUDGET, WRITTEN DOWN BEFORE IT IS SPENT

Watchmode is 2500 requests a MONTH. Hard cap, no way to buy more. This script
takes one page of 250 per (service, type), which is 16 calls -- 0.6% of a month --
and refuses to exceed MAX_CALLS whatever else happens.

That is far below the 150-200 NEXT.md budgeted, because that figure was for
enumerating *complete* catalogues. Picking 500 titles needs only the popular
head, and popularity_desc gives exactly that.

    python tools/seed/enumerate_watchmode.py

Writes tools/seed/watchmode-ca.json. Never re-asks for something already there:
delete the file deliberately if you want a fresh pull.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "tools" / "seed" / "watchmode-ca.json"

# Verified live on 2026-08-06 and recorded in docs/NEXT.md. Subscription
# services only.
#
# Tubi (296) is deliberately absent: free and ad-supported with a catalogue in
# the tens of thousands, so enumerating it would cost more than every
# subscription service combined and change no subscription decision.
#
# Crave Starz (395) is a *tier* of Crave rather than a separate subscription, so
# its titles are folded into Crave. Treating it as its own service would inflate
# coverage, which is the optimiser's primary input.
SOURCES = {
    203: "netflix",
    26: "prime-video",
    372: "disney-plus",
    371: "apple-tv-plus",
    444: "paramount-plus",
    393: "crave",
    395: "crave",  # Starz tier, same subscription
    402: "cbc-gem",
}

# Watchmode's own vocabulary, and it is not the obvious one: `tv` is a 400.
# The first run of this script spent eight calls discovering that, which is the
# argument for the resumability below rather than against probing.
TYPES = ("movie", "tv_series")

# One page per (source, type). 8 sources x 2 types = 16.
MAX_CALLS = 20
PAGE_SIZE = 250


def api_key() -> str:
    """Read the key from .env without ever printing it."""
    env = ROOT / ".env"
    if not env.exists():
        sys.exit(".env not found. The Watchmode key lives there and is git-ignored.")
    for line in env.read_text(encoding="utf-8").splitlines():
        if line.startswith("WATCHMODE_API_KEY="):
            key = line.split("=", 1)[1].strip()
            if key:
                return key
    sys.exit("WATCHMODE_API_KEY is not set in .env")


def main() -> None:
    # Resume rather than restart. NEXT.md's rule is "persist before you re-ask",
    # and it earned its place here: the first run spent eight calls on movies and
    # then failed on every series because the type value was wrong. Re-running
    # from scratch would have spent those eight again to learn nothing.
    rows: list[dict] = []
    done: set[tuple[str, str]] = set()
    if OUT.exists():
        rows = json.loads(OUT.read_text(encoding="utf-8"))
        done = {(r["provider"], r["media"]) for r in rows if "media" in r}
        print(f"Resuming: {len(rows)} rows already cached across {len(done)} pulls.")

    key = api_key()
    calls = 0

    for source_id, slug in SOURCES.items():
        for media in TYPES:
            if (slug, media) in done:
                print(f"{slug}/{media}: cached, not re-asked")
                continue
            if calls >= MAX_CALLS:
                # A budget that is discovered afterwards is not a budget. This
                # stops rather than trimming, so a partial pull is obvious.
                sys.exit(f"Stopped at the {MAX_CALLS}-call ceiling with work left.")

            query = urllib.parse.urlencode(
                {
                    "apiKey": key,
                    "source_ids": source_id,
                    "regions": "CA",
                    "types": media,
                    "sort_by": "popularity_desc",
                    "limit": PAGE_SIZE,
                    "page": 1,
                }
            )
            url = f"https://api.watchmode.com/v1/list-titles/?{query}"

            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    payload = json.load(response)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as failure:
                # Deliberately no retry. A retry storm against a monthly quota
                # can erase weeks, and the partial data already collected is
                # worth more than one more attempt.
                print(f"FAILED {slug}/{media}: {failure}. Not retrying; writing what we have.")
                break

            calls += 1
            titles = payload.get("titles", [])
            for row in titles:
                if row.get("tmdb_id") is None:
                    # No join key, so useless here however popular it is.
                    continue
                rows.append(
                    {
                        "tmdb_id": row["tmdb_id"],
                        "tmdb_type": row.get("tmdb_type"),
                        "title": row.get("title"),
                        "year": row.get("year"),
                        "popularity_percentile": row.get("popularity_percentile"),
                        "provider": slug,
                        "media": media,
                    }
                )
            print(
                f"{slug}/{media}: {len(titles)} of {payload.get('total_results')} "
                f"(call {calls}/{MAX_CALLS})"
            )

    OUT.write_text(json.dumps(rows, indent=1), encoding="utf-8")
    unique = len({r["tmdb_id"] for r in rows})
    print(f"\n{len(rows)} rows, {unique} unique tmdb ids, {calls} Watchmode calls spent.")


if __name__ == "__main__":
    main()
