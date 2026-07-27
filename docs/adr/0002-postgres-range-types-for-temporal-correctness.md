# ADR 0002 — Range types and exclusion constraints for temporal data

- **Status:** Accepted
- **Date:** 2026-07-26
- **Phase:** 1

## Context

Three things in Plotted are true only for a period of time: what a provider plan
costs, whether a title is available on a provider, and what a subscription was
billed for a given period.

The original schema modelled these with a nullable `effective_from` /
`available_from` plus a unique constraint including that column:

```sql
unique (title_id, provider_id, region_code, access_type, available_from)
```

In PostgreSQL, `NULL` is never equal to `NULL`, so a unique constraint containing
a nullable column does not fire when that column is null. The most common case by
far — "available now, start date unknown" — therefore permits unlimited duplicate
rows.

That is not a cosmetic problem. Watchlist coverage is computed by counting
availability rows, and coverage is the primary input to the subscription
optimiser. Duplicate rows inflate coverage, which makes a service look more
valuable than it is, which makes the optimiser recommend keeping it. The bug
passes every unit test and produces plausible, wrong advice.

## Decision

Model the period as a `DATERANGE` and enforce non-overlap with a GiST exclusion
constraint, which requires the `btree_gist` extension for the scalar columns.

```sql
ALTER TABLE title_availability ADD CONSTRAINT title_availability_no_overlap
    EXCLUDE USING gist (
        title_id    WITH =,
        provider_id WITH =,
        region_code WITH =,
        access_type WITH =,
        validity    WITH &&
    );
```

Applied to `provider_plans.validity`, `title_availability.validity` and
`subscription_billing_periods.period`.

## Consequences

**Good.** The invalid state is unrepresentable rather than merely untested. The
database rejects it regardless of which code path, migration or manual fix tries
to create it. Half-open ranges also give "currently valid" a single, unambiguous
expression: an unbounded upper bound.

**Bad.** The jOOQ code generator has no model for range types or exclusion
constraints, so these columns are added out of band (see
[ADR 0004](0004-jooq-generated-from-migrations.md)) and are not type-safe in
Kotlin. Application code reads and writes them as plain SQL until a custom
binding is added. Range semantics are also less familiar than two date columns,
so the intent is spelled out in comments where it appears.

**Verification.** A CI job applies every migration to a real PostgreSQL 16 and
asserts the exclusion constraints exist, because they are invisible to every
other check in the build.
