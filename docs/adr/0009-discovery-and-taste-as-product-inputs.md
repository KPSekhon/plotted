# ADR 0009 — Discovery is in scope, and taste becomes a product input

- **Status:** Accepted
- **Date:** 2026-08-08
- **Phase:** spans 4, 7, 8, 9 and 12
- **Decided by:** Kanwar

## Context

Plotted has been built on an implicit product definition: *order the things you
have already chosen*. The watchlist is the candidate universe, and Queue Theory
ranks it against tonight's constraints.

Two findings pushed against that definition at once.

**Pilot Season is disconnected end to end.** The fitter works — it recovered a
stated persona's sign on all six axes — but fifteen comparisons over six axes is
2.5 observations each, and a perfectly consistent respondent answering every
question maxes out at 1.39σ against a 1.96σ bar. Nothing consumes the result
either: `PilotService` has no caller outside its own controller,
`Candidate.tasteMatch` is never populated in production, and the linear ranker
carries no taste feature. Fifteen taps, no output, read by nobody. See
`PROGRESS.md`, "Measured 2026-08-08: the ladder cannot reach its own bar".

**Tonight is only as good as the list.** Against a thin watchlist the honest
answer was "add more titles", which is a correct answer to the wrong question.
The user came to be told what to watch.

Both stop being awkward the moment the product definition changes.

## Decision

Plotted answers three questions in sequence:

```
WHAT MIGHT I ENJOY?   taste
        ↓
WHAT FITS TONIGHT?    context
        ↓
HOW SHOULD I WATCH IT? access and cost
        ↓
        ╳
```

Concretely, five commitments.

### 1. Candidate generation is separated from ranking

Today the two are fused: the watchlist *is* the candidate set. They become
distinct stages, and every candidate records where it came from.

```
      taste ── history ── watchlist
                  ↓
        CANDIDATE GENERATION      hundreds
                  ↓
           HARD FILTERS           availability, runtime, exclusions, budget
                  ↓                tens
          FEATURE BUILD
                  ↓
             RANKER
                  ↓                top N
         DIVERSIFICATION
                  ↓
     PRIMARY + 2 ALTERNATES
```

Sources: `WATCHLIST`, `CONTINUE_WATCHING`, `TASTE_DISCOVERY`,
`SIMILAR_TO_LIKED`. The source travels to the interface, which can then say *on
your list* or *discovered for you*, and to the decision log, which makes
acceptance and completion measurable per source. If discovered titles perform
badly, that is a fact rather than a suspicion.

### 2. Discovery searches widely so the user does not have to browse

Plotted does not grow a Trending row, a Top 10, or 200 posters. That is the
product it exists to replace. The wider catalogue sits *behind* the system, not
in front of the user, and the output stays one recommendation and two alternates.

A discovery appetite setting — stay close / balanced / explore — shapes the
*composition of the candidate pool*, not the final score.

When nothing survives, the refusal gets specific: "214 titles considered; 173
unavailable on your services; 26 exceed your 45-minute window; 11 conflict with
your exclusions; 4 had insufficient availability confidence." That is a stronger
answer than "your list is too small", and it is the existing `NothingFits`
diagnosis widened to the new candidate set.

### 3. Taste feeds candidate generation *before* it touches the score

The tempting move is to bolt `TASTE` on as a sixth weighted feature. That is
rejected, because it would invalidate the phase 7 ablation with no measurement
to justify a new arrangement — the same reason phase 9 deliberately left the
linear ranker alone.

The order is:

1. Taste selects catalogue candidates. Pilot Season now has a reason to exist
   that does not require it to be significant.
2. `tasteMatch`, `tasteConfidence`, `candidateSource` and `tasteProfileVersion`
   are populated on every real candidate — including watchlist ones — and
   **logged without affecting the live score**. That closes a structural gap:
   `tasteMatch` currently exists in simulation and not in production, which
   means the evaluation code and the serving code differ in exactly the feature
   under investigation.
3. Only then, ranker v2, measured against v1 with a fresh ablation.

Ranker v1 (`PRIORITY`, `RUNTIME_FIT`, `ACCESS`, `DEADLINE`, `ACCLAIM`) keeps its
existing results. It is versioned, not mutated.

### 4. Confidence travels with taste

An axis the fitter cannot defend must contribute less, not equally. The profile
already returns null rather than 0.5 for an axis it cannot speak to; the same
discipline extends into scoring, so a weak estimate is weighted by how weak it
is rather than being either fully trusted or discarded.

Pilot Season also becomes progressive: 5–7 high-information comparisons to
establish a weak prior, honestly described as one, then continued learning from
real behaviour — added, blocked, accepted, rejected, completed, abandoned — at
different statistical weights. The name keeps referring to the visible
onboarding; the subsystem is taste learning.

### 5. `PRIORITY` becomes `INTENT`, eventually

`PRIORITY` only means something for a watchlist entry. A discovered title has
none, and giving it a neutral value is a transitional compromise rather than a
model. The eventual feature is `INTENT` — how much evidence Plotted has that you
want this title — with explicit watchlist priority as its strongest signal,
behavioural signals below it, and inferred taste below that.

Target vector: `INTENT`, `TASTE`, `RUNTIME_FIT`, `ACCESS`, `DEADLINE`,
`ACCLAIM`, with `PROGRESS` later. Taste and intent should dominate acclaim once
there is data; acclaim is deliberately the weakest because it is the only one
that is not about *you*.

## Consequences

**Good.** Pilot Season stops being a sophisticated component in search of a
justification: your watchlist says what you know you want, and Pilot Season is
how Plotted finds what you do not know yet. Tonight works for somebody with
three titles saved. The candidate-source dimension makes personalisation
measurable rather than assertable. And the tagline stops assuming the user has
already done the browsing.

**Bad, and accepted.** The candidate set grows by orders of magnitude, so hard
filtering moves onto the critical path for latency in a way it was not when the
input was a watchlist of twenty. Discovery quality is unmeasurable until there
are users, so the first version ships on faith with instrumentation attached.
And a recommendation the user never asked for is a higher-stakes claim than one
they put on their own list — a bad discovery costs more trust than a bad
ordering.

**Explicitly not decided here.** The retrieval mechanism for taste-based
candidate selection. Embeddings, attribute matching over the existing six axes,
or a nearest-neighbour index over completed-and-liked titles are all open, and
the choice should follow a measurement rather than precede one.

## Related

- [ADR 0008](0008-cross-module-reads-through-the-shared-kernel.md) — candidate
  generation reading catalogue and availability crosses the same seam.
- `docs/PILOT.md` — the fitter, and why two of its four verdicts are ways of
  saying "we do not know".
- `docs/EVALUATION.md` — where a ranker v2 claim has to be settled.
