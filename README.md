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

## Status: phase 1 complete, phase 2 in progress

This repository is being built in phases against a written specification. Phase 1
is the foundation: schema, authentication, module boundaries, local stack, CI.

| | Phase | State |
|---|---|---|
| 1 | Skeleton — Compose, Flyway, jOOQ, Spring Security, Angular shell, CI | **Done** |
| 2 | Catalogue — TMDB ingestion, search, availability snapshots | **In progress** |
| 3 | Watchlists, subscriptions, coverage dashboard | |
| 4 | **Queue Theory** — filters, scoring, diversification, explanations | |
| 5 | **Cancel Culture** — CP-SAT optimiser, plan types, sensitivity | |
| 6 | Polish, demo mode, deployment | |
| 7–11 | Evaluation harness, learned ranking, Pilot Season, Temporal, analytics | |

There is no Tonight Mode screen yet, and the application does not pretend to have
one.

### What actually works today

- Account creation, sign-in, sign-out, and session restore across a page reload
- Rotating refresh tokens with **reuse detection** — replaying a spent token
  revokes the whole token family
- Recommendation and budget defaults, readable and editable end to end
- 27-table PostgreSQL schema with the temporal-integrity constraints in place
- Module boundaries enforced by ArchUnit, failing the build on a violation
- RFC 9457 Problem Details on every error path
- One-command local stack

### Phase 2 so far

The ingestion foundation, not yet the ingestion. There is no catalogue endpoint
and no search screen; those are the rest of phase 2.

- **TMDB client** with a token bucket, selective retry and a typed failure
  taxonomy — see [ADR 0006](docs/adr/0006-tmdb-client-fails-typed-and-retries-selectively.md)
- **Mapping** from TMDB's shape to Plotted's, in one pure, exhaustively tested place
- **Idempotent title persistence**, including replacing genre links that TMDB has
  dropped rather than leaving them behind
- **Provider canonicalisation** — TMDB reports "Crave" and "Crave Amazon Channel"
  as different services, and five separate Paramount+ entries. Left alone they
  would inflate the coverage figure the subscription optimiser runs on. See
  [ADR 0007](docs/adr/0007-canonical-providers.md)
- **`make premise-check`** — Appendix A's day-one question, answered by probing
  real Canadian availability for a sample of deliberately awkward titles

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
| Architecture | ArchUnit | Module boundaries, layering, SQL containment |
| Unit | Kotest, MockK | Token issue and verification, rotation and reuse detection |
| Integration | Testcontainers | The real schema: case-insensitive email, defaults, fenced DDL |
| Contract | OpenAPI drift check | The committed specification matches the running API |
| Frontend | Karma, Jasmine | Session state, error rendering |

Container-backed tests are skipped where Docker is unavailable and always run in
CI, so they cannot be quietly lost.

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

- [`docs/adr/`](docs/adr/) — architecture decision records, including what was
  deliberately *not* built
- [`docs/data-sources.md`](docs/data-sources.md) — sources, terms, attribution
- [`docs/seed/provider-plans.md`](docs/seed/provider-plans.md) — how to enter
  verified Canadian pricing
- [`PRIVACY.md`](PRIVACY.md) — privacy commitments
