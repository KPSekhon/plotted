# Handoff

Paste the block below into a new session. Everything after it is detail.

---

## The prompt

> Continuing Plotted (`C:\dev\plotted`). Read `docs/PROGRESS.md` first, then this
> file. Phases 1–11 are built and merged; `main` is green.
>
> **The catalogue is live locally.** A native PostgreSQL 16 on `localhost:5432`
> holds 503 titles, ~2600 availability rows and 520 snapshots, re-seeded on
> 2026-08-07. There is no Docker on this machine, so 137 of 405 API tests skip
> locally and only run in CI — assume anything touching Postgres, CP-SAT or a
> Spring profile is unverified until CI says otherwise, and budget several round
> trips per red run because the API job's steps hide each other.
>
> What is left is in `docs/HANDOFF.md` under "What is missing", split by who can
> do it. Do the things marked **agent**; tell me when you need the things marked
> **me**. Do not do the things marked **don't**, even though you could.
>
> Hard constraints, unchanged: Watchmode 2500/month (18 spent), MDBList 1000/day
> (0 spent), keys in `.env`. Never call a quota'd API from a test or CI. Never
> invent seed data or provider prices — research and cite, or say it is blocked.
> No AI attribution in commits; short conversational messages, and PR bodies
> written plainly rather than in headed sections.

---

## Where things actually are

| | Phase | State |
|---|---|---|
| 1 | Skeleton | Done |
| 2 | Catalogue | **Seeded and running locally.** 503 titles, 96% with availability |
| 3 | Watchlists, subscriptions, coverage | Done |
| 4 | Queue Theory | Done, including the `blocked_titles` writer |
| 5 | Cancel Culture | Done |
| 6 | Polish, demo mode, deployment | **Built, never deployed.** No video |
| 7 | Evaluation harness | Built; needs outcomes |
| 8 | Learned ranking | Pipeline proven; model not served |
| 9 | Pilot Season | Done — persistence, API, screen |
| 10 | Outbox relay, Plot Armour | Built; **no Temporal**; never seen a real removal |
| 11 | End Credits, rate limiting, observability | Built; **metrics return null** until there are users |
| 12 | Stretch | Not started |

405 API tests, 26 frontend. **268 run locally, 137 need CI.**

---

## What is missing

### Marked **agent** — do these

1. **Nothing is blocking.** Every remaining code task is small or optional.
2. **Verify the ingest transaction end to end.** *Done 2026-08-07.* The
   transaction carries the event on its own: with `fallbackExecution` turned off
   it still delivered, and with the `@Transactional` removed it dropped silently,
   exactly as the original bug did. The three-run table is in
   `docs/PROGRESS.md`. `availability_snapshots` went 0 → 520.

   An earlier note here claimed the title-plus-genres write was not atomic. That
   was wrong — `TitleRepository.upsert` is itself `@Transactional`. The bug was
   only ever that the *event* was published after that transaction had already
   committed, so the listener saw none.

   Also corrected: this file used to say the local database held 504 snapshots.
   It held none.
3. **Nothing tests `TonightService.recommend`.** *Done.* `TonightServiceTest`
   covers the orchestration — both empty answers staying distinct, every outcome
   reaching the log once, the returned request id being the log's own, and a
   deleted title being dropped rather than throwing. Runs without Docker. It
   also pinned two things nothing recorded: `Pick` carries no position, so list
   order *is* the position in two places independently; and the
   `scored.isEmpty()` branch is unreachable, because `PRIORITY` is always
   present.
4. **A throughput benchmark.** *Partly done.* Latency is measured locally —
   median 15.8 ms, p95 26.7 ms on `GET /api/v1/tonight`, in `docs/PROGRESS.md`.
   Requests per second still needs a real load tool against a deployed instance:
   the local attempt measured PowerShell's process startup rather than the
   server, and was discarded rather than quoted.
5. **Population prior for Pilot Season** is zero-mean, which is a placeholder.
   Once profiles exist it should be their average. Needs users first.
6. **`docs/EVALUATION.md` claims are still simulation-only.** Real outcomes need
   accepted recommendations, which need users.

### Marked **me** — only Kanwar can

1. **Create the accounts.** Neon or Supabase, Cloud Run or Render, Cloudflare
   Pages. Most need a card even for a $0 tier.
2. **Set a billing alert before the first deploy.** A free tier does not stop.
3. **Run `ops/deploy/preflight.sql`** against the hosted database before the
   first migration. If it refuses an extension, stop — do not fence the
   exclusion constraints out. Without `btree_gist` duplicate availability rows
   become possible and every coverage number the optimiser depends on inflates.
4. **Deploy**, then paste the Cloud Run URL into `plotted-web/public/_redirects`.
5. **Turn on `PLOTTED_SNAPSHOT_ENABLED`** in the first environment that runs
   continuously, and set a minimum instance so `@Scheduled` actually fires. This
   is the only clock that cannot be rewound.
6. **Record the 90-second video.** `docs/DEMO.md` is the shot list.
7. **Enter provider plan prices** per `docs/seed/provider-plans.md`, from the
   providers' own pages.
8. **Decide what to watch.** Pilot Season needs fifteen real answers before it
   says anything.

### Marked **don't** — the agent could, and should not

These are technically reachable from this machine. They are listed so a future
session does not helpfully do one.

1. **Do not create accounts or enter payment details.** Not a capability limit —
   a line. Direct Kanwar to do it.
2. **Do not answer Pilot Season on his behalf.** Fifteen fabricated comparisons
   produce a taste profile that is a lie, and the whole feature is built around
   refusing to state things it cannot support. `POST /api/v1/pilot/answers`
   against the local API would work fine, which is exactly why this is written
   down.
3. **Do not invent provider prices**, even plausible ones. Fabricated money in
   the optimiser's objective does not produce a visibly broken feature; it
   produces confident, wrong financial advice.
4. **Do not add titles to the seed by taste.** The 119 curated names exist
   because a person chose them. Deriving more from availability is fine; deciding
   what is worth watching is not the agent's call.
5. **Do not accept recommendations to populate End Credits.** Both metrics are
   null because nobody has used the product. Synthetic acceptances would make
   decision latency and completion rate into measurements of a script.
6. **Do not spend MDBList quota speculatively.** 1000/day, 0 spent. The ratings
   cross-check is worth doing *after* hydration, so there is something to compare
   against — not before, to have the data ready.
7. **Do not push straight to `main`.** It happened twice in the last session for
   one-line docs changes. Branch, PR, wait for green.

---

## Local development, without Docker

PostgreSQL 16 native on `localhost:5432`, database and user both `plotted`,
password `plotted`, superuser. That last part is fine locally and is exactly what
must not be true of the hosted database.

```bash
make check-env                                    # before anything
.\gradlew.bat :plotted-api:bootRun                # API on :8080
cd plotted-web && npm start                       # web on :4200, proxies /api
```

`bootRun` sits at "EXECUTING" for as long as the server runs — that is not a
hang. Add `--console=plain` to see the application log instead of the progress
bar.

Re-seeding is idempotent:

```bash
.\gradlew.bat :plotted-api:bootRun --args='--plotted.catalogue.seed.enabled=true --plotted.demo.enabled=true'
```

---

## Things that bit, and would bite again

Seven mechanisms in this project have reported success while doing nothing. Three
more were found on 2026-08-07, all by running the application for the first time
on a machine with a real database:

- **`@TransactionalEventListener` discards events published outside a
  transaction.** `ingest` is not `@Transactional`, so availability was never
  fetched for any title, ever. 503 titles, zero availability rows, no error.
  Fixed with `fallbackExecution = true`.
- **A fail-closed rate limiter with no Redis refuses forever.** The demo endpoint
  answered 429 permanently. "Redis is briefly down" and "there is no Redis" were
  the same code path. Now defaults to an in-memory counter.
- **A duplicate YAML key stopped the application booting**, and the whole test
  suite was green when it shipped — because nothing that runs without Docker
  loads a profile YAML, and everything that does is Docker-gated.
  `ApplicationYamlTest` closes that.

The pattern to carry forward: **a check nobody has watched fail is not a check**,
and on this machine the tests most likely to catch a real problem are the ones
that do not run.

Docker Desktop is free for personal use and would close the 137-test gap.
