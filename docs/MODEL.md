# The learned ranker

Phase 8: a gradient-boosted model, trained in Python, exported to ONNX, served
in-process on the JVM — and, more importantly, the machinery that stops it going
quietly wrong.

```bash
./gradlew :plotted-api:exportTrainingData
.venv-ml/Scripts/python ml/train_ranker.py
./gradlew :plotted-api:test --tests 'app.plotted.recommendation.model.*'
```

---

## Read this first: what the committed model is

`models/ranker.onnx` is a **distillation of the linear ranker**, trained on
simulated candidates. It is not a better recommender and it is not trained on
anybody's behaviour, because nobody has used Plotted and there are no outcomes to
learn from.

It exists because a pipeline nobody has run is a pipeline nobody has debugged.
With it, every link in the chain — export, train, convert, load, score — is
executed on every build. Without it, `OnnxScorer` would be several hundred lines
that have never once been asked to do their job.

**It is not served to anyone.** `plotted.model.enabled` defaults to `false`, and
the reasons are in the next section.

---

## Training-serving skew, and why this is the whole phase

The defining production failure of applied machine learning: the features
computed when the model was fitted differ subtly from the ones computed when it
is asked a question. A column reordered. A rating divided by 10 on one side and
not the other. A missing value written as `0` in Python and `NaN` in Kotlin.

**Nothing throws.** The model keeps returning confident scores drawn from a
distribution it was never trained on, and the only symptom is that
recommendations get slightly worse. There is no alert for "slightly worse".

Four mechanisms here, in increasing order of how much they actually help.

### 1. There is only one feature implementation

The usual arrangement computes features in Python for training and reimplements
them in the serving language for inference. Those two implementations start
identical and then drift, and that drift is where most skew comes from.

Here `FeatureSchema` on the JVM is the only implementation that exists.
`exportTrainingData` writes a CSV using the *serving* extractor, and
`ml/train_ranker.py` never computes a feature — it reads columns and refuses the
file if the header disagrees with the schema it asked the JVM for. Skew is not
guarded against so much as made hard to express.

### 2. The schema fingerprint

A hash over the schema version and the feature names in order. The training
script stamps it into the model's ONNX metadata; `OnnxScorer.load` compares it
and **refuses to serve a model whose fingerprint does not match**, naming both
values. A reordered or renamed feature becomes a startup refusal rather than a
slow decline.

It deliberately does *not* cover the extraction code. A Kotlin lambda has no
stable hash, and a fingerprint that changed on unrelated refactors would train
everyone to ignore it.

### 3. Golden vectors

The fingerprint proves the *shape* agrees. The golden vectors prove the
*arithmetic* does: the training script records the score its model gave 200 real
input rows, and `GoldenVectorTest` replays those exact rows through ONNX Runtime
on the JVM and compares.

**161 of the 200 contain missing values, on purpose.** LightGBM treats `NaN` as
absent and learns a direction for it; whether that survives conversion to an ONNX
`TreeEnsembleRegressor` is a real risk. A golden set of complete rows would pass
while the case most likely to be wrong went untested — so the training script
*refuses to write* a set without missing values.

### 4. The distillation is falsifiable end to end

Because the model was trained to imitate the linear ranker, a correct pipeline
has a known right answer: the two should rank almost identically.

Measured over 2,000 simulated queries:

> **learned-distill against linear-v1: no measurable difference
> (0.0000 NDCG@3, 95% CI −0.0002 to 0.0003, n=2000).**

Break any link — features exported out of order, `NaN` read as zero, the wrong
float width — and that number moves. `DistillationFidelityTest` asserts it stays
put.

---

## Missing values

`NaN`, everywhere, and never `0`.

LightGBM has native missing-value handling. Encoding an absent rating as `0.0`
would tell the model "everybody hated it", which is the same mistake the linear
ranker avoids by renormalising.

The two models solve the same problem differently, and the contrast is worth
stating because it is the clearest reason to have both:

| | Linear ranker | Learned ranker |
|---|---|---|
| Missing feature | Weight **redistributed** over what is present | Model is **told it is absent** and learns its own split |
| Measured effect | +0.0170 NDCG@3 — see [EVALUATION.md](EVALUATION.md) | not yet measurable |

---

## Why this is not on the request path

`plotted.model.enabled` is `false` by default. Two reasons, and the first is the
real one.

**Explanations.** The product's rule is that a reason must be a real feature
contribution, never prose that sounds like one. A gradient-boosted tree does not
hand you contributions; getting them means SHAP, which ONNX does not export.
Serving the learned *ranking* alongside the linear model's *explanations* would
mean the interface confidently explaining a decision it did not make — the
invented-prose failure this project has refused everywhere else, wearing a better
costume.

**Nothing has measured it against what it would replace.** There is no outcome
data. When there is, the harness in `recommendation.evaluation` scores it against
the linear model and the priority-only baseline first. That is why phase 7 was
built before phase 8 and not after.

So the model loads, validates and scores, and the decision to put it in front of
a person is a separate one that has not been earned.

---

## MovieLens, and why it is not used

The plan called for bootstrapping from MovieLens 32M via TMDB ids. Having built
the feature schema, that no longer fits, and it is worth writing down why rather
than leaving it looking unfinished.

MovieLens gives `(user, item, rating, timestamp)`. Plotted's feature vector is
almost entirely **context**: how much *you* said you want it, whether it fits the
time *you* have tonight, whether it is on a service *you* pay for, whether *you*
set a deadline. MovieLens has no analogue for any of those, and never will —
they are facts about a viewing session, not about a catalogue.

What MovieLens could contribute is an item-quality prior, which TMDB's community
rating already provides for free. So bootstrapping from it would mean training on
two or three columns that map and **fabricating the other five**, which is the
one thing this project does not do.

Where it genuinely belongs is Pilot Season (phase 9) — a taste model over
user–item preference, which is a different feature space and a different model.
Recorded as deferred with a reason rather than dropped.

---

## What has not been verified

- **Nothing here has run in CI.** The GitHub Actions outage covered the whole of
  phases 5 to 8. Every test above passes locally on Windows, including ONNX
  Runtime's native library — which, unlike CP-SAT, loads here without trouble.
- **The model has never been served.** It scores in tests and in the evaluation
  harness; no HTTP request has ever reached it.
- **One artefact is committed as a binary**, `models/ranker.onnx`, 443 KB. That
  is a deliberate trade: it is what lets the golden-vector and refusal tests run
  on every build rather than only where somebody has Python installed.
