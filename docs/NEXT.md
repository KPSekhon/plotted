# The working plan

What to do next, why, and how to spend the API budgets doing it.
`PROGRESS.md` records where the project *is*; this records where it goes.

Last updated: 2026-08-08. Watchmode calls spent verifying the facts below: **2**,
none of them since — the seed enumeration has not been run, so the whole 2500 for
this month is still available.

---

## Part 0 — The product definition changed on 2026-08-08

Read this before the rest of the file, because it reorders it.

Plotted is no longer *"order what you already chose"*. It is:

> **Learn what I enjoy, search my watchlist and the wider catalogue, then find
> the best thing for the night I actually have.**

The reasoning and the architecture are in
[ADR 0009](adr/0009-discovery-and-taste-as-product-inputs.md). The short version:
candidate generation separates from ranking, discovery becomes a real subsystem,
taste feeds candidate *generation* before it ever touches the score, and the
linear ranker is versioned rather than mutated.

### The five open questions, answered

Kanwar's decisions, recorded so they are not re-litigated:

1. **Pilot Season** — a product feature, not a portfolio flourish. Keep the
   Bradley–Terry fitter; cut mandatory onboarding to 5–7 comparisons and call
   the result a weak prior honestly; wire it into taste-based candidate
   retrieval; log `tasteMatch` in shadow before it ever moves a ranking.
2. **The demo's fabricated data** — label it. Quietly. *Done 2026-08-08:* a
   `Demo` badge in the chrome plus a caption on the three screens making
   behavioural claims.
3. **Per-episode progress** — minimal now, events later. A `user_series_progress`
   row carrying the last completed episode unlocks "next episode", remaining
   runtime, the "you are here" marker, and feasibility before a removal date.
   **Do not claim viewing *pace*** — position is not pace, and pace needs
   completion events over time. Plot Armour says "at your configured 3 hours a
   week", never "at your current pace".
4. **Discovery** — in scope, and core. But Plotted never becomes a browsing
   feed: it searches widely so the user does not have to, and still returns one
   pick and two alternates.
5. **An unreleased title in the demo** — yes, exactly one. The "no verified
   route in your region" refusal is otherwise invisible, and it is one of the
   things a competitor cannot show. More than one starts to read as broken.

### The order to work in

**P0 — truth and stability.** These protect the only thing the product is
selling.

1. ~~Label the demo's fixture data.~~ *Done 2026-08-08.*
2. ~~Stop unverified prices reaching Cancel Culture.~~ *Done 2026-08-13.*
   `provider_plans.price_provenance` (V18), three states, and only
   `USER_ENTERED` and `VERIFIED` reach the objective. A reference-only price
   makes its service uncostable, and the titles that depend on it are reported
   under `unconfirmedPrice` — kept apart from `unpricedService` because it is
   the only exclusion the user can clear themselves.
3. ~~Fix the auth 500.~~ *Done 2026-08-08, and it was not where it was thought
   to be — see `PROGRESS.md`.*
4. Isolate CP-SAT from the API JVM. A native solver crash currently takes every
   endpoint with it. **Not started** — design notes below.
5. Turn on `PLOTTED_SNAPSHOT_ENABLED`. Still the only clock that cannot be
   rewound, and still blocked on an environment that runs continuously.
6. ~~Fix or remove `verify:api`.~~ *Done 2026-08-08.*

**P1 — make the core promise true.** Minimal series progress; resolve a series
recommendation to an actual episode; introduce candidate source; wider-catalogue
candidate generation; populate `tasteMatch` in production; connect Pilot Season
to discovery; specific no-route explanations.

**P2 — validate the personalisation.** Shorten Pilot onboarding; run taste in
shadow; gather acceptance and completion by source; build ranker v2; fresh
ablation including taste; *only then* let taste move the live score.

**P3 — data quality.** Replace one blunt `metadata_status` with per-capability
eligibility (`SEARCHABLE`, `TONIGHT_ELIGIBLE`, `CANCEL_CULTURE_ELIGIBLE`, …), so
"192 incomplete" becomes "31 missing runtime and block Tonight, 9 missing a
provider mapping, 152 missing display metadata only". Seed the intentional demo
edge cases. Split the test suites by contract — unit, integration, solver, e2e —
so local output reads "integration suite: not executed in this environment"
rather than "138 skipped".

### Isolating CP-SAT (P0 #4), in outline

The solver is a JNI binding and a bad native environment kills the process, not
the request. On Windows one `GET /api/v1/plan` takes the whole API down. That is
a blast-radius problem rather than a developer-machine annoyance, and Temporal
was already chosen for durable execution:

```
Angular → Spring API → create optimisation run → Temporal workflow
                                                      ↓
                                          optimiser worker (own JVM)
                                                      ↓
                                                 OR-Tools CP-SAT
                                                      ↓
                                          PostgreSQL result → API → screen
```

A solver crash then kills the worker, and Temporal reports or retries. Until
that exists, Windows stays marked as not supporting live optimisation and CI
remains the only place the feature has ever run — which should be said plainly
rather than implied.

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

### Phase 2 — Catalogue (list built; ingestion never run)

**Steps 1 and 2 of the procedure above are done.** The enumeration cost 18
Watchmode requests and produced 2550 unique Canadian titles; the stratification
took 400 of them and kept the 119 curated names. Both scripts are in
`tools/seed/` and the raw enumeration is committed as provenance.

Both halves of the earlier claim turned out right. Watchmode answers "is this
actually on Crave in Canada", so that part was mechanical. Deciding which titles
are worth having at all was not — popularity ordering drops the awkward cases the
curated list exists for, which is why it survives alongside the derived set.

**Steps 3 to 5 are still owed and all need a database:** TMDB hydration, the
MDBList cross-check, and filing the TMDB-versus-Watchmode disagreements through
the correction endpoint. `make seed` has never run against live TMDB, so the
ingestion pipeline remains unproven end to end.

Also outstanding: `PLOTTED_SNAPSHOT_ENABLED` in the first environment that runs
continuously. Every night not collected is unrecoverable, and Plot Armour
(phase 10) needs months of it.

### Phase 3 — Watchlists, subscriptions, coverage (done)

Nothing missing. The one thing to keep intact: coverage is **priority-weighted,
not counted**, and unchecked titles are **excluded from the denominator**, not
scored as uncovered. `CoverageServiceTest` pins both.

### Phase 4 — Queue Theory (done)

`blocked_titles` now has its writer, so the filter both recommenders have been
running since phase 4 finally has something to filter on.

The part that was worth getting right, and still is: blocking does not hide a
title from catalogue search. `TitleSearchRepository.search` takes no user id and
cannot filter, a test asserts it, and the reason is that a blocked title missing
from search reads as a missing catalogue entry rather than a preference being
honoured — and leaves no way to change your mind.

### Phase 5 — Cancel Culture (done)

Built and green in CI: `PlanChecker`, `PlanSolver`, `CancelCultureService`,
`GET /api/v1/plan`, the screen, and `PlanSolverAgreementTest` — which enumerates
every possible plan for small instances, scores them with the checker alone, and
asserts CP-SAT found the best of them. See `PROGRESS.md` for what the first
execution turned up.

Still open, and neither blocks anything:

1. **The CP-SAT crash on Windows**, still unexplained. `msvcp140.dll` and
   `vcruntime140_1.dll` are current (14.44.35211.0), so the obvious cause is
   ruled out. Start from the module list in `hs_err_pid*.log`. CI is Linux and
   runs all ten solver tests, so this is a developer-machine inconvenience
   rather than a product problem.
2. **The optimiser still has an empty catalogue to work with.** The seed *list*
   is now ~500 titles rather than 119, but nothing has been ingested, so the
   answers stay uninteresting until `make seed` runs against a real database.
   Prices are unchanged at 17 researched figures, and those stay manual.

**One result worth carrying into phase 6.** A limit on services held *at once*
is much weaker than it looks once the model has a time dimension: given two
months and a one-service limit, the solver rotates rather than giving up half
the list. That rotation is the single best thing to put in the demo — it is a
plan no coverage dashboard could produce, and it is obviously right the moment
you see it.

---

## Part 3 — The phases after, and how to do them well

### Phase 6 — Polish and deploy (built, not deployed)

Demo mode, the architecture diagram, the demo script and the deployment plan are
done — see `PROGRESS.md`. Two things are left and both need a person:

1. **Actually deploy it.** `docs/DEPLOYMENT.md` is the checklist. The three
   likeliest first failures are listed there; the worst is a managed Postgres
   that will not let the migration `CREATE EXTENSION btree_gist`, because
   without it the exclusion constraints silently do not exist and duplicate
   availability rows become possible — which inflates every coverage number the
   optimiser depends on. Do not fence them out to get past it.
2. **Record the 90 seconds.** `docs/DEMO.md` is the shot list. Seed the
   catalogue first, then pick the two runtime figures from the demo persona's
   actual list rather than using the placeholders — getting those wrong is the
   one thing that makes the best moment in the demo look like a bug.

**Remember to turn the snapshot job on** in the first environment that runs
continuously, and note that a scale-to-zero host does not run `@Scheduled`
methods on an idle container. Either pin a minimum instance or drive the jobs
from an external scheduler. Deploying with neither means the nightly snapshot
never runs, which looks exactly like it running and finding nothing.

### Phase 7 — Evaluation harness (built; needs data, not code)

The harness is done and tested, and it produced one result it can defend:
renormalisation is worth 0.0170 NDCG@3. Everything else in `EVALUATION.md` is a
smoke test, because the simulation's ground truth is the model's own score.

**What would make it a real evaluation, in order:**

1. **A timestamp on the outcome.** `watchlist_items.status` already reaches
   `completed`, which is the closest available label to "the recommendation
   worked". What is missing is *when* it got there — the temporal split needs
   it. A column and a write, not a feature.
2. **Real logged decisions.** `recommendation_items` already carries the score,
   the feature contributions and the propensity. The propensity is the one that
   could not have been added retroactively, and it is already there.
3. **Off-policy estimation.** With propensities logged, inverse-propensity
   scoring estimates how a *different* ranker would have done on traffic the
   shipped one served. That is what the exploration slot is for, and why its
   rate is 10% rather than 0.
4. **Then a claim about beating popularity**, and not before.

**The finding worth carrying into phase 8:** sorting by watchlist priority alone
comes within 0.0125 NDCG@3 of the five-feature model and beats it on precision@3.
Some of that is circularity, but the residue says the other four features buy
less than their combined 0.65 weight suggests. A learned model should be measured
against *that* baseline, not against random.

### Phase 8 — Learned ranking (pipeline built and proven)

Done, and written up in [MODEL.md](MODEL.md). The schema, the fingerprint guard,
the golden vectors and a committed distillation model that makes the whole chain
falsifiable without a user.

**Two things left, and both need data rather than code:**

1. **Train on something real.** The committed model imitates the linear ranker,
   which proves the plumbing and nothing else. A model worth serving needs logged
   decisions with outcomes — the same blocker as phase 7.
2. **Solve explanations before serving it.** A boosted tree has no feature
   contributions, and the product's rule is that a reason must be a real
   contribution rather than prose that sounds like one. SHAP is the usual answer
   and ONNX does not export it; a candidate approach is to serve the learned
   ranking only where the linear model agrees with it, and fall back where they
   differ — which is testable and honest, and has not been tried.

**MovieLens is deferred with a reason.** It gives `(user, item, rating,
timestamp)`; five of the eight features here are session context it has no
analogue for. Using it would mean fabricating them. Its real home is phase 9's
taste model.

### Phase 9 — Pilot Season (done)

The fitter, the ladder and the profile were done in phase 9 — see
[PILOT.md](PILOT.md). The persistence, the four endpoints and the screen are now
done too, skip included: a skipped pair is recorded so it is not re-asked and
carries no choice and no difference, so it cannot reach the fitter.

Two things are left, and both need users rather than code:

1. **Make the population prior real.** Zero-mean today, which for a population
   nobody has measured is the same number and a different claim. Once profiles
   exist the mean should be their average, and that single change is what turns
   "assume you are indifferent" into "assume you are typical". An average over
   nobody is still zero, so there is nothing to do until then.
2. **Then measure whether taste helps.** `taste_match` is plumbed into the
   feature schema and carries no learned signal, because the training target is
   the linear ranker's score and that does not read taste. The harness in
   `recommendation.evaluation` is where that question gets answered, and it needs
   the same real outcome data phase 7 needs.

**Do not make the ladder adaptive yet.** Adaptive selection tunes against a model
of the population, and there is no population; it would be adapting to the prior,
which is a fixed ladder reached by a more complicated route and much harder to
test.

### Phase 10 — the relay and Plot Armour (built; no Temporal)

Built and merged: the outbox relay, the `availability_changes` writer, Plot
Armour's suppression rules, the alerts API and a list on the home page. See
`PROGRESS.md` for the two mistakes worth not repeating — the lease-versus-lock
claim, and `@Transactional` on a self-call.

Left open:

1. **Temporal.** No dependency, and nowhere to run a worker. The relay is a
   Spring `@Scheduled` poller, which is honest about what it is. Worth adding
   once there is a server to point it at, and not before — workflow code that
   cannot be executed is the failure mode this project keeps finding.
2. **A real removal.** Nothing has ever produced an `availability.removed` event,
   because that needs the nightly refresh, which needs
   `PLOTTED_SNAPSHOT_ENABLED` and a deployment. Every path is tested with
   synthetic events.
3. **A batched subscription lookup**, if the load ever warrants it. Plot Armour
   does one `currentSubscriptions` call per watcher. The fix is a
   `currentSubscriptions(userIds)` on `SubscriptionDirectory`, and it should wait
   for a number rather than a guess.

### Phase 11 — End Credits (built; the metrics need users)

Built and merged: the acceptance signal, both metrics, the Redis rate limiter
with the health contributor restored, and the latency bound pinned as a solve
count. See `PROGRESS.md` for why each metric refuses to flatter.

Left open:

1. **Both metrics return null**, which is correct on an empty log and not very
   interesting. They need users and, for the completion rate, fourteen days.
2. **A throughput benchmark**, against the deployment. The structural bound is
   asserted; requests per second is not, and measuring it here would measure the
   absence of a database.
3. **A site-wide view.** End Credits is per-user because that needs no
   authorisation concept. The same two queries without the user filter are the
   product-level numbers, and that is worth having once there is more than one
   person's data to pool — along with the roles to protect it.

---

## The standing lesson

Six of the bugs found so far were mechanisms that reported success while doing
nothing: reuse detection whose revocation was rolled back, a drift check that
could never fail, a build step that had never run, an exploration slot logging a
propensity of zero, two DTOs whose shared simple name silently collapsed to one
schema in the published contract, and an evaluation report that claimed in its
own comments to be seeded and reproducible while its intervals moved between
runs. None of them threw. All of them passed their tests.

A seventh joined them in phase 9: `PilotLadder.build` put its exhaustion guard
below a `continue` that skipped it, so a catalogue too small to fill the ladder
looped forever. It needed a *non-empty* ladder to trigger, and the test that
existed used identical titles — which produces an empty ladder, and the empty
path was the one that terminated. The case that was covered and the case that
was broken sat next to each other.

And one guard turned out not to be able to fire at all. `ModuleBoundaryTest`
cannot see a cross-module `const val`: Kotlin inlines it, so the bytecode ArchUnit
reads holds the string with no reference to the class it came from. Plot Armour
read an event type straight across a feature boundary and
`featureModulesAreIndependent` passed. **Be suspicious of a rule that has never
failed, including the ones written to catch a real defect earlier** — this one
did catch a real collision in phase 5, and is still blind to this shape.

Two more nearly shipped, from the other direction:

- The Redis health contributor was live for a dependency **nothing in the
  codebase uses**, so the first deployment would have failed its readiness probe
  on a component with no callers.
- A demo test fixture built rows with the typed jOOQ API while the schema
  requires a column jOOQ has been told to ignore. Every insert would have failed
  a not-null violation on Postgres — and only on Postgres.

**Be suspicious of any check that has never had the chance to fail**, and prefer
a second implementation that disagrees over a test that agrees. When you add a
guard, make it fail once on the real defect before you trust it —
`apiClassNamesAreUnique` was run against the live collision, and the first
version of it also flagged 24 Kotlin companion objects, which is exactly the
kind of thing you only find by looking.

### And two that were not code at all

During the Actions outage, the symptoms were diagnosed as an exhausted allowance
and written up that way. They were a platform incident. Everything checkable with
the available token *was* checked first, which felt like diligence and was still
the wrong shape of investigation: the status page needed no auth and would have
answered it in one request. **Check whether the platform is up before reasoning
about your account.**

And afterwards, three pull requests sat with **no checks at all** — not because
CI was still broken, but because squash-merging their base had left them
`CONFLICTING`, and GitHub will not build a merge commit for a dirty PR, so
`pull_request` workflows never fire. Silence from CI is not the same as a passing
CI. `gh pr view --json mergeStateStatus` is the first thing to look at.
