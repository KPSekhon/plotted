# ADR 0004 — jOOQ over JPA, generated from the migration scripts

- **Status:** Accepted
- **Date:** 2026-07-26
- **Phase:** 1

## Context

Two decisions, related enough to record together: what to use for data access,
and where the generated code comes from.

Plotted's hot path is a single indexed query returning 200–500 candidate titles
in under 80 ms, joined against availability, watchlist state and viewing
progress. The optimiser needs aggregate coverage figures. Neither is a good fit
for an object graph.

## Decision

### jOOQ, not JPA

SQL is written as SQL, type-checked at compile time against the real schema.
Flyway owns the schema; jOOQ reads it.

The tradeoff is the point. JPA is more productive for CRUD over an aggregate root
and worse at everything Plotted actually does: no lazy-loading surprises, no
accidental N+1, no fighting the query planner through a criteria API, and the
generated SQL is the SQL that was written. The cost is more code for simple
operations and no free dirty-checking or caching.

### Code generation reads the migrations, not a database

The generator is configured with jOOQ's `DDLDatabase`, pointed at
`src/main/resources/db/migration/*.sql`. It interprets the DDL in memory and
generates from the result.

The usual alternative — generating from a live database — means `./gradlew build`
fails on a machine without Docker running, which is a poor property for a
repository other people are meant to clone and build.

`DDLDatabase` simulates the DDL through H2, so anything Postgres-specific has to
be kept out of its way. Statements it cannot model are fenced:

```sql
/* [jooq ignore start] */
ALTER TABLE title_availability ADD COLUMN validity DATERANGE NOT NULL;
/* [jooq ignore stop] */
```

Postgres treats the markers as comments and applies the DDL normally; jOOQ never
sees it. Fenced in this project: extensions, `CITEXT` conversion, `DATERANGE`
columns, GiST exclusion constraints, generated columns (`release_year`,
`search_vector`), array columns, partial indexes, trigram and GIN indexes, and
`num_nonnulls` check constraints.

## Consequences

**Good.** A clean clone builds and its unit tests run with no database, no Docker
and no network. The generated code cannot drift from the migrations, because it
is produced from them. Setting up a new machine is `git clone` and `./gradlew
build`.

**Bad.** Fenced columns are invisible to the generator, so `validity`, `period`,
`embedding`, `profile_vector`, `scopes`, `release_year` and `search_vector` are
not type-safe in Kotlin and must be read and written as plain SQL. Fencing is
also a manual discipline: a future migration using an unsupported construct will
fail codegen, which is at least a loud failure at build time rather than a quiet
one.

Because the fenced DDL is never exercised by the generator, a dedicated CI job
applies every migration to a real PostgreSQL 16 and asserts the exclusion
constraints exist and every foreign key is indexed.

**Revisit if** the fenced surface grows large enough that most of the interesting
schema is untyped. At that point, generating from a Testcontainers-backed
Postgres and accepting the Docker requirement becomes the better trade.
