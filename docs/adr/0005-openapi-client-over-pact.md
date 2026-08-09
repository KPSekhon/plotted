# ADR 0005 — Generated OpenAPI client instead of consumer-driven contracts

- **Status:** Accepted
- **Date:** 2026-07-26
- **Phase:** 1

## Context

The Angular client and the Spring API have to agree on request and response
shapes. Consumer-driven contract testing with Pact is the well-known answer.

Pact exists to solve coordination between services that are developed by
different teams and deployed independently, where neither side can simply look at
the other's code. Neither condition holds here: both applications live in this
repository and deploy together.

## Decision

Generate the OpenAPI document from the code with springdoc, commit it, and fail
CI when the implementation and the committed document disagree. Generate the
TypeScript client from that document.

The drift check is an integration test
([`OpenApiContractTest`](../../plotted-api/src/test/kotlin/app/plotted/platform/OpenApiContractTest.kt))
that boots the application, fetches `/v3/api-docs`, and compares it with
`openapi/openapi.json`. `make openapi` regenerates it after an intentional
change.

## Consequences

**Good.** A breaking API change fails the build with a readable diff. The client
types are derived from the server rather than restated by hand, so they cannot be
subtly wrong. The cost is roughly a day, against the two weeks a Pact setup would
take.

**Bad.** This verifies shape, not semantics — it will not catch a field whose
meaning changed while its type stayed the same. It also verifies nothing about
consumers outside this repository, though there are none and none are planned.
The specification is only as good as the annotations, so DTOs need real
`@Schema` descriptions rather than being left to reflection.

**Bootstrap note.** `openapi/openapi.json` is produced by the first CI run, or by
`make openapi` on a machine with Docker. Until it is committed, the drift check
creates it and passes with a warning rather than failing on a file that has never
existed.

## Correction, 2026-08-08: half of this was never actually done

The consequence recorded above — *"the client types are derived from the server
rather than restated by hand, so they cannot be subtly wrong"* — has not been true
at any point. `npm run generate:api` is wired up and writes to `src/app/core/api`,
and nothing in the application imports what it produces: every request and
response model under `src/app/core` is hand-written. The only file in that
directory is `api.config.ts`, which was written by hand too.

So `OpenApiContractTest` guards one half of the seam. The server cannot drift from
the document. The *caller* could, freely, and the failure mode is a rename landing
green and surfacing later as a 404 on whichever screen nobody happened to open.

`npm run verify:api` now closes the part of that gap which can be closed cheaply:
it checks that every endpoint the application calls exists in the committed
document, with the method it is called with, and CI runs it. **Paths and methods
only.** A field that changed type, or a response property that quietly
disappeared, still passes — that limit is stated in the script's own header so the
name cannot be read as a larger promise than it keeps.

Two things worth being explicit about. The script was watched fail against a
deliberately renamed path before being trusted, per the standing rule that a check
nobody has seen fail is not a check. And this is not a reversal of the decision
above: the generated client is still the right answer and adopting it is still
owed. Until then, read this ADR as describing the destination rather than the
current state.

## Also deliberately not built

Recorded here because being able to explain what was *not* built is worth as much
as the list of what was.

- **OpenSearch.** PostgreSQL full-text search plus `pg_trgm` handles a catalogue
  of this size comfortably. Revisit when relevance is measurably the bottleneck.
- **NgRx.** Angular signals and a handful of injectable services cover every state
  need here. A state library at this scale is the kind of over-engineering an
  interviewer probes.
- **Loki in production.** The full Prometheus, Grafana and Loki stack runs in
  Docker Compose so the dashboards are demonstrable. Cloud Run's own logging is
  sufficient, searchable and free.
- **Kafka and Kubernetes.** No requirement in sight justifies either.
