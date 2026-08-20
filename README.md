# plotted

### stop browsing. your night's plotted.

You pay for five streaming services and still spend twenty minutes scrolling
before giving up and rewatching something you've seen. That's the problem. Not a
shortage of things to watch, too many of them, split across too many apps, none
of which will ever tell you the good one is somewhere else.

Plotted works for the viewer instead. It answers two questions:

1. **what should i watch right now?**
2. **which of these services should i actually be paying for?**

Netflix will never say "it's on Crave." Prime will never suggest cancelling
Prime. That neutrality is the whole point, and most of the design decisions in
here exist to protect it.

---

## what it does

Give it your watchlist, what you subscribe to, and how long you've got. It
returns **one thing to watch and two backups**, not a wall of posters, because a
wall of posters is the problem rather than the fix.

Partway through a series, it resolves to the actual episode:

```
one piece
you are up to  S2 E62  "the first line of defense? the giant whale laboon appears!"
25 min · 1112 left
```

And when nothing fits, it says so and says why, instead of quietly loosening
your constraints to produce something:

```
no route fits.
  173 titles  unavailable on your services
   26 titles  longer than the 45 minutes you have
   11 titles  blocked
```

That refusal is the feature. Every competitor can show you a list. Almost none
of them can tell you when the honest answer is no.

---

## the two headline features

**[queue theory](docs/ARCHITECTURE.md#queue-theory--what-to-watch-tonight)**
picks tonight's answer. Hard filters, a weighted ranker, MMR diversification,
and a propensity-logged exploration slot. Every reason shown to you is a real
feature contribution from the scorer, never generated prose. The runtime filter
measures *the episode you're actually being offered*, not the series average,
because "45 minutes" is a promise.

**[cancel culture](docs/ARCHITECTURE.md#cancel-culture--which-subscriptions-to-keep)**
decides what to pay for. A CP-SAT model over which services to hold each month,
where start and stop are separate variables: starting costs money you weren't
spending, stopping costs access you had, and collapsing them prices a
cancellation like a signup. Every number it shows is recomputed by
`PlanChecker`, an independent reimplementation of the rules written *before* the
solver, so its logic couldn't be shaped by the model it audits.

It also **won't spend a price you didn't confirm**. A published price isn't your
bill; legacy plans, bundles, student rates and promos all move it, always
downward. Researched figures are shown but never optimised against, and the plan
names the services it had to leave out.

---

## what's measured

Renormalising scores over the features a candidate actually has is worth
**0.0184 NDCG@3** (95% CI 0.0158 to 0.0207, n=2000) under a 30%
metadata-censoring simulation.

[EVALUATION.md](docs/EVALUATION.md) reports that alongside four baselines, a
paired bootstrap and a temporal split, and is explicit about what a simulation
result does and does not support. It also reports where the model loses: sorting
by watchlist priority alone beats it on precision@3. A harness that only
produces flattering numbers isn't a harness.

---

## running it

Needs **JDK 17** and **Node 20**. Postgres from Docker or installed natively;
the build itself needs neither.

```bash
make dev
```

```bash
make api
```

```bash
make web
```

Then <http://localhost:4200>. `make help` lists the rest.

| | |
|---|---|
| web | <http://localhost:4200> |
| api | <http://localhost:8080> |
| api docs | <http://localhost:8080/swagger-ui.html> |
| health | <http://localhost:8080/actuator/health> |

---

## architecture

A **modular monolith** in Kotlin and Spring Boot, an Angular front end, Postgres
as the source of truth, plus a separate process for the optimiser.

```
plotted-api/
  platform/          shared kernel: security, errors, audit, outbox, config
  identity/          accounts, sessions, settings
  catalogue/         titles, seasons, episodes, search
  availability/      regional availability and snapshots
  watchlist/         watchlists, coverage, series progress
  subscriptions/     what you pay for, and what it costs
  recommendation/    queue theory
  optimisation/      cancel culture, talking to the solver process
  preferences/       pilot season
  alerts/            plot armour
  analytics/         end credits
  demo/              throwaway accounts

plotted-solver/      CP-SAT, in its own JVM
```

Modules don't reach into each other; `platform` is the only shared dependency.
That's checked rather than intended.
[`ModuleBoundaryTest`](plotted-api/src/test/kotlin/app/plotted/architecture/ModuleBoundaryTest.kt)
fails the build on a cross-module dependency, a controller touching a
repository, SQL escaping a repository, or two API classes sharing a schema name.

Kafka, Kubernetes and microservices are deliberately absent. The expected scale
doesn't justify them, and unjustified distributed systems are a negative signal.

### five decisions worth explaining

**The optimiser runs in its own process.** CP-SAT is a JNI binding, and a native
fault isn't an exception, it's a process death nothing in Kotlin can catch. One
request to `/api/v1/plan` could take the entire API down with it, every other
endpoint included. The solver is now its own module and its own JVM, and
`plotted-api` excludes OR-Tools outright so it can't load the natives even by
accident. A crash costs one request and returns a 503.
[ADR 0010](docs/adr/0010-optimiser-runs-in-its-own-process.md)

**The learned ranker sits behind an explainability gate.** A LightGBM model is
trained and exported to ONNX for in-process inference, with no Python sidecar on
the request path, and it refuses to load if its feature-schema fingerprint
doesn't match the serving code. Features are extracted in exactly one place, the
serving code, and the training script consumes what serving produced, so there
is no second implementation to drift. It stays behind a flag because a boosted
tree can't produce the per-feature contributions the interface shows as reasons,
and a ranking presented beside explanations it didn't generate is the kind of
dishonesty this project refuses everywhere else. [MODEL.md](docs/MODEL.md)

**jOOQ generates from the migration scripts, not a live database.** So
`./gradlew build` works with no database and no Docker. DDL the generator can't
model, such as extensions, GiST exclusion constraints and partial indexes, is
fenced with `[jooq ignore]` markers that Postgres reads as comments, and is
exercised by a dedicated CI job against real Postgres 16.

**Temporal correctness lives in the schema.** `provider_plans`,
`title_availability` and `subscription_billing_periods` use `DATERANGE` with
GiST exclusion constraints, so overlapping price periods and duplicate
availability rows are *unrepresentable*. The original design keyed these on a
nullable column, and in Postgres `NULL != NULL`, so the constraint never fired
for the commonest case, which would have inflated every coverage number the
optimiser depends on.

**Access tokens never touch browser storage.** The short-lived JWT lives in an
Angular signal, in memory. Continuity across a reload comes from an HttpOnly,
SameSite=Lax refresh cookie. Refresh tokens rotate, and replaying a spent one
revokes the whole family: a surprise sign-out is recoverable, a silently shared
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
| integration | Testcontainers | the real schema: exclusion constraints, partial indexes, cascades, fenced DDL |
| optimiser | CP-SAT and exhaustive search | every possible plan enumerated, scored by an independent checker, compared against the solver's |
| contract | OpenAPI drift check | the committed specification matches the running API |
| evaluation | seeded simulation and drift check | the report reproduces byte for byte, and still matches the committed figures |
| migrations | clean Postgres and static checks | every migration applies, every foreign key is indexed, no NOT NULL column is added to a populated table |
| frontend | Karma, Jasmine | session state, the demo flow, error rendering |

Container-backed tests skip without Docker, and CP-SAT tests skip on Windows
where the JNI binding crashes the JVM. Both always run in CI, so they can't be
quietly lost: `DockerSupport` and `SolverSupport` gate them explicitly rather
than letting them disappear.

---

## data sources

Title metadata and regional availability come from third parties with real
obligations attached. See [`docs/data-sources.md`](docs/data-sources.md) for
every source, its licence terms and its refresh cadence, and
[`PRIVACY.md`](PRIVACY.md) for what Plotted does with viewing data.

**No scraping.** It violates terms of service, breaks constantly, and is the
wrong answer to a data-availability problem.

No public feed publishes forward-looking removal dates, so Plotted doesn't claim
them. Availability changes are detected by diffing nightly snapshots, and every
availability claim renders with its source, region and last-verified time.

Not endorsed or certified by TMDB or JustWatch.

---

## docs

Start with `ARCHITECTURE.md` for the shape of it, and `EVALUATION.md` for what's
actually measured.

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) the module graph, both
  pipelines, and a table of what *enforces* each correctness property rather
  than what documents it
- [`docs/EVALUATION.md`](docs/EVALUATION.md) metrics, baselines, the headline
  result, and an explicit list of what the numbers don't say
- [`docs/MODEL.md`](docs/MODEL.md) the learned ranker, and the four mechanisms
  that stop training-serving skew shipping silently
- [`docs/PILOT.md`](docs/PILOT.md) taste elicitation, why the prior isn't
  optional, and why two of the four verdicts mean "we don't know"
- [`docs/adr/`](docs/adr/) decision records, including what was deliberately
  *not* built
- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) environments, secrets and the
  verification sequence
- [`docs/data-sources.md`](docs/data-sources.md) sources, terms, attribution
- [`PRIVACY.md`](PRIVACY.md) privacy commitments
