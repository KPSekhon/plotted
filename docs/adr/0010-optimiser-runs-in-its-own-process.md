# ADR 0010 — The optimiser runs in its own process

- **Status:** Accepted
- **Date:** 2026-08-14
- **Phase:** 5, revisited

## Context

Cancel Culture's model is CP-SAT, reached through OR-Tools' JNI binding. A
native fault there is not an exception — it is a `SIGSEGV` or, on Windows, an
`EXCEPTION_ACCESS_VIOLATION` — and nothing in Kotlin can catch it. The JVM dies.

On the development machine it dies reliably. `PROGRESS.md` recorded this as a
test problem in phase 5 and corrected it on 2026-08-08 to something worse: **one
request to `GET /api/v1/plan` from the running application killed the entire
API**, taking every other endpoint, every in-flight request and the scheduled
jobs with it. The only evidence left behind was an `hs_err_pid*.log` in the
working directory. A solver problem presented as an outage.

That was treated as a developer-machine annoyance because CI and production are
Linux, where the natives load. It is not one. The failure mode is a property of
running a native solver *in the same process as the web application*; the
Windows install merely makes it reproducible on demand. Any deployment where
OR-Tools cannot allocate, or hits a bug on some instance shape, loses the whole
API rather than one request.

## Decision

The optimiser moves into `plotted-solver`, a separate Gradle module that runs as
a **child process**: one request in on stdin, one answer out on stdout, exit.

```
Angular → Spring API ── writes PlanRequest ──▶ plotted-solver (own JVM)
                                                     │  OR-Tools CP-SAT
              PlanOutcome ◀── reads stdout ──────────┘
```

**`plotted-api` excludes OR-Tools from that dependency.** It gets the shared
model types and `PlanChecker`; it does not get the native library, so it cannot
load it even by mistake. `SolverIsolationTest` asserts
`Class.forName("com.google.ortools.Loader")` fails, and was watched fail with
the exclusion removed.

**The model types are shared rather than mirrored.** An earlier draft duplicated
them on each side of a JSON contract with a golden file to catch drift. Sharing
one set through the module dependency is strictly better: there is no drift to
catch, because both processes serialise the same classes.

**Exit status is the protocol, and it is checked before anything is parsed.** A
JVM dying on a native fault writes its crash report to **stdout**, not stderr —
so on a crash the bytes the parent reads are an `hs_err` dump. Parsing first
would report a JSON error for what is actually a dead solver.

**Failure is `OptimiserUnavailable` → 503, never a `PlanOutcome`.** `Infeasible`
and `NothingToPlan` are *answers*: the model ran and this is what it found.
Folding "the solver died" in beside them would let an infrastructure failure
render as a finding about the user's subscriptions.

## Consequences

**Good.** The blast radius of a native crash is one solve. Verified on the
machine where the crash reproduces: `/api/v1/plan` now returns

```
503  {"code":"OPTIMISER_UNAVAILABLE",
      "detail":"Cancel Culture could not be run: the optimiser stopped unexpectedly (exit 1)."}
```

and `/alerts`, `/watchlist`, `/watchlist/coverage`, `/tonight`,
`/analytics/end-credits`, `/subscriptions` and `/actuator/health` all still
answer 200 from the same process. Before this change that request ended the
process. **This is the first time the optimiser's failure path has been observed
end to end anywhere**, and it was observable precisely because the development
machine is the broken one.

It also makes the Windows position honest. The API is fully usable here now, and
Cancel Culture reports itself unavailable rather than being a denial of service
against everything else.

**Bad, and accepted.** A JVM start per solve — small against the endpoint's
existing twenty-second worst case (four solves at a five-second cap), and paid
only by `/api/v1/plan`. Not measured under load, because the solver still cannot
run here.

Two artefacts to ship instead of one, and a path between them: the API resolves
the worker through `plotted.optimiser.worker-directory`, which the Docker image
sets absolutely and `bootRun` satisfies by pinning its working directory to the
repository root. **Neither the image nor a deployment has been built**, and CI
does not build images, so the packaging half is unverified.

**Deliberately not done: a worker pool.** It would amortise startup, and a
crashed member then has to be noticed, drained and replaced — getting that wrong
reintroduces the failure this removes. A process per solve has no state to
corrupt.

**Deliberately not done: Temporal.** `NEXT.md` sketches the optimiser as a
Temporal worker, and that remains the destination for durable execution and
retries. This ADR is the process boundary only — the part that can be built and
verified today without a Temporal server to run. Writing workflow code that
cannot be executed here is the pattern this project keeps catching.

## Related

- [ADR 0001](0001-modular-monolith-with-enforced-boundaries.md) — the first
  place a boundary is enforced by a test rather than by convention. This is the
  first one that is also a *process* boundary, and the reason is not coupling
  but blast radius.
- `docs/NEXT.md` Part 0, P0 item 4.
