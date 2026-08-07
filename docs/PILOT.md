# Pilot Season

Phase 9: fifteen "which of these two?" questions, turned into a taste profile —
and, more carefully, into a decision about which parts of that profile are worth
saying out loud.

```bash
./gradlew :plotted-api:test --tests 'app.plotted.preferences.*'
```

---

## The model

Bradley–Terry, parameterised by **attributes** rather than by titles. A title's
strength is `θ(t) = w · x(t)`, so

```
P(A chosen over B) = σ(w · (x_A − x_B))
```

which is logistic regression on feature differences.

Parameterising by attribute is what makes fifteen questions enough. A per-title
Bradley–Terry model has one parameter per film and learns nothing transferable —
knowing you preferred *this* film says nothing about the thousand nobody asked
about. Six attributes, learned from fifteen answers, apply to the whole
catalogue.

## Six axes, not nineteen genres

TMDB has nineteen genres. Fitting nineteen weights from fifteen answers is not a
modelling choice, it is arithmetic that cannot work: most genres would never
appear in a pair, and the few that did would carry the entire profile.

| Axis | Positive | Negative | Derived from |
|---|---|---|---|
| Levity | lighter | heavier | comedy/family/animation against drama/war/crime |
| Pace | faster | slower | action/thriller against drama/documentary |
| Grounding | grounded | invented | documentary/history against fantasy/sci-fi |
| Commitment | a series | a film | media type |
| Recency | newer | older | release year against five years ago |
| Acclaim | well reviewed | overlooked | community rating against 7.0 |

Six axes are also six questions a person could be asked out loud. That matters
beyond the fit, because the profile is *shown* to the user, and "you scored 0.7
on Science Fiction" is not something anybody can agree or disagree with.

**Every axis is centred by construction**, returning `[-1, 1]` with 0 meaning
balanced. So a title with no lean anywhere scores exactly 0.5 against any
profile. The alternative — centring on population means — would need
catalogue-wide statistics that shift every time the seed grows, and a profile
fitted in March would silently mean something different in June.

The two reference points that are not structural (what counts as "recent", what
counts as "acclaimed") are constants in `TasteAxes.kt`, stated so they can be
argued with.

---

## The prior is not optional

With fifteen comparisons and six axes, maximum likelihood is not merely noisy —
it is frequently **undefined**.

If somebody picks the comedy every time one appears, the likelihood increases
without bound as `w_levity → ∞`. An unregularised fit runs until it hits an
iteration cap and reports an enormous number with no warning attached. That is
not an edge case; it is the *expected* outcome for a decisive person answering a
short questionnaire.

A Gaussian prior makes the posterior strictly concave, so the mode always exists
and Newton always converges. `BradleyTerryTest` pins this with fifty unanimous
answers and asserts the weight stays finite and the fit converges.

And the prior mean is the **population's** taste rather than zero, which is the
difference between *"we have no data, so assume you are typical"* and *"we have
no data, so assume you are indifferent"*. Only the first is true.

`BradleyTerry.Prior` refuses a precision of zero rather than silently becoming
maximum likelihood, because that failure would otherwise surface later as a huge
weight nobody questioned.

---

## What the profile is allowed to say

The fit always returns six numbers. Reporting all six as preferences would be
the same failure this project refuses everywhere else — a confident statement
whose confidence is not backed by anything.

So each axis gets a verdict, and **two of the four are ways of saying we do not
know**:

| Verdict | Means | Condition |
|---|---|---|
| `LIKES` / `DISLIKES` | A finding | credible interval excludes zero at 1.96 SE |
| `NO_PREFERENCE` | We asked; you were genuinely balanced | interval narrow, contains zero |
| `NOT_ASKED` | The ladder never contrasted this axis | posterior still ≈ as wide as the prior |

The last two are the point. They produce nearly identical **weights** and
completely different advice:

- `NO_PREFERENCE` is a real result. It means this axis can be ignored when
  ranking for this person.
- `NOT_ASKED` is an *absence of evidence wearing the costume of a measurement* —
  the weight sitting at the population average because nothing moved it.

Only the posterior width tells them apart, which is why the fitter returns one.
The Laplace approximation — the inverse Hessian at the mode — is where it comes
from, and it is three lines given the Hessian Newton already needed.

**A profile with nothing it can defend saying scores nothing.**
`PreferenceProfile.match` returns null rather than 0.5, because both rankers
already handle an absent feature properly and handing them a real-looking number
computed from noise would put that noise into a decision and call it signal.

---

## The ladder

Fixed, and deterministic, and that is a decision rather than a shortcut.

The obvious design chooses each question from the answers so far, picking
whatever the posterior is least sure about. That is right eventually and wrong
now: **adaptive selection tunes against a model of the population, and there is
no population.** With no users, an adaptive ladder adapts to the prior — which is
a fixed ladder, reached by a more complicated route and much harder to test.

So the interesting work is making each question worth asking. A pair teaches you
about an axis in proportion to how far apart the titles are **on that axis**, and
teaches you *confusingly* in proportion to how far apart they are on every other
one. Somebody choosing between a light, fast, recent film and a heavy, slow, old
one has told you something — but not which of the three differences drove it, and
the fit cannot separate them either.

Each pair therefore maximises `contrast on its axis − 0.35 × contrast elsewhere`,
and the ladder round-robins the axes so a half-finished questionnaire still spans
all six rather than knowing everything about levity and nothing else.

Two smaller constraints, both with a reason:

- **No title appears more than three times.** One strongly-polarised film
  otherwise wins every axis, and fifteen questions about the same movie are both
  tedious and much weaker than they look, because the answers stop being
  independent.
- **A pair must contrast at least 0.2 on its axis** or it is not asked. An answer
  driven entirely by noise, recorded as evidence, is worse than a shorter
  questionnaire. `PilotLadderTest` checks that a catalogue of identical titles
  produces an *empty* ladder rather than a bad one.

---

## What this changed elsewhere

Adding `taste_match` to the ranker's feature vector bumped the schema from **v1
to v2** — and that is worth recording, because it made phase 8's guard fire on a
real change rather than a fixture:

```
GoldenVectorTest > the committed model was trained against this build's feature schema  FAILED
GoldenVectorTest > the model loads and reports the schema it was trained on             FAILED
```

The committed model was refused, the application fell back to the linear ranker,
and the fix was to retrain rather than to override anything. That is exactly the
behaviour `MODEL.md` claims, observed rather than asserted.

**One of my own tests was wrong**, and in an instructive way.
`OnnxScorerRefusalTest` checked that the fingerprint changes when the version
changes, using the literal `"v2"` as "some other version". It passed for as long
as the schema was v1 and broke the moment the schema *became* v2 — at which point
it was asserting the fingerprint differed from itself. Now derived from
`FeatureSchema.VERSION` rather than hard-coded.

**The linear ranker was deliberately left alone.** Adding a sixth feature would
mean rebalancing five designed weights, which invalidates the phase 7 ablation
without any evidence to justify a new arrangement. Do not change a measured thing
without a measurement.

---

## The flow

`pilot_comparisons` stores one row per question shown, answered or skipped.
`GET /api/v1/pilot` returns the next question, `POST /api/v1/pilot/answers`
records one and returns the state that follows, `GET /api/v1/pilot/profile`
returns the fit (204 until something has been answered), and
`DELETE /api/v1/pilot/answers` starts over.

**Skipping is a first-class answer.** A skipped row is stored, so the ladder does
not offer the pair again, and it carries no choice and no attribute difference,
so it cannot reach the fitter. Three things enforce that rather than one: the
repository filters on `chosen_title_id IS NOT NULL`, and the schema refuses both
a choice with no difference and a difference with no choice.

**The attribute difference is frozen at answer time**, stored as JSONB rather
than recomputed. RECENCY is measured against the current year, so re-deriving an
old answer would restate a 2026 comparison in the language of 2029 — and a title
later removed from the catalogue would otherwise take its evidence with it.

**A duplicate answer is refused, not counted.** The unique index normalises the
pair with `LEAST`/`GREATEST`, so answering (A, B) settles (B, A) too. The fit
counts rows, so a double-tap would weight one opinion twice and report a tighter
interval than the evidence supports.

### The ladder could hang

Found while wiring this up. `PilotLadder.build` put its exhaustion guard at the
bottom of the loop, below a `continue` that skipped it — so when no pair could be
found and the ladder was already non-empty, it span forever instead of returning
short. The guard now runs at the top.

The existing test covered a catalogue of *identical* titles, which produces an
empty ladder, and the empty case happened to be the one path that terminated. So
"too uniform to ask a question" was tested and "too small to ask fifteen" was
not, which is every catalogue smaller than the seeded one. The new test carries a
`@Timeout`, because this failure does not throw. It was run against the original
code and fails there.

## What is not built
- **`taste_match` carries no learned signal.** It is plumbed into the schema and
  the simulation varies it, but the training target — the linear ranker's score —
  does not depend on taste, so the model correctly learns to ignore it. Confirmed
  rather than assumed: `DistillationFidelityTest` still holds at −0.0001 NDCG@3.
  A column that were always absent would have left the feature untested rather
  than tested and found uninformative.
- **The population prior is currently zero-mean.** It should be the average
  fitted profile across users, which requires users. Until then a new person
  starts at "no lean anywhere", which is the same thing for a population nobody
  has measured — but it is a placeholder, not a finding.
- **Nothing here has run in CI**, along with the whole of phases 5–9.
