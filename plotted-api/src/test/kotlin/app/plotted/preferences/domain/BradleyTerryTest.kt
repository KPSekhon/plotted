package app.plotted.preferences.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The fitter, checked against things that are true by construction.
 *
 * A preference model is unusually easy to get plausibly wrong: it always returns
 * numbers, they are always in a sensible range, and a sign error produces a
 * profile that is confidently the exact opposite of the person's taste while
 * looking completely normal. So none of these tests asserts "the output looks
 * reasonable" — each one has an answer that is knowable in advance.
 */
class BradleyTerryTest {
    private val threeAttributes = BradleyTerry.Prior(mean = doubleArrayOf(0.0, 0.0, 0.0), precision = 1.0)

    @Test
    fun `no answers returns the population, exactly`() {
        val population = BradleyTerry.Prior(mean = doubleArrayOf(0.3, -0.2, 0.0), precision = 2.0)

        val fit = BradleyTerry.fit(emptyList(), population)

        // Exactly, not approximately. "We know nothing about you" must produce
        // the population profile rather than something within a convergence
        // tolerance of it.
        fit.weights.toList() shouldBe listOf(0.3, -0.2, 0.0)
        fit.observations shouldBe 0
        // And the uncertainty is the prior's own width, which is what tells the
        // profile there is nothing here worth reporting.
        fit.standardErrors.forEach { it shouldBe (1.0 / sqrt(2.0) plusOrMinus TOLERANCE) }
    }

    @Test
    fun `consistently choosing an attribute gives it a positive weight`() {
        // Chosen title has attribute 0, rejected does not. Ten times.
        val comparisons = List(10) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }

        val fit = BradleyTerry.fit(comparisons, threeAttributes)

        fit.converged shouldBe true
        (fit.weights[0] > 0.0) shouldBe true
        // The untouched attributes stay at the prior. A fitter that moved them
        // would be inventing preferences from data that never mentioned them.
        fit.weights[1] shouldBe (0.0 plusOrMinus TOLERANCE)
        fit.weights[2] shouldBe (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `the sign follows the choice, not the ordering`() {
        val forAttribute = List(8) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }
        val againstAttribute = List(8) { BradleyTerry.Comparison(doubleArrayOf(-1.0, 0.0, 0.0)) }

        val liked = BradleyTerry.fit(forAttribute, threeAttributes)
        val disliked = BradleyTerry.fit(againstAttribute, threeAttributes)

        // A sign error here produces a profile that is confidently the exact
        // opposite of somebody's taste and looks entirely normal doing it.
        liked.weights[0] shouldBe (-disliked.weights[0] plusOrMinus TOLERANCE)
        (liked.weights[0] > 0.0) shouldBe true
    }

    @Test
    fun `perfect separation stays finite, which is the whole reason for the prior`() {
        // Fifty unanimous answers. Under maximum likelihood the objective
        // increases without bound as this weight grows, so an unregularised fit
        // returns whatever it reached when the iteration cap stopped it — a
        // number with no meaning, reported with no warning.
        val unanimous = List(50) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }

        val fit = BradleyTerry.fit(unanimous, threeAttributes)

        fit.converged shouldBe true
        fit.weights[0].isFinite() shouldBe true
        // Bounded by the prior: at the mode the likelihood pull equals the prior
        // pull, and with n unanimous answers and precision 1 that lands well
        // below the runaway an unregularised fit would produce.
        (fit.weights[0] < 10.0) shouldBe true
    }

    @Test
    fun `stronger priors move less for the same evidence`() {
        val comparisons = List(6) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }

        val loose = BradleyTerry.fit(comparisons, BradleyTerry.Prior(DoubleArray(3), precision = 0.25))
        val tight = BradleyTerry.fit(comparisons, BradleyTerry.Prior(DoubleArray(3), precision = 16.0))

        // The knob that decides how many questions it takes to overrule the
        // population. If it did nothing, fifteen answers would be fitted as
        // though they were fifteen thousand.
        (loose.weights[0] > tight.weights[0]) shouldBe true
    }

    @Test
    fun `an attribute nobody was asked about keeps its prior uncertainty`() {
        // Every comparison contrasts attribute 0 only. Attribute 2 is never
        // varied, so nothing in this data says anything about it.
        val comparisons = List(15) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }

        val fit = BradleyTerry.fit(comparisons, threeAttributes)

        // This is the test that lets the profile refuse to overclaim. Attribute
        // 2's weight is 0 — the prior mean — and reporting that as "no strong
        // feeling either way" would be a lie: it is "we never asked".
        // The standard error is what distinguishes them.
        val untouched = fit.standardErrors[2]
        val learned = fit.standardErrors[0]
        untouched shouldBe (1.0 / sqrt(1.0) plusOrMinus TOLERANCE)
        (learned < untouched) shouldBe true
    }

    @Test
    fun `more evidence narrows the posterior`() {
        val few = BradleyTerry.fit(List(3) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }, threeAttributes)
        val many = BradleyTerry.fit(List(30) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }, threeAttributes)

        (many.standardErrors[0] < few.standardErrors[0]) shouldBe true
    }

    @Test
    fun `the fit is a maximum, not merely a fixed point`() {
        val comparisons = listOf(
            BradleyTerry.Comparison(doubleArrayOf(1.0, -1.0, 0.0)),
            BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, -1.0)),
            BradleyTerry.Comparison(doubleArrayOf(0.0, 1.0, -1.0)),
            BradleyTerry.Comparison(doubleArrayOf(-1.0, 1.0, 0.0)),
            BradleyTerry.Comparison(doubleArrayOf(1.0, 1.0, -2.0)),
        )
        val fit = BradleyTerry.fit(comparisons, threeAttributes)
        val best = BradleyTerry.logPosterior(comparisons, fit.weights, threeAttributes)

        // Nudged in every direction, including diagonally. A Newton step that
        // solved the wrong linear system can still stop moving, and "it
        // converged" is not the same claim as "it converged to the right place".
        val nudges = listOf(
            doubleArrayOf(EPSILON, 0.0, 0.0),
            doubleArrayOf(-EPSILON, 0.0, 0.0),
            doubleArrayOf(0.0, EPSILON, 0.0),
            doubleArrayOf(0.0, -EPSILON, 0.0),
            doubleArrayOf(0.0, 0.0, EPSILON),
            doubleArrayOf(0.0, 0.0, -EPSILON),
            doubleArrayOf(EPSILON, EPSILON, EPSILON),
            doubleArrayOf(-EPSILON, EPSILON, -EPSILON),
        )
        nudges.forEach { nudge ->
            val moved = DoubleArray(3) { fit.weights[it] + nudge[it] }
            (BradleyTerry.logPosterior(comparisons, moved, threeAttributes) <= best) shouldBe true
        }
    }

    @Test
    fun `contradictory answers pull the weight toward the prior`() {
        val contradictory = List(6) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) } +
            List(6) { BradleyTerry.Comparison(doubleArrayOf(-1.0, 0.0, 0.0)) }

        val fit = BradleyTerry.fit(contradictory, threeAttributes)

        // Somebody who picked the comedy half the time has told us they have no
        // strong feeling, and the fit should say so rather than latching onto
        // whichever direction happened to be one answer ahead.
        fit.weights[0] shouldBe (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `match is a probability, and an average title sits at one half`() {
        val weights = doubleArrayOf(1.5, -0.5, 0.0)

        // The zero vector is "an average title" by construction, since the
        // attributes are centred. Reporting anything else for it would mean the
        // scale had a hidden offset.
        BradleyTerry.match(weights, doubleArrayOf(0.0, 0.0, 0.0)) shouldBe (0.5 plusOrMinus TOLERANCE)
        (BradleyTerry.match(weights, doubleArrayOf(1.0, 0.0, 0.0)) > 0.5) shouldBe true
        (BradleyTerry.match(weights, doubleArrayOf(0.0, 1.0, 0.0)) < 0.5) shouldBe true
    }

    @Test
    fun `extreme inputs do not produce a singular fit`() {
        // The naive logistic returns exactly 1 here, which makes p(1-p) zero,
        // the information matrix singular, and Cholesky throw. Real answers can
        // reach this once a few unanimous comparisons have pushed the weight up.
        val extreme = List(20) { BradleyTerry.Comparison(doubleArrayOf(50.0, 0.0, 0.0)) }

        val fit = BradleyTerry.fit(extreme, threeAttributes)

        fit.weights.forEach { it.isFinite() shouldBe true }
        fit.standardErrors.forEach { (it.isFinite() && it > 0.0) shouldBe true }
    }

    @Test
    fun `a zero-precision prior is refused rather than silently becoming maximum likelihood`() {
        // Not a style preference. Precision zero is exactly the unregularised
        // fit that is undefined under separation, and accepting it here would
        // mean the failure surfaced later as a huge weight nobody questioned.
        shouldThrow<IllegalArgumentException> {
            BradleyTerry.Prior(mean = doubleArrayOf(0.0), precision = 0.0)
        }
    }

    @Test
    fun `a comparison of the wrong width is refused`() {
        shouldThrow<IllegalArgumentException> {
            BradleyTerry.fit(listOf(BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0))), threeAttributes)
        }
    }

    @Test
    fun `two attributes that always move together are not separately identified`() {
        // Collinear evidence: nothing here can distinguish "likes 0" from
        // "likes 1". The prior is what keeps this solvable at all, and the wide
        // standard errors are what stop the profile claiming either.
        val comparisons = List(12) { BradleyTerry.Comparison(doubleArrayOf(1.0, 1.0, 0.0)) }

        val fit = BradleyTerry.fit(comparisons, threeAttributes)

        fit.converged shouldBe true
        // Symmetric evidence, so symmetric weights.
        fit.weights[0] shouldBe (fit.weights[1] plusOrMinus TOLERANCE)
        // ...and each is less certain than an attribute varied on its own would
        // have been, which is the honest outcome rather than a failure.
        val alone = BradleyTerry.fit(List(12) { BradleyTerry.Comparison(doubleArrayOf(1.0, 0.0, 0.0)) }, threeAttributes)
        (fit.standardErrors[0] > alone.standardErrors[0]) shouldBe true
    }

    @Test
    fun `the fit is independent of the order answers arrived in`() {
        val comparisons = listOf(
            BradleyTerry.Comparison(doubleArrayOf(1.0, -1.0, 0.0)),
            BradleyTerry.Comparison(doubleArrayOf(0.0, 1.0, -1.0)),
            BradleyTerry.Comparison(doubleArrayOf(-1.0, 0.0, 1.0)),
            BradleyTerry.Comparison(doubleArrayOf(1.0, 1.0, -1.0)),
        )

        val forwards = BradleyTerry.fit(comparisons, threeAttributes)
        val backwards = BradleyTerry.fit(comparisons.reversed(), threeAttributes)

        // A posterior does not depend on the order of exchangeable observations.
        // If this ever failed, something stateful had crept into the fit.
        forwards.weights.indices.forEach {
            abs(forwards.weights[it] - backwards.weights[it]) shouldBe (0.0 plusOrMinus TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-8
        const val EPSILON = 1e-4
    }
}
