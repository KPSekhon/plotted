# The 90-second demo

A shot list for the video, and the argument behind the running order.

**This has not been recorded.** Recording it needs a person, a seeded database
and a deployed environment. What follows is the script; the file to update when
it exists is this one.

---

## The argument

Every product in this category can show a list of films. Almost none can show a
system that knows when to say **no**. So the demo leads with the refusals, and
the successful recommendation is the thing it comes back to rather than the
thing it opens with.

That is also the honest order. Plotted's catalogue is a ~500-title seed derived
from what is actually streaming in Canada; a demo built around "look how good the
suggestions are" invites a comparison with Netflix's own recommender, which has
your whole viewing history and a thousand times the catalogue. A demo built
around "look at what it refuses to claim" is strongest precisely where this
project is strongest, and does not pretend to a scale it does not have.

---

## Shot list

Times are cumulative. The whole thing is 90 seconds and every second of it is
the real application against a real database — nothing is mocked, and nothing is
sped up except where noted.

| Time | Shot | What is said |
|---|---|---|
| **0:00–0:08** | Sign-in page. Click **Try it without an account**. Land on Tonight. | "No signup. That's a throwaway account with a watchlist and two subscriptions already on it." |
| **0:08–0:22** | Tonight, with **40 minutes** entered. One pick, two backups. Hover the reason bars. | "One recommendation, two backups. Those bars are the actual feature contributions the ranker used — not a sentence written to sound like one." |
| **0:22–0:38** | Change the time to **20 minutes**. Submit. The empty state appears with its diagnosis. | "Twenty minutes. Nothing on the list fits, and it says which constraint did the damage instead of quietly relaxing one to fill the space. **That refusal is the feature.**" |
| **0:38–0:50** | Navigate to **Plan**. Default settings. Work it out. Month-by-month plan appears. | "Same watchlist, different question: what should I be paying for. This is a constraint solver over six months, not a ranked list." |
| **0:50–1:05** | Point at the month strip: the committed service is held, then dropped. | "It wants to cancel this one immediately — and it can't, because there's a commitment. So it holds it, says so, and drops it the first month it's allowed to. Advice you can't act on is worse than no advice." |
| **1:05–1:16** | Point at the sensitivity line. | "And this: one more service would buy fourteen percent more of the list for twenty-one dollars a month. That's a second solve of the same model with the limit lifted." |
| **1:16–1:26** | Scroll to **Left out of the calculation**. | "These three are on the list and deliberately not in the maths — free to watch, never checked, or on a service with no price we could verify. Guessing a price doesn't break the feature. It produces confident, wrong financial advice." |
| **1:26–1:30** | Cut to the repository: `PlanSolverAgreementTest`. | "And the model is checked against an independent reimplementation that enumerates every possible plan. Because a solver will optimally solve a model you specified wrong." |

---

## Setting it up

The demo persona is built from whatever is in the database, so the shots above
depend on the seed having run. In order:

1. **Seed the catalogue.** `PLOTTED_SEED_ENABLED=true` with a TMDB token. Free
   quota; takes several minutes for 527 entries with their seasons. Read the
   report it prints — the number of titles actually created is what you should
   quote, not the entry count.
2. **Check the persona is interesting.** Start a demo session and look at
   `/coverage`. You want the two subscriptions to have visibly different shares
   — that is what makes the "cancel this one" moment land. If both cover roughly
   the same amount, the seed is too narrow rather than the feature being wrong.
3. **Find the two runtimes.** The 40-minute and 20-minute figures in the shot
   list are placeholders. Pick them from the persona's actual list: the first
   should let two or three titles through, the second none. Getting this wrong
   is the one thing that can make the demo's best moment look like a bug.

### Two screens to stay away from on day one

Both work and both are empty on a fresh deployment, which reads as broken rather
than as new:

- **End Credits** (`/analytics/end-credits`) returns nulls until somebody has
  accepted a recommendation, and its completion rate holds back anything accepted
  in the last fourteen days. That is correct — a latency computed from no
  observations is not zero — but "we have nothing to show you yet" is not a demo
  moment.
- **Pilot Season** (`/pilot`) needs fifteen answers before the profile says
  anything. If you want it in the video, answer the questionnaire *before*
  recording and open on the fitted profile, where the interesting part is the
  axes it marks **not asked** rather than the ones it scores.

Both become demo material once the deployment has been used for a fortnight.
Neither is worth faking.

## Rules for the recording

- **No speed-up on the solve.** It takes milliseconds. Showing that honestly is
  worth more than a progress bar.
- **Do not clear the console.** If something logs a warning, it logs a warning.
- **One take per shot, no cuts inside a shot.** A cut in the middle of the
  "nothing fits" moment reads as a retake that hid something.
- **Say the catalogue size out loud once.** "A seeded catalogue of about five
  hundred titles, derived from what's actually streaming in Canada." Three
  seconds, and it pre-empts the only question the demo cannot answer — while
  making the point that the list came from availability data rather than from
  someone's guesses.

---

## The 20-second version

For a résumé link or a recruiter who will not watch 90 seconds. Two shots:

1. Tonight with an impossible time limit, returning its diagnosis.
2. The plan holding a service it wants to cancel, with the commitment named.

Both refusals. Nothing else.
