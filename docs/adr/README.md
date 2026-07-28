# Architecture decision records

Short records of decisions that were not obvious, written when the decision was
made rather than reconstructed afterwards. Each states the context, the decision,
and — most importantly — the consequences that were accepted, including the bad
ones.

| # | Decision | Phase |
|---|---|---|
| [0001](0001-modular-monolith-with-enforced-boundaries.md) | A modular monolith, with the boundaries enforced | 1 |
| [0002](0002-postgres-range-types-for-temporal-correctness.md) | Range types and exclusion constraints for temporal data | 1 |
| [0003](0003-in-memory-access-token-with-rotating-refresh-cookie.md) | In-memory access token, rotating refresh cookie | 1 |
| [0004](0004-jooq-generated-from-migrations.md) | jOOQ over JPA, generated from the migration scripts | 1 |
| [0005](0005-openapi-client-over-pact.md) | Generated OpenAPI client instead of consumer-driven contracts | 1 |
| [0006](0006-tmdb-client-fails-typed-and-retries-selectively.md) | The TMDB client fails with types and retries selectively | 2 |
| [0007](0007-canonical-providers.md) | Canonical providers, resolved from a TMDB alias map | 2 |

A record is never edited to change its decision. If a decision is reversed, a new
record supersedes it and says so.
