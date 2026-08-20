"""Check every derived seed id resolves on TMDB, before anything is deployed.

The seed list is 400 tmdb ids taken from a Watchmode enumeration. Watchmode's
tmdb_id is not guaranteed to be right: it can be stale, point at a title that has
since been merged or deleted, or carry the wrong media type. None of that shows
up until `make seed` runs, and by then it looks like "17 titles unmatched" in a
log nobody is watching.

This asks TMDB directly. It is free quota, it needs no database, and it answers
two questions the seed run would otherwise answer slowly:

  * does the id resolve at all, and as the media type the seed claims?
  * does it have a runtime?

The second matters more than it sounds. Tonight Mode's time filter is a hard
filter, and a title with no runtime cannot be recommended into a time window at
all -- so a seed full of runtime-less titles produces a recommender that
mysteriously refuses to answer.

    python tools/seed/validate_seed.py

Writes tools/seed/validation-report.md. Makes one request per id, sequentially,
with a small delay: TMDB is generous but this is 400 requests and there is no
reason to be rude about it.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
SEED = ROOT / "plotted-api" / "src" / "main" / "resources" / "seed" / "canadian-seed.txt"
REPORT = ROOT / "tools" / "seed" / "validation-report.md"

ENTRY = re.compile(r"^tmdb:(\d+):(movie|tv|series)\s*(?:#\s*(.*))?$")


def token() -> str:
    env = ROOT / ".env"
    if not env.exists():
        sys.exit(".env not found. TMDB_READ_ACCESS_TOKEN lives there and is git-ignored.")
    for line in env.read_text(encoding="utf-8").splitlines():
        if line.startswith("TMDB_READ_ACCESS_TOKEN="):
            value = line.split("=", 1)[1].strip()
            if value:
                return value
    sys.exit("TMDB_READ_ACCESS_TOKEN is not set in .env")


def fetch(path: str, bearer: str) -> dict | None:
    request = urllib.request.Request(
        f"https://api.themoviedb.org/3{path}",
        headers={"Authorization": f"Bearer {bearer}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        if failure.code == 404:
            return None
        raise


def runtime_of(detail: dict, is_series: bool) -> int | None:
    """Minutes as TMDB states them, which is not the same as what Plotted ends up with."""
    if not is_series:
        return detail.get("runtime") or None
    runtimes = detail.get("episode_run_time") or []
    return runtimes[0] if runtimes else None


def main() -> None:
    bearer = token()
    entries = []
    for line in SEED.read_text(encoding="utf-8").splitlines():
        match = ENTRY.match(line.strip())
        if match:
            entries.append((int(match.group(1)), match.group(2), (match.group(3) or "").strip()))

    print(f"Checking {len(entries)} derived ids against TMDB...")

    missing: list[tuple[int, str]] = []
    # Split by media type, because the consequence is completely different and
    # the first run of this script (2026-08-14) reported 152 titles as
    # runtime-less without saying that every one of them was a series.
    #
    # A FILM with no `runtime` is genuinely unusable: Tonight's time filter is
    # hard, there is nothing to derive a length from, and it will silently never
    # be recommended into a window.
    #
    # A SERIES with no `episode_run_time` is not. TMDB leaves that field empty
    # for most shows, and `SeasonRepository.recalculateTotalRuntime` stopped
    # depending on it -- it derives the typical episode from the episodes it is
    # already summing. That change took the seeded catalogue from 77 of 260
    # series having an episode length to 260 of 260. Reporting these as a
    # blocking problem would send somebody to fix data that fixes itself.
    no_runtime_film: list[tuple[int, str]] = []
    no_runtime_series: list[tuple[int, str]] = []
    wrong_type: list[tuple[int, str]] = []
    ok = 0

    for index, (tmdb_id, kind, label) in enumerate(entries, start=1):
        is_series = kind in ("tv", "series")
        path = f"/tv/{tmdb_id}" if is_series else f"/movie/{tmdb_id}"

        try:
            detail = fetch(path, bearer)
        except Exception as failure:  # noqa: BLE001 - report and continue
            print(f"  ! {tmdb_id}: {failure}")
            missing.append((tmdb_id, label))
            continue

        if detail is None:
            # Not found as the claimed type. Try the other one before calling it
            # dead -- a wrong media type is a fixable seed entry, a dead id is not.
            other = f"/movie/{tmdb_id}" if is_series else f"/tv/{tmdb_id}"
            alternative = None
            try:
                alternative = fetch(other, bearer)
            except Exception:  # noqa: BLE001
                pass
            if alternative is not None:
                wrong_type.append((tmdb_id, label))
            else:
                missing.append((tmdb_id, label))
            continue

        if runtime_of(detail, is_series) is None:
            (no_runtime_series if is_series else no_runtime_film).append((tmdb_id, label))
        else:
            ok += 1

        if index % 50 == 0:
            print(f"  {index}/{len(entries)}")
        # TMDB's published limit is generous and unenforced in practice, but 400
        # sequential requests deserve some manners.
        time.sleep(0.05)

    lines = [
        "# Seed validation",
        "",
        f"Checked {len(entries)} derived ids against TMDB. No database involved, free quota only.",
        "",
        f"- **{ok}** resolve and state a runtime",
        f"- **{len(no_runtime_film)}** films state no runtime — **blocking**: Tonight's time "
        "filter is hard and there is nothing to derive a length from, so these can never be "
        "recommended into a window",
        f"- **{len(no_runtime_series)}** series state no `episode_run_time` — **not blocking**: "
        "ingest derives the typical episode from the episodes it already sums, which is what took "
        "the seeded catalogue from 77 of 260 series with an episode length to 260 of 260",
        f"- **{len(wrong_type)}** exist under the *other* media type — the seed line is wrong",
        f"- **{len(missing)}** do not resolve at all",
        "",
        "Only the first two lines are worth acting on, and only the films are urgent. TMDB leaves "
        "`episode_run_time` empty for most shows; Plotted stopped depending on it deliberately.",
        "",
    ]

    for heading, rows in (
        ("Wrong media type", wrong_type),
        ("Not found", missing),
        ("Films with no runtime (blocking)", no_runtime_film),
        ("Series with no episode_run_time (derived at ingest, not blocking)", no_runtime_series),
    ):
        if rows:
            lines += [f"## {heading}", ""]
            lines += [f"- `tmdb:{i}` — {label or '(unnamed)'}" for i, label in rows]
            lines += [""]

    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("")
    print(
        f"{ok} good, {len(no_runtime_film)} films with no runtime (blocking), "
        f"{len(no_runtime_series)} series with no episode_run_time (derived at ingest), "
        f"{len(wrong_type)} wrong type, {len(missing)} missing"
    )
    print(f"Report: {REPORT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
