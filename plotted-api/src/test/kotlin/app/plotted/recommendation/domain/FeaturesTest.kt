package app.plotted.recommendation.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The scoring arithmetic, which is where a recommender goes quietly wrong.
 *
 * None of these failures would crash anything. They produce rankings that look
 * reasonable and are systematically biased, which is exactly why they are worth
 * asserting rather than eyeballing.
 */
class FeaturesTest {
    @Test
    fun `the declared weights sum to one`() {
        // If they do not, every score is scaled by an arbitrary constant and
        // comparisons against any fixed threshold quietly mean something else.
        Feature.TOTAL_WEIGHT shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a missing feature does not cost a candidate anything`() {
        val complete = FeatureVector.of(
            Feature.PRIORITY to 1.0,
            Feature.RUNTIME_FIT to 1.0,
            Feature.ACCESS to 1.0,
            Feature.DEADLINE to 1.0,
            Feature.ACCLAIM to 1.0,
        )
        val missingAcclaim = FeatureVector.of(
            Feature.PRIORITY to 1.0,
            Feature.RUNTIME_FIT to 1.0,
            Feature.ACCESS to 1.0,
            Feature.DEADLINE to 1.0,
        )

        // This is the renormalisation, and it is the whole reason it exists.
        // Without it the second scores 0.90 and loses to an otherwise identical
        // title that happens to have a rating — so the ranking becomes a ranking
        // of metadata completeness rather than of what someone wants to watch.
        complete.score() shouldBe (1.0 plusOrMinus 1e-9)
        missingAcclaim.score() shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a candidate with no features at all scores null rather than zero`() {
        // Zero would sort it below everything, which is a claim about the title
        // rather than about what is known. Null lets the caller decide.
        FeatureVector.of().score().shouldBeNull()
    }

    @Test
    fun `contributions sum to the score and are ordered by influence`() {
        val features = FeatureVector.of(
            Feature.PRIORITY to 1.0,
            Feature.ACCLAIM to 0.5,
        )

        val contributions = features.contributions()
        contributions.map { it.feature } shouldBe listOf(Feature.PRIORITY, Feature.ACCLAIM)
        // Explanations are rendered from these shares, so if they did not sum to
        // the score the interface would be describing a different calculation
        // from the one that ranked the list.
        contributions.sumOf { it.share } shouldBe (features.score()!! plusOrMinus 1e-9)
    }

    @Test
    fun `a perfect runtime fit scores one`() {
        runtimeFit(90, 90) shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `running over is penalised harder than finishing early`() {
        // Ten percent over versus ten percent under, from the same budget.
        val over = runtimeFit(110, 100)
        val under = runtimeFit(90, 100)

        // The asymmetry is the product's whole premise: being twenty minutes
        // short of the end when you have to stop is the failure it exists to
        // prevent, and finishing early is a mild disappointment.
        (over < under) shouldBe true
        under shouldBe (0.9 plusOrMinus 1e-9)
        over shouldBe (0.7 plusOrMinus 1e-9)
    }

    @Test
    fun `runtime fit never goes negative`() {
        // A wildly over-length title is filtered out long before this, but a
        // negative feature value would corrupt every other candidate's
        // renormalised score if one ever reached here.
        runtimeFit(600, 60) shouldBe 0.0
    }

    @Test
    fun `values outside zero and one are clamped on the way in`() {
        // A feature accidentally computed as 1.4 would let one signal outvote the
        // entire rest of the model.
        FeatureVector.of(Feature.PRIORITY to 4.0).score() shouldBe (1.0 plusOrMinus 1e-9)
        FeatureVector.of(Feature.PRIORITY to -2.0).score() shouldBe (0.0 plusOrMinus 1e-9)
    }
}
