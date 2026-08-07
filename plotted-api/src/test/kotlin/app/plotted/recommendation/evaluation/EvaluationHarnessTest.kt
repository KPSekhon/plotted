package app.plotted.recommendation.evaluation

import app.plotted.recommendation.domain.AccessPolicy
import app.plotted.recommendation.domain.Candidate
import app.plotted.recommendation.domain.TonightContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The harness itself, and the ablation's honesty.
 *
 * The most important test here is the last one. An ablation is only evidence if
 * it changes exactly one thing, and the way ablations mislead is by quietly
 * differing in a second respect — at which point the number measures the
 * difference between two implementations rather than the effect being studied.
 */
class EvaluationHarnessTest {
    private val harness = EvaluationHarness()
    private val provider = UUID.randomUUID()
    private val today = LocalDate.of(2026, 8, 6)

    @Test
    fun `queries with nothing relevant are dropped once, for every strategy`() {
        val answerable = query("answerable", relevanceOfFirst = 1.0)
        val hopeless = query("hopeless", relevanceOfFirst = 0.0)

        val report = harness.run(listOf(answerable, hopeless), listOf(RandomStrategy(), PopularityStrategy))

        report.queriesConsidered shouldBe 2
        report.queriesScored shouldBe 1
        // Filtering per strategy would give each a different denominator, and
        // two means over different query sets are not comparable however alike
        // the numbers look. It would also break the pairing that every
        // comparison in this harness depends on.
        report.result("random").perQueryNdcg.size shouldBe 1
        report.result("popularity").perQueryNdcg.size shouldBe 1
    }

    @Test
    fun `a strategy that drops candidates is refused rather than rewarded`() {
        val dropsEverythingButOne = object : RankingStrategy {
            override val name = "cheat"
            override fun rank(query: EvaluationQuery) = query.candidates.take(1).map { it.titleId }
        }

        // Returning a shorter list shortens what the metric is computed over,
        // which flatters precision and can flatter NDCG. Left unchecked this is
        // an easy way to produce a spectacular and meaningless result.
        shouldThrow<IllegalArgumentException> {
            harness.run(listOf(query("q", relevanceOfFirst = 1.0)), listOf(dropsEverythingButOne))
        }
    }

    @Test
    fun `a temporal split holds out the future, boundary included`() {
        val queries = listOf(
            query("old", relevanceOfFirst = 1.0, askedOn = today.minusDays(10)),
            query("boundary", relevanceOfFirst = 1.0, askedOn = today),
            query("new", relevanceOfFirst = 1.0, askedOn = today.plusDays(5)),
        )

        val split = harness.temporalSplit(queries, today)

        // The boundary date belongs to the held-out side. A random split would
        // leak the future into the past and report a number production cannot
        // reproduce.
        split.train.map { it.queryId } shouldBe listOf("old")
        split.test.map { it.queryId } shouldBe listOf("boundary", "new")
    }

    @Test
    fun `a perfect ranker scores one and random scores less over the same queries`() {
        val perfect = object : RankingStrategy {
            override val name = "oracle"
            override fun rank(query: EvaluationQuery) =
                query.candidates.sortedByDescending { query.relevance[it.titleId] ?: 0.0 }.map { it.titleId }
        }
        val queries = (0 until 40).map { graded("q$it") }

        val report = harness.run(queries, listOf(perfect, RandomStrategy()))

        // A smoke test with teeth: if the harness cannot separate an oracle from
        // a shuffle, nothing else it reports means anything.
        report.result("oracle").ndcg.shouldNotBeNull().mean shouldBe 1.0
        (report.result("random").ndcg!!.mean < 1.0) shouldBe true
        harness.compare(report, "oracle", "random").difference.shouldNotBeNull().excludesZero shouldBe true
    }

    @Test
    fun `with complete metadata the ablation is indistinguishable from the shipped scorer`() {
        // The heart of it. With every feature present, renormalisation divides by
        // the total weight, which is exactly what the ablated version does — so
        // the two must produce byte-identical rankings. If they differ here, the
        // ablation differs in some second respect and every number it produces
        // measures that instead of renormalisation.
        val queries = (0 until 30).map { completeMetadata("q$it") }

        val report = harness.run(queries, listOf(LinearModelStrategy(), NoRenormalisationStrategy()))

        report.result("linear-v1").perQueryNdcg shouldBe report.result("linear-v1-no-renormalisation").perQueryNdcg
    }

    @Test
    fun `with metadata missing the two diverge, and renormalisation is the one ahead`() {
        val queries = MetadataCensoringSimulation(queries = 300, seed = 7).generate()

        val report = harness.run(queries, listOf(LinearModelStrategy(), NoRenormalisationStrategy()))
        val difference = harness.compare(report, "linear-v1", "linear-v1-no-renormalisation").difference.shouldNotBeNull()

        // Direction and significance are asserted; the magnitude is not, because
        // it is a property of the censoring rate rather than of the code, and
        // pinning it here would make this test fail whenever the simulation is
        // tuned rather than when the ranker regresses.
        (difference.mean > 0.0) shouldBe true
        difference.excludesZero shouldBe true
    }

    // --- fixtures -----------------------------------------------------------

    private fun query(id: String, relevanceOfFirst: Double, askedOn: LocalDate = today): EvaluationQuery {
        val candidates = (0 until 3).map { candidate(priority = it + 1) }
        return EvaluationQuery(
            queryId = id,
            candidates = candidates,
            context = TonightContext("CA", 120, AccessPolicy.SUBSCRIBED_ONLY),
            subscribedProviderIds = setOf(provider),
            askedOn = askedOn,
            relevance = mapOf(candidates.first().titleId to relevanceOfFirst),
        )
    }

    /** Graded relevance spread over the candidates, so ordering actually matters. */
    private fun graded(id: String): EvaluationQuery {
        val candidates = (0 until 6).map { candidate(priority = (it % 5) + 1) }
        return EvaluationQuery(
            queryId = id,
            candidates = candidates,
            context = TonightContext("CA", 120, AccessPolicy.SUBSCRIBED_ONLY),
            subscribedProviderIds = setOf(provider),
            askedOn = today,
            relevance = candidates.mapIndexed { index, c -> c.titleId to (6 - index).toDouble() }.toMap(),
        )
    }

    /** Every optional field populated, so no feature is ever absent. */
    private fun completeMetadata(id: String): EvaluationQuery {
        val candidates = (0 until 8).map { index ->
            candidate(priority = (index % 5) + 1).copy(
                watchMinutes = 90 + index * 5,
                desiredByDate = today.plusDays((index + 1).toLong()),
                communityRating = 5.0 + index * 0.4,
            )
        }
        return EvaluationQuery(
            queryId = id,
            candidates = candidates,
            context = TonightContext("CA", 120, AccessPolicy.SUBSCRIBED_ONLY),
            subscribedProviderIds = setOf(provider),
            askedOn = today,
            relevance = candidates.mapIndexed { index, c -> c.titleId to (8 - index).toDouble() }.toMap(),
        )
    }

    private fun candidate(priority: Int) = Candidate(
        titleId = UUID.randomUUID(),
        name = "Title",
        mediaType = "movie",
        posterUrl = null,
        watchMinutes = 110,
        priority = priority,
        desiredByDate = null,
        communityRating = 7.0,
        offers = listOf(Candidate.Offer(provider, "Service", isFree = false)),
    )
}
