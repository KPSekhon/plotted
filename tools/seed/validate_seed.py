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
    """Minutes, resolved the way the catalogue resolves them."""
    if not is_series:
        return detail.get("runtime") or None
    # A series carries per-episode runtimes; the catalogue sums real episodes at
    # ingest. Here we only need to know whether *anything* is known, so the
    # average is enough to answer "will this be usable in a time filter".
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
    no_runtime: list[tuple[int, str]] = []
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
            no_runtime.append((tmdb_id, label))
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
        f"- **{ok}** resolve and have a runtime",
        f"- **{len(no_runtime)}** resolve with no runtime — ingestible, but invisible to "
        "Tonight Mode's time filter until TMDB fills it in",
        f"- **{len(wrong_type)}** exist under the *other* media type — the seed line is wrong",
        f"- **{len(missing)}** do not resolve at all",
        "",
    ]

    for heading, rows in (
        ("Wrong media type", wrong_type),
        ("Not found", missing),
        ("No runtime", no_runtime),
    ):
        if rows:
            lines += [f"## {heading}", ""]
            lines += [f"- `tmdb:{i}` — {label or '(unnamed)'}" for i, label in rows]
            lines += [""]

    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n{ok} good, {len(no_runtime)} runtime-less, {len(wrong_type)} wrong type, {len(missing)} missing")
    print(f"Report: {REPORT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
