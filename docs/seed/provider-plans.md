# Seeding provider plan pricing

> **Update, 2026-08-06.** `V11__provider_plan_prices.sql` seeds a researched
> starting set for Netflix, Crave, Disney+, Amazon Prime, Apple TV+, Paramount+
> and CBC Gem, so coverage and the phase 5 optimiser have something real to run
> against. Every figure was read from a published source on that date, and the
> migration records which source and flags the least confident of them. That
> makes them **researched, not verified** — everything below still applies, and
> the procedure in this document is how they get corrected.
>
> One result worth keeping: the two figures read from a provider's own page are
> the two that a secondary source had wrong. Apple TV+ was widely reported at
> $12.99, which is the US price; Apple's Canadian page says $14.99. Prefer the
> provider.

`V8__reference_data.sql` seeds the Canadian providers and deliberately seeded **no
prices**.

Plan pricing is manually curated from public pricing pages. A migration has no
way to verify what a service costs today, and seeding invented numbers would put
fabricated money in front of a user and into the subscription optimiser's
objective function. Wrong pricing does not produce a visibly broken feature — it
produces confident, wrong financial advice, which is worse.

So the prices are entered by hand, once, from the pricing pages, with the date
they were checked.

## How to enter them

For each provider you actually subscribe to, open its Canadian pricing page and
record the plan name, billing period and price. Then insert a row per plan.

`validity` is a half-open `DATERANGE`: `[checked-on, )` with an unbounded upper
bound means "current". When a price changes, close the old row and insert a new
one — the GiST exclusion constraint will reject any overlap, which is the point.

```sql
INSERT INTO provider_plans (
    id, provider_id, region_code, name, billing_period,
    price, currency, ad_supported, simultaneous_streams, validity
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM providers WHERE slug = 'netflix'),
    'CA',
    'Standard with ads',
    'monthly',
    0.00,               -- the price from the pricing page
    'CAD',
    TRUE,
    2,
    daterange(DATE '2026-07-26', NULL)   -- the date you checked
);
```

Closing a price period when it changes:

```sql
UPDATE provider_plans
   SET validity = daterange(lower(validity), DATE '2026-09-01')
 WHERE provider_id = (SELECT id FROM providers WHERE slug = 'netflix')
   AND name = 'Standard with ads'
   AND upper_inf(validity);
```

Save your inserts to `docs/seed/provider-plans.local.sql`. That path is
git-ignored: prices are point-in-time facts about a market, and a stale committed
price is worse than no committed price.

## Provenance, and why a researched price is not spent (V18)

Every price in `provider_plans` carries `price_provenance`, and every seeded row
is `reference`. **The optimiser will not spend a `reference` price.**

That is a stronger rule than the warnings above, and it exists because the
warnings were not enough. `SubscriptionRepository` read a held subscription's
price as `COALESCE(user_subscriptions.actual_price, provider_plans.price)`, so a
subscription the user never priced silently adopted the researched figure and
reached Cancel Culture's objective function indistinguishable from one they had
confirmed. This document said "researched, not verified"; the code could not tell.

| Provenance | Where from | May be optimised against |
|---|---|---|
| `USER_ENTERED` | `user_subscriptions.actual_price` — they typed it | **Yes** |
| `VERIFIED` | Checked by Plotted against a live source, with a date | **Yes** — nothing produces it yet |
| `REFERENCE` | Researched from a published source, per this document | **No** |

A published list price is not a bill. Legacy rates, student pricing, bundles,
promotional periods, annual plans and family arrangements all move it, and every
one of them moves it *down* — so optimising against list prices systematically
overstates what cancelling would save, in the direction that flatters the advice.

Reference prices are still displayed, and still pre-fill the subscription form.
Withholding them would push somebody towards inventing one, which is worse. What
changed is that Cancel Culture now names the services it could not cost instead
of planning around them silently: *"Paramount+ was not considered, because you
have not told Plotted what you pay for it."*

**The demo account looks like an exception and is not one.** Demo subscriptions
are created with `actual_price` set to the plan's own researched figure, so the
persona has confirmed its fixture price the same way it has a fixture watchlist.
The number is not invented — it is the same cited figure, copied — and the
subscriptions screen says in so many words that the data was generated. Without
this, the account that exists to demonstrate Cancel Culture would have nothing to
demonstrate it with.

## Providers seeded in V8

| Slug | Type |
|---|---|
| `netflix`, `prime-video`, `disney-plus`, `crave`, `apple-tv-plus`, `paramount-plus` | subscription |
| `tubi`, `cbc-gem` | free |
| `apple-tv-store`, `google-play` | transactional |
| `hoopla`, `kanopy` | library |

Hoopla and Kanopy are library services, reachable free with a Canadian library
card. They fit the platform-neutral premise better than a disc price lookup, and
Side Quest treats them as first-class access options.
