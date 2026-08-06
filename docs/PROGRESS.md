# Progress

Where Plotted actually is, what each remaining phase involves, and what is still
open. Written to be read by someone picking the project up cold — including a
future me.

Last updated: 2026-08-05.

---

## Status at a glance

| | Phase | Tier | State |
|---|---|---|---|
| 1 | Skeleton — schema, auth, boundaries, Compose, CI | 1 | **Done** |
| 2 | Catalogue — TMDB ingestion, availability, search, screens | 1 | **Verified in CI; seeding still owed** |
| 3 | Watchlists, subscriptions, coverage dashboard | 1 | **Done** |
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

33 tables · 10 migrations · 68 Kotlin source files · 176 API tests (8 of them
ArchUnit rules, 64 needing Docker) · 19 frontend tests · 18 API paths · 7 ADRs ·
107 provider aliases.

---

## Read this before doing anything else

**Install Docker Desktop.** This is now the clearest finding in the project, and
it has been paid for four times.

33 database tests are gated on `DockerSupport.isDockerAvailable()` and skip on
the dev machine, so anything touching Postgres is unverified until CI runs it.
PR #2 (merged 2026-08-05) was the first time CI ever executed the newest
commits, and it took four round trips because the API job's steps are sequential
— each failure hid the next one:

1. Reopening a closed availability window tripped the GiST exclusion constraint.
   `close` clamps a same-day window to `[today, today + 1)` so Postgres will not
   normalise it to `empty`, but that row still claims today, so the replacement
   overlapped it. Windows now abut: `[today, today+1)` then `[today+1, )`.
2. **Refresh-token reuse detection was inert.** It revoked the family and then
   threw, and the rejection is a `RuntimeException`, so the transaction rolled
   back and took the revocation with it. The caller still got its 401, so
   nothing looked wrong — while the stolen successor token kept working. The
   unit test passed throughout, because a mock records the call the database
   then discards. Fixed by committing the revocation in its own transaction.
3. Committing `openapi/openapi.json` made the drift check live, and it could
   never have passed: springdoc filled `servers.url` in from the request, which
   under `RANDOM_PORT` is a different ephemeral port every run. Pinned to `/`.
4. `bootJar` then ran for the first time ever and failed — `TmdbPremiseCheck` is
   a second class with a `main` and the Boot plugin will not guess between two.

Items 3 and 4 had never once been executed. Nothing here was catchable locally:
three needed Docker, and the fourth needed the build step, which only runs after
the Docker-backed tests pass.

**The lesson generalises.** A step that "passed" may simply never have run, and
a test that passes against a mock proves only that a call was made. Read the
whole job log, and be suspicious of any check that has never had the chance to
fail.

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

**Verified:** unit and architecture tests, both builds, both linters, and — as
of PR #2 — the migrations and every Testcontainers test against real
PostgreSQL 16.

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

Items 1 and 2 are done — PR #2 is merged, all four CI jobs are green, 133 tests
pass, and `openapi/openapi.json` is committed so the drift check now has teeth.

What is left needs a person, not another commit. All three are blocked on
something no amount of code can supply, and each is blocked for a different
reason worth keeping straight:

3. **Run `make seed`** with a real token and a database. Nobody has yet run the
   ingestion pipeline end to end against live TMDB. *Blocked on Postgres* — see
   the Docker note above. This is the one that unblocks the other two.
4. **Turn on `PLOTTED_SNAPSHOT_ENABLED`.** Plot Armour needs months of history
   and a night not collected cannot be recovered, so this clock should already
   be running. *Blocked on an environment that runs continuously*, which does
   not exist until deployment in phase 6. The `false` default is deliberate — a
   developer machine should not quietly spend the TMDB quota — so the fix is not
   to change it but to set the variable in the first long-lived environment.
   Both this flag and `PLOTTED_SEED_ENABLED` are now listed in `.env.example`,
   which is where someone setting that environment up will actually look.
5. **Grow the seed toward 500 hand-verified titles** (119 today), and enter
   provider plan prices per `docs/seed/provider-plans.md`. *Deliberately manual,
   and must stay that way.* The value of a curated seed is precisely that a
   person checked it, and invented prices would put fabricated money into the
   optimiser's objective function — which does not produce a visibly broken
   feature, it produces confident, wrong financial advice. Compare what comes
   back against the provider's own app and record disagreements through the
   correction endpoint rather than editing rows.

---

## Phase 3 — Watchlists, subscriptions, coverage (built)

The first screens that are genuinely useful, and the last groundwork the two
headline features need.

**Shipped.**

- **Watchlists** with 1–5 priority (1 = highest, restated in the schema, the
  column comment and `Priority` itself, because ambiguity here produces
  optimiser bugs that look like bad taste rather than defects). Priority and
  status are editable in place — a weighting nobody adjusts stays at its default
  and makes the weighting meaningless.
- **Subscription tracking**: plans, prices, renewal dates, `cannot_cancel`.
  `cannotCancel` is *derived* from a commitment end date rather than trusted from
  the request, so the flag cannot disagree with the date beside it.
- **Coverage dashboard** — the first screen that answers a question rather than
  displaying a record, and the direct ancestor of phase 5's objective.
- Watchlist titles now sort ahead of everything else in the refresh priority,
  restricted to outstanding items: the nightly batch is finite, so a title
  promoted is a title demoted.

**Where the prices come from.** `provider_plans` still ships unseeded. The
subscription form asks the *user* what they pay, and that figure is what gets
stored. This is not a workaround for the no-fabricated-data rule, it is the rule
applied correctly: what a person reports about their own bill is the most
reliable pricing available, and it carries a source. Plotted still invents
nothing.

**Two decisions in coverage worth not undoing.**

- Shares are **priority-weighted, not counted**. A service carrying one film
  someone is desperate to see outranks one carrying four they are lukewarm about.
  `CoverageServiceTest` asserts exactly this inversion, so anyone who
  "simplifies" it back to a count gets a failing test rather than a plausible
  wrong answer.
- Titles whose availability has **never been checked are excluded from the
  denominator** and reported separately. Scoring them as uncovered would penalise
  every service in proportion to how stale Plotted's own data is — invisibly,
  because a low percentage looks the same either way.

**Module boundaries.** `watchlist` and `subscriptions` reach `catalogue` and
`availability` through `platform.spi` interfaces (`TitleDirectory`,
`AvailabilityDirectory`), never by importing them. Cross-module *SQL* joins are
allowed and used, following the precedent already set by the catalogue's join
onto `title_availability`; the line ArchUnit enforces is that no class crosses a
feature boundary, because that is the coupling that spreads.

**The new SQL is covered.** The first CI run on phase 3 passed with 145 tests
and proved almost nothing about the repositories, because none of the new
queries had a test at all — a green API job meant only that nothing *else*
broke. Integration tests now exist for all of it:
`WatchlistRepositoryIntegrationTest`, `SubscriptionRepositoryIntegrationTest`,
`TitleSearchRepositoryIntegrationTest`, and additions to
`AvailabilityRepositoryIntegrationTest`.

They target the parts nothing else type-checks: the partial unique index behind
`findOrCreateDefault`, the composite unique constraint that makes a second
`addItem` idempotent, the GiST exclusion constraint on `provider_plans` that
`findOrCreatePlan` has to avoid tripping, the `IN` batching in `findSummaries`,
the `EXISTS` subquery driving refresh priority, and — in every repository —
that one user cannot read or change another's rows. Also that a `PATCH {}` does
not become an `UPDATE` with an empty `SET`, which is invalid SQL rather than a
no-op.

**All 176 tests pass.** Every one of the new queries was correct on its first
execution against Postgres, which after phase 2 was not the way to bet. The two
failures that did appear were in a *test fixture*: it inserted an availability
row as one plain-SQL string, and in plain SQL jOOQ has no target type for a
bind, so an `OffsetDateTime` reached a `timestamptz` column as
`character varying`. The production repositories never hit this because they use
the typed API and keep raw SQL for `validity` alone. **Write fixtures the same
way the repository writes** — a fixture that takes a shortcut the real code
avoids reintroduces exactly the bug the real code was designed around.

**One more CI defect fixed in passing.** The workflow uploaded
`openapi/openapi.json` — the *committed* file, which the contract test stops
overwriting once it exists. So the artifact re-uploaded the stale copy and the
workflow's own advice to "download it from this run's artifacts" quietly stopped
working from the moment the file was committed, which is precisely when a drift
failure means you need it. It now uploads `build/openapi-actual.json` too. This
is the only way to regenerate the document without Docker.

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
