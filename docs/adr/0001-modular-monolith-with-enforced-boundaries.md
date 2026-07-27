# ADR 0001 — A modular monolith, with the boundaries enforced

- **Status:** Accepted
- **Date:** 2026-07-26
- **Phase:** 1

## Context

Plotted has eleven identifiable modules: identity, catalogue, availability,
watchlist, viewing, recommendation, optimisation, subscriptions, households,
notifications, analytics. The obvious options were a single unstructured
application, a modular monolith, or a set of services.

The expected scale is one to a few dozen users. There is no independent scaling
requirement, no team-boundary requirement, and no separate release cadence.

## Decision

A modular monolith, with the module boundaries enforced mechanically by ArchUnit
tests that fail the build.

Modules communicate through published interfaces and Spring events. `platform` is
a shared kernel every module may depend on; nothing may depend on `platform` in
the other direction. Each module is layered `api` → `domain` → `persistence`, and
that direction is checked too.

Temporal workers will deploy separately when they arrive in phase 10, because
they genuinely do have different runtime, scaling and retry characteristics.

## Consequences

**Good.** One deployment, one transaction boundary, one place to look. No network
hop between modules, no distributed tracing needed to answer "why was this slow",
no eventual consistency where a transaction would do. The boundaries are real
because a test proves it, rather than aspirational because a document says so.

**Bad.** Nothing scales independently. A single slow module can consume the
shared thread pool. If the project ever needed independent deploys, extracting a
module would be work — though far less work than it would be without the
boundaries.

**Rejected: microservices.** They would enforce the same boundaries at the cost
of a network hop, a deployment topology, and distributed failure modes, for a
system with one user. Unjustified distributed systems are a negative signal, not
a positive one.

**Rejected: an unstructured application.** It is the same thing as this one right
up until the moment it is not, and the moment is never visible while it is
happening.
