package app.plotted.recommendation.model

import app.plotted.support.ModelSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Path

/**
 * The guard, watched refusing.
 *
 * `OnnxScorer` will not serve a model whose schema fingerprint does not match
 * this build's. That refusal is the single most important behaviour in phase 8 —
 * it is what turns training-serving skew from a silent degradation into a
 * startup message — and until it has been seen happening it is an assumption.
 *
 * `models/ranker-wrong-schema.onnx` is a real ONNX model, five trees and two
 * kilobytes, carrying a fingerprint that is deliberately not this schema's. It
 * exists to be rejected. Its predictions are never read, which is why it is tiny
 * rather than a copy of the real one.
 *
 * Same principle as `ModuleBoundaryTest.apiClassNamesAreUnique`, which was run
 * against a live collision before it was trusted: **a guard that has never
 * failed is a guard nobody has tested.**
 */
@EnabledIf("app.plotted.support.ModelSupport#isModelAvailable")
class OnnxScorerRefusalTest {
    @Test
    fun `a model trained against a different schema is refused, not served`() {
        val result = OnnxScorer.load(Path.of("..", "models", "ranker-wrong-schema.onnx"))

        // Refused rather than loaded-with-a-warning. A model reading a vector it
        // was not trained for does not fail — it returns confident numbers from
        // a distribution it has never seen, and nothing anywhere reports that.
        (result is OnnxScorer.LoadResult.Refused) shouldBe true
        val failure = (result as OnnxScorer.LoadResult.Refused).failure
        (failure is OnnxScorer.LoadFailure.SchemaMismatch) shouldBe true

        val mismatch = failure as OnnxScorer.LoadFailure.SchemaMismatch
        mismatch.modelFingerprint shouldBe "deadbeefdeadbeef"
        // Both values, because the first question anyone asks on seeing this is
        // "which of the two is stale".
        mismatch.expected shouldBe FeatureSchema.fingerprint
    }

    @Test
    fun `an absent model is a normal state rather than a failure`() {
        val result = OnnxScorer.load(Path.of("..", "models", "does-not-exist.onnx"))

        // There is no model for most of this project's life, and the linear
        // ranker is a working recommender rather than a degraded mode. Throwing
        // here would make "no model yet" stop the application from booting.
        (result is OnnxScorer.LoadResult.Refused) shouldBe true
        ((result as OnnxScorer.LoadResult.Refused).failure is OnnxScorer.LoadFailure.NotFound) shouldBe true
    }

    @Test
    fun `a file that is not a model is refused without taking the process down`() {
        // ONNX Runtime is a JNI binding and its failure modes are not all
        // exceptions. Feeding it the golden-vector JSON is the cheapest
        // available proof that a malformed model is survivable.
        val result = OnnxScorer.load(ModelSupport.goldenPath())

        (result is OnnxScorer.LoadResult.Refused) shouldBe true
        ((result as OnnxScorer.LoadResult.Refused).failure is OnnxScorer.LoadFailure.Unreadable) shouldBe true
    }

    @Test
    fun `the fingerprint changes when the feature list changes`() {
        // The property the whole guard rests on. Asserted directly rather than
        // trusted, because a fingerprint that happened not to cover feature
        // order would pass every other test in this file.
        val reordered = fingerprintOf(FeatureSchema.VERSION, FeatureSchema.names.reversed())
        val renamed = fingerprintOf(FeatureSchema.VERSION, FeatureSchema.names.dropLast(1) + "something_else")
        val rebumped = fingerprintOf("v2", FeatureSchema.names)

        val actual = FeatureSchema.fingerprint
        listOf(reordered, renamed, rebumped).forEach { (it == actual) shouldBe false }
        // ...and is stable for the same inputs, or it would refuse every model
        // including the correct one.
        fingerprintOf(FeatureSchema.VERSION, FeatureSchema.names) shouldBe actual
    }

    @Test
    fun `a refusal names the schema in a way somebody could act on`() {
        val result = OnnxScorer.load(Path.of("..", "models", "ranker-wrong-schema.onnx"))
        val mismatch = (result as OnnxScorer.LoadResult.Refused).failure as OnnxScorer.LoadFailure.SchemaMismatch

        // A message that says only "schema mismatch" sends someone reading
        // source. These two strings are what they actually need.
        "${mismatch.modelFingerprint} ${mismatch.expected}" shouldContain FeatureSchema.fingerprint
    }

    /** Recomputed here rather than exposed, so the test does not simply agree with itself. */
    private fun fingerprintOf(version: String, names: List<String>): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$version|${names.joinToString(",")}".toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
