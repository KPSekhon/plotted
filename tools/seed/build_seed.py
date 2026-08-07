"""Turn the Watchmode enumeration into a stratified seed list.

No API calls. This is arithmetic on data already fetched, which is the whole
point of inverting the query: the expensive part happened once, and the
selection can be re-run and argued with for free.

WHAT IT SELECTS

Section 7.3 wants roughly 500 titles weighted toward recent releases, because
people ask about what is current. `docs/NEXT.md` fixes the shape:

    2026 22% · 2025 20% · 2024 19% · 2023 16% · 2022 13% · 2021 10%

split evenly between films and series. Series carry more weight than their count
suggests -- they are where the runtime work and the "is this a commitment"
question actually bite.

DERIVED IS 400, NOT 500, AND THAT IS DELIBERATE

The 119 curated titles already in the seed stay. They were chosen to span the
awkward cases -- Canadian originals, CBC Gem, anime, subtitled film, titles that
have drifted between services -- and that judgement is exactly what an
enumeration cannot supply. Popularity ordering would quietly drop most of them.

So 400 derived plus 119 curated is ~500 once the overlap between them is
resolved at ingest. The exact unique figure is whatever the seed run reports;
this script cannot know it, because the curated entries are names and the
derived ones are ids, and matching them is what TMDB resolution is for.

    python tools/seed/build_seed.py
"""

from __future__ import annotations

import collections
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
ENUMERATION = ROOT / "tools" / "seed" / "watchmode-ca.json"
SEED = ROOT / "plotted-api" / "src" / "main" / "resources" / "seed" / "canadian-seed.txt"

# Recency-weighted, from docs/NEXT.md, scaled to 400.
ALLOCATION = {2026: 88, 2025: 80, 2024: 76, 2023: 64, 2022: 52, 2021: 40}

MEDIA = ("movie", "tv_series")


def load() -> dict[int, dict]:
    """Unique titles, keyed by tmdb id, remembering every provider carrying them."""
    rows = json.loads(ENUMERATION.read_text(encoding="utf-8"))
    merged: dict[int, dict] = {}
    for row in rows:
        key = row["tmdb_id"]
        if key not in merged:
            merged[key] = dict(row, providers={row["provider"]})
        else:
            merged[key]["providers"].add(row["provider"])
            # Keep the strongest signal seen. A title on two services appears
            # twice with slightly different percentiles.
            if (row.get("popularity_percentile") or 0) > (merged[key].get("popularity_percentile") or 0):
                merged[key]["popularity_percentile"] = row["popularity_percentile"]
    return merged


def select(titles: dict[int, dict]) -> list[dict]:
    """Most popular first, within each (year, media) bucket."""
    chosen: list[dict] = []
    for year, quota in ALLOCATION.items():
        per_media = quota // 2
        for index, media in enumerate(MEDIA):
            # The odd one goes to film in odd quotas, arbitrarily but
            # consistently, so re-running produces the same list.
            want = per_media + (quota % 2 if index == 0 else 0)
            bucket = [t for t in titles.values() if t["year"] == year and t["media"] == media]
            bucket.sort(key=lambda t: (-(t.get("popularity_percentile") or 0), t["tmdb_id"]))
            if len(bucket) < want:
                print(f"  ! {year}/{media}: wanted {want}, only {len(bucket)} available")
            chosen.extend(bucket[:want])
    return chosen


def existing_curated() -> list[str]:
    """The hand-picked names, kept verbatim including their comments."""
    lines = SEED.read_text(encoding="utf-8").splitlines()
    # Everything from the first curated section onwards. The header is rewritten
    # below; the curation is not touched.
    start = next(i for i, line in enumerate(lines) if line.startswith("# ---"))
    return lines[start:]


def main() -> None:
    titles = load()
    chosen = select(titles)

    header = f"""# Plotted :: Canadian catalogue seed
#
# Two kinds of line, and the difference is the provenance:
#
#   tmdb:634649:movie   Derived from a live Watchmode enumeration of what is
#                       actually streaming in Canada. Resolved by id, so there
#                       is no name-matching to get wrong.
#   Schitt's Creek      Curated by hand. Resolved through TMDB search.
#
# Blank lines, lines starting with #, and anything after a # are ignored.
#
#     make seed          (needs TMDB_READ_ACCESS_TOKEN and a running database)
#
# WHERE THE DERIVED HALF CAME FROM
#
# `tools/seed/enumerate_watchmode.py` asked Watchmode which titles each Canadian
# subscription service carries, one page of 250 per service per media type --
# {len(titles)} unique titles for 18 requests, against a 2500-a-month cap.
# `tools/seed/build_seed.py` then bucketed them by release year and took the
# most popular of each, no further requests needed.
#
# That is the inversion worth understanding: the old approach was to guess a
# list and check it one title at a time, which costs a request per guess and
# answers only for titles somebody thought of. This starts from what is
# streaming and selects from it.
#
# The enumeration is a snapshot, not a subscription. It says these titles were
# on these services on the day it ran; the nightly job is what keeps that
# current, and disagreements between it and TMDB's watch-providers belong in the
# availability-correction endpoint rather than in edits to this file.
#
# WHY THE CURATED HALF STAYS
#
# {len(existing_curated_titles := [l for l in existing_curated() if l.strip() and not l.startswith('#')])} titles below were chosen by a person to span the awkward cases --
# Canadian originals, public broadcasters, anime, subtitled film, titles that
# have drifted between services. Popularity ordering drops most of them, and
# they are what keeps the coverage numbers informative rather than flattering.
# Taste is not an API.
#
# Some of them will also appear in the derived set. Ingestion is idempotent and
# keyed on tmdb id, so the overlap resolves on the first run rather than
# producing duplicates -- which is why this file has more lines than it has
# titles, and why the seed run's own report is the number to quote.

# === Derived from live Canadian availability ===============================
"""

    lines = [header]
    by_year: dict[int, list[dict]] = collections.defaultdict(list)
    for title in chosen:
        by_year[title["year"]].append(title)

    for year in sorted(by_year, reverse=True):
        lines.append(f"\n# --- {year} " + "-" * (60 - len(str(year))))
        for title in sorted(by_year[year], key=lambda t: (t["media"], t["title"] or "")):
            kind = "movie" if title["media"] == "movie" else "tv"
            providers = ",".join(sorted(title["providers"]))
            lines.append(f"tmdb:{title['tmdb_id']}:{kind}  # {title['title']} — {providers}")

    lines.append("\n\n# === Curated by hand =======================================================\n")
    lines.extend(existing_curated())

    SEED.write_text("\n".join(lines) + "\n", encoding="utf-8")

    films = sum(1 for t in chosen if t["media"] == "movie")
    print(f"\n{len(chosen)} derived ({films} films, {len(chosen) - films} series)")
    print(f"{len(existing_curated_titles)} curated kept")
    print(f"{len(chosen) + len(existing_curated_titles)} entries written to {SEED.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
