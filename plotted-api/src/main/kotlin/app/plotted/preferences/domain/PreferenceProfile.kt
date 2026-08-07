package app.plotted.preferences.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What fifteen answers actually justify saying about somebody's taste.
 *
 * The fit always returns six numbers. Reporting all six as preferences would be
 * the same failure this project refuses everywhere else — a confident statement
 * whose confidence is not backed by anything. So each axis gets a [Verdict], and
 * two of the four possible answers are ways of saying *we do not know*.
 *
 * The distinction that matters most is between the two:
 *
 * - **[Verdict.NO_PREFERENCE]** — we asked, and you genuinely did not lean either
 *   way. A real finding, and useful: it means the axis can be ignored when
 *   ranking for you.
 * - **[Verdict.NOT_ASKED]** — the ladder never contrasted this axis, so the
 *   weight sitting at the population average is an *absence of evidence* wearing
 *   the costume of a measurement.
 *
 * They produce nearly identical weights and completely different advice. Only the
 * posterior width tells them apart, which is why the fitter returns one.
 */
data class PreferenceProfile(
    val opinions: List<AxisOpinion>,
    /** How many comparisons the profile rests on. */
    val observations: Int,
    val converged: Boolean,
) {
    /** The raw weights, in axis order, for scoring. */
    val weights: DoubleArray get() = DoubleArray(opinions.size) { opinions[it].weight }

    /** Axes the profile is prepared to make a claim about, strongest first. */
    val stated: List<AxisOpinion>
        get() = opinions.filter { it.verdict.isDirectional }.sortedByDescending { abs(it.weight) }

    /**
     * Whether this profile should influence ranking at all.
     *
     * A profile with nothing to say is worse than no profile: it adds a feature
     * that varies with noise, and the rankers would treat that variation as
     * signal. Absent beats meaningless.
     */
    val isInformative: Boolean get() = stated.isNotEmpty()

    /**
     * How well a title matches, or null when the profile has nothing to say.
     *
     * Null rather than 0.5, and the difference is load-bearing. Both rankers
     * already handle an absent feature properly — the linear one redistributes
     * its weight, the learned one is told it is missing — and handing them a
     * flat 0.5 instead would be feeding a real-looking number into a decision on
     * the strength of nothing.
     */
    fun match(attributes: TitleAttributes): Double? = if (!isInformative) null else BradleyTerry.match(weights, attributes.values)

    override fun equals(other: Any?): Boolean = this === other ||
        (other is PreferenceProfile && opinions == other.opinions && observations == other.observations)

    override fun hashCode(): Int = 31 * opinions.hashCode() + observations

    companion object {
        /**
         * Turns a fit into a profile, deciding what may be said.
         *
         * A direction is claimed only when the credible interval excludes zero at
         * [CREDIBLE_MULTIPLIER] standard errors. That threshold is doing real
         * work: with fifteen answers over six axes, an unfiltered profile
         * typically "finds" two or three preferences that are noise, and they are
         * indistinguishable from the real ones by eye.
         */
        fun from(fit: BradleyTerry.Fit, prior: BradleyTerry.Prior): PreferenceProfile {
            val priorWidth = 1.0 / sqrt(prior.precision)

            val opinions = TasteAxis.ordered.mapIndexed { index, axis ->
                val weight = fit.weights[index]
                val error = fit.standardErrors[index]
                AxisOpinion(
                    axis = axis,
                    weight = weight,
                    standardError = error,
                    verdict = verdict(weight, error, priorWidth),
                )
            }
            return PreferenceProfile(opinions, fit.observations, fit.converged)
        }

        private fun verdict(weight: Double, error: Double, priorWidth: Double): Verdict = when {
            // Barely narrower than the prior means the data never constrained
            // this axis. Checked first: an unconstrained axis can drift far
            // enough from zero to look like a preference, and calling that
            // "likes" would be reporting the prior back as a discovery.
            error > priorWidth * UNINFORMED_FRACTION -> Verdict.NOT_ASKED
            abs(weight) > CREDIBLE_MULTIPLIER * error -> if (weight > 0) Verdict.LIKES else Verdict.DISLIKES
            else -> Verdict.NO_PREFERENCE
        }

        /** Roughly a 95% credible interval, which is the conventional bar and a defensible one. */
        const val CREDIBLE_MULTIPLIER = 1.96

        /**
         * An axis whose posterior is still this close to the prior width was
         * never really asked about. Not 1.0: every axis narrows a little from
         * every answer, because the axes are not perfectly independent.
         */
        const val UNINFORMED_FRACTION = 0.95
    }
}

data class AxisOpinion(
    val axis: TasteAxis,
    val weight: Double,
    val standardError: Double,
    val verdict: Verdict,
) {
    /** What to show a person. Assembled from the verdict, never written per case. */
    val sentence: String
        get() = when (verdict) {
            Verdict.LIKES -> "You lean toward ${axis.positive}."
            Verdict.DISLIKES -> "You lean toward ${axis.negative}."
            Verdict.NO_PREFERENCE -> "No strong feeling between ${axis.positive} and ${axis.negative}."
            Verdict.NOT_ASKED -> "Not enough answers to tell."
        }
}

enum class Verdict {
    LIKES,
    DISLIKES,

    /** Asked, and genuinely balanced. A finding. */
    NO_PREFERENCE,

    /** Never contrasted, so the weight is the population's rather than yours. */
    NOT_ASKED,
    ;

    val isDirectional: Boolean get() = this == LIKES || this == DISLIKES
}
