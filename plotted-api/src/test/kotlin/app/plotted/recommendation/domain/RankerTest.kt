package app.plotted.recommendation.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Diversification and the exploration slot.
 *
 * The propensity assertions matter most. Phase 7 divides by these numbers, and a
 * wrong one does not fail — it silently biases every estimate built on months of
 * logs, discovered long after the policy that produced them is gone.
 */
class RankerTest {
    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()

    @Test
    fun `the top pick is never traded away for variety`() {
        val best = scored("Best", score = 0.9, provider = netflix, mediaType = "movie")
        val sameProvider = scored("Also Netflix", score = 0.85, provider = netflix, mediaType = "movie")
        val different = scored("Different", score = 0.5, provider = crave, mediaType = "series")

        val selected = Ranker().diversify(listOf(sameProvider, best, different), 3)

        // Slot one is the answer to the question. Trading its quality for
        // variety would be optimising the wrong thing.
        selected.first().candidate.name shouldBe "Best"
    }

    @Test
    fun `backups differ in kind from the pick even at some cost in score`() {
        val best = scored("Netflix film", score = 0.90, provider = netflix, mediaType = "movie")
        val nearlyAsGood = scored("Another Netflix film", score = 0.88, provider = netflix, mediaType = "movie")
        val lower = scored("Crave series", score = 0.60, provider = crave, mediaType = "series")

        val selected = Ranker().diversify(listOf(best, nearlyAsGood, lower), 2)

        // Pure score ordering would return two near-identical Netflix films.
        // Someone rejecting the first is usually rejecting a kind of thing, so a
        // backup that differs only by two points of score is not a backup.
        selected[1].candidate.name shouldBe "Crave series"
    }

    @Test
    fun `with exploration off every slot is deterministic and certain`() {
        val ranker = Ranker(random = Random(1), explorationRate = 0.0)
        val selected = listOf(
            scored("A", 0.9, netflix, "movie"),
            scored("B", 0.8, crave, "series"),
        )

        val picks = ranker.explore(selected, alternatives = listOf(scored("C", 0.4, crave, "movie")))

        picks.forEach { it.exploration shouldBe false }
        // A deterministic policy chose these with certainty, so dividing by the
        // propensity in phase 7 is a no-op rather than a distortion.
        picks.forEach { it.propensity shouldBe (1.0 plusOrMinus 1e-9) }
    }

    @Test
    fun `an explored slot records the probability it was chosen with`() {
        // nextDouble() returns 0.0 < explorationRate, so exploration fires.
        val ranker = Ranker(random = Random(0), explorationRate = 1.0)
        val selected = listOf(
            scored("A", 0.9, netflix, "movie"),
            scored("B", 0.8, crave, "series"),
        )
        val alternatives = listOf(scored("C", 0.4, crave, "movie"), scored("D", 0.3, netflix, "series"))

        val picks = ranker.explore(selected, alternatives)

        val last = picks.last()
        last.exploration shouldBe true
        // Uniform over the alternatives, scaled by how often exploration runs:
        // 1.0 / 2. Getting this wrong is undetectable until phase 7 produces
        // confident nonsense.
        last.propensity shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `the exploited last slot is discounted by the chance it was replaced`() {
        val ranker = Ranker(random = Random(0), explorationRate = 0.0)
        val selected = listOf(
            scored("A", 0.9, netflix, "movie"),
            scored("B", 0.8, crave, "series"),
        )

        val picks = ranker.explore(selected, alternatives = emptyList())

        // With no alternatives there was nothing to explore into, so the slot was
        // certain. Reporting 1 - rate here would understate the certainty and
        // over-weight this row in every later estimate.
        picks.last().propensity shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a single pick is certain even at a high exploration rate`() {
        // Exploration replaces the *last of several* slots, so with only one
        // selected it can never fire. Discounting that slot anyway would record
        // a decision as uncertain when it was not — and at rate 1.0 it recorded
        // a propensity of zero, which makes phase 7 divide by zero over a choice
        // that was never in doubt.
        val ranker = Ranker(random = Random(0), explorationRate = 1.0)

        val picks = ranker.explore(
            selected = listOf(scored("Only", 0.9, netflix, "movie")),
            alternatives = listOf(scored("Other", 0.4, crave, "movie")),
        )

        picks.single().exploration shouldBe false
        picks.single().propensity shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `every logged propensity is strictly positive`() {
        // An action recorded as impossible but taken makes importance-weighted
        // estimators divide by zero. The database rejects it too; this is the
        // check that it never gets that far.
        val ranker = Ranker(random = Random(7), explorationRate = 0.5)
        repeat(50) {
            val picks = ranker.explore(
                listOf(scored("A", 0.9, netflix, "movie"), scored("B", 0.8, crave, "series")),
                listOf(scored("C", 0.4, crave, "movie")),
            )
            picks.forEach { (it.propensity > 0.0) shouldBe true }
        }
    }

    private fun scored(name: String, score: Double, provider: UUID, mediaType: String) = ScoredCandidate(
        candidate = Candidate(
            titleId = UUID.randomUUID(),
            name = name,
            mediaType = mediaType,
            posterUrl = null,
            watchMinutes = 100,
            priority = 3,
            desiredByDate = null,
            communityRating = null,
            offers = listOf(Candidate.Offer(provider, "Provider", isFree = false)),
        ),
        score = score,
        features = FeatureVector.of(Feature.PRIORITY to score),
    )
}
