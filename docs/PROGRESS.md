# Progress

Where Plotted actually is, what each remaining phase involves, and what is still
open. Written to be read by someone picking the project up cold — including a
future me.

Last updated: 2026-08-07.

**Picking this up cold? Start at [What is still open](#what-is-still-open-and-how-to-finish-it)** —
every remaining item, why it is blocked, what finishing it looks like, and the
order worth doing them in.

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
| 5 | **Cancel Culture** — CP-SAT subscription optimiser | 1 | **Done, merged** |
| 6 | Polish, demo mode, deployment | 1 | **Merged; not deployed** ← *résumé-ready line* |
| 7 | Evaluation harness, MovieLens, baselines | 2 | **Merged; one defensible result** |
| 8 | LightGBM → ONNX → JVM inference | 2 | **Merged; pipeline proven, model not served** |
| 9 | Pilot Season, preference profile | 2 | **Done** — persistence, API and screen merged |
| 10 | Temporal workflows, outbox, Plot Armour detection | 2 | **Relay and Plot Armour merged; no Temporal** |
| 11 | End Credits analytics, load testing, observability | 2 | **Merged; metrics need users** |
| 12 | Stretch: removal-risk model, Group Plot, Side Quest | 3 | |

**Phases 1–6 are the hard line.** The spec is explicit that the project is
presentable at the end of phase 6 and that five half-finished features read worse
than two complete ones. Do not start a Tier 2 item while a Tier 1 item is open.

### By the numbers

36 tables · 17 migrations · 131 Kotlin source files · **405 API tests, all
green in CI** · 26 frontend tests · 30 API paths · 9 ADRs · 107 provider
aliases · 17 seeded plan prices · 1 trained model.

**268 of the 405 run on a developer machine; 137 need CI** — the gated classes
need Docker or CP-SAT. That third is the one covering the database and the
optimiser, which is exactly why it is worth knowing which number you are
quoting.

The gap keeps widening, and it is worth knowing why: almost everything added
since phase 9 is persistence or solver work, and both are exactly what cannot be
verified here. **A third of this suite has never run on the machine it was
written on** — which is why every bug in the last session was found by CI rather
than locally, and why each red run cost several round trips.

---

## What is still open, and how to finish it

Read this first if you are picking the project up. Every item below is either
**blocked on a person**, **blocked on data**, or **plumbing** — none of it is
blocked on a hard problem, and that is deliberate: the hard parts were done
first, on purpose, so that what remains is finishable.

| # | What is missing | Blocked on | Effort |
|---|---|---|---|
| 2 | Seed list is built (519 entries); `make seed` has never run | A database to ingest into | an hour |
| 2 | `PLOTTED_SNAPSHOT_ENABLED` never turned on | An environment that runs continuously | one env var |
| 6 | Never deployed | A person with a cloud account | half a day |
| 6 | No demo video | A person, a seeded database, a deployment | an hour |
| 7 | No real evaluation data | Users, and the timestamp column that now exists | wait, not work |
| 8 | Model trained on synthetic data; explanations unsolved | Phase 7's data | a week |
| 10 | No Temporal workflows | Somewhere to run a Temporal server | unknown |
| 10 | Plot Armour has never seen a real removal | A deployment with the snapshot job on | wait, not work |
| 11 | Both metrics return null | Users, and time | wait, not work |
| 11 | No throughput benchmark | A deployed environment | half a day |

**Closed since this table was last written:** `blocked_titles` now has a writer
(#12), `watchlist_items.completed_at` records when an outcome happened (#11),
Pilot Season has its persistence, API and screen (#14), and the outbox has a
relay with Plot Armour behind it (#15). The deployment preflight (#13) is what
turns the extension-permission risk into a five-second check.

---

### Phase 2 — the seed is built; the pipeline has still never run

**Done: the list.** 519 entries — 400 derived from a live Watchmode enumeration
of what is actually streaming in Canada, plus the 119 curated names kept. About
500 unique titles once the overlap resolves at ingest; the seed run's own report
is the number to quote, because the curated half is names and the derived half is
ids and matching them is what TMDB resolution does.

**The query was inverted, which is the part worth understanding.** The old seed
was names checked one request at a time — guess, then verify, and only ever about
titles somebody thought of. `/v1/list-titles/` filters by *source* and returns
titles in bulk carrying `tmdb_id`, so the seed is derived from availability
instead. **18 Watchmode requests bought 2550 unique titles**, against a
2500-a-month cap. The budgeted figure was 150–200, which was for enumerating
complete catalogues; picking 500 needs only the popular head.

`tools/seed/enumerate_watchmode.py` pulls it, `tools/seed/build_seed.py` buckets
it by release year on the recency weighting. The raw enumeration is committed as
provenance — it is the evidence for what was on each service on 2026-08-07, and
it means nobody re-spends quota to recover it.

**One deviation from the plan, recorded because it is not what NEXT.md says.**
Ranking uses Watchmode's `popularity_percentile` rather than TMDB's `popularity`.
It arrived free in the enumeration response so it cost no extra quota, but it is
a different number from the one the procedure names.

**Still missing: steps 3 to 5, all of which need a database.** TMDB hydration,
the MDBList ratings cross-check (500 calls, half a day's quota), and filing the
places TMDB's watch-providers and Watchmode's enumeration disagree through the
**availability-correction endpoint rather than editing rows**. That last one is
the interesting data: two independent sources disagreeing is precisely the signal
the `confidence` column exists for, and a seed that records its own uncertainty
is worth more than one that hides it.

**`make seed` has still never run**, so the ingestion pipeline is still unproven
end to end against live TMDB.

**Separately: turn on `PLOTTED_SNAPSHOT_ENABLED`** in the first environment that
runs continuously. Plot Armour (phase 10) needs months of nightly history and
**a night not collected cannot be recovered**. This is the most time-sensitive
item in the project — it costs one environment variable today and cannot be
bought back later.

---

### Phase 4 — `blocked_titles` now has a writer

**Closed.** `GET`, `POST` and `DELETE` on `/api/v1/watchlist/blocked`, plus a
control on the title page. The filter had been live since phase 4 over a table
nothing could put a row in.

Three decisions worth not undoing:

- **Catalogue search is untouched.** `TitleSearchRepository.search` takes no user
  id, so it cannot filter — and that is the design. A blocked title missing from
  search reads as a missing catalogue entry rather than a preference being
  honoured, and it leaves no way to change your mind. A test asserts it, so a
  later "fix" fails there rather than shipping.
- **Blocking does not delete the watchlist entry.** That would destroy the
  priority and notes as a side effect of a different request. The entry comes
  back marked and the screen says so — otherwise it sits there never being
  recommended with nothing on screen explaining why.
- **A second block keeps the first reason and timestamp** rather than
  overwriting with an empty one.

---

### Phase 6 — deploy it, and record the ninety seconds

**Deployment.** `DEPLOYMENT.md` is the checklist. Three things will bite, in
order of likelihood:

1. **`CREATE EXTENSION` permissions.** The schema needs `citext`, `pg_trgm` and
   `btree_gist`. Some managed Postgres providers restrict this. Without
   `btree_gist` the GiST exclusion constraints silently do not exist — and those
   are what make duplicate availability rows unrepresentable, which is what keeps
   every coverage number the optimiser depends on honest. **Do not fence them out
   to get past it.**
2. **Scheduled jobs do not run on a scale-to-zero host.** The nightly snapshot
   and the hourly demo sweep are Spring `@Scheduled` methods; they fire only
   while an instance is alive. Pin a minimum instance or drive them from an
   external scheduler. Deploying with neither means the snapshot silently never
   runs, which looks exactly like it running and finding nothing.
3. **Same-origin cookies.** The refresh token is `HttpOnly`, `SameSite=Lax`,
   scoped to `/api/v1/auth`. A cross-origin split breaks session persistence in a
   way that presents as random sign-outs rather than as a configuration error.

**The video.** `DEMO.md` is a shot-by-shot script. Two notes matter more than the
rest: seed the catalogue *first*, and pick the two runtime figures from the demo
persona's **actual** list rather than the placeholders — getting those wrong
makes the best moment in the demo look like a bug.

**Lead with the refusals.** Tonight Mode returning "nothing fits: everything on
your list is longer than the time you have", and the optimiser explaining an
infeasible plan. Every competitor can show a list of films; almost none can show
a system that knows when to say no.

---

### Phase 7 — the harness is built; it needs outcomes

**What is missing is data, not code.** The harness takes queries and returns
numbers, so pointing it at real data is a data problem.

**In order:**

1. **A timestamp on the outcome.** `watchlist_items.status` already reaches
   `completed`, which is the closest available label to "the recommendation
   worked". What is missing is *when* it got there — the temporal split needs it.
   **A column and a write, not a feature.** Do this first; everything else waits
   on it.
2. **Join logged decisions to outcomes.** `recommendation_requests` and
   `recommendation_items` already carry the score, the feature contributions and
   the propensity. The propensity is the one that could not have been added
   retroactively, and it is already there.
3. **Off-policy estimation.** With propensities logged, inverse-propensity
   scoring estimates how a *different* ranker would have done on traffic the
   shipped one served. That is what the 10% exploration slot exists for.
4. **Then, and only then, a claim about beating popularity.**

**How to make it excellent: keep reporting where it loses.** The current page
already does — sorting by watchlist priority alone beats the five-feature model
on precision@3. A harness that only produces flattering numbers is not a harness,
and the credibility of the one real result rests on the honesty of the rest.

---

### Phase 8 — a real model needs phase 7's data, and explanations need solving

**Two blockers, and the second is the interesting one.**

*Training data.* The committed model is a distillation of the linear ranker. It
proves the pipeline and nothing else. A model worth serving needs the outcomes
phase 7 is waiting for.

*Explanations.* The product's rule is that a reason must be a real feature
contribution, never prose that sounds like one. A gradient-boosted tree does not
hand you contributions; SHAP does, and ONNX does not export it. **Serving the
learned ranking beside the linear model's explanations would have the interface
confidently explaining a decision it did not make** — the invented-prose failure
this project refuses everywhere else, in a better costume.

**A candidate approach worth trying**, and it has not been: serve the learned
ranking only where it *agrees* with the linear model, and fall back where they
differ. That is testable, honest, and degrades toward the model whose
explanations are real. Measure the agreement rate first — if it is 95%, the
learned model is buying very little, and that is worth knowing before building
anything.

**MovieLens stays deferred, with the reason recorded.** It gives
`(user, item, rating, timestamp)`; five of the eight features here are session
context — how much *you* want it, whether it fits the time *you* have, what *you*
pay for — for which MovieLens has no analogue. Using it would mean fabricating
those five. Its real home is phase 9's taste model.

---

### Phase 9 — the maths is done; the questionnaire is not

**Missing: persistence, API, screen.** A `pilot_comparisons` table, an endpoint
that serves the next question and records an answer, and fifteen taps on a phone.
Nothing about it is subtle.

**Three things that are, and should not be skipped:**

1. **Allow "skip".** A forced choice between two titles somebody has not seen
   produces a coin flip recorded as evidence. The ladder already tolerates a
   short questionnaire, so a skipped pair should simply not become a comparison.
2. **Make the population prior real.** It is currently zero-mean, which for a
   population nobody has measured is the same thing — but it is a placeholder,
   not a finding. Once profiles exist, the prior mean should be their average.
   That single change turns "assume you are indifferent" into "assume you are
   typical".
3. **Then measure whether taste helps.** `taste_match` is plumbed into the
   feature schema and carries no learned signal, because the training target does
   not read taste. The harness is where that question gets answered.

**Do not make the ladder adaptive yet.** Adaptive selection tunes against a model
of the population, and there is no population; it would be adapting to the prior,
which is a fixed ladder reached by a more complicated route and much harder to
test.

**Built since:** `pilot_comparisons`, the four endpoints and the screen. Skip is
recorded and is not evidence — the repository filters on a non-null choice and
the schema refuses half-skipped rows in either direction. The attribute
difference is frozen at answer time rather than recomputed, because RECENCY is
measured against the current year and refitting an old answer would restate it.

**And it exposed an infinite loop in `PilotLadder.build`.** The exhaustion guard
sat below a `continue` that skipped it, so a catalogue too small to fill the
ladder span forever rather than returning short. It needed a *non-empty* ladder
to trigger, which is why the existing identical-titles test never found it: that
case produces an empty ladder, and the empty path was the one that terminated. So
"too uniform to ask anything" was covered and "too small to ask fifteen" was not
— which is every catalogue smaller than the seeded one, including a fresh deploy
before `make seed` runs. The new test carries a `@Timeout`, because the failure
does not throw, and it was run against the original code to check it fails there.

**Still a placeholder: the population prior.** Zero-mean, which for a population
nobody has measured is the same number and a different claim. Once profiles
exist the mean should be their average. Left until there are users, because an
average over nobody is still zero.

### Measured 2026-08-08: the ladder cannot reach its own bar

The demo persona is a stated weight vector, and its answers are whatever that
vector implies — so it is the most consistent respondent Pilot Season will ever
have. Fitting a **complete fifteen-answer** questionnaire from it:

| Axis | fitted weight | standard error | \|w\|/se | verdict |
|---|---|---|---|---|
| Levity | 0.879 | 0.632 | **1.39** | `NO_PREFERENCE` |
| Pace | 0.876 | 0.633 | **1.38** | `NO_PREFERENCE` |
| Grounding | −0.879 | 0.632 | **1.39** | `NO_PREFERENCE` |
| Commitment | 0.741 | 0.673 | **1.10** | `NO_PREFERENCE` |
| Recency | 0.723 | 0.668 | **1.08** | `NO_PREFERENCE` |
| Acclaim | 0.767 | 0.671 | **1.14** | `NO_PREFERENCE` |

`CREDIBLE_MULTIPLIER` is 1.96. Nothing gets close, and `isInformative` is
false. **A perfectly consistent respondent, answering every question, is told
Plotted has nothing strong enough to act on** — and so, therefore, is every real
user. The feature is built, correct and well tested, and structurally cannot
produce its headline output.

The cause is arithmetic rather than a defect: fifteen comparisons over six axes
is 2.5 observations each, the Gaussian prior deliberately shrinks weights toward
zero to keep the mode defined, and the posterior stays nearly as wide as the
prior. A 95% bar is then unreachable.

**The good news is that the fit is right.** It recovered the persona's sign on
all six axes and its rank order roughly — the estimator works, the *evidence
budget* does not.

Four levers, and this is a design decision rather than a bug fix, so none has
been pulled:

1. **More comparisons.** Separating six axes at 95% wants something closer to
   thirty or forty, not fifteen.
2. **Fewer axes.** Six was a choice; four would spend the same answers on less.
3. **A lower credible multiplier.** Cheapest and the most dangerous — the
   threshold's own comment records that without it a profile "finds" two or
   three preferences that are noise, indistinguishable from the real ones.
4. **A weaker prior.** Widens the posterior it is there to constrain.

Worth deciding before Pilot Season is shown to anybody, because at present the
honest answer it gives is also the only answer it can give.

---

## Phases 10 to 12, and how to approach them

### Phase 10 — the outbox relay and Plot Armour (built; no Temporal)

**Four things existed and did nothing.** The `outbox` table had a writer nobody
called, `availability_changes` and `alerts` had no writer at all, and the nightly
diff computed added and removed offers and discarded them. They are connected
now.

**The claim is a lease, not a lock.** The obvious implementation is
`SELECT ... FOR UPDATE SKIP LOCKED`, and it is wrong here in a way that looks
right: a row lock lasts only as long as its transaction, and delivery happens in
a later one so a single failure does not roll back the batch. By the time a
handler runs the locks are gone, and two instances would deliver everything twice
while appearing to use `SKIP LOCKED` correctly. `claim` is an
`UPDATE ... RETURNING` that pushes `next_attempt_at` out instead.

**Delivery is a separate bean** because `@Transactional` works through a proxy
and a self-call gets no transaction at all — the annotation is read, looks
applied, and does nothing. `REQUIRES_NEW`, so recording a failure survives the
failure that caused it. Phase 1 shipped that exact bug once already.

**Alert suppression is the product.** Seven rules, all counted and logged,
ordered so the reported reason is the first that applied. The one that matters
most is `NOT_SUBSCRIBED`: a title leaving Paramount+ is nothing to somebody who
never had Paramount+, and it is the alert that would make the feature feel like
spam. `PlotArmourTest` covers one case per rule plus a check that every reason is
reachable — a reason nothing can produce is a rule edited out of the decision and
left in the enum.

**The alert says "has left", not "about to leave."** The diff sees departures
that already happened. Predicting one needs the removal-risk model and months of
snapshot history, which is phase 12 and cannot start until the snapshot job runs.

**Which is why the alert list has no boundary marker**, and that is worth
recording because the boundary is the obvious design for this feature. A dated
line the route crosses — *leaves Crave on 19 August* — needs a future date, and
an `Alert` carries none: `alertType`, `severity`, `titleId`, `message`,
`createdAt`. Drawing one would mean inventing both a date and a tense, on the
single feature whose whole value is being trusted about availability. The list
uses a **dead end** instead, which is true of a departure that has happened, and
the boundary waits for the removal-risk model that could honestly produce one.

**Designed against fixtures rather than production content**, since nothing has
ever fired: `alert-list.component.spec.ts` carries a development story set —
both event kinds, all three severities, a long message, and one alert with no
title to link to — plus the two states that matter most, rendering nothing at
all when there is nothing to say, and surviving a failed load without breaking
the home page it sits on.

**`ModuleBoundaryTest` could not catch a real violation here.** Plot Armour first
read its event-type constant from `availability.domain`, straight across a
feature boundary, and `featureModulesAreIndependent` passed — a Kotlin
`const val` is inlined by the compiler, so the bytecode ArchUnit reads holds the
string and no reference to the class. A cross-module constant is invisible to the
rule that exists to forbid cross-module coupling. Moved to
`platform.outbox.OutboxEventTypes`; where these live is discipline rather than
enforcement.

**`RETURNING` has no ordering guarantee**, and a comment here claimed it did.
The `ORDER BY id` inside the claim decides *which* rows are taken — oldest first,
so nothing starves — and says nothing about the order they come back. Postgres
returned a batch reversed and the ordering test caught it. The repository sorts
after claiming. Without that, the relay would occasionally deliver one
transaction's events out of order, depending on how the update happened to walk
the rows.

**The phase 3 bind bug, from the other end.** The claim failed in CI with
`operator does not exist: timestamp with time zone <= character varying`. Phase 3
recorded exactly this, in a *test fixture*, under the heading "write fixtures the
same way the repository writes" — in plain SQL jOOQ has no target column to infer
a bind type from, so an `OffsetDateTime` crosses as `varchar`. This was the
repository written the way that fixture was. Every bind in the statement is cast
explicitly now. The lesson generalises further than it was written: the typed DSL
is the protection, and *any* plain SQL gives it up, whichever side it is on.

**Not built: Temporal.** There is no dependency and nowhere to run a worker. The
relay is a Spring `@Scheduled` poller and says so. Writing workflow code that
cannot be executed or tested here would be the pattern this project keeps
catching.

**Nothing has produced a real event.** `availability.removed` is emitted only by
the nightly refresh, which needs `PLOTTED_SNAPSHOT_ENABLED` and a deployment.
Every path is tested with synthetic events. **Every day this is not deployed is a
day of history that cannot be recovered** — if you do one thing from this
document, do that one.

### Phase 11 — End Credits (built; the metrics need users)

**The chain is complete.** Request → served → accepted → completed. The middle
two links did not exist: `completed_at` arrived first, then
`recommendation_items.accepted_at` with `POST /tonight/{requestId}/accept` and a
button on the Tonight screen. Acceptance points at a *served item* rather than a
title, so the position and propensity travel with it — which is what makes it
usable for off-policy evaluation.

**Both metrics refuse to flatter, and that is most of the work.**

- Decision latency is a **median**, because wall-clock has an unbounded tail.
  Acceptances more than four hours later are excluded and *counted*, not dropped.
- The completion rate holds back anything accepted in the last fourteen days.
  Counting those as failures would make the rate climb on its own as the log
  aged, which looks exactly like the product improving.
- Both are **null rather than zero** when there is nothing to compute from. Zero
  is the best possible latency and the worst possible completion rate, and both
  would be reached by having no evidence.
- The completion join tests `completed_at >= accepted_at`, so a title somebody
  had already watched cannot be credited to a later recommendation, and it is a
  **left** join, so an accepted pick since removed from the list counts as
  unfinished rather than disappearing from its own denominator.

**Redis has a caller, so the health contributor is back on** — and deliberately
*not* in the readiness group. Everything except rate limiting works without
Redis, and including it would turn a degraded limiter into every instance
leaving the load balancer. Fail-open and fail-closed are per limit: the optimiser
fails open (authenticated, one account's blast radius), demo session creation
fails closed (unauthenticated, it writes, and a filled free-tier database does
not recover).

**The 20-second bound is pinned as a count, not a stopwatch.** It was never a
measurement — it is the five-second cap times the most solves one request can
need. `PlanSolverBoundTest` asserts that count, which is deterministic and fails
if a fourth probe is ever added; a wall-clock assertion would pass with three
orders of magnitude to spare and would be flaky besides.

**Latency measured locally on 2026-08-07; throughput still not.** Against the
seeded native Postgres, `GET /api/v1/tonight` over 200 warmed requests:
**median 15.8 ms, p95 26.7 ms, p99 36.4 ms, max 41.6 ms**. That is the whole
request path — auth, three batched SPI lookups, screen, score, diversify, and
the decision-log write — and it sits comfortably inside the §13.1 budget.

**The requests-per-second figure from the same run is discarded, and why is the
useful part.** Eight concurrent PowerShell workers produced 32.6 req/s against
63 req/s sequential — concurrency made it *slower*, which is impossible for the
server and obvious once stated: `Start-Job` spawns a process per worker, so the
harness was measuring PowerShell's startup cost. Quoting it would have been a
number about the test rig wearing the costume of a number about Plotted. A real
throughput figure needs a proper load tool against a deployed instance; the
latency figures above stand on their own because they are sequential and
warmed.

**The optimiser is deliberately not benchmarked here.** CP-SAT crashes the JVM
on this machine (phase 5), so `/api/v1/plan` cannot be driven locally at all.
Its 20-second worst case remains asserted structurally as a solve count rather
than measured.

**Built 2026-08-08: the screen.** `GET /api/v1/analytics/end-credits` had no
interface at all, so the two numbers the product's thesis rests on were
reachable only with a bearer token. `/analytics` renders them, and the two
exclusion counts — acceptances left out as stale, acceptances held back as too
recent — are given the same weight as the headlines rather than tucked into a
footnote. They are the rules that stop both figures drifting upward on their
own, and a reader who cannot see them is taking the headline on trust, which is
the one thing this screen exists not to ask for.

A null metric renders as a sentence naming the condition that was not met,
never as a dash or a zero. Both would read as "nothing happened"; the true
meaning is "not enough evidence to say", which is a claim about the log rather
than about the product.

`Analytics` is in the main navigation now that there is something behind it.

**What is still missing is users.** Both metrics return null on an empty log,
and that is the correct answer rather than a gap in the code. The demo account
carries a manufactured history so the screen can be evaluated before then —
fixture rows, stamped `demo-fixture` rather than `linear-v1` so no later
analysis can pool them with anything real.

### Phase 12 — stretch, and only if the earlier ones are genuinely done

Removal-risk model, Group Plot, Side Quest, household fairness, history import,
interleaved experiments. The spec is explicit and it is right: **two complete
headline features with measured results beat five half-built ones.**

---

## The order I would actually do it in

Everything that could be built without a person has been built. The list is now
one item long.

1. **Deploy** (phase 6), and **turn the snapshot job on**. Run
   `ops/deploy/preflight.sql` against the database before anything else — if a
   managed Postgres refuses an extension, that is the moment to find out, and the
   fix is never to fence the constraints out.
2. **Run `make seed` once**, then turn it off. The list is built; the ingestion
   has never executed against live TMDB.
3. **Record the video** (phase 6). Now it has something real to show.

Everything else is waiting on time rather than on work: Plot Armour has never
seen a real removal because nothing has produced one, and both End Credits
metrics return null because no decision has been accepted yet. Both are correct
answers to an empty log, and both start producing numbers the day step 1 happens.

Step 1 also starts the only clock that cannot be rewound. The snapshot history
Plot Armour's removal-risk model needs accumulates from the night the job first
runs, and a night not collected is gone.

---


## The GitHub Actions outage of 2026-08-06, and what it cost

Phases 5 to 9 were written while GitHub Actions was in a **major outage**, and
merged afterwards. All five are now on `main` and **every one of them was
verified by CI before merging** — 313 tests, nothing skipped, nothing failing,
including the ~94 that only ever run there.

Kept because the failure mode is worth recognising again, and because the first
diagnosis was wrong.

**What it looked like.** Three symptoms over about nine hours:

1. A run whose remaining jobs failed with *"not acquired by Runner of type
   hosted"*.
2. No workflow runs created at all for six consecutive pushes, despite the pushes
   landing and the pull request's head advancing.
3. A manually triggered re-run accepted, then sitting `queued` indefinitely.

GitHub's status page said it plainly: *"Webhook triggers are currently throttled
to help with recovery and we are processing approximately 15% of webhooks"*, and
*"runners are stuck retrying jobs that are no longer available"*. That is exactly
(1), (2) and (3).

**The wrong diagnosis.** The pattern was first read as an exhausted Actions
allowance on a private repository — which fits the symptoms just as well and is
the more common cause. Everything checkable with the available token *was*
checked: Actions enabled, all actions permitted, workflow `active`. That felt
like diligence and was still the wrong shape of investigation, because the one
source that could settle it in a single unauthenticated request — the status
page — was never consulted. **Check whether the platform is up before reasoning
about your account.**

**Two things that outlived the outage.**

*A lost webhook never replays.* Once Actions recovered, the pull requests still
had no checks: the events for their heads had been dropped, and retargeting a
base does not create one either. Closing and reopening each PR fired a fresh
`reopened` event, which is cleaner than an empty commit.

*A conflicted PR runs nothing, silently.* After squash-merging phase 5, the
stacked branches went `CONFLICTING` — and GitHub cannot build a merge commit for
a dirty PR, so `pull_request` workflows never fire at all. "No checks reported"
looked like the outage lingering and was actually a conflict. Rebasing each
branch onto the new `main` fixed it. **`gh pr view --json mergeStateStatus` is
the first thing to check when a PR has no checks.**

**What the delay actually bought.** Three real bugs were found by reading the
code during the wait rather than by CI:

- `DemoRepositoryIntegrationTest` built availability rows with the typed jOOQ
  API, but `title_availability.validity` is `DATERANGE NOT NULL` with no default
  and is fenced out of the generator's view. Every insert would have failed a
  not-null violation — on Postgres only, which is to say only in CI. Fixed by
  going through `AvailabilityRepository.open`, which is the phase 3 lesson from a
  new direction: *write fixtures the same way the repository writes*.
- A plan-lookup assertion read `== cheap || != dear`, which is true for any value
  including null and so had no way to fail.
- The committed OpenAPI document was missing the demo endpoint. That one CI did
  catch, on the first green-able run.


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

### The ingest transaction, verified 2026-08-07

`TitleWriter` puts the title write and the `TitleIngested` publish in one
transaction. It had never been observed working — the seed that succeeded
earlier that day was carried by `fallbackExecution` on the listener, not by the
fix.

**The obvious check could not have worked.** With `fallbackExecution = true`
still on the listener, re-seeding and confirming availability appears is passed
identically by the fix and by a full revert of it, because the safety net writes
the same rows. So the check was made to discriminate instead — three runs,
single ingests through `POST /api/v1/titles`:

| Run | `@Transactional` on `store` | `fallbackExecution` | `availability_snapshots` |
|---|---|---|---|
| Negative control | removed | `false` | **0** — HTTP 200, silent drop |
| The fix alone | restored | `false` | **0 → 1** |
| Committed code, full seed | as merged | `true` | **1 → 520** |

The middle row is the result: with the safety net off, the transaction alone
delivered the event. The first row matters as much — it reproduced the original
bug exactly, returning 200 with no exception and no log line, so this check has
now been watched fail rather than merely watched pass. The working tree was
restored between runs and `git diff` against `HEAD` was empty before the seed,
which reported `519 requested: 0 new, 519 refreshed, 0 unmatched, 0 failed`.

**One correction.** `docs/HANDOFF.md` recorded 504 snapshots in the local
database. There were none: `pg_stat_user_tables` showed 519 inserted and 519
deleted, and nothing in the codebase deletes from that table, so it was cleared
by hand. That happened to make "above zero" a clean signal.

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
5. **The seed list is built** — 519 entries, ~500 unique. *Done*, and the part
   that was genuinely manual stayed manual: the 119 curated names are still
   there, because popularity ordering drops the Canadian originals, CBC Gem, the
   anime and the subtitled film, and those are what keep the coverage numbers
   informative rather than flattering.

   **Provider plan prices are still hand-entered** per
   `docs/seed/provider-plans.md`, and that must stay true. Invented prices put
   fabricated money into the optimiser's objective function, which does not
   produce a visibly broken feature — it produces confident, wrong financial
   advice. Compare what comes back against the provider's own app and record
   disagreements through the correction endpoint rather than editing rows.

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

### The runtime filter was measuring the wrong thing (fixed 2026-08-08)

The worst defect found in this project so far, and it was never a crash.

Tonight Mode's time filter read `watchMinutes`, which for a series is **every
episode added up**. One Piece is 472 hours, so it never fitted an evening — and
neither did any other multi-season series. Asked *"I have 45 minutes"* against a
list of half-hour comedies and anime, Plotted answered *"nothing fits:
everything on your list is longer than the time you have."*

| Ask | Before | After |
|---|---|---|
| 45 min | nothing fits | Chainsaw Man, Demon Slayer, Beyblade X |
| 90 min | nothing fits | The Boys, ONE PIECE, Chainsaw Man |

Nobody watches a series in one sitting; they watch an episode. There are two
figures now, named for the questions they answer — `watchMinutes` is the whole
**commitment**, and `sessionMinutes` is one **sitting**. The filter and the
runtime-fit feature read the second. Tonight shows the episode length with the
total beside it: showing only the total was the bug, and showing only the
episode would hide what somebody is signing up for.

The filter is still a filter. A 75-minute episode is still refused for a
45-minute evening, and a series with no known episode length is
`RUNTIME_UNKNOWN` rather than being handed a total divided by an episode count.

**The half that mattered more.** Only **77 of 260** seeded series had an episode
length at all; the other 181 had a total and nothing else, because that field
came from TMDB's `episode_run_time`, which upstream leaves empty for most shows.
Two thirds of the series catalogue would have stayed unrecommendable even after
the filter was corrected. `recalculateTotalRuntime` now derives the typical
episode from the episodes it is already summing — the same principle the total
follows, that real stored rows beat a field upstream is abandoning. After a
re-seed it is **260 of 260**, and The Boys and Demon Slayer stopped being
`partial` metadata.

**How it hid.** Every test passed throughout. `HardFiltersTest` only ever built
film-shaped candidates, so the one case that mattered — a long series with short
episodes — was never constructed. The filter was correct about the number it was
given and the number was the wrong one, which no unit test asserting "180
minutes does not fit 90" can catch. It surfaced only by asking the running
product the question it exists to answer, with a watchlist somebody had actually
chosen.

**One knock-on worth knowing.** `MetadataCensoringSimulation` ties both figures
to a single draw. The ranker reads only `sessionMinutes`, so censoring
`watchMinutes` alone would have left the runtime feature permanently present and
turned the phase 7 ablation into a measurement of nothing — while still printing
a plausible number. `EVALUATION.md` is byte-identical after the change, which is
the check that it worked.

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

## Phase 5 — Cancel Culture (done)

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

**Confirmed at runtime on 2026-08-08, which is worse than it sounds.** This note
previously described a *test* problem. One request to `GET /api/v1/plan` from
the running application killed the whole API — same `EXCEPTION_ACCESS_VIOLATION`,
a fresh `hs_err_pid*.log`, and the process gone, taking every other endpoint
with it. So on a Windows development machine Cancel Culture is not merely
unverifiable, it is a denial of service against the local server: anything else
being tested at the time dies too, and the failure looks like the API crashing
rather than like the solver failing.

Two consequences worth carrying: the Cancel Culture screen cannot be seen
against real solver output here at all, so anything built for it needs
component-level tests rather than a look (`plan-transit-map.component.spec.ts`
is that); and CI, being Linux, remains the only place the feature has ever run.

`SolverSupport` gates the solver tests exactly as `DockerSupport` gates the
database ones, and it has to guess from the OS rather than probe, because
probing is the thing that crashes. Linux and macOS run them; Windows skips
unless `PLOTTED_SOLVER_ENABLED=true`. CI is Linux and runs all ten solver tests
unconditionally, as does production — so this is a developer-machine problem
rather than a product one, and the whole phase was in fact finished and verified
through CI regardless.

Everything that is not the solver is plain Kotlin and runs everywhere, which is
most of the interesting logic: `PlanChecker` and its tests touch no native code.

### The model and the checker

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

### The service, the API and the screen

- **`CancelCultureService`** — gathers from the four SPIs and decides what the
  model is shown, which decides what it says more completely than the model
  does. Three kinds of watchlist item are deliberately excluded and *reported*
  rather than dropped: free to watch (no subscription decision turns on them),
  never checked (the coverage dashboard's denominator rule, inherited rather
  than reinvented), and only on a service with no established price (guessing
  one puts fabricated money in the objective).
- **An empty demand set returns no advice at all**, not a plan. The optimum over
  an empty watchlist is "cancel everything you are not locked into", with a
  dollar figure attached and every appearance of being advice. `NothingToPlan`
  exists so that answer can never be given.
- **`GET /api/v1/plan`** and the Cancel Culture screen. The screen leads with
  the refusals — the infeasible explanation, the sensitivity line, and what the
  optimiser was never shown — because those are the parts a competitor cannot
  show.
- **`PlanSolverAgreementTest`** — the one that matters. For instances small
  enough to enumerate, *every* possible plan is built, judged feasible and
  scored by `PlanChecker` alone, and the best compared against CP-SAT's answer.
  Nothing on that path shares a line with the model builder. Five fixed shapes
  plus forty seeded random ones.

### What CI found on the first execution

`PlanSolver` had never run. Three things came out of the first real execution,
and only one of them was in the model.

1. **The model was right.** No feasible plan beat the solver's on any of the 45
   instances, and the checker rejected none of its answers. That is the claim
   the whole phase rests on, and it is now tested rather than assumed.
2. **A failing test whose premise was wrong, not whose subject was.** The
   sensitivity case assumed a one-service limit had to cost half the coverage.
   It does not: over two months the solver *rotates* — hold the cheap service
   this month, switch to the other next month, see the whole list — so relaxing
   the limit buys no extra coverage and is correctly not reported as binding.
   The rotation is now pinned as its own test, and the sensitivity case moved to
   a single month where the limit really does bind. Worth keeping in mind: a
   constraint on services held *at once* is much weaker than it looks once the
   model has a time dimension.
3. **A schema collision the drift check could never have caught.**
   `optimisation.api` and `watchlist.api` both declared a `CoveredTitleResponse`.
   springdoc keys `components.schemas` by simple class name, so one silently
   overwrote the other and the optimiser's covered titles were published with a
   `priority` and no `month` — the generated Angular client would have been
   wrong for that endpoint. Nothing threw. The drift check compares the document
   to the committed copy, and the drifted document was internally consistent and
   matched itself perfectly. **This is the fifth mechanism in this project that
   reported success while doing nothing.** `ModuleBoundaryTest.apiClassNamesAreUnique`
   now fails the build on any API name collision; it was verified to fail on the
   real one before being trusted, and is scoped to top-level classes because
   every Kotlin companion object compiles to a nested class called `Companion`.

### Known, and deliberately not changed

A request can in the worst case take four solves — the plan plus one per binding
constraint — at a 5-second cap each. On instances this size CP-SAT proves
optimality in milliseconds and nothing has come close, but the *bound* is 20
seconds, and that is the number phase 11's load testing should be aimed at. The
alternative, a shorter cap on the sensitivity re-solves, is worse: a re-solve
that times out returns null and the constraint silently disappears from the
panel, which turns a latency problem into a correctness one.

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

## Phase 6 — Polish and deploy — the résumé line

**Built.**

- **Demo mode** — `POST /api/v1/demo/session` gives a visitor their own
  throwaway account with a watchlist and two subscriptions already on it. Own,
  not shared: a shared demo account means the first thing an evaluator sees is
  the last visitor's half-finished experiment. The persona is derived from the
  data rather than hard-coded — the two subscriptions are the service covering
  most of the list and the one covering least, and the weak one carries a
  commitment, so Cancel Culture has to hold something it wants to cancel and say
  why. Off by default; the endpoint is unauthenticated and it writes.
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — diagrams of the module graph, both
  headline pipelines, the data provenance path and the request path, plus the
  table of what *enforces* each correctness property rather than what documents
  it.
- **[DEMO.md](DEMO.md)** — a shot-by-shot 90-second script that leads with the
  refusals. The video does not exist; recording it needs a person, a seeded
  database and a deployed environment.
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — the plan, the environment variables, the
  verification sequence, and an explicit list of what has never been executed.

**One real bug fixed on the way.** Nothing in the codebase uses Redis — it is a
declared dependency waiting for the phase 11 rate limiter — but its health
contributor was live, so on any deployment without a Redis instance
`/actuator/health` would report DOWN and the readiness probe would keep the
service from ever receiving traffic. Disabled in the `prod` profile, with a note
to turn it back on in the change that gives Redis its first caller.

**Not done: the deployment itself.** No image has ever been built (no Docker on
this machine), no migration has run against a managed Postgres, and the
scheduled jobs have never fired anywhere. `DEPLOYMENT.md` lists the three things
most likely to break first, of which the extension-permission problem is the
worst — without `btree_gist` the exclusion constraints silently do not exist.

**Stop here if interviews start.** Two complete headline features with measured
results beat five half-built ones.

---

## Phase 7 — Evaluation harness (harness built, one result)

**Built.** `NDCG@k`, precision@k, MRR and a percentile bootstrap, all written out
rather than pulled from a library — each has a detail that is silently wrong in
some implementations, and a number nobody can defend is worse than no number.
Four baselines, a paired bootstrap for comparisons, and a temporal split that
exists now precisely so phase 8 is not the moment somebody decides how to divide
the data. `./gradlew :plotted-api:evaluate` regenerates
[EVALUATION.md](EVALUATION.md) with no Spring context and no database.

**The one result that is not circular.** Renormalising over present features is
worth **0.0170 NDCG@3 (95% CI 0.0145–0.0194, n=2000)** at a 30% metadata
censoring rate. It matters that this is an ablation of *one* thing: it rescales
the shipped scorer rather than reimplementing it, and a test asserts the two
produce identical rankings when nothing is missing.

**Everything else on that table is a smoke test**, because the simulation's
ground truth is the model's own score — so any comparison against a different
ranker is circular by construction. `EVALUATION.md` says so at the top rather
than in a footnote, and also reports where the model *loses*: sorting by
watchlist priority alone beats it on precision@3, and the ablated model takes the
best MRR.

**The sixth "reported success while doing nothing" bug.** The first version of
the report was not reproducible. Every strategy breaks ties on title id and the
simulation minted those with `UUID.randomUUID()`, so the intervals moved between
runs while the comments insisted the whole thing was seeded. Nothing failed; the
numbers were plausible either way, and the document would have stopped matching
the code within a day. `EvaluationReportTest` now runs the report twice and diffs
the markdown, and once more with a different seed — because a generator that
ignored its seed entirely would pass the first check and be just as wrong.

**Still missing, and it is data rather than code:** no user has ever used
Plotted, so there are no outcomes to evaluate against. The first thing needed is
a timestamp on the `watchlist_items.status` transition to `completed`, which is a
column and a write rather than a feature.

## Phase 8 — Learned ranking (pipeline built and proven)

**Built.** `FeatureSchema` — one ordered declaration of the feature vector, used
by both training and serving; `OnnxScorer` — in-process inference that **refuses
to load a model whose schema fingerprint does not match**; `exportTrainingData`
and `ml/train_ranker.py`; a committed ONNX model, golden vectors, and a
deliberately-wrong model that exists to be rejected. Full write-up in
[MODEL.md](MODEL.md).

**The design decision worth pointing at.** Feature extraction exists in exactly
one place — the serving code — and the training script consumes what serving
produced. The usual arrangement computes features in Python and reimplements them
for inference, and that pair is where most training-serving skew comes from. Here
there is nothing to drift.

**The pipeline is falsifiable end to end without a single user.** The committed
model is a *distillation* of the linear ranker, so a correct pipeline has a known
right answer: the two must rank alike. Measured over 2,000 queries, they differ
by **0.0000 NDCG@3 (95% CI −0.0002 to 0.0003)**. Break any link — features
exported out of order, `NaN` read as zero, the wrong float width — and that moves.

**Not served, deliberately.** `plotted.model.enabled` is `false`. A boosted tree
does not produce feature contributions, and serving its ranking beside the linear
model's explanations would mean the interface confidently explaining a decision
it did not make. That is the invented-prose failure this project refuses
everywhere else.

**MovieLens is not used, and that is a decision rather than an omission.** It
gives `(user, item, rating, timestamp)`; Plotted's vector is almost entirely
session context — time available, what you pay for, how much you want it — for
which MovieLens has no analogue. Bootstrapping from it would mean fabricating
five of eight columns. It belongs in phase 9's taste model instead.

**A tolerance bug found while writing the tests.** The batch-invariance check
demanded agreement within `1e-9` on a `float32` output, where adjacent
representable values near 0.9 are ~6e-8 apart. It was asserting something finer
than the type can express and failed for a reason unrelated to batching. *A
tolerance tighter than the data type is not a strict test, it is a broken one.*

## Phase 9 — Pilot Season (the maths, not the screen)

**Built.** A feature-parameterised Bradley–Terry model fitted by Newton on the
log-posterior, a fixed ladder that chooses informative pairs, and a profile layer
that decides which fitted weights are worth saying out loud. 29 tests, none
skipped. Full write-up in [PILOT.md](PILOT.md).

**The prior is not optional.** With fifteen comparisons and six axes, maximum
likelihood is frequently **undefined** — somebody who picks the comedy every time
drives that weight to infinity, and an unregularised fit reports whatever it
reached when the iteration cap stopped it. The Gaussian prior makes the posterior
strictly concave so the mode always exists, and its mean is the *population's*
taste rather than zero: "assume you are typical" rather than "assume you are
indifferent".

**Two of the four verdicts are ways of saying we do not know.** `NO_PREFERENCE`
(we asked, you were balanced — a real finding) and `NOT_ASKED` (the ladder never
contrasted this axis) produce nearly identical weights and completely different
advice. Only the posterior width separates them, which is why the fitter returns
one. A profile with nothing it can defend saying returns **null** rather than
0.5: both rankers already handle an absent feature properly, and a real-looking
number computed from noise is noise a decision would treat as signal.

**It made phase 8's guard fire on a real change.** Adding `taste_match` bumped
the feature schema v1 → v2, the committed model was refused for a fingerprint
mismatch, and the fix was to retrain rather than override anything. That is
`MODEL.md`'s central claim, observed rather than asserted.

**And caught one of my own tests being wrong.** `OnnxScorerRefusalTest` used the
literal `"v2"` as "some other version". It passed until the schema *became* v2,
at which point it was asserting the fingerprint differed from itself. A test that
hard-codes "some other value" breaks when the real value becomes that value.

**The linear ranker was deliberately left alone.** Adding a sixth feature would
mean rebalancing five designed weights, invalidating the phase 7 ablation with no
evidence to justify a new arrangement. Do not change a measured thing without a
measurement.

**Not built: persistence, API, screen.** The part that is hard to get right is
done; the plumbing is not.

## Phases 10–11 (Tier 2) — what turns "built" into "measured"
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
