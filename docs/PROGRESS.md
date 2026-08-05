# Progress

Where Plotted actually is, what each remaining phase involves, and what is still
open. Written to be read by someone picking the project up cold — including a
future me.

Last updated: 2026-07-30.

---

## Status at a glance

| | Phase | Tier | State |
|---|---|---|---|
| 1 | Skeleton — schema, auth, boundaries, Compose, CI | 1 | **Done** |
| 2 | Catalogue — TMDB ingestion, availability, search, screens | 1 | **Code-complete, unverified** |
| 3 | Watchlists, subscriptions, coverage dashboard | 1 | Next |
| 4 | **Queue Theory** — tonight's recommendation | 1 | |
| 5 | **Cancel Culture** — CP-SAT subscription optimiser | 1 | |
| 6 | Polish, demo mode, deployment | 1 | ← *résumé-ready line* |
| 7 | Evaluation harness, MovieLens, baselines | 2 | |
| 8 | LightGBM → ONNX → JVM inference | 2 | |
| 9 | Pilot Season, preference profile | 2 | |
| 10 | Temporal workflows, outbox, Plot Armour detection | 2 | |
| 11 | End Credits analytics, load testing, observability | 2 | |
| 12 | Stretch: removal-risk model, Group Plot, Side Quest | 3 | |

**Phases 1–6 are the hard line.** The spec is explicit that the project is
presentable at the end of phase 6 and that five half-finished features read worse
than two complete ones. Do not start a Tier 2 item while a Tier 1 item is open.

### By the numbers

33 tables · 10 migrations · 50 Kotlin source files · 130 API tests (8 of them
ArchUnit rules) · 16 frontend tests · 7 ADRs · 107 provider aliases.

---

## Read this before doing anything else

Three things are true right now and all three are traps.

**`main` carries two known bugs.** PR #1 merged an older state. The
migration-ordering fix and the empty-`daterange` fix live only on
`phase-2-ingestion`. Open a second PR.

**CI has never run on the newest commits.** `.github/workflows/ci.yml` triggers
on pushes to `main` and PRs targeting it. With PR #1 merged, pushes to a feature
branch trigger nothing — so the CI fixes and season ingestion are untested.
Opening the PR is what runs them.

**33 database tests always skip locally.** No Docker on the dev machine, so
anything touching Postgres is unverified until CI runs it. Both real bugs found
so far surfaced only when CI finally executed. Installing Docker Desktop is the
single highest-leverage change to how this project is developed.

---

## Phase 1 — Skeleton (done)

**Shipped.** A 33-table PostgreSQL schema; accounts, sessions and settings;
module boundaries enforced by ArchUnit; RFC 9457 errors; Docker Compose; CI;
Angular shell.

**The parts worth pointing at in an interview:**

- **Temporal correctness in the schema.** `provider_plans`,
  `title_availability` and `subscription_billing_periods` use `DATERANGE` with
  GiST exclusion constraints. The original design keyed these on a nullable
  column, and in Postgres `NULL != NULL`, so that constraint never fires for the
  commonest case. Duplicate availability rows would have inflated every coverage
  number the optimiser depends on — a bug that passes every unit test and
  produces plausible, wrong financial advice.
- **Rotating refresh tokens with reuse detection.** Replaying a spent token
  revokes the whole family. A surprise sign-out is recoverable; a silently shared
  session is not.
- **jOOQ generates from the migration scripts**, not a live database, so a clean
  clone builds with no Postgres and no Docker. Postgres-only DDL is fenced with
  `[jooq ignore]` markers and exercised by a dedicated CI job.

**Verified:** unit and architecture tests, both builds, both linters.
**Verified by CI later:** migrations against real PostgreSQL 16.

---

## Phase 2 — Catalogue (code-complete, unverified)

**Shipped.**

- **TMDB client** — token bucket, selective retry honouring `Retry-After`, and a
  sealed failure taxonomy so callers can tell "this title is gone" from "try
  again shortly" from "your credential is wrong".
- **Provider canonicalisation** — TMDB reports billing variants and reseller
  channels as separate providers (`Crave` and `Crave Amazon Channel`; five
  Paramount+ entries). Left alone they inflate coverage, which is the
  optimiser's primary input. 107 aliases collapse onto subscribable services.
- **Title, season and episode ingestion** — idempotent; series runtime is summed
  from real episodes rather than `episode_count × average`, because Tonight
  Mode's time filter is a promise.
- **Availability with provenance** — dated windows opened and closed rather than
  overwritten; every offer carries source, last-verified time and confidence.
- **Search** — Postgres full-text *and* trigram, so typos still match.
- **Screens** — search, title pages, availability panel that hides stale prices
  while keeping the presence claim. Dark theme with an amber accent.
- **Seed tooling and the nightly snapshot job.**

**The premise check passed.** 20/20 sampled titles have Canadian availability,
17 on a subscription; Crave and CBC Gem both appear. The platform-neutral premise
is safe to build on.

### Remaining in phase 2

1. **Open the PR and get CI green.** The only way to test the newest commits.
2. **Commit `openapi/openapi.json`** from the CI artifact. The drift check is
   inert until that file exists.
3. **Run `make seed`** with a real token and a database. Nobody has yet run the
   ingestion pipeline end to end against live TMDB.
4. **Turn on `PLOTTED_SNAPSHOT_ENABLED`.** Plot Armour needs months of history
   and a night not collected cannot be recovered. This clock should already be
   running.
5. **Grow the seed toward 500 hand-verified titles.** The current list is a
   starting set. The value of a curated seed is that a person checked it —
   compare what comes back against the provider's own app, and record
   disagreements through the correction endpoint rather than editing rows.

---

## Phase 3 — Watchlists, subscriptions, coverage (~1.5 weeks)

The first screen that is genuinely useful, and the last piece of groundwork the
two headline features need.

- Watchlists with 1–5 priority (1 = highest; the direction is documented in the
  column comment because ambiguity here produces optimiser bugs that are very
  hard to see).
- Subscription tracking: plans, prices, renewal dates, `cannot_cancel` flags.
  **Provider plan prices are deliberately unseeded** — fill them from public
  pricing pages per `docs/seed/provider-plans.md`.
- **Coverage dashboard**: which service covers the largest weighted share of the
  watchlist. Appendix A calls this out as already demoable on its own.
- Wire watchlist titles into the refresh priority — `TitleSearchRepository`
  currently orders purely by staleness, with a comment marking the spot.

---

## Phase 4 — Queue Theory (~3 weeks)

The first headline feature. Tonight's context in, one pick plus two backups out,
each with a reason.

- Hard filters (region, runtime, access policy, content rating), then the
  weighted linear score from §9.5.
- **Renormalise weights over available features.** A missing feature must not be
  treated as zero, or scores stop being comparable across candidates. Two lines,
  and most implementations ship without it.
- **Asymmetric runtime fit** — overshoot costs far more than undershoot, and
  overshoot beyond tolerance is a *hard* filter, never a soft penalty.
- **Diversification (MMR) and a propensity-logged exploration slot.** Logging
  that propensity is what makes off-policy evaluation possible in phase 7. It is
  three bytes and cannot be added retroactively.
- **Explanations from real feature contributions**, never invented prose.
- **The "nothing fits" path.** Returning zero results with a diagnostic rather
  than silently relaxing constraints. This is the moment in the demo that
  separates Plotted from every other recommender.

---

## Phase 5 — Cancel Culture (~3 weeks)

The second headline feature, and the most technically distinctive.

- CP-SAT model over `x[s,m]`, `y[t,m]`, `u[s,m]`, `d[s,m]` with the switching
  linearisation from §11.2 — start and stop split, because they cost differently.
- **Normalised objective.** Every term in [0,1] with weights summing to 1; scale
  by 1000 for CP-SAT's integer arithmetic. Mixing dollars with coverage
  percentages makes weights meaningless.
- **Binding constraints and sensitivity.** Re-solve with each constraint relaxed
  by one unit. "One more service buys 14% coverage for $20.99" is the single most
  useful thing the optimiser can say, and it is nearly free.
- **An independent constraint checker** — verify the solver's plan against a
  plain reimplementation. A solver will happily solve a model you specified
  wrong, and this is the highest-value test in the project.
- Infeasibility must be explained, not returned as an error.

---

## Phase 6 — Polish and deploy (~1.5 weeks) — the résumé line

Demo mode with no signup, a 90-second video, architecture diagram, near-zero-cost
deployment. **Stop here if interviews start.** Two complete headline features
with measured results beat five half-built ones.

---

## Phases 7–11 (Tier 2) — what turns "built" into "measured"

- **7 — Evaluation harness.** Baselines (random, popularity, watchlist recency,
  the linear model), NDCG@3, temporal splits, bootstrap confidence intervals,
  ablations, `EVALUATION.md`. The spec calls this the highest value per hour in
  the whole project, and it is what makes every ML claim defensible.
- **8 — Learned ranking.** LightGBM bootstrapped from MovieLens 32M via TMDB
  ids, exported to ONNX, served in-process. Guard training-serving skew with a
  shared feature schema and a golden-vector equivalence test.
- **9 — Pilot Season.** Bradley–Terry over ~15 comparisons with population
  priors. Start with a fixed ladder of pairs; adaptive selection is a later
  refinement with no data to be adaptive about yet.
- **10 — Temporal workflows.** Durable refresh and renewal analysis, the outbox
  relay (the table and writer already exist), and Plot Armour change detection
  from the snapshot diffs. Alert suppression matters — a job that fires nightly
  is one the user turns off.
- **11 — End Credits.** Decision latency and accepted-and-completed rate are the
  two metrics that carry the product's thesis. Load testing against the §13.1
  latency budget.

## Phase 12 (Tier 3) — only if time genuinely allows

Removal-risk model (needs ~6 months of snapshots — *which is why collection
starts now*), Group Plot, Side Quest, household fairness, history import,
interleaved experiments.

---

## Standing rules

- **No fabricated data.** Provider prices are unseeded; TMDB provider ids were
  fetched live. If a number cannot be verified, ship without it.
- **No placeholder screens.** A demo that navigates to an empty page is worse
  than one with fewer links.
- **Unbuilt compliance items are listed unchecked**, not omitted.
- **Every displayed fact carries provenance** — source, region, last-verified
  time. This is what lets Plotted be wrong gracefully.
- **Tests must not depend on wall-clock timing.** Inject clocks; assert on
  recorded behaviour, not on how fast the machine happened to be.
