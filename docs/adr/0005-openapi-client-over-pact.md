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
