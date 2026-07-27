# ADR 0003 — In-memory access token, rotating refresh cookie

- **Status:** Accepted
- **Date:** 2026-07-26
- **Phase:** 1

## Context

Plotted stores a detailed record of what someone watches. Section 18 of the
specification is blunt about why that matters: viewing history exposes taste,
mood, schedule, and sometimes health, relationships or beliefs. Session handling
therefore deserves more than the usual "JWT in localStorage".

The requirement was short-lived access tokens, rotating refresh tokens with reuse
detection, and secure `HttpOnly` `SameSite=Lax` cookies.

## Decision

Two tokens with different jobs.

**Access token** — a 15-minute HS256 JWT, returned in the response body and held
in an Angular signal. In memory only: never localStorage, never sessionStorage,
never a readable cookie. A unit test asserts this, because it is the kind of
thing that gets "temporarily" changed during debugging.

**Refresh token** — 32 bytes from a CSPRNG, returned as an `HttpOnly`, `Secure`,
`SameSite=Lax` cookie scoped to `/api/v1/auth`. Stored server-side as a SHA-256
digest in `refresh_tokens`; the token itself is never written down.

Refresh tokens belong to a **family**. Each refresh spends the presented token
and issues a successor in the same family. Because a spent token can never be
spent again, presenting one means a copy exists somewhere it should not — so the
entire family is revoked, not just that token.

A page reload has no access token, since it only ever lived in memory. An
`APP_INITIALIZER` trades the refresh cookie for a new access token before the
first route resolves.

## Consequences

**Good.** An XSS payload cannot read the refresh token at all, and gets at most
fifteen minutes out of the access token. Token theft is *detected* rather than
merely made harder — the attacker and the legitimate user cannot both keep
refreshing, and the collision is the alarm. Revocation is real, because refresh
state is in Postgres.

**Bad.** Reuse detection sometimes signs out an innocent user: a client that
fires two refreshes concurrently, or retries after a network timeout that
actually succeeded, looks exactly like theft. This is a deliberate trade — a
surprise sign-out is recoverable, a silently shared session is not. The Angular
interceptor is written to never issue concurrent refreshes, and the refresh
endpoint is excluded from its own retry path.

Refresh also requires cookies to reach the API, so the front end is served
same-origin: the dev server proxies `/api`, and nginx proxies it in the container
build. That keeps development and production behaving identically, which is where
cookie bugs otherwise hide.

**Also decided.** Argon2id for passwords, with the OWASP-recommended parameters
(19 MiB, t=2, p=1). A 12-character minimum with no composition rules — length
beats punctuation, and composition rules push people towards predictable
substitutions. Sign-in verifies against a dummy hash when no account exists, so
response time does not reveal which addresses are registered.
