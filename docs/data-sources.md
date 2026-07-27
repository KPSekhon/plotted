# Data sources, terms and attribution

Everything Plotted claims about a title comes from somewhere, and every source
comes with obligations. This page is the record of both. It is also served at
`/legal/data-sources` in the application.

## Sources

| Need | Source | Terms | Refresh cadence |
|---|---|---|---|
| Title metadata, posters, cast, genres, keywords, seasons, episodes | TMDB API | Free for non-commercial use. Attribution required. Must not imply endorsement. | Watchlist titles daily, catalogue weekly |
| Regional streaming availability (provider, access type) | TMDB `/watch/providers`, powered by JustWatch | Included with TMDB. Attribution required. Redistribution of the data as a dataset is prohibited. | Watchlist titles daily |
| Rental and purchase pricing | Watchmode or a JustWatch partner API | Freemium, low free quotas | On demand, cached |
| Ratings and popularity priors | TMDB; MovieLens 32M for model bootstrapping | MovieLens is research-use, non-commercial, citation required | Weekly / one-off |
| Provider plan pricing | Manually curated `provider_plans` table | Public pricing pages, entered by hand, versioned | Manual — see [seed/provider-plans.md](seed/provider-plans.md) |
| Viewing history import | User-initiated Netflix and Prime data exports | User-supplied and user-consented | On request |

## Three constraints the design works around

**No scraping of provider sites.** It violates terms of service, breaks
constantly, and is an immediate negative signal to anyone security-minded.
Plotted has no scraper and will not gain one.

**No forward-looking removal dates exist.** No public feed publishes reliable
removal dates across providers. Plotted therefore does not display them. What it
does instead is diff nightly availability snapshots to detect changes that have
already happened — sourced, timestamped and true — and, once enough snapshot
history exists, present removal *risk* as a calibrated probability rather than a
date.

**Availability data will sometimes be wrong.** JustWatch coverage of Crave and
smaller Canadian services is thinner than its US coverage. The `confidence`
column, the `source_checked_at` timestamp and the user-facing "report incorrect
availability" action are therefore load-bearing product features, not polish.
Every availability statement renders with its source, region and last-verified
time, so the product can be wrong gracefully instead of confidently.

## Compliance checklist

- [x] TMDB attribution in the application footer, on every page displaying its data
- [x] JustWatch attribution on availability displays
- [x] "Not endorsed or certified by TMDB" stated in the README and the application
- [ ] MovieLens citation in the README and in the evaluation writeup — phase 7
- [x] `/legal/data-sources` page listing every source, its terms and its cadence
- [x] No bulk availability dataset redistributed, including in this repository
      (enforced by `.gitignore`)
- [ ] `robots.txt` and rate-limit compliance on every outbound integration — phase 2
- [ ] Imported viewing history deleted when the linked account is disconnected — phase 12

Unchecked items belong to phases that have not started. They are listed now so
that they are requirements rather than afterthoughts.

## Attribution text

> This product uses the TMDB API but is not endorsed or certified by TMDB.

> Streaming availability data provided by JustWatch.
