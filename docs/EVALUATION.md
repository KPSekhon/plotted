# Evaluation

What Plotted's ranker has actually been measured to do, what it has not, and the
machinery that will answer the rest once there is data.

Regenerate everything below with:

```bash
./gradlew :plotted-api:evaluate
```

No Spring context, no database, no API keys. A report that needs an environment
is a report nobody regenerates, and one nobody regenerates stops being true
without anybody noticing.

Last run: 2026-08-06, against feature schema **v2** (nine features, including
phase 9's `taste_match`).

---

## Read this first

**There are no users, so there are no preferences to be right about.** Nobody has
used Plotted, `recommendation_requests` is empty, and no title has ever been
marked watched after being recommended. Every number on this page comes from a
simulation, and the simulation's ground truth is *the ranker's own opinion given
complete metadata*.

That makes exactly one comparison on this page meaningful and the rest a smoke
test. The meaningful one is the ablation, because both sides of it are the same
model differing in a single line. Every comparison against a *different* ranker
is circular by construction: the thing being predicted is the model's own score,
so of course the model predicts it well.

Saying that plainly is the point. A harness that only produces flattering
numbers is not a harness, and an evaluation section that implies user validation
where there is none is worse than no evaluation section.

---

## The result: renormalisation is worth 0.019 NDCG@3

`FeatureVector.score()` divides the weighted sum by the weight *actually present*
on a candidate. Without it, a candidate missing a 0.10-weight feature caps at
0.90 and loses to an otherwise identical candidate with complete metadata — so
the ranking quietly becomes a ranking of data quality. Two lines, and most
implementations ship without them.

**Setup.** 2,000 simulated queries, 12 candidates each. Every candidate is
generated with all five features populated, and its true relevance is the score
the shipped ranker gives that complete vector. Then 30% of optional fields are
blanked at random and the ranker sees only what survives. So the question is
precisely: *given missing metadata, how much ranking quality does
renormalisation recover?*

| Strategy | NDCG@3 | 95% CI | Precision@3 | MRR |
|---|---|---|---|---|
| **linear-v1** | 0.9615 | 0.9599 – 0.9631 | 0.9875 | 0.9980 |
| learned-distill | 0.9614 | 0.9598 – 0.9630 | 0.9878 | 0.9975 |
| watchlist-priority | 0.9481 | 0.9461 – 0.9503 | **0.9962** | **0.9998** |
| linear-v1-no-renormalisation | 0.9425 | 0.9405 – 0.9446 | 0.9922 | **0.9998** |
| popularity | 0.8204 | 0.8164 – 0.8242 | 0.8628 | 0.9298 |
| random | 0.8000 | 0.7956 – 0.8042 | 0.8357 | 0.9097 |

`learned-distill` is the phase 8 ONNX model. It sits level with `linear-v1`
because it was **trained to imitate it**, which is the point of it rather than a
disappointment — see below and [MODEL.md](MODEL.md).

Paired bootstrap over identical queries, 2,000 resamples, seed 20260806:

> **linear-v1 ahead of linear-v1-no-renormalisation by 0.0191 NDCG@3
> (95% CI 0.0166 – 0.0215, n = 2000).**

The interval excludes zero, so the effect is real at this censoring rate. It is
also small in absolute terms, and the section below explains why the absolute
size of anything on this table means very little.

**Why this ablation can be trusted to isolate one thing.** It does not
reimplement the scorer. Since `score = Σ(weight × value) / availableWeight`, the
un-normalised score is exactly `score × availableWeight / totalWeight`, so the
ablation runs the production scoring path and undoes one division.
`EvaluationHarnessTest` asserts that with *complete* metadata the two strategies
produce byte-identical rankings — which is the check that the ablation differs in
this one respect and no other. An ablation that quietly differs in a second way
measures the difference between two implementations and calls it an effect.

---

## Three things this table does not say

### The absolute numbers are compressed, and random scoring 0.80 proves it

A shuffle scores 0.8000 NDCG@3. That is not a good shuffle — it is what NDCG does
when relevances are graded and similar. With twelve candidates whose true scores
mostly sit between 0.5 and 0.9, almost any ordering accumulates most of the ideal
gain, so the metric's useful range is squeezed into the top fifth of its scale.

**Only differences on this table mean anything. The levels do not.** Quoting
"0.96 NDCG@3" as a standalone achievement would be quoting an artefact of the
relevance distribution.

### The full model loses on two of the three metrics it is winning on

`watchlist-priority` sorts by nothing but the number the user typed in. It comes
within 0.0134 NDCG@3 of the five-feature model and is **ahead on precision@3**
(0.9962 against 0.9875). The ablated model — the one this page is arguing is
worse — **ties for the best MRR on the table** (0.9998 against 0.9980).

Both of those are real and both are reported because they are. NDCG@3 is the
metric this product should be judged on, because it is graded and
position-aware and the product shows three ranked slots; precision@3 treats a
strong pick and a mediocre one as the same event, and MRR only looks at where the
first acceptable item landed. So "linear-v1 wins the metric that matters and
loses two that matter less" is a defensible reading — but it is a reading, and
anyone quoting the NDCG column alone should know the other two exist.

Some of `watchlist-priority`'s strength is circularity: priority carries the
largest weight (0.35) in the ground truth, so a baseline that reads it directly
is scored against a target it half defines. The honest residue is still that
**the other four features buy less than their combined 0.65 weight suggests**,
at least under this simulation. Whether they earn their place is a question only
real outcome data can settle, and it is the first thing to look at when there is
any.

### The learned model matching the linear one is a passing test, not a null result

> **learned-distill against linear-v1: no measurable difference (−0.0001 NDCG@3,
> 95% CI −0.0004 to 0.0001, n = 2000).**

Read as a model comparison this says nothing — and it is not one. The ONNX model
was distilled from the linear ranker, so a correct pipeline *must* produce this
number, which makes it the strongest end-to-end check available with no real
data. Features exported out of order, `NaN` read as zero, the wrong float width
anywhere along export → train → convert → load → score, and this interval moves
off zero. `DistillationFidelityTest` asserts it does not.

Being precise about what that buys: the plumbing is honest, and nothing
whatsoever is known about whether a learned ranker would be *better*.

### Popularity barely beats random, and that is a property of the simulation

0.8204 against 0.8000. In the real world popularity is a strong baseline and hard
to beat. Here it is weak because `ACCLAIM` carries only 0.10 of the ground-truth
score, so a ranker that reads nothing else is discarding 90% of the signal *by
construction*.

**This says nothing about whether Plotted beats popularity for a real person.**
It is a sanity check that the harness discriminates at all.

---

## What the harness does, and why each choice

| Decision | Why |
|---|---|
| **NDCG@3** | The product answers with one pick and two backups. Measuring at 10 would score slots the user never sees. |
| **NDCG returns null when nothing is relevant** | A query with no relevant candidate is unanswerable, not answered badly. Averaging a zero for it drags the mean toward a number describing the dataset instead of the ranker — the commonest way an NDCG figure is quietly deflated. |
| **Unanswerable queries dropped once, for all strategies** | Filtering per strategy gives each a different denominator, and two means over different query sets are not comparable however alike they look. It also breaks the pairing. |
| **Precision divides by `min(k, size)`** | Charging a ranker for slots it was never given measures how short the list was. |
| **Percentile bootstrap, not a t-interval** | Per-query NDCG is bounded in [0,1] and skewed. A normal approximation routinely puts bounds outside the range the metric can take. |
| **Paired bootstrap for comparisons** | Both strategies saw identical queries, so most variance is queries being easy or hard. Comparing two independent intervals discards that and will call a real improvement inconclusive. |
| **Seeded everything, including the ids** | A harness that reports a different interval each run invites exactly one behaviour: running it again until it says something better. The first version of this page was wrong about it — see below. |
| **Temporal split, never random** | Preferences drift and catalogues change, so a random split leaks the future into the past. There is nothing to train yet, which is exactly why the split exists now — phase 8 must not be the moment somebody decides how to divide the data. |
| **MMR and exploration excluded from the evaluated strategy** | Both trade relevance for something else *on purpose*. Scoring them on a pure relevance metric measures them against a goal they were built to compromise. |
| **A strategy that returns fewer candidates than it was given is rejected** | Dropping candidates shortens the list the metric is computed over and flatters everything. It is an easy way to produce a spectacular, meaningless result. |

---

---

## The bug in the first version of this page

Worth keeping, because it is the same shape as every other defect this project
has found.

The first run of the harness produced a table that was quoted here to four
decimal places, under a sentence promising anyone could regenerate it. Running it
a second time moved the confidence intervals in the third decimal.

Every strategy breaks ties on title id, and the simulation was minting those with
`UUID.randomUUID()`. So the *data* was seeded, the *bootstrap* was seeded, the
comments said "seeded, so the same data gives the same interval" — and the
rankings still differed run to run because the tie-break did not.

Nothing failed. The numbers were plausible either way, and the document would
have quietly stopped matching the code within a day. `EvaluationReportTest` now
runs the whole report twice and fails the build if the markdown differs — and
runs it once more with a different seed, because a generator that ignores its
seed entirely would pass the first check and be just as wrong.

**Five previous bugs here were mechanisms that reported success while doing
nothing. This one is a claim in a comment that nothing had ever checked.**

---

## Threats to validity

Listed rather than omitted, in rough order of how much they should worry you.

1. **The ground truth is the model's own score.** Every comparison against a
   non-model baseline is circular. Only the ablation escapes this.
2. **There is no user in any of it.** Nothing here is evidence about preference,
   satisfaction, or whether anyone watched what they were shown.
3. **Censoring is independent of quality**, and the real world is not like that:
   obscure titles are both more likely to lack metadata and less likely to be
   wanted. Where those correlate, the un-normalised scorer is accidentally right
   some of the time, so **the real renormalisation effect is probably smaller
   than 0.019.** This is the conservative direction to be wrong in, but it is
   still a direction.
4. **One censoring rate (30%).** The effect size is a function of it. The
   direction is asserted by a test; the magnitude is not, because pinning it
   would make the test fail whenever the simulation is tuned rather than when the
   ranker regresses.
5. **The candidate distribution is uniform-ish and synthetic.** Real watchlists
   are small, skewed, and correlated within a user.

---

## What would make this a real evaluation

In order, and none of it needs new machinery — the harness takes queries and
returns numbers, so pointing it at real data is a data problem rather than a code
one.

1. **A viewing outcome.** `watchlist_items.status` already moves to `completed`,
   which is the closest available label to "this recommendation worked". What is
   missing is *when* it moved: the temporal split needs a timestamp on the
   transition, and that is a column plus a write, not a feature.
2. **Logged decisions with outcomes joined to them.**
   `recommendation_requests` and `recommendation_items` already record the pick,
   the score, the feature contributions and the propensity. The propensity is the
   one that cannot be added retroactively, and it is already there.
3. **Off-policy estimation.** With propensities logged, inverse-propensity
   scoring can estimate how a *different* ranker would have performed on traffic
   the shipped one served. That is the point of the exploration slot, and it is
   why its rate is 10% rather than 0.
4. **Then, and only then, a claim about beating popularity.**

The honest summary today: **the machinery is built and tested, one design
decision has been measured and defended, and everything else on this page is a
smoke test wearing a table.**
