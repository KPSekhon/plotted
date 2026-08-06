package app.plotted.recommendation.model

import app.plotted.support.ModelSupport
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.math.abs

/**
 * The training-serving skew guard, and the reason phase 8 has a point.
 *
 * The training script recorded the score its model gave a sample of input
 * vectors. This replays those exact vectors through the JVM path — ONNX Runtime,
 * the same model file, the same float widths — and asserts the scores match.
 *
 * ### What each assertion here would catch
 *
 * - **A reordered or renamed feature.** Caught earlier, by the fingerprint, and
 *   asserted again here so the two mechanisms cannot both be quietly disabled.
 * - **A missing-value convention that does not survive export.** The golden set
 *   is mostly rows containing `NaN`, deliberately. LightGBM treats `NaN` as
 *   absent and learns a direction for it; whether that survives conversion to an
 *   ONNX `TreeEnsembleRegressor` is a real risk, not a theoretical one. A
 *   converter that silently reads `NaN` as `0` would produce a model that works
 *   perfectly on complete rows and is wrong on the two thirds of real candidates
 *   that are missing something.
 * - **A precision or dtype mismatch.** `float32` in, `float64` accumulated —
 *   getting the widths wrong shows up as scores that *nearly* match, which is
 *   the worst failure available because it invites raising the tolerance until
 *   it passes.
 *
 * None of these throws in production. All of them make recommendations quietly
 * worse, and there is no alert for "quietly worse".
 */
@EnabledIf("app.plotted.support.ModelSupport#isModelAvailable")
class GoldenVectorTest {
    private val golden: JsonNode = ObjectMapper().readTree(ModelSupport.goldenPath().toFile())

    @Test
    fun `the committed model was trained against this build's feature schema`() {
        // If this fails, every other assertion in the file is measuring the
        // wrong thing, so it is asserted first and separately.
        golden["schemaFingerprint"].asText() shouldBe FeatureSchema.fingerprint
        golden["features"].map { it.asText() } shouldBe FeatureSchema.names
    }

    @Test
    fun `the model loads and reports the schema it was trained on`() {
        val result = OnnxScorer.load(ModelSupport.modelPath())

        (result is OnnxScorer.LoadResult.Loaded) shouldBe true
        (result as OnnxScorer.LoadResult.Loaded).scorer.use { scorer ->
            scorer.metadata.schemaFingerprint shouldBe FeatureSchema.fingerprint
            scorer.metadata.schemaVersion shouldBe FeatureSchema.VERSION
            // Stamped on every logged decision so the evaluation harness can
            // never pool rows from two different scoring functions.
            scorer.metadata.rankerVersion.startsWith("learned-") shouldBe true
        }
    }

    @Test
    fun `every golden vector scores the same on the JVM as it did at training time`() {
        val vectors = golden["vectors"].map { row ->
            // null is the JSON encoding of a missing feature — JSON has no NaN
            // literal, and a bare NaN produces a file most parsers reject.
            FloatArray(row.size()) { index -> if (row[index].isNull) Float.NaN else row[index].floatValue() }
        }
        val expected = golden["scores"].map { it.doubleValue() }

        val loaded = OnnxScorer.load(ModelSupport.modelPath()) as OnnxScorer.LoadResult.Loaded
        val actual = loaded.scorer.use { it.score(vectors) }

        actual.size shouldBe expected.size

        // Reported as the worst row rather than the first mismatch. A single
        // failing index says almost nothing; the largest divergence and where it
        // is says whether this is a systematic dtype problem or one odd leaf.
        val worst = expected.indices.maxByOrNull { abs(actual[it] - expected[it]) }!!
        val delta = abs(actual[worst] - expected[worst])

        withClue(
            "worst divergence at row $worst: JVM ${actual[worst]} vs training ${expected[worst]} " +
                "(delta $delta, tolerance $TOLERANCE)\n" +
                "features: ${FeatureSchema.describe(vectors[worst])}",
        ) {
            (delta < TOLERANCE) shouldBe true
        }
    }

    @Test
    fun `the golden set actually exercises missing values`() {
        val withMissing = golden["vectors"].count { row -> (0 until row.size()).any { row[it].isNull } }

        // A guard that only covers complete rows would pass while the case most
        // likely to be wrong went untested. The training script refuses to write
        // a set without missing values; this asserts the file on disk kept them.
        (withMissing > 0) shouldBe true
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (failure: AssertionError) {
        throw AssertionError("$clue\n${failure.message}", failure)
    }

    private companion object {
        /**
         * Tight on purpose. This is the same arithmetic on the same model file,
         * so anything above float rounding is a real disagreement — and a
         * tolerance loose enough to absorb a dtype bug is a tolerance that
         * absorbs the thing it was written to catch.
         */
        const val TOLERANCE = 1e-6
    }
}
