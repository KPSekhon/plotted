# ADR 0007 — Canonical providers, resolved from a TMDB alias map

- **Status:** Accepted
- **Date:** 2026-07-27
- **Phase:** 2

## Context

Running the Appendix A premise check against real Canadian data surfaced a
problem the design had not accounted for. TMDB reports billing variants and
reseller channels as distinct providers. One title comes back as:

```
Crave, Crave Amazon Channel
Netflix, Netflix Standard with Ads, Netflix Kids
Paramount Plus, Paramount+ Amazon Channel, Paramount Plus Apple TV Channel,
  Paramount Plus Basic with Ads, Paramount Plus Premium
```

Those are one catalogue bought several ways. TMDB's Canadian list has 135
providers, of which a large share are `… Amazon Channel` or `… Apple TV Channel`
entries.

Stored as-is, each variant becomes its own `title_availability` row. Watchlist
coverage is computed by counting those rows, and coverage is the primary input to
the subscription optimiser. So *Succession* would count five times towards
Paramount+ coverage, and Cancel Culture could "cover" a title by recommending a
subscription — "Crave Amazon Channel" — that does not independently exist.

This is a correctness problem in the feature that tells people what to do with
their money. It is also invisible: the plan looks reasonable, the numbers are
just wrong.

TMDB also returns genuine contradictions. Amazon Prime Video appears under both
`flatrate` and `free` for some titles, including *Fleabag* and *Letterkenny*.

## Decision

A `provider_aliases` table mapping TMDB provider id to a Plotted provider, with
an `alias_kind` recording *why* — `direct`, `reseller_channel`, `ad_tier`,
`plan_tier`, `profile`. Resolution is many-to-one.

`ProviderResolver` collapses a title's providers in two steps:

1. **Same provider, same access type** → one offer. Where a `direct` listing
   exists it wins, so retained provenance names the service rather than a
   reseller.
2. **Same provider, conflicting included access types** → keep the most demanding
   one. Subscription beats ads beats free. Telling someone a title is free when
   it needs a subscription is the failure that destroys trust in a decision tool;
   the reverse is a pleasant surprise.

Rent and buy are never collapsed together. The same storefront legitimately
offers both at different prices.

Unmapped provider ids are returned in the result, not swallowed. A mapping gap
removes real availability, and it surfaces as a title Plotted believes nobody
carries — the hardest kind of bug to notice.

Every identifier in the seed was read from TMDB's live `/watch/providers` list
for region CA. None were guessed.

## Consequences

**Good.** Coverage counts services, not billing arrangements. The optimiser can
only ever recommend something subscribable. The `alias_kind` column means the
data can answer "is Crave cheaper through Amazon?" later without re-deriving
anything. Provenance survives resolution, so an availability row can still be
traced to the exact upstream entry.

**Bad.** The mapping is hand-maintained and still not exhaustive. V9 covered the
majors and the Canadian broadcasters; V10 added the speciality, anime and
world-cinema services, plus the brands that reach Canada only through Amazon or
Apple channels — roughly 110 aliases against TMDB's ~135 Canadian providers.
What remains is the very long tail of single-genre services.

Everything unmapped is still discarded, and that is the failure worth watching:
it surfaces as a title Plotted believes nobody carries. The mapping also decays,
because TMDB adds services and nothing notices automatically. That is why
`ProviderResolution.unmapped` is returned as data rather than logged and
forgotten — the next gap announces itself in an ingestion run instead of hiding.

The subscription-beats-free rule is a judgement call that will occasionally be
wrong in the user's disfavour, showing a subscription requirement for something
genuinely free. Given a choice between the two failure directions, that is the
one to prefer.

**Also true:** three services — OUTtv, StackTV, Super Channel — reach Canadian
viewers only through reseller channels and have no `direct` TMDB entry at all.
They have canonical rows regardless, since a user really can subscribe to them;
the mapping just has no direct alias to point at.
