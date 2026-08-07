package app.plotted.recommendation.evaluation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The report reproduces exactly, run to run.
 *
 * `docs/EVALUATION.md` quotes figures to four decimal places and tells the
 * reader to regenerate them with one command. That promise was false when it was
 * first written: every strategy breaks ties on title id, the simulation was
 * minting those with [java.util.UUID.randomUUID], and the confidence intervals
 * moved in the third decimal between runs while the comments insisted the whole
 * thing was seeded.
 *
 * Nothing failed. The numbers were plausible, the code said "seeded", and the
 * document would have drifted away from the code within a day. This is the check
 * that could have caught it, which is the reason it exists now.
 */
class EvaluationReportTest {
    private val strategies = listOf(
        LinearModelStrategy(),
        NoRenormalisationStrategy(),
        WatchlistPriorityStrategy,
        PopularityStrategy,
        RandomStrategy(),
    )

    @Test
    fun `two runs of the same simulation produce identical queries`() {
        val first = MetadataCensoringSimulation(queries = 50).generate()
        val second = MetadataCensoringSimulation(queries = 50).generate()

        // Title ids included. They are what ties break on, so ids that differ
        // are enough on their own to move every metric downstream.
        first.map { it.candidates.map(::identity) } shouldBe second.map { it.candidates.map(::identity) }
        first.map { it.relevance } shouldBe second.map { it.relevance }
    }

    @Test
    fun `two runs produce byte-identical markdown`() {
        val harness = EvaluationHarness()
        val first = harness.run(MetadataCensoringSimulation(queries = 200).generate(), strategies).toMarkdown()
        val second = harness.run(MetadataCensoringSimulation(queries = 200).generate(), strategies).toMarkdown()

        first shouldBe second
    }

    @Test
    fun `a different seed produces a different report, so the seed is doing something`() {
        val harness = EvaluationHarness()
        val one = harness.run(MetadataCensoringSimulation(queries = 200, seed = 1).generate(), strategies).toMarkdown()
        val two = harness.run(MetadataCensoringSimulation(queries = 200, seed = 2).generate(), strategies).toMarkdown()

        // The other half of the claim. A "seeded" generator that ignores its seed
        // would pass the test above and be just as wrong.
        one shouldNotBe two
    }

    private fun identity(candidate: app.plotted.recommendation.domain.Candidate) = listOf(
        candidate.titleId.toString(),
        candidate.priority.toString(),
        candidate.watchMinutes.toString(),
        candidate.communityRating.toString(),
    )
}
