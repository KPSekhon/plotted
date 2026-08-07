# Deployment

How to put Plotted somewhere a person can click on it, for as close to nothing
as the hosting market currently allows.

**Status: not yet deployed.** Everything below has been written and reviewed but
none of it has been executed — no image has been built (there is no Docker on
the development machine) and no environment exists. Treat this as the plan and
the checklist, not as a record of something that worked. The parts most likely
to be wrong on first contact are called out as they come up.

---

## The shape

```mermaid
flowchart LR
    user((Visitor)) --> cdn["Static host<br/><i>Angular bundle</i>"]
    cdn -->|"/api/*"| run["Cloud Run<br/><i>scales to zero</i>"]
    run --> pg[("Serverless Postgres<br/><i>Neon / Supabase</i>")]
    run -.->|"free quota"| tmdb["TMDB"]
```

Three pieces, and the cost argument for each:

| Piece | Choice | Why this one |
|---|---|---|
| **API** | Google Cloud Run | Scales to zero, so an idle demo costs nothing but storage. The `Dockerfile` and `application-prod.yml` were already written against it — serial GC and `MaxRAMPercentage` rather than a fixed heap, because startup time matters more than throughput when most requests arrive at a cold container. |
| **Database** | A serverless Postgres with a free tier (Neon, Supabase, or equivalent) | Needs Postgres 16 with `citext`, `pg_trgm` and `btree_gist`. Cloud SQL has no free tier and bills by the hour whether or not anyone visits. |
| **Web** | Any static host (Cloudflare Pages, Netlify, or Cloud Storage behind the same domain) | The Angular build is a folder of static files. Serving it from a container is paying for a process to hand out files that never change. |

**Check the current terms before committing to any of these.** Free tiers move,
and a document that quotes last year's allowance is worse than one that says to
go and look.

### Starting from no accounts at all

Nothing above needs to be decided in advance except one thing, and it is the one
worth deciding on evidence: **provision the database first, run the preflight
against it, and only then commit to the rest.** The database is the piece with a
real constraint attached — three extensions the schema cannot do without — and it
is much cheaper to discover a provider will not grant them before anything else
is wired to it.

The API host is the reversible choice. Cloud Run is what the `Dockerfile` and
`application-prod.yml` were written against, but the image is an ordinary Spring
Boot container and any host that runs one will do. If you would rather not think
about the scale-to-zero scheduling problem below at all, a host that keeps one
instance alive removes it entirely — see the note on `@Scheduled`.

---

## Before the first deploy

### 1. The database must have three extensions

**Run the preflight first.** It is the one step that turns the most likely
first-deploy failure into a five-second answer:

```bash
psql "$PLOTTED_DB_URL" -v ON_ERROR_STOP=1 -f ops/deploy/preflight.sql
```

No psql? It is plain SQL with no meta-commands, so it pastes straight into a
provider's browser console.

`V1__extensions.sql` creates `citext`, `pg_trgm` and `btree_gist`, so a role with
`CREATE EXTENSION` rights makes this automatic. Some managed Postgres providers
restrict that; on those, enable the three from the provider's console **before**
the first migration runs.

This is the most likely first-deploy failure and the least obvious: without
`btree_gist` the exclusion constraints do not exist, and their absence is the
thing that makes duplicate availability rows possible — which inflates every
coverage number the optimiser depends on. It fails loudly at migration time,
which is the good case. Do not be tempted to fence those constraints out to get
past it.

**The preflight checks more than the three names**, because "the extension is
listed" and "the exclusion constraint fires" are different claims. It builds a
constraint of the same shape `title_availability` uses — a `DATERANGE` keyed by
two scalar columns, which is exactly the combination that needs `btree_gist` —
and asserts that an overlapping range is *rejected*. If it is accepted, the
script raises and says not to deploy. A guard nobody has watched fail is a guard
nobody knows is there.

It runs on every CI build, against the clean database in the migrations job, at
the same point in the sequence the real one would. A preflight nobody executes
is a preflight that has quietly stopped matching the schema.

### 2. Generate a real JWT secret

```bash
openssl rand -base64 48
```

`SecurityConfig` refuses to start with the development default outside the `dev`
profile, so this is enforced rather than remembered.

### 3. Decide what the deployment is for

| Variable | Demo deployment | Anything else |
|---|---|---|
| `PLOTTED_DEMO_ENABLED` | `true` | `false` — it is an unauthenticated write |
| `PLOTTED_SEED_ENABLED` | `true` for the first boot, then `false` | `false` |
| `PLOTTED_SNAPSHOT_ENABLED` | `true` | `true` if it runs continuously |
| `TMDB_READ_ACCESS_TOKEN` | required | required for any ingestion |

`PLOTTED_SNAPSHOT_ENABLED` deserves a note. Plot Armour (phase 10) needs months
of nightly availability history and **a night not collected cannot be
recovered**. The first environment that runs continuously should turn it on
immediately, not when phase 10 starts.

And a warning about scale-to-zero: **Cloud Run does not run scheduled jobs on an
idle container.** The nightly snapshot and the hourly demo sweep are Spring
`@Scheduled` methods, so they only fire while an instance is alive. Either set a
minimum instance count of 1 on the service, or drive both from Cloud Scheduler
against an endpoint. Deploying with neither means the snapshot silently never
runs — which looks exactly like it running and finding nothing.

---

## Deploying

### API

```bash
gcloud run deploy plotted-api \
  --source . \
  --region northamerica-northeast1 \
  --allow-unauthenticated \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod \
  --set-secrets PLOTTED_JWT_SECRET=plotted-jwt-secret:latest,PLOTTED_DB_PASSWORD=plotted-db-password:latest,TMDB_READ_ACCESS_TOKEN=tmdb-token:latest
```

`--source .` uses the repository-root build context the `Dockerfile` expects.
The region is Canadian because the product is Canada-only and the database
should be beside it.

Secrets go through Secret Manager rather than `--set-env-vars`. An environment
variable set on the command line is in your shell history and in the Cloud Run
revision description, and both are readable by anyone with view access.

### Web

```bash
cd plotted-web && npm run build
```

Then upload `dist/plotted-web/browser` to the static host. The one thing that
must be right: `/api/*` has to reach the Cloud Run service on the **same origin**
as the page. The refresh token is an `HttpOnly` `SameSite=Lax` cookie scoped to
`/api/v1/auth`, and a cross-origin split breaks session persistence in a way
that looks like random sign-outs rather than a configuration error.

If the static host cannot proxy, set `plotted.cors.allowed-origins` to the web
origin and expect to revisit the cookie's `SameSite` — that is a real trade-off,
not a checkbox, and `SameSite=None` requires `Secure` and a reason.

### Seeding

First boot only, with `PLOTTED_SEED_ENABLED=true`. It resolves 119 titles
through TMDB search and ingests each with its Canadian availability, which is
free quota but takes a few minutes. Then **turn it off** — it is idempotent, so
leaving it on is not destructive, but it re-pulls the whole seed on every cold
start, and on a scale-to-zero service that is every few minutes.

---

## Verifying it actually works

In this order, because each one tells you something different:

```bash
curl -s https://<api-host>/actuator/health
```

Expect `{"status":"UP"}`. If it is `DOWN`, read the components — the Redis
contributor is disabled in the `prod` profile precisely because Redis has no
callers yet and an absent one would fail this check for no reason.

```bash
curl -s https://<api-host>/api/v1/providers | head -c 200
```

Reference data from the migrations. If this is empty the migrations did not run.

```bash
curl -sX POST https://<api-host>/api/v1/demo/session | head -c 400
```

The demo path end to end. Two answers worth telling apart:

- `404` — demo mode is off. Expected on a non-demo deployment.
- `"catalogueIsEmpty": true` — the account was created but the seed has not run,
  so the demo will show two empty screens. The response says so rather than
  leaving you to guess whether the features are broken.

---

## Cost control

- **Set a maximum instance count.** Cloud Run's default is high, and the point of
  scale-to-zero is lost if a crawler can scale it to fifty.
- **Set a billing alert before the first deploy, not after.** The failure mode of
  a free tier is not a hard stop.
- **The demo endpoint has its own ceiling** (`maximum-live-accounts`, 500 by
  default) and refuses past it rather than degrading. That ceiling exists because
  the endpoint is unauthenticated and writes: a demo that is briefly unavailable
  is recoverable, and a free-tier database filled by a script is not.
- **Never put a quota'd API key on a public deployment without a limit in front
  of it.** Watchmode is 2500 requests a *month* and MDBList 1000 a *day*, both
  hard caps with no way to buy more. Neither is called by anything today, and
  neither should be reachable from a user request when they are.

---

## What has not been verified

Being specific, because "should work" is not a status:

- **No image has ever been built.** There is no Docker on the development
  machine, so both `Dockerfile`s are unexecuted. `bootJar` itself is built in CI
  and does work.
- **No migration has run against a managed Postgres**, only against
  `postgres:16-alpine` in CI. The extension-permission problem above is the
  known risk.
- **The scheduled jobs have never run anywhere.** Neither the snapshot nor the
  demo sweep has executed outside a test.
- **The same-origin cookie path is reasoned about, not observed.** It is the
  thing most likely to be subtly wrong on the first deploy.
