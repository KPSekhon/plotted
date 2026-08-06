# Progress

Where Plotted actually is, what each remaining phase involves, and what is still
open. Written to be read by someone picking the project up cold — including a
future me.

Last updated: 2026-08-06.

**The forward plan lives in [NEXT.md](NEXT.md)** — how to spend the Watchmode
and MDBList budgets, the verified Canadian source IDs, the 500-title seed
procedure, and how to approach phases 6 onwards. This document is the state of
the world; that one is what to do about it.

---

## Status at a glance

| | Phase | Tier | State |
|---|---|---|---|
| 1 | Skeleton — schema, auth, boundaries, Compose, CI | 1 | **Done** |
| 2 | Catalogue — TMDB ingestion, availability, search, screens | 1 | **Verified in CI; seeding still owed** |
| 3 | Watchlists, subscriptions, coverage dashboard | 1 | **Done** |
| 4 | **Queue Theory** — tonight's recommendation | 1 | **Done** |
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

35 tables · 12 migrations · 78 Kotlin source files · 205 API tests (8 of them
ArchUnit rules, 70 needing Docker) · 22 frontend tests · 19 API paths · 8 ADRs ·
107 provider aliases · 17 seeded plan prices.

---

## Read this before doing anything else

**Install Docker Desktop.** This is now the clearest finding in the project, and
it has been paid for four times.

70 database tests are now gated on `DockerSupport.isDockerAvailable()` and skip
on the dev machine, so anything touching Postgres is unverified until CI runs it.
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

## Phase 2 — Catalogue (done; seeding still owed)

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

**Where the prices come from.** Two sources, and neither is invented.

The subscription form asks the *user* what they pay, and
`user_subscriptions.actual_price` overrides everything else on read — a
grandfathered rate or a bundle discount is what someone is really billed.

Underneath that, `V11__provider_plan_prices.sql` seeds a researched list price
per plan so coverage and phase 5 have something to run against before anyone has
entered a subscription. Every figure was read from a published source on
2026-08-06 and the migration records which, flags the least confident, and gives
the procedure for closing a stale row rather than editing it. They are
**researched, not verified**; `docs/seed/provider-plans.md` still governs.

One result worth carrying forward: the two figures read from a provider's own
page were the two that secondary sources had wrong. Apple TV+ is widely reported
at $12.99, which is the US price; Apple's Canadian page says $14.99. Prefer the
provider.

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
feature boundary, because that is the coupling that spreads. Written up as
[ADR 0008](adr/0008-cross-module-reads-through-the-shared-kernel.md), including
the cost that was accepted: a cross-module join is invisible to the compiler.

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

## Phase 4 — Queue Theory (done)

The first headline feature. Tonight's context in, one pick plus two backups out,
each with a reason. `GET /api/v1/tonight?availableMinutes=&accessPolicy=`.

**Built.** Hard filters → weighted linear score → MMR → exploration → decision
log. Each stage is a separate function with its own tests, because the failures
that matter here are not exceptions, they are rankings that look plausible and
are wrong.

The six things the plan below warned about, and where each one lives:

- **Renormalisation over available features** — `FeatureVector.score()`. A
  missing feature is absent, never zero: without this a candidate lacking a
  0.10-weight feature caps at 0.90 and loses to one with complete metadata, so
  the ranking silently becomes a ranking of data quality. `FeaturesTest` asserts
  two candidates identical but for a missing rating score the same.
- **Asymmetric runtime fit** — `runtimeFit()`, overshoot penalised 3× undershoot,
  and overshoot past 10% is a *hard filter* in `screen()` rather than a penalty.
- **MMR and a propensity-logged exploration slot** — `Ranker.diversify` and
  `Ranker.explore`. Slot 1 is never diversified; it is the answer.
- **Explanations from real contributions** — `FeatureVector.contributions()`,
  rendered straight into the response. Nothing generates prose.
- **The "nothing fits" path** — `Recommendation.NothingFits` carries counts per
  rejection reason and the API returns 200 with a diagnosis. Silently relaxing a
  constraint to produce *something* would be answering a different question.
- **Propensity logging from day one** — `V12__recommendations.sql`. It is one
  numeric column and phase 7 cannot be built without it.

**One bug found while writing this**, worth keeping in mind for the rest of the
phase: with a single pick and a nonzero exploration rate, the last slot was
discounted by the chance it was replaced even though exploration can only fire
when there are at least two slots — at rate 1.0 that recorded a propensity of
*zero* for a decision that was never in doubt. Nothing would have failed; phase 7
would simply have divided by zero over months of logs. `RankerTest` now pins it.

**The Tonight screen** is built and is the app's primary action from the home
page. The empty answer is rendered as a diagnosis rather than an apology, and
each pick shows its real feature contributions as bars — the same numbers the
ranker used, not a description of them. An explored slot is labelled a wildcard
rather than passed off as a considered choice.

**The decision log has integration tests**, written before merging rather than
after, on the phase 3 evidence that untested SQL is where this project's bugs
live. They cover the propensity guard, the unique-position constraint, empty
answers being logged with their reasons and no items, and `availableMinutes`
storing null rather than a sentinel — "no particular limit" and "no time at all"
have to stay distinguishable in the logs forever.

- Hard filters (region, runtime, access policy, content rating, **and blocked
  titles**), then the weighted linear score from §9.5. `blocked_titles` has
  existed since V6 and nothing reads it yet. It belongs here rather than in
  catalogue search: someone searching for a title they have blocked should still
  find it, because hiding it there looks like a missing catalogue entry rather
  than a preference being honoured. `CatalogueQueryService` is the seam.
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

## Phase 5 — Cancel Culture (in progress)

**Read this before running the tests on Windows.** CP-SAT is a JNI binding and
it crashes the JVM on the current dev machine. The solve dies with an
`EXCEPTION_ACCESS_VIOLATION` inside `msvcp140.dll`, preceded by
`Loading ... jniortools.dll failed, error code 126` (a dependent DLL could not
be resolved). That is a **process crash, not an exception** — nothing catches
it, the test JVM disappears taking the Gradle worker with it, and what Gradle
reports is an unrelated-looking `MessageIOException` about a socket. The real
diagnosis is only in the `hs_err_pid*.log` the JVM leaves behind.

**The cause is not yet known.** The obvious explanation — an outdated Visual C++
redistributable — was checked and does not hold: `msvcp140.dll` and
`vcruntime140_1.dll` are both 14.44.35211.0, current VS 2022 runtimes. Remaining
candidates, untested: OR-Tools extracts its natives to a random `%TEMP%`
directory on each run and the first extraction failed to load, which is
consistent with antivirus interference or a `%TEMP%` permissions problem; or a
sibling DLL genuinely missing from the packaged set. Anyone picking this up
should start from the `hs_err` log's module list rather than from this note.

`SolverSupport` gates the solver tests exactly as `DockerSupport` gates the
database ones, and it has to guess from the OS rather than probe, because
probing is the thing that crashes. Linux and macOS run them; Windows skips
unless `PLOTTED_SOLVER_ENABLED=true`. CI is Linux and runs the solver
unconditionally, as does production — so this is a developer-machine problem
rather than a product one, and phase 5 can be finished and verified through CI
regardless.

Everything that is not the solver is plain Kotlin and runs everywhere, which is
most of the interesting logic: `PlanChecker` and its tests touch no native code.

### Built so far

- **`PlanChecker`** — the independent reimplementation, written *before* the
  solver so its logic could not be shaped by the model it audits. Plain loops,
  no CP-SAT types. The spec calls this the highest-value test in the project and
  it is: a solver will optimally solve a model you specified wrong, and the
  result is indistinguishable from a correct answer.
- **`PlanSolver`** — `x`/`u`/`d`/`y` with start and stop split, because starting
  costs money you were not spending and stopping costs access you had; folding
  them into one "changed" indicator prices a cancellation like a signup. The
  `u + d ≤ 1` constraint is the one that fails silently — without it the solver
  satisfies the transition equality by setting both and under-reports churn.
- **A normalised objective**, every term a fraction of its own maximum before
  weighting. Scaled by 1,000,000 rather than the spec's 1,000: at ×1000 the
  rounding on one service's cost coefficient can exceed the real difference
  between two plans, so the solver goes indifferent for arithmetic reasons.
  Nothing reported to the user comes from that scale — `PlanChecker` recomputes
  every displayed number exactly.

### Still to build

The service that gathers inputs, the API and screen, and tests that the solver
and the checker agree on a plan neither one chose alone.

### The original plan

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
