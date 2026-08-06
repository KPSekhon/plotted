# ADR 0008 — Cross-module reads go through the shared kernel, cross-module joins do not

- **Status:** Accepted
- **Date:** 2026-08-05
- **Phase:** 3

## Context

Phase 3 added two feature modules, `watchlist` and `subscriptions`, and both need
things the catalogue and availability modules own.

- The watchlist screen shows title names, posters and runtimes for every row.
- The coverage dashboard needs, for a whole watchlist at once, which providers
  carry each title on a subscription.
- The subscriptions screen needs the provider list, and the server has to check
  that a submitted provider id is real.
- The nightly availability refresh should prefer titles somebody is waiting on,
  which means the catalogue's refresh query needs to know about watchlist items.

ADR 0001 forbids one feature module from depending on another, and
`ModuleBoundaryTest` fails the build on it. That rule was written for exactly
this moment, and it does not by itself say what to do instead.

Three options were available, and the honest observation is that the existing
code already used two of them without ever writing down which applied when.

## Decision

**No class crosses a feature boundary. Published interfaces live in
`platform.spi`.** `TitleDirectory` already existed for `availability` → 
`catalogue`; phase 3 extended it with a batched `findSummaries` and added
`AvailabilityDirectory` for `watchlist` → `availability`. The owning module
implements the interface and keeps its own types — `MediaType`, `AccessType`,
`Provider` — on its own side. Only ids and primitives cross.

**SQL joins across module tables are allowed.** `TitleSearchRepository` already
joined `title_availability`, which the availability module owns, before phase 3
existed. Phase 3 follows that precedent: the refresh query gained an `EXISTS`
over `watchlist_items`, and `SubscriptionRepository` joins `providers` and
`provider_plans`.

The line is therefore drawn at *compile-time coupling*, not at the database. One
schema, one deployment, one transaction manager — and a join is the difference
between one query and one per row.

**Composition at the HTTP layer stays the default when a screen needs two
things.** The title page fetches `/titles/{id}` and `/titles/{id}/availability`
separately and assembles them in Angular. That is still right for one record. It
stops being right at list scale, which is why the watchlist resolves titles
through the SPI in a single batched call instead of fetching each row.

## Consequences

**Accepted, and genuinely bad:** a cross-module join is invisible to the
compiler. Renaming `watchlist_items.status` breaks the catalogue's refresh
ordering with no build error — only a failing test, and only if one exists. This
is a real cost and the reason the boundary is where it is rather than further
out: the SPI protects the code that changes often, and the schema is protected by
migrations and integration tests instead.

**Accepted:** `platform.spi` grows over time, and every entry is a small amount
of mapping boilerplate. `AvailabilityCoverageDirectory` exists only to convert
one module's types into another's. That is the price of the boundary and it is
cheap; the alternative is `watchlist` importing `AccessType` and inheriting every
future change to it.

**Accepted:** an SPI method is a batch method or it is a performance bug.
`findSummaries` and `subscriptionCoverage` both take collections, because the
callers are always drawing lists. A per-id SPI would look tidier and turn every
screen into N queries.

**Rejected — an SPI for everything, including the refresh join.** The refresh
query would have to load every watchlisted title id into memory and pass them
into the SQL, or sort the whole catalogue in application code. It buys a compiler
check on a table that only migrations change, and pays for it in the hot path of
the nightly job.

**Rejected — dropping the boundary and letting modules import each other.** It
is one line of ArchUnit to delete and impossible to reverse later. The rule is
most of what makes "modular monolith" a description rather than an aspiration.

## Not decided here

Whether `coverage` eventually becomes its own module. It lives in `watchlist`
today because coverage is a property of a watchlist, and because phase 5's
optimiser will want the same weighted number over combinations of services. If
phase 5 finds it needs coverage without a watchlist, that is the signal to split
it — and the SPI boundary is what will make the split cheap.
