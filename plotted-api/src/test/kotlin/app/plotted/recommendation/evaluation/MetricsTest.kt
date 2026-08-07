package app.plotted.recommendation.evaluation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The metrics, checked against arithmetic done by hand.
 *
 * These are the numbers every claim in `EVALUATION.md` rests on, and each one
 * has a detail that is silently wrong in some implementations. A metric that is
 * subtly wrong does not fail — it produces a plausible number, and the whole
 * report becomes confident fiction.
 */
class MetricsTest {
    @Test
    fun `NDCG of a perfectly ordered list is one`() {
        Metrics.ndcgAt(listOf(3.0, 2.0, 1.0), 3)!! shouldBe (1.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `DCG applies a one-based log2 discount, so position one is undiscounted`() {
        // 1/log2(2) + 1/log2(3) + 1/log2(4) = 1 + 0.63093 + 0.5
        Metrics.dcgAt(listOf(1.0, 1.0, 1.0), 3) shouldBe (2.13093 plusOrMinus 1e-5)
    }

    @Test
    fun `a worked example, computed by hand`() {
        // Ranked gains 0, 2, 1. DCG = 0 + 2/log2(3) + 1/log2(4) = 1.26186 + 0.5
        // Ideal 2, 1, 0.        DCG = 2 + 1/log2(3) + 0       = 2 + 0.63093
        val ndcg = Metrics.ndcgAt(listOf(0.0, 2.0, 1.0), 3).shouldNotBeNull()
        ndcg shouldBe (1.76186 / 2.63093 plusOrMinus 1e-5)
    }

    @Test
    fun `a query with nothing relevant is unanswerable rather than answered badly`() {
        // Null, not zero. Averaging a zero here would drag every mean toward a
        // number describing the dataset rather than the ranker — the commonest
        // way an NDCG figure gets quietly deflated.
        Metrics.ndcgAt(listOf(0.0, 0.0, 0.0), 3).shouldBeNull()
    }

    @Test
    fun `normalisation makes an easy query and a hard one comparable`() {
        // Both rankers did the best that was possible with what they were given.
        // Un-normalised DCG would rank the first far above the second purely
        // because it had more to work with.
        val plenty = Metrics.ndcgAt(listOf(3.0, 3.0, 3.0), 3)!!
        val scarce = Metrics.ndcgAt(listOf(1.0, 0.0, 0.0), 3)!!
        plenty shouldBe (1.0 plusOrMinus TOLERANCE)
        scarce shouldBe (1.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `precision divides by the slots that existed, not by k`() {
        // Two candidates, one relevant, k of 3. The answer is 0.5 — charging the
        // ranker for a third slot it was never given would measure how short the
        // list was.
        Metrics.precisionAt(listOf(1.0, 0.0), 3)!! shouldBe (0.5 plusOrMinus TOLERANCE)
    }

    @Test
    fun `reciprocal rank is zero when nothing relevant was found anywhere`() {
        // Zero rather than null, and the asymmetry with NDCG is deliberate:
        // "put nothing relevant anywhere" is a real, bad outcome. The caller
        // excludes queries that had nothing to find before they reach here.
        Metrics.reciprocalRank(listOf(0.0, 0.0, 0.0)) shouldBe 0.0
        Metrics.reciprocalRank(listOf(0.0, 1.0)) shouldBe (0.5 plusOrMinus TOLERANCE)
    }

    @Test
    fun `a bootstrap interval brackets the mean and is reproducible`() {
        val values = listOf(0.1, 0.4, 0.9, 0.3, 0.7, 0.2, 0.85, 0.5)

        val first = Metrics.bootstrapMean(values).shouldNotBeNull()
        val again = Metrics.bootstrapMean(values).shouldNotBeNull()

        (first.lower <= first.mean && first.mean <= first.upper) shouldBe true
        // Seeded. A harness that reports a different interval each run invites
        // exactly one behaviour: running it again until it says something better.
        first shouldBe again
    }

    @Test
    fun `a single observation gives an interval with no width, and says n is one`() {
        val interval = Metrics.bootstrapMean(listOf(0.42)).shouldNotBeNull()

        interval.mean shouldBe (0.42 plusOrMinus TOLERANCE)
        interval.lower shouldBe interval.upper
        // The sample size travels with the number because a mean over one
        // observation and a mean over four hundred are different claims.
        interval.sampleSize shouldBe 1
    }

    @Test
    fun `a difference that straddles zero is not called significant`() {
        val a = listOf(0.5, 0.6, 0.4, 0.55, 0.45)
        val b = listOf(0.52, 0.58, 0.42, 0.53, 0.47)

        val difference = Metrics.bootstrapDifference(a, b).shouldNotBeNull()

        // Two nearly identical rankers. The honest answer is "cannot tell",
        // and `excludesZero` is the only phrasing of significance available here.
        difference.excludesZero shouldBe false
    }

    @Test
    fun `a real difference is detected`() {
        val better = List(50) { 0.8 }
        val worse = List(50) { 0.5 }

        Metrics.bootstrapDifference(better, worse).shouldNotBeNull().excludesZero shouldBe true
    }

    @Test
    fun `comparing rankers over different query sets is refused`() {
        // Unpaired data silently answers a different question. Failing here is
        // the only way that mistake stays visible.
        shouldThrow<IllegalArgumentException> {
            Metrics.bootstrapDifference(listOf(0.1, 0.2), listOf(0.1))
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
