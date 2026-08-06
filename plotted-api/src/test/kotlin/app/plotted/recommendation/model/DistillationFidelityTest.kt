package app.plotted.recommendation.model

import app.plotted.recommendation.evaluation.EvaluationHarness
import app.plotted.recommendation.evaluation.LearnedModelStrategy
import app.plotted.recommendation.evaluation.LinearModelStrategy
import app.plotted.recommendation.evaluation.MetadataCensoringSimulation
import app.plotted.support.ModelSupport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.math.abs

/**
 * The whole pipeline, made falsifiable without a single user.
 *
 * The committed model is a **distillation**: it was trained on the linear
 * ranker's own scores, over features produced by the serving extractor. So a
 * correct pipeline has a property nothing else in this project has — a known
 * right answer. The learned model should rank almost exactly as the model it
 * copied.
 *
 * That makes this an end-to-end integration test of five things at once, none of
 * which announces its own failure:
 *
 * 1. `TrainingDataExport` wrote the features in schema order.
 * 2. The Python side read them in that order and did not silently transpose.
 * 3. `NaN` survived LightGBM → ONNX conversion as *missing* rather than becoming
 *    zero.
 * 4. `float32` narrowing happens at the same boundary on both sides.
 * 5. ONNX Runtime on this JVM computes what LightGBM computed in Python.
 *
 * Break any one and the ranking diverges. `GoldenVectorTest` checks the same
 * chain at the level of individual scores; this checks it at the level of the
 * thing anybody actually cares about, which is the order of the list.
 *
 * **This is not evidence the learned model is good.** It is evidence the
 * plumbing is honest. A distillation that matched its teacher perfectly and a
 * distillation of a bad teacher look identical here, which is why phase 7 exists
 * separately.
 */
@EnabledIf("app.plotted.support.ModelSupport#isModelAvailable")
class DistillationFidelityTest {
    @Test
    fun `the learned model reproduces the ranking it was distilled from`() {
        val loaded = OnnxScorer.load(ModelSupport.modelPath())
        (loaded is OnnxScorer.LoadResult.Loaded) shouldBe true

        (loaded as OnnxScorer.LoadResult.Loaded).scorer.use { scorer ->
            val harness = EvaluationHarness()
            val queries = MetadataCensoringSimulation(queries = QUERIES).generate()
            val learned = LearnedModelStrategy(scorer)

            val report = harness.run(queries, listOf(LinearModelStrategy(), learned))
            val difference = harness.compare(report, learned.name, "linear-v1").difference.shouldNotBeNull()

            // Asserted on the *magnitude*, not on "no significant difference".
            // A paired bootstrap over enough queries will eventually call a
            // 0.0001 gap significant, and a test that fails because the model
            // got very slightly better is a test people delete.
            withClue(
                "learned model differs from its teacher by ${difference.mean} NDCG@3 " +
                    "(95% CI ${difference.lower} to ${difference.upper}). " +
                    "A distillation should track the linear ranker; a gap this large means a " +
                    "link in the export → train → convert → serve chain is broken.",
            ) {
                (abs(difference.mean) < MAXIMUM_DIVERGENCE) shouldBe true
            }
        }
    }

    @Test
    fun `scoring is batch-invariant`() {
        val loaded = OnnxScorer.load(ModelSupport.modelPath()) as OnnxScorer.LoadResult.Loaded

        loaded.scorer.use { scorer ->
            val queries = MetadataCensoringSimulation(queries = 5).generate()
            val vectors = queries.flatMap { query ->
                query.candidates.map { FeatureSchema.extract(it, query.context, query.subscribedProviderIds, query.askedOn) }
            }

            val together = scorer.score(vectors)
            val separately = vectors.map { scorer.score(listOf(it)).single() }

            // The strategy batches for speed and the golden vectors are scored
            // in one call. If batching changed the answer materially, every
            // number in the evaluation would depend on how many candidates
            // happened to be in the list — and it would never fail visibly.
            val worst = together.indices.maxByOrNull { abs(together[it] - separately[it]) }!!
            val delta = abs(together[worst] - separately[worst])

            withClue(
                "batched and single-row scoring differ by $delta at row $worst " +
                    "(${together[worst]} against ${separately[worst]}), tolerance $BATCH_TOLERANCE",
            ) {
                (delta < BATCH_TOLERANCE) shouldBe true
            }
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (failure: AssertionError) {
        throw AssertionError("$clue\n${failure.message}", failure)
    }

    private companion object {
        const val QUERIES = 600

        /**
         * Generous relative to what is observed (about 0.00003), and
         * deliberately so: this is a regression guard on the pipeline, not a
         * measurement of distillation quality. Tightening it to the observed
         * value would make retraining fail the build.
         */
        const val MAXIMUM_DIVERGENCE = 0.005

        /**
         * Float32 resolution, not zero.
         *
         * The first version of this test demanded `1e-9`, which is *below what
         * a float32 can represent* near 0.9 — the gap between adjacent values
         * there is about 6e-8. So it was asserting a difference smaller than the
         * output's own precision, and it failed for a reason that had nothing to
         * do with batching. Worth keeping as a comment: a tolerance tighter than
         * the data type is not a strict test, it is a broken one.
         */
        const val BATCH_TOLERANCE = 1e-6
    }
}
