# The working plan

What to do next, why, and how to spend the API budgets doing it.
`PROGRESS.md` records where the project *is*; this records where it goes.

Last updated: 2026-08-06. Watchmode calls spent verifying the facts below: **2**.

---

## Part 1 — The data sources

Three sources. One is free, two are hard-capped, and the difference should drive
every design decision.

| Source | Quota | Window | Refills |
|---|---|---|---|
| TMDB | generous | — | n/a |
| **Watchmode** | **2500 requests** | **month** | 1st of the month |
| **MDBList** | **1000 requests** | **day** | midnight |

Keys live in `.env` (git-ignored). Placeholders and quota notes are in
`.env.example`. **Never inline a key, never call a quota'd API from a test or a
CI job** — WireMock, the way `TmdbClientTest` already does it.

### The single most important thing: invert the query

The obvious design is "for each title, ask which services carry it". For 500
titles that is 500 Watchmode calls — 20% of the month, for a partial answer.

**Don't do that.** `/v1/list-titles/` filters by *source* and returns titles in
bulk, each carrying a `tmdb_id`, which is exactly Plotted's join key
(`titles.external_id` where `external_source = 'tmdb'`).

Verified live on 2026-08-06:

```
GET /v1/list-titles/?apiKey=…&source_ids=393&regions=CA&types=movie&sort_by=popularity_desc&limit=250
→ { titles: [...], page, total_pages, total_results }
   total_results: 2303   (Crave, movies, CA)
   per title: id, title, year, type, tmdb_id, tmdb_type, imdb_id, popularity_percentile
```

So the whole Canadian availability map costs roughly **one call per 250 titles
per service**, not one per title. Crave's entire film catalogue is ~10 calls.
Eight subscription services is on the order of **150–200 calls for complete
coverage** — 6–8% of a month, and it answers for *every* title at once rather
than only the ones you thought to ask about.

Better still, it lets the seed be **derived from** availability rather than
guessed and then checked: start from what is actually streaming in Canada.

### Canadian source IDs (verified, 2026-08-06)

| Service | Watchmode `source_id` | Plotted slug |
|---|---|---|
| Netflix | 203 | `netflix` |
| Prime Video | 26 | `prime-video` |
| Disney+ | 372 | `disney-plus` |
| AppleTV+ | 371 | `apple-tv-plus` |
| Paramount+ | 444 | `paramount-plus` |
| Crave | 393 | `crave` |
| Crave Starz | 395 | `crave` (alias — same subscription) |
| CBC Gem | 402 | `cbc-gem` |
| Hayu | 392 | *(not seeded)* |
| Tubi TV | 296 | `tubi` |

`/v1/sources/?regions=CA` returns 87 sources. These are the ones that matter.
**Crave Starz is a tier of Crave, not a separate subscription** — it belongs in
`provider_aliases` with `alias_kind = 'plan_tier'`, exactly like the TMDB
reseller channels phase 2 already collapses. Getting this wrong inflates
coverage, which is the optimiser's primary input.

**Skip Tubi for bulk enumeration.** It is free and ad-supported with a catalogue
in the tens of thousands; enumerating it would cost more than every subscription
service combined and change no subscription decision.

### Building the seed: 500 titles across six years

The brief: top titles from the last six years, weighted toward recent, because
people ask about what is current. Release year is what should be spread — a 2021
film still streaming today is a perfectly good candidate.

Recency-weighted allocation, 500 titles:

| Release year | Titles | Share |
|---|---|---|
| 2026 | 110 | 22% |
| 2025 | 100 | 20% |
| 2024 | 95 | 19% |
| 2023 | 80 | 16% |
| 2022 | 65 | 13% |
| 2021 | 50 | 10% |

Split roughly 50/50 between films and series. Series matter more than their
count suggests: they are where the runtime work and the "is this a commitment"
question actually bite.

**The procedure, and what each step costs:**

1. **Enumerate availability from Watchmode** — `/v1/list-titles/` per service,
   `regions=CA`, `sort_by=popularity_desc`, `limit=250`, both `types`. Persist
   every row keyed by `tmdb_id`. **~150–200 calls, one time.** This is now the
   ground truth for "what is streaming in Canada".
2. **Rank and stratify locally** — no API calls. Join the enumeration against
   TMDB popularity, bucket by release year, take the allocation above. All of
   this is arithmetic on data already fetched.
3. **Hydrate through TMDB** — the existing phase 2 ingestion, unchanged. Details
   and watch-providers per title. **~1000 TMDB calls, free.**
4. **Cross-check ratings via MDBList** — only for the final 500. **500 calls,
   50% of one day.** Do it in one pass and cache; there is no reason to ask
   twice.
5. **Reconcile and record disagreements.** Where TMDB's watch-providers and
   Watchmode's enumeration disagree, that disagreement is the interesting data —
   file it through the availability-correction endpoint rather than editing
   rows. Two independent sources disagreeing is exactly the signal the
   confidence column exists for.

Total one-time cost: **~200 Watchmode (8% of a month)**, **500 MDBList (half a
day)**, ~1000 free TMDB. That leaves ~2300 Watchmode calls a month for ongoing
re-verification.

**Ongoing:** re-enumerate the subscription services monthly (~200 calls) rather
than polling per title. A monthly full refresh is both cheaper and more complete
than daily per-title checks, and the nightly TMDB snapshot job already covers
day-to-day drift.

### Rules that keep the budget safe

- **Persist before you re-ask.** `availability_snapshots` exists for this.
  A title answered today must not be asked again tomorrow.
- **Write the budget down before spending it.** "200 calls, 8% of the month,
  buys the complete Canadian map" is a decision. A loop that discovers its cost
  afterwards is not.
- **Never retry into the quota.** A retry storm can erase weeks. The token
  bucket and selective-retry logic in `TmdbClient` is the model to copy, with a
  persistent counter added — an in-memory one resets on restart and lies.
- **Use TMDB for anything popularity-shaped.** `popularity` and `trending` are
  free TMDB fields. Paying Watchmode or MDBList quota for them is spending a
  scarce resource on something you already have.

---

## Part 2 — What is missing, phase by phase

### Phase 2 — Catalogue (done; seeding owed)

Missing: the pipeline has never run end to end, and the seed is 119 titles.

**Plan.** Needs Postgres, so it is gated on Docker. Then the five-step procedure
above. This is now *mechanically* solvable — my earlier claim that verifying the
seed "needs a person" was only half right. Watchmode answers "is this actually
on Crave in Canada". What still needs a person is deciding which titles are
worth having on the list at all; taste is not an API.

Also outstanding: `PLOTTED_SNAPSHOT_ENABLED` in the first environment that runs
continuously. Every night not collected is unrecoverable, and Plot Armour
(phase 10) needs months of it.

### Phase 3 — Watchlists, subscriptions, coverage (done)

Nothing missing. The one thing to keep intact: coverage is **priority-weighted,
not counted**, and unchecked titles are **excluded from the denominator**, not
scored as uncovered. `CoverageServiceTest` pins both.

### Phase 4 — Queue Theory (done)

Nothing missing. `blocked_titles` still has no reader — it belongs with phase
4's hard filters conceptually but was deliberately left for a later pass; see
the note in `CatalogueQueryService`.

### Phase 5 — Cancel Culture (in progress)

Built: `PlanChecker` (the independent verifier, complete and tested),
`PlanSolver` (the CP-SAT model, **never executed**), the SPI extensions it needs.

Missing:

1. **The service** that gathers inputs from the four SPIs and calls the solver.
2. **The API and screen.**
3. **The test that matters most:** solver and checker agreeing on a plan neither
   produced alone. Right now the checker is proven and the model is not.
4. **The CP-SAT crash on Windows**, still unexplained. `msvcp140.dll` and
   `vcruntime140_1.dll` are current (14.44.35211.0), so the obvious cause is
   ruled out. Start from the module list in `hs_err_pid*.log`. CI is Linux and
   unaffected, so **phase 5 can be finished and verified through CI regardless**
   — do not let this block the work.

**Plan.** Open the PR early; CI is the first real execution of `PlanSolver` and
it will find things. Expect the model to be wrong somewhere on first run — that
is what the independent checker is for, and `violations` being non-empty on a
returned plan is a defect in the model, reported rather than thrown.

---

## Part 3 — The phases after, and how to do them well

### Phase 6 — Polish and deploy ← **the résumé line. Stop here if interviews start.**

Demo mode with no signup, a 90-second video, an architecture diagram,
near-zero-cost deployment.

**How to make it excellent:** the demo should show the *refusals*. Tonight Mode
returning "nothing fits: everything on your list is longer than the time you
have" is more convincing than any successful recommendation, because every
competitor can show a list of films and almost none can show a system that knows
when to say no. Same for the optimiser explaining an infeasible plan. Lead with
those.

Two complete headline features with measured results beat five half-built ones.
The spec is explicit and it is right.

### Phase 7 — Evaluation harness ← highest value per hour in the project

Baselines (random, popularity, watchlist-recency, the linear model), NDCG@3,
temporal splits, bootstrap confidence intervals, ablations, `EVALUATION.md`.

**How to make it excellent:** this is what converts "I built a recommender" into
"I measured it against baselines and here is where it wins and where it does
not". The propensity column in `recommendation_items` exists precisely so this
phase is possible — every off-policy estimator divides by it. Report the
ablation where renormalisation is removed; it is a concrete, defensible number
showing a subtle decision mattered.

Be honest about where the linear model *loses* to popularity. A harness that
only produces flattering numbers is not a harness.

### Phase 8 — Learned ranking

LightGBM from MovieLens 32M via TMDB ids, ONNX, in-process. Guard
training-serving skew with a shared feature schema and a golden-vector
equivalence test — that test is the whole point.

### Phases 9–11

Pilot Season (Bradley–Terry, fixed ladder first — adaptive selection has no data
to be adaptive about yet), Temporal workflows and Plot Armour, End Credits
analytics. Decision latency and accepted-and-completed rate are the two metrics
that carry the product's thesis.

---

## The standing lesson

Four of the bugs found so far were mechanisms that reported success while doing
nothing: reuse detection whose revocation was rolled back, a drift check that
could never fail, a build step that had never run, an exploration slot logging a
propensity of zero. None of them threw. All of them passed their tests.

**Be suspicious of any check that has never had the chance to fail**, and prefer
a second implementation that disagrees over a test that agrees.
