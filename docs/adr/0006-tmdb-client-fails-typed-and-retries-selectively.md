# ADR 0006 — The TMDB client fails with types and retries selectively

- **Status:** Accepted
- **Date:** 2026-07-27
- **Phase:** 2

## Context

TMDB is the only source of title metadata and, through JustWatch, the only
source of Canadian availability. There is no second supplier. Section 26 of the
specification rates quota exhaustion as a real risk, and section 8.4 requires
that a provider outage degrades the product rather than breaking it.

A single `TmdbException` with a message would have been quicker. It also makes
every caller guess: a 404 means the title genuinely does not exist and should be
recorded as such, a 429 means wait, a 503 means try again shortly, and a 401
means stop and tell a human. Collapsing those into one type guarantees retrying
the one case that can never succeed and giving up on the ones that would.

## Decision

**A sealed failure taxonomy.** `NotConfigured`, `Unauthorised`, `NotFound`,
`RateLimited`, `Upstream`, `Unavailable` and `MalformedResponse`, each carrying a
`retryable` flag the retry loop reads. Callers switch on the type.

**Retry only what can succeed**, with exponential backoff. `Retry-After` wins
over the computed delay when TMDB sends one: it knows when its window resets and
guessing shorter only spends quota faster. Backoff has no jitter, deliberately —
jitter exists to stop many clients retrying in lockstep, there is one ingestion
worker, and a deterministic delay is far easier to assert on.

**A token bucket in front of every call**, at a rate set well below anything TMDB
is known to permit. Ingestion is a background job with nobody waiting on it, so
there is nothing to gain from running near a limit and the entire catalogue to
lose from crossing one. Tokens accrue continuously rather than in fixed windows,
because fixed windows allow twice the intended rate across a boundary.

**A malformed 2xx is not retryable.** The same request produces the same
unparseable body. Treating it as transient would turn an upstream schema change
into silent data loss spread over three attempts instead of one.

**No caching in the client.** Availability freshness is a product concern with
its own `source_checked_at` and `confidence` columns. Caching underneath those
would make "verified 3 hours ago" a claim the interface cannot check.

## Consequences

**Good.** Each failure mode is asserted directly in `TmdbClientTest` against a
stubbed TMDB: timeouts, rate limits, 5xx sequences, expired tokens, malformed
JSON, unknown new fields, and attempt-count exhaustion. Adding a case is adding a
type, and the compiler finds every caller that has to handle it.

**Bad.** The taxonomy is more code than a single exception, and callers can still
lump the types back together with a `catch (e: TmdbException)`. Backoff without
jitter would need revisiting if ingestion ever ran on more than one instance.
Injecting the sleeper for testability means production and test take slightly
different paths through the retry loop — a real, accepted seam.

**Not yet done.** No circuit breaker. With one worker and a bounded attempt
count, repeated failure costs a few retries and a log line; a breaker would add
state and a failure mode of its own for no benefit at this scale. Revisit when
ingestion runs continuously.
