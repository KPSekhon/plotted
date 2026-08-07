# Plotted

## Stop browsing. Your night's plotted.

Plotted is a platform-neutral streaming decision and subscription optimisation
platform for Canada. It combines a watchlist, regional availability, viewing
context and billing constraints to answer two questions:

1. **What should I watch right now?**
2. **Which streaming platforms should I actually pay for?**

Every streaming app recommends from its own catalogue. Netflix will not tell you
the best thing tonight is on Crave, and Prime will not suggest cancelling Prime.
Plotted works for the viewer instead. That neutrality is the whole point, and
every design decision here protects it.

---

## Status: phases 1–9 built, not yet deployed

This repository is built in phases against a written specification. Both headline
features are complete, tested and — where it was possible without users — measured.

| | Phase | State |
|---|---|---|
| 1 | Skeleton — Compose, Flyway, jOOQ, Spring Security, Angular shell, CI | **Done** |
| 2 | Catalogue — TMDB ingestion, search, availability snapshots | **Done** (119-title seed, not the 500 the spec asks for) |
| 3 | Watchlists, subscriptions, coverage dashboard | **Done** |
| 4 | **Queue Theory** — filters, scoring, diversification, explanations | **Done** |
| 5 | **Cancel Culture** — CP-SAT optimiser, plan types, sensitivity | **Done** |
| 6 | Polish, demo mode, deployment | **Built, not deployed** |
| 7 | Evaluation harness, baselines, ablations | **Harness built; one defensible result** |
| 8 | Learned ranking — LightGBM → ONNX → JVM | **Pipeline built and proven; not served** |
| 9 | Pilot Season — Bradley–Terry taste profile | **Maths built; no screen** |
| 10–12 | Temporal, analytics, stretch | |

### The two things worth looking at

**[Cancel Culture](docs/ARCHITECTURE.md#cancel-culture--which-subscriptions-to-keep)** —
a CP-SAT model over which services to hold each month, with start and stop as
separate variables because starting costs money you were not spending and
stopping costs access you had. Every number it shows is recomputed by
`PlanChecker`, an independent reimplementation of the rules written *before* the
solver so its logic could not be shaped by the model it audits.
`PlanSolverAgreementTest` enumerates every possible plan for small instances,
scores them with the checker alone, and asserts CP-SAT found the best of them —
because a solver will optimally solve a model you specified wrong, and the result
looks exactly like a correct answer.

**[Queue Theory](docs/ARCHITECTURE.md#queue-theory--what-to-watch-tonight)** —
one pick and two backups, each with the ranker's real feature contributions
rather than generated prose. When nothing fits it returns a *diagnosis* naming
the constraint that did the damage instead of quietly relaxing one.

Both are more interesting when they refuse. That is what the
[demo script](docs/DEMO.md) leads with.

### What has been measured

Renormalising scores over the features a candidate actually has is worth
**0.0191 NDCG@3 (95% CI 0.0166–0.0215)** under a 30% metadata-censoring
simulation. [EVALUATION.md](docs/EVALUATION.md) reports that, and is equally
explicit that it is the *only* non-circular number on the page — there are no
users yet, so the simulation's ground truth is the model's own score. It also
reports where the model loses: sorting by watchlist priority alone beats it on
precision@3.

### What is not true yet

- **Never deployed.** No container image has been built, and no migration has run
  against a managed Postgres. [DEPLOYMENT.md](docs/DEPLOYMENT.md) is the plan and
  lists what has never been executed.
- **The catalogue is a 119-title hand-checked seed.** Every answer is real; the
  range it chooses from is narrow.
- **No demo video yet.** [DEMO.md](docs/DEMO.md) is the shot list.

### How the catalogue works

Search TMDB, ingest a title, see where it streams in Canada, and keep
re-checking nightly — backend and screens both.

- **TMDB client** with a token bucket, selective retry and a typed failure
  taxonomy — see [ADR 0006](docs/adr/0006-tmdb-client-fails-typed-and-retries-selectively.md)
- **Mapping** from TMDB's shape to Plotted's, in one pure, exhaustively tested place
- **Idempotent title ingestion**, including replacing genre links that TMDB has
  dropped rather than leaving them behind
- **Seasons and episodes**, so a series' length is summed from real episode
  runtimes instead of episode-count times an average — specials excluded,
  because nobody counting whether they can finish a show before a renewal is
  counting the Christmas special
- **Provider canonicalisation** — TMDB reports "Crave" and "Crave Amazon Channel"
  as different services, and five separate Paramount+ entries. Left alone they
  would inflate the coverage figure the subscription optimiser runs on. See
  [ADR 0007](docs/adr/0007-canonical-providers.md)
- **Availability with provenance** — dated windows opened and closed rather than
  overwritten, every offer carrying its source and last-verified time
- **Nightly snapshot collection**, budgeted and off by default
- **Catalogue search** over PostgreSQL full-text *and* trigram similarity, so a
  typo still finds the title and OpenSearch stays unjustified
- **Catalogue screens** — search, title pages, and an availability panel that
  shows each offer's source and last-verified time, and hides prices when the
  data is stale rather than showing stale money
- **`make premise-check`** — Appendix A's day-one question, answered by probing
  real Canadian availability for a sample of deliberately awkward titles

#### API

```
GET  /api/v1/titles/search?query=           the ingested catalogue
GET  /api/v1/titles/discover?query=         TMDB, for titles not ingested yet
GET  /api/v1/titles/{titleId}
POST /api/v1/titles                         ingest from TMDB
GET  /api/v1/titles/{titleId}/availability  where to watch it, with provenance
```

#### Fill the catalogue

```bash
export TMDB_READ_ACCESS_TOKEN=...
make seed
```

Ingests the curated list in
[`canadian-seed.txt`](plotted-api/src/main/resources/seed/canadian-seed.txt),
resolving each title through the same path a user adding to a watchlist takes.
Idempotent, so it is also how the whole seed gets re-pulled after a schema
change.

The list is a **starting set, not the 500 hand-verified titles** §7.3 asks for.
Growing it is manual on purpose: the value of a curated seed is that a person
checked it. Add what you would actually watch, run the seed, then check the
availability that comes back against the provider's own app — where they
disagree is the interesting data.

#### Start collecting snapshots early

Off unless you turn it on, because it spends TMDB quota:

```bash
PLOTTED_SNAPSHOT_ENABLED=true make api
```

Plot Armour's removal-risk model needs months of history before it can exist at
all, and a night not collected cannot be recovered later.

#### Answer the premise question before building further

Plotted's value rests entirely on knowing what is streaming where, in Canada,
today. If that data is thin, nothing downstream can rescue it, so the check runs
standalone — no database, no Docker, just a token.

```bash
export TMDB_READ_ACCESS_TOKEN=...
make premise-check
```

It reports Canadian availability per title and exits non-zero below 70% coverage.
Run it before phase 2 goes any further.

---

## Run it

Requires **JDK 17**, **Node 20**, and **Docker** (for Postgres and Redis).

```bash
make dev     # start Postgres and Redis, print the URLs
make api     # run the Spring application on :8080 — Flyway migrates on startup
make web     # run the Angular dev server on :4200
```

Then open <http://localhost:4200> and create an account.

| | |
|---|---|
| Web | <http://localhost:4200> |
| API | <http://localhost:8080> |
| API docs | <http://localhost:8080/swagger-ui.html> |
| Health | <http://localhost:8080/actuator/health> |

`make help` lists everything else. `make verify` is what CI runs.

---

## Architecture

A **modular monolith** in Kotlin and Spring Boot, with an Angular front end and
PostgreSQL as the source of truth.

```
plotted-api/
  platform/          shared kernel — security, errors, audit, outbox, config
  identity/          accounts, sessions, settings          ← phase 1
  catalogue/         titles, search                        ← phase 2
  availability/      regional availability and snapshots   ← phase 2
  watchlist/         watchlists and coverage               ← phase 3
  recommendation/    Queue Theory                          ← phase 4
  optimisation/      Cancel Culture                        ← phase 5
```

Each module has `api/` (HTTP), `domain/` (behaviour), and `persistence/` (SQL).
Modules do not reach into each other; `platform` is the only shared dependency.
This is checked, not merely intended — see
[`ModuleBoundaryTest`](plotted-api/src/test/kotlin/app/plotted/architecture/ModuleBoundaryTest.kt),
which fails the build on a cross-module dependency, a controller touching a
repository, or SQL escaping a repository class.

Kafka, Kubernetes and microservices are deliberately absent. The expected scale
does not justify them, and unjustified distributed systems are a negative signal.

### Three decisions worth explaining

**jOOQ generates from the migration scripts, not from a live database.** The
generator parses `db/migration/*.sql` directly, so `./gradlew build` works with
no database and no Docker. DDL the generator cannot model — extensions, GiST
exclusion constraints, generated columns, partial indexes — is fenced with
`/* [jooq ignore start] */` markers, which Postgres reads as comments. That DDL
is exercised by a dedicated CI job against a real PostgreSQL 16.

**Temporal correctness lives in the schema.** `provider_plans`,
`title_availability` and `subscription_billing_periods` use `DATERANGE` with GiST
exclusion constraints, so overlapping price periods and duplicate availability
rows are unrepresentable. The original design keyed these on a nullable
`available_from` — and in Postgres `NULL != NULL`, so that constraint never fires
for the most common case. Duplicated availability rows would have inflated every
coverage number the optimiser depends on.

**Access tokens never touch browser storage.** The short-lived JWT lives in an
Angular signal, in memory. Continuity across a reload comes from an HttpOnly,
SameSite=Lax refresh cookie, exchanged once at startup. Refresh tokens rotate,
and presenting a spent one revokes the entire family — a surprise sign-out is
recoverable, a silently shared session is not.

---

## Testing

```bash
make test
```

| Layer | Tool | What it covers |
|---|---|---|
| Architecture | ArchUnit | Module boundaries, layering, SQL containment, and that no two API classes share a schema name |
| Unit | Kotest, MockK | Ranking, scoring, the plan checker, demo persona construction |
| Integration | Testcontainers | The real schema: exclusion constraints, partial indexes, cascade behaviour, fenced DDL |
| Optimiser | CP-SAT + exhaustive search | Every possible plan enumerated and scored by an independent checker, then compared to the solver's |
| Contract | OpenAPI drift check | The committed specification matches the running API |
| Evaluation | Seeded simulation | The report reproduces byte-for-byte, and the ablation isolates one variable |
| Frontend | Karma, Jasmine | Session state, the demo flow, error rendering |

Container-backed tests skip where Docker is unavailable and CP-SAT tests skip on
Windows, where the JNI binding crashes the JVM. Both always run in CI, so they
cannot be quietly lost — `DockerSupport` and `SolverSupport` gate them explicitly
rather than letting them disappear.

---

## Data sources

Title metadata and regional availability come from third parties with real
obligations attached. See [`docs/data-sources.md`](docs/data-sources.md) for
every source, its licence terms and its refresh cadence, and
[`PRIVACY.md`](PRIVACY.md) for what Plotted does with viewing data.

Plotted does not scrape provider websites. It violates terms of service, breaks
constantly, and is the wrong answer to a data-availability problem.

No public feed publishes forward-looking removal dates, so Plotted does not claim
them. Availability changes are detected by diffing nightly snapshots, and every
availability claim renders with its source, region and last-verified time.

This product is not endorsed or certified by TMDB or JustWatch.

---

## Documentation

Start with `ARCHITECTURE.md` if you want the shape of it, `EVALUATION.md` if you
want to know what is actually measured, and `PROGRESS.md` if you want the honest
state of every phase including the bugs.

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — the module graph, both
  headline pipelines, and a table of what *enforces* each correctness property
  rather than what documents it
- [`docs/EVALUATION.md`](docs/EVALUATION.md) — metrics, baselines, the one
  defensible result, and an explicit list of what the numbers do not say
- [`docs/MODEL.md`](docs/MODEL.md) — the learned ranker, and the four mechanisms
  that stop training-serving skew shipping silently
- [`docs/PILOT.md`](docs/PILOT.md) — taste elicitation, why the prior is not
  optional, and why two of the four verdicts mean "we do not know"
- [`docs/PROGRESS.md`](docs/PROGRESS.md) — where every phase actually stands,
  and the six bugs that reported success while doing nothing
- [`docs/NEXT.md`](docs/NEXT.md) — the forward plan and how to spend the API
  budgets
- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — how to deploy it, and what has
  never been executed
- [`docs/DEMO.md`](docs/DEMO.md) — the 90-second shot list
- [`docs/adr/`](docs/adr/) — architecture decision records, including what was
  deliberately *not* built
- [`docs/data-sources.md`](docs/data-sources.md) — sources, terms, attribution
- [`docs/seed/provider-plans.md`](docs/seed/provider-plans.md) — how to enter
  verified Canadian pricing
- [`PRIVACY.md`](PRIVACY.md) — privacy commitments
