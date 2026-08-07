package app.plotted.preferences.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a taste profile from a handful of "which of these two?" answers.
 *
 * ### The model
 *
 * Bradley–Terry, parameterised by *attributes* rather than by titles. A title's
 * strength is `θ(t) = w · x(t)` for an attribute vector `x`, so
 *
 *     P(A chosen over B) = σ(w · (x_A − x_B))
 *
 * which is logistic regression on feature differences. Parameterising by
 * attribute rather than by title is what makes fifteen questions enough: a
 * per-title Bradley–Terry model would have one parameter per film and learn
 * nothing transferable, because the answer to "do you like *this* film" says
 * nothing about the thousand films nobody asked about.
 *
 * ### Why the prior is not optional
 *
 * With fifteen comparisons and eight attributes, maximum likelihood is not
 * merely noisy — it is frequently **undefined**. If someone picks the comedy
 * every time one appears, the likelihood increases without bound as
 * `w_comedy → ∞`, and an unregularised fit runs until it hits an iteration cap
 * and reports an enormous, meaningless number. That is not an edge case; it is
 * the *expected* outcome for a decisive person answering a short questionnaire.
 *
 * A Gaussian prior makes the posterior strictly concave, so the mode always
 * exists and Newton always converges. And the prior mean is the **population's**
 * taste rather than zero, which is the difference between "we have no data, so
 * assume you are typical" and "we have no data, so assume you are indifferent" —
 * only the first is true.
 *
 * ### What comes out
 *
 * The mode, and the curvature at it. The inverse Hessian is the Laplace
 * approximation to the posterior covariance, so every weight arrives with a
 * standard error. That is what lets [PreferenceProfile] say "no evidence either
 * way" about an attribute nobody was asked to compare, instead of reporting a
 * shrunk-to-prior weight as though it were a finding.
 */
object BradleyTerry {
    /**
     * One answered comparison, already reduced to the difference in attributes
     * between the chosen title and the rejected one.
     *
     * A difference rather than two vectors, because that is all the model uses,
     * and because it makes the sign convention impossible to get wrong at the
     * call site: this is *chosen minus rejected*, always.
     */
    data class Comparison(val attributeDifference: DoubleArray) {
        init {
            require(attributeDifference.isNotEmpty()) { "A comparison needs at least one attribute" }
        }

        // Value semantics for a class wrapping an array, so tests can compare
        // fixtures and equal comparisons deduplicate as callers expect.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Comparison && attributeDifference.contentEquals(other.attributeDifference))

        override fun hashCode(): Int = attributeDifference.contentHashCode()
    }

    /**
     * An isotropic Gaussian prior over the weights.
     *
     * `mean` is the population's taste, and `precision` is how strongly a new
     * user is assumed to resemble it. Higher precision means fewer questions
     * move the profile — which is the correct trade when the alternative is
     * fitting eight parameters to fifteen noisy binary answers.
     */
    data class Prior(val mean: DoubleArray, val precision: Double) {
        init {
            require(precision > 0.0) {
                "Precision must be positive. A zero-precision prior is maximum likelihood, which is " +
                    "undefined whenever an attribute is perfectly separated — the common case here."
            }
        }

        val size: Int get() = mean.size

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Prior && mean.contentEquals(other.mean) && precision == other.precision)

        override fun hashCode(): Int = 31 * mean.contentHashCode() + precision.hashCode()
    }

    data class Fit(
        val weights: DoubleArray,
        /**
         * Posterior standard deviation per weight, from the Laplace
         * approximation. Equal to `1/√precision` for an attribute nothing in the
         * data touched — which is the signal that there is nothing to report.
         */
        val standardErrors: DoubleArray,
        val iterations: Int,
        val converged: Boolean,
        /** Comparisons the fit was built from. Travels with it so nobody has to infer it. */
        val observations: Int,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is Fit && weights.contentEquals(other.weights) &&
                    standardErrors.contentEquals(other.standardErrors) &&
                    iterations == other.iterations && converged == other.converged &&
                    observations == other.observations
                )

        override fun hashCode(): Int = weights.contentHashCode()
    }

    /**
     * Newton's method on the log-posterior.
     *
     * The objective is `Σ log σ(w·dᵢ) − ½·precision·‖w − μ‖²`, strictly concave
     * because the prior term is, so there is exactly one maximum and Newton
     * reaches it in a handful of steps from any start. No line search and no
     * learning rate: both exist to cope with objectives that are not this
     * well-behaved, and adding them would be carrying machinery for a problem
     * this does not have.
     *
     * With no comparisons at all the answer is the prior itself, which is
     * returned directly rather than iterated to — not an optimisation, but a
     * guarantee that "we know nothing about you" produces the population profile
     * exactly rather than to within a convergence tolerance.
     */
    fun fit(comparisons: List<Comparison>, prior: Prior): Fit {
        comparisons.forEach {
            require(it.attributeDifference.size == prior.size) {
                "Comparison has ${it.attributeDifference.size} attributes, prior has ${prior.size}"
            }
        }

        val dimension = prior.size
        if (comparisons.isEmpty()) {
            return Fit(
                weights = prior.mean.copyOf(),
                standardErrors = DoubleArray(dimension) { 1.0 / sqrt(prior.precision) },
                iterations = 0,
                converged = true,
                observations = 0,
            )
        }

        var weights = prior.mean.copyOf()
        var iterations = 0
        var converged = false

        while (iterations < MAXIMUM_ITERATIONS) {
            iterations++

            // Gradient of the log-posterior, and the negative Hessian (which is
            // positive definite, so it can be Cholesky-factored directly).
            val gradient = DoubleArray(dimension)
            val curvature = Array(dimension) { DoubleArray(dimension) }

            comparisons.forEach { comparison ->
                val d = comparison.attributeDifference
                val probability = logistic(dot(weights, d))
                val residual = 1.0 - probability
                val weight = probability * residual

                for (i in 0 until dimension) {
                    gradient[i] += residual * d[i]
                    for (j in 0 until dimension) {
                        curvature[i][j] += weight * d[i] * d[j]
                    }
                }
            }

            for (i in 0 until dimension) {
                gradient[i] -= prior.precision * (weights[i] - prior.mean[i])
                curvature[i][i] += prior.precision
            }

            val step = solvePositiveDefinite(curvature, gradient)
            var largestStep = 0.0
            for (i in 0 until dimension) {
                weights[i] += step[i]
                largestStep = maxOf(largestStep, abs(step[i]))
            }

            if (largestStep < CONVERGENCE_TOLERANCE) {
                converged = true
                break
            }
        }

        return Fit(
            weights = weights,
            standardErrors = posteriorStandardErrors(comparisons, weights, prior),
            iterations = iterations,
            converged = converged,
            observations = comparisons.size,
        )
    }

    /**
     * How well a title matches a profile, as a probability.
     *
     * `σ(w · x)` — the model's own probability that this title beats an
     * average one. Reported as a probability rather than a raw strength because
     * a strength is on an arbitrary scale nobody can read, and because it is
     * exactly the quantity the model was fitted to produce.
     */
    fun match(weights: DoubleArray, attributes: DoubleArray): Double {
        require(weights.size == attributes.size) {
            "Profile has ${weights.size} attributes, title has ${attributes.size}"
        }
        return logistic(dot(weights, attributes))
    }

    /**
     * Square roots of the diagonal of the inverse negative Hessian.
     *
     * Only the diagonal is used, so the full inverse is computed and discarded —
     * at eight attributes that is free, and solving column by column would be
     * the same arithmetic written less clearly.
     */
    private fun posteriorStandardErrors(comparisons: List<Comparison>, weights: DoubleArray, prior: Prior): DoubleArray {
        val dimension = prior.size
        val information = Array(dimension) { DoubleArray(dimension) }

        comparisons.forEach { comparison ->
            val d = comparison.attributeDifference
            val probability = logistic(dot(weights, d))
            val weight = probability * (1.0 - probability)
            for (i in 0 until dimension) {
                for (j in 0 until dimension) {
                    information[i][j] += weight * d[i] * d[j]
                }
            }
        }
        for (i in 0 until dimension) information[i][i] += prior.precision

        val inverse = invertPositiveDefinite(information)
        return DoubleArray(dimension) { sqrt(maxOf(inverse[it][it], 0.0)) }
    }

    /**
     * Numerically stable logistic.
     *
     * The textbook `1 / (1 + exp(-z))` overflows for `z` around −746 and returns
     * exactly 0 or 1 well before that, which makes `p(1−p)` zero and the Hessian
     * singular. Branching on the sign keeps the exponent negative in both cases.
     */
    private fun logistic(z: Double): Double = if (z >= 0) {
        1.0 / (1.0 + exp(-z))
    } else {
        val e = exp(z)
        e / (1.0 + e)
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var total = 0.0
        for (i in a.indices) total += a[i] * b[i]
        return total
    }

    /**
     * Cholesky solve for `A x = b`, `A` symmetric positive definite.
     *
     * Positive definiteness is guaranteed by the prior: the likelihood's
     * curvature is positive *semi*-definite, and adding `precision · I` makes it
     * strictly positive. So no pivoting and no fallback — the one input that
     * could break this is a zero-precision prior, which [Prior] refuses to
     * construct.
     */
    private fun solvePositiveDefinite(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val lower = cholesky(matrix)
        val size = rhs.size

        val forward = DoubleArray(size)
        for (i in 0 until size) {
            var sum = rhs[i]
            for (j in 0 until i) sum -= lower[i][j] * forward[j]
            forward[i] = sum / lower[i][i]
        }

        val solution = DoubleArray(size)
        for (i in size - 1 downTo 0) {
            var sum = forward[i]
            for (j in i + 1 until size) sum -= lower[j][i] * solution[j]
            solution[i] = sum / lower[i][i]
        }
        return solution
    }

    private fun invertPositiveDefinite(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val size = matrix.size
        return Array(size) { column ->
            val unit = DoubleArray(size).also { it[column] = 1.0 }
            solvePositiveDefinite(matrix, unit)
        }
    }

    private fun cholesky(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val size = matrix.size
        val lower = Array(size) { DoubleArray(size) }
        for (i in 0 until size) {
            for (j in 0..i) {
                var sum = matrix[i][j]
                for (k in 0 until j) sum -= lower[i][k] * lower[j][k]
                if (i == j) {
                    check(sum > 0.0) {
                        "Information matrix is not positive definite at $i (pivot $sum). " +
                            "The prior precision should have guaranteed this."
                    }
                    lower[i][i] = sqrt(sum)
                } else {
                    lower[i][j] = sum / lower[j][j]
                }
            }
        }
        return lower
    }

    /** Log-posterior, exposed so tests can check the fit is a maximum rather than merely a fixed point. */
    fun logPosterior(comparisons: List<Comparison>, weights: DoubleArray, prior: Prior): Double {
        var total = 0.0
        comparisons.forEach { total += ln(logistic(dot(weights, it.attributeDifference))) }
        for (i in weights.indices) {
            val deviation = weights[i] - prior.mean[i]
            total -= 0.5 * prior.precision * deviation * deviation
        }
        return total
    }

    /**
     * Newton on a concave objective converges in a handful of steps; this cap
     * exists so a numerical pathology becomes a reported non-convergence rather
     * than a hang.
     */
    private const val MAXIMUM_ITERATIONS = 50

    private const val CONVERGENCE_TOLERANCE = 1e-10
}
