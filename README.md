# plotted

### stop browsing. your night's plotted.

you pay for five streaming services and still spend twenty minutes scrolling
before giving up and rewatching something. that's the problem. not "not enough
content" — too much of it, split across too many apps, none of which will ever
tell you the good thing is somewhere else.

plotted works for the viewer instead. it answers two questions:

1. **what should i watch right now?**
2. **which of these services should i actually be paying for?**

netflix will never say "it's on crave." prime will never suggest cancelling
prime. that neutrality is the entire point, and basically every design decision
in here exists to protect it.

---

## what it actually does

give it your watchlist, what you subscribe to, and how long you've got. it gives
you **one thing to watch and two backups** — not a wall of posters, because a
wall of posters is the problem, not the fix.

if you're partway through a series it resolves to the actual episode:

```
one piece
you are up to  S2 E62  "the first line of defense? the giant whale laboon appears!"
25 min · 1112 left
```

and when nothing fits it says so, and says why, instead of quietly loosening
your constraints to produce something:

```
no route fits.
  173 titles  unavailable on your services
   26 titles  longer than the 45 minutes you have
   11 titles  blocked
```

that refusal is the feature. every competitor can show you a list. almost none
of them can tell you when the honest answer is no.

---

## the two headline bits

**[queue theory](docs/ARCHITECTURE.md#queue-theory--what-to-watch-tonight)** —
hard filters, a weighted ranker, MMR diversification, and a propensity-logged
exploration slot. every reason shown to you is a real feature contribution from
the scorer, never generated prose. the runtime filter measures *the episode
you're being offered*, not the series average, because "45 minutes" is a promise.

**[cancel culture](docs/ARCHITECTURE.md#cancel-culture--which-subscriptions-to-keep)** —
a CP-SAT model over which services to hold each month. start and stop are
separate variables, because starting costs money you weren't spending and
stopping costs access you had, and collapsing them prices a cancellation like a
signup. every number it shows you is recomputed by `PlanChecker`, an independent
reimplementation written *before* the solver so its logic couldn't be shaped by
the model it audits.

it also **won't spend a price you didn't confirm**. published prices aren't your
bill — legacy plans, bundles, student rates and promos all move them, always
down — so researched figures get shown but never optimised against. it tells you
which services it had to leave out, and why.

---

## status: not deployed yet

built in phases against a written spec. everything below was verified by CI
before merging.

| | phase | state |
|---|---|---|
| 1 | skeleton — flyway, jOOQ, spring security, angular, CI | **done** |
| 2 | catalogue — TMDB ingestion, availability, search | **done** (527-entry seed, never run against live TMDB) |
| 3 | watchlists, subscriptions, coverage | **done** |
| 4 | **queue theory** | **done** |
| 5 | **cancel culture** | **done** |
| 6 | polish, demo mode, deployment | **merged, not deployed** |
| 7 | evaluation harness, baselines, ablations | **merged; one defensible result** |
| 8 | learned ranking — LightGBM to ONNX to JVM | **merged; proven, deliberately not served** |
| 9 | pilot season — bradley–terry taste profile | **merged; cannot reach significance yet, and says so** |
| 10 | outbox relay, plot armour | **merged; nothing has fired** |
| 11 | end credits analytics | **merged; metrics need users** |
| 12 | discovery, removal-risk, group plot | in progress |

### stuff that isn't true yet, said out loud

- **never deployed.** no image built, no migration run against a managed
  postgres. [DEPLOYMENT.md](docs/DEPLOYMENT.md) lists exactly what has never
  been executed.
- **`make seed` has never run against live TMDB.** the 527-entry list is built
  and validated; the pipeline has not ingested it end to end.
- **the learned model isn't served.** on purpose — boosted trees can't produce
  the per-feature contributions the UI shows as reasons, and serving the ranking
  next to explanations it didn't generate would be the exact dishonesty this
  thing refuses everywhere else.
- **taste doesn't affect ranking yet.** pilot season fits a profile; nothing
  consumes it. wiring it in is
  [ADR 0009](docs/adr/0009-discovery-and-taste-as-product-inputs.md).
- **no demo video.** [DEMO.md](docs/DEMO.md) is the shot list.

### what's actually been measured

renormalising scores over the features a candidate actually has is worth
**0.0184 NDCG@3 (95% CI 0.0158–0.0207, n=2000)** under a 30% metadata-censoring
simulation.

[EVALUATION.md](docs/EVALUATION.md) reports that, and is equally loud that it is
the *only* non-circular number on the page — there are no users yet, so the
simulation's ground truth is the model's own score. it also reports where the
model loses: sorting by watchlist priority alone beats it on precision@3.

---

## run it

needs **JDK 17** and **node 20**. postgres either from docker or installed
natively — the build itself needs neither.

```bash
make dev
```

```bash
make api
```

```bash
make web
```

then <http://localhost:4200>. `make help` lists the rest.

| | |
|---|---|
| web | <http://localhost:4200> |
| api | <http://localhost:8080> |
| api docs | <http://localhost:8080/swagger-ui.html> |
| health | <http://localhost:8080/actuator/health> |

---

## architecture

a **modular monolith** in kotlin + spring boot, angular front end, postgres as
the source of truth — plus one separate process for the optimiser.

```
plotted-api/
  platform/          shared kernel — security, errors, audit, outbox, config
  identity/          accounts, sessions, settings
  catalogue/         titles, seasons, episodes, search
  availability/      regional availability and snapshots
  watchlist/         watchlists, coverage, series progress
  subscriptions/     what you pay for, and what it costs
  recommendation/    queue theory
  optimisation/      cancel culture (talks to the solver process)
  preferences/       pilot season
  alerts/            plot armour
  analytics/         end credits
  demo/              throwaway accounts

plotted-solver/      CP-SAT, in its own JVM
```

modules don't reach into each other; `platform` is the only shared dependency.
that's checked rather than intended —
[`ModuleBoundaryTest`](plotted-api/src/test/kotlin/app/plotted/architecture/ModuleBoundaryTest.kt)
fails the build on a cross-module dependency, a controller touching a repository,
SQL escaping a repository, or two API classes sharing a schema name.

kafka, kubernetes and microservices are deliberately absent. the expected scale
doesn't justify them, and unjustified distributed systems are a negative signal.

### four decisions worth explaining

**the optimiser runs in its own process.** CP-SAT is a JNI binding, and a native
fault isn't an exception — it's a process death nothing in kotlin can catch. one
request to `/api/v1/plan` used to kill the entire API, every other endpoint
included. now the solver is its own module and its own JVM, and `plotted-api`
excludes OR-Tools entirely so it cannot load the natives even by accident. a
crash costs one request and returns a 503.
[ADR 0010](docs/adr/0010-optimiser-runs-in-its-own-process.md)

**jOOQ generates from the migration scripts, not a live database.** so
`./gradlew build` works with no database and no docker. DDL the generator can't
model — extensions, GiST exclusion constraints, partial indexes — is fenced with
`[jooq ignore]` markers that postgres reads as comments, and exercised by a
dedicated CI job against real postgres 16.

**temporal correctness lives in the schema.** `provider_plans`,
`title_availability` and `subscription_billing_periods` use `DATERANGE` with GiST
exclusion constraints, so overlapping price periods and duplicate availability
rows are *unrepresentable*. the original design keyed these on a nullable column,
and in postgres `NULL != NULL`, so the constraint never fired for the commonest
case — which would have inflated every coverage number the optimiser depends on.

**access tokens never touch browser storage.** the short-lived JWT lives in an
angular signal, in memory. continuity across a reload comes from an HttpOnly,
SameSite=Lax refresh cookie. refresh tokens rotate, and replaying a spent one
revokes the whole family — a surprise sign-out is recoverable, a silently shared
session isn't.

---

## testing

```bash
make test
```

| layer | tool | what it covers |
|---|---|---|
| architecture | ArchUnit | module boundaries, layering, SQL containment, API name collisions |
| unit | Kotest, MockK | ranking, scoring, the plan checker, series progress, demo persona |
| integration | Testcontainers | the real schema — exclusion constraints, partial indexes, cascades, fenced DDL |
| optimiser | CP-SAT + exhaustive search | every possible plan enumerated, scored by an independent checker, compared to the solver's |
| contract | OpenAPI drift check | the committed spec matches the running API |
| evaluation | seeded simulation + drift check | the report reproduces byte-for-byte *and* still matches the committed figures |
| migrations | clean postgres + static checks | every migration applies; every FK is indexed; no NOT NULL column added to a populated table |
| frontend | Karma, Jasmine | session state, the demo flow, error rendering |

container-backed tests skip without docker, and CP-SAT tests skip on windows
where the JNI binding crashes the JVM. both always run in CI, so they can't be
quietly lost — `DockerSupport` and `SolverSupport` gate them explicitly rather
than letting them vanish.

---

## data sources

title metadata and regional availability come from third parties with real
obligations attached. see [`docs/data-sources.md`](docs/data-sources.md) for
every source, its licence terms and refresh cadence, and
[`PRIVACY.md`](PRIVACY.md) for what plotted does with viewing data.

**no scraping.** it violates terms of service, breaks constantly, and is the
wrong answer to a data-availability problem.

no public feed publishes forward-looking removal dates, so plotted doesn't claim
them. availability changes are detected by diffing nightly snapshots, and every
availability claim renders with its source, region and last-verified time.

not endorsed or certified by TMDB or JustWatch.

---

## docs

start with `ARCHITECTURE.md` for the shape, `EVALUATION.md` for what's actually
measured, and `PROGRESS.md` for the honest state of everything — including the
bugs that reported success while doing nothing.

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module graph, both pipelines,
  and a table of what *enforces* each correctness property rather than documents it
- [`docs/EVALUATION.md`](docs/EVALUATION.md) — metrics, baselines, the one
  defensible result, and an explicit list of what the numbers don't say
- [`docs/MODEL.md`](docs/MODEL.md) — the learned ranker and the four mechanisms
  that stop training-serving skew shipping silently
- [`docs/PILOT.md`](docs/PILOT.md) — taste elicitation, why the prior isn't
  optional, and why two of the four verdicts mean "we don't know"
- [`docs/PROGRESS.md`](docs/PROGRESS.md) — where every phase actually stands
- [`docs/NEXT.md`](docs/NEXT.md) — the forward plan and how to spend the API budgets
- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — how to deploy, and what has never been run
- [`docs/DEMO.md`](docs/DEMO.md) — the 90-second shot list
- [`docs/adr/`](docs/adr/) — decision records, including what was deliberately *not* built
- [`PRIVACY.md`](PRIVACY.md) — privacy commitments
